package org.indexact.execution;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.indexact.analysis.ContractAnalyzer;
import org.indexact.expression.AnyOf;
import org.indexact.expression.GapChoice;
import org.indexact.expression.LexicalExpression;
import org.indexact.expression.Near;
import org.indexact.expression.Phrase;
import org.indexact.expression.Term;
import org.indexact.index.Snapshot;

/** Exact lexical evaluator over Lucene postings for one snapshot document. */
public final class OccurrenceEvaluator {
    @FunctionalInterface
    interface TermPositionSource {
        int[] positions(
                int internalDocument, String docKey, String analyzedToken) throws IOException;
    }

    private final Snapshot snapshot;
    private final String docKey;
    private final int internalDocument;
    private final ContractAnalyzer analyzer;
    private final Map<String, String> analyzedTerms;
    private final TermPositionSource termPositionSource;
    private final Map<LexicalExpression, Set<Interval>> occurrenceCache = new IdentityHashMap<>();

    public OccurrenceEvaluator(Snapshot snapshot, String docKey, ContractAnalyzer analyzer) {
        this(
                snapshot,
                docKey,
                snapshot.internalDocumentId(docKey),
                analyzer,
                new HashMap<>(),
                (ignoredDocument, key, token) -> snapshot.termPositions(key, token));
    }

    OccurrenceEvaluator(
            Snapshot snapshot,
            String docKey,
            int internalDocument,
            ContractAnalyzer analyzer,
            Map<String, String> analyzedTerms,
            TermPositionSource termPositionSource) {
        this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
        this.docKey = Objects.requireNonNull(docKey, "docKey");
        this.internalDocument = internalDocument;
        this.analyzer = Objects.requireNonNull(analyzer, "analyzer");
        this.analyzedTerms = Objects.requireNonNull(analyzedTerms, "analyzedTerms");
        this.termPositionSource = Objects.requireNonNull(
                termPositionSource, "termPositionSource");
        if (snapshot.internalDocumentId(docKey) != internalDocument) {
            throw new IllegalArgumentException("unknown DocKey: " + docKey);
        }
    }

    public Set<Interval> occurrences(LexicalExpression expression) throws IOException {
        Objects.requireNonNull(expression, "expression");
        Set<Interval> cached = occurrenceCache.get(expression);
        if (cached != null) {
            return cached;
        }

        record Frame(LexicalExpression expression, boolean expanded) {}
        java.util.ArrayDeque<Frame> pending = new java.util.ArrayDeque<>();
        pending.push(new Frame(expression, false));
        while (!pending.isEmpty()) {
            Frame frame = pending.pop();
            LexicalExpression current = frame.expression();
            if (occurrenceCache.containsKey(current)) {
                continue;
            }
            List<LexicalExpression> children = switch (current) {
                case Term ignored -> List.of();
                case AnyOf any -> any.children();
                case Phrase phrase -> phrase.children();
                case Near near -> near.children();
            };
            if (!frame.expanded()) {
                pending.push(new Frame(current, true));
                for (int index = children.size() - 1; index >= 0; index--) {
                    pending.push(new Frame(children.get(index), false));
                }
                continue;
            }
            Set<Interval> result = switch (current) {
                case Term term -> termOccurrences(term);
                case AnyOf ignored -> {
                    Set<Interval> candidates = new HashSet<>();
                    for (LexicalExpression child : children) {
                        candidates.addAll(occurrenceCache.get(child));
                    }
                    yield minIntervals(candidates);
                }
                case Phrase ignored -> nearOccurrences(children, true, 0);
                case Near near -> nearOccurrences(children, near.ordered(), near.maxGaps());
            };
            occurrenceCache.put(current, result);
        }
        return occurrenceCache.get(expression);
    }

    private Set<Interval> termOccurrences(Term term) throws IOException {
        String analyzedToken = analyzedTerms.computeIfAbsent(
                term.surface(), surface -> analyzer.analyzeTerm(surface).text());
        List<Interval> result = new ArrayList<>();
        for (int position : termPositionSource.positions(internalDocument, docKey, analyzedToken)) {
            result.add(new Interval(position, (long) position + 1));
        }
        // TERM intervals all have length one, so only exact duplicates can contain one another.
        return Collections.unmodifiableSet(new LinkedHashSet<>(result));
    }

    private Set<Interval> nearOccurrences(
            List<LexicalExpression> children, boolean ordered, long maxGaps) throws IOException {
        Set<Interval> covers = new HashSet<>();
        try {
            GapChoice.forEachExpandedTuple(children, expanded -> {
                try {
                    List<List<Interval>> childOccurrences = new ArrayList<>(expanded.size());
                    for (LexicalExpression child : expanded) {
                        List<Interval> available = new ArrayList<>(occurrences(child));
                        if (available.isEmpty()) {
                            return;
                        }
                        Collections.sort(available);
                        childOccurrences.add(available);
                    }
                    if (!ordered && expanded.stream().allMatch(Term.class::isInstance)) {
                        covers.addAll(unorderedTermOccurrences(childOccurrences, maxGaps));
                    } else {
                        enumerateWitnesses(
                                childOccurrences,
                                0,
                                ordered,
                                maxGaps,
                                new ArrayList<>(children.size()),
                                covers);
                    }
                } catch (IOException error) {
                    throw new UncheckedIOException(error);
                }
            });
        } catch (UncheckedIOException error) {
            throw error.getCause();
        }
        return minIntervals(covers);
    }

    private record TermWindowEvent(Interval interval, BitSet eligibleSlots) {}

    /**
     * Exact unordered-NEAR evaluation for TERM-only tuples.
     *
     * <p>Every TERM occurrence is a unit interval. A valid assignment of {@code slotCount}
     * pairwise-distinct occurrences therefore has union length {@code slotCount}, so its gap
     * count is exactly {@code cover.length() - slotCount}. The merged occurrence stream can be
     * searched with a minimum covering window instead of enumerating the Cartesian product of
     * every slot's postings. Bipartite matching preserves the public distinct-witness rule when
     * repeated or analyzer-equivalent TERM slots share occurrence intervals.
     */
    static Set<Interval> unorderedTermOccurrences(
            List<List<Interval>> alternatives, long maxGaps) {
        Objects.requireNonNull(alternatives, "alternatives");
        if (alternatives.size() < 2) {
            throw new IllegalArgumentException("unordered NEAR requires at least two slots");
        }
        if (maxGaps < 0) {
            throw new IllegalArgumentException("maxGaps must be non-negative");
        }

        Map<Interval, BitSet> slotsByInterval = new HashMap<>();
        for (int slot = 0; slot < alternatives.size(); slot++) {
            List<Interval> available = Objects.requireNonNull(
                    alternatives.get(slot), "TERM occurrence list");
            if (available.isEmpty()) {
                return Set.of();
            }
            for (Interval interval : available) {
                Objects.requireNonNull(interval, "TERM occurrence");
                if (interval.length() != 1) {
                    throw new IllegalArgumentException(
                            "TERM occurrence intervals must have length one");
                }
                slotsByInterval
                        .computeIfAbsent(interval, ignored -> new BitSet(alternatives.size()))
                        .set(slot);
            }
        }
        if (slotsByInterval.size() < alternatives.size()) {
            return Set.of();
        }

        List<TermWindowEvent> events = slotsByInterval.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> new TermWindowEvent(entry.getKey(), entry.getValue()))
                .toList();
        Set<Interval> covers = new HashSet<>();
        int left = 0;
        for (int right = 0; right < events.size(); right++) {
            if (Thread.currentThread().isInterrupted()) {
                throw new java.util.concurrent.CancellationException(
                        "unordered TERM window traversal interrupted");
            }
            if (!hasDistinctTermAssignment(events, left, right, alternatives.size())) {
                continue;
            }
            while (left < right
                    && hasDistinctTermAssignment(events, left + 1, right, alternatives.size())) {
                left++;
            }
            Interval cover = new Interval(
                    events.get(left).interval().start(), events.get(right).interval().end());
            if (cover.length() - alternatives.size() <= maxGaps) {
                covers.add(cover);
            }
        }
        return minIntervals(covers);
    }

    private static boolean hasDistinctTermAssignment(
            List<TermWindowEvent> events, int left, int right, int slotCount) {
        int eventCount = right - left + 1;
        if (eventCount < slotCount) {
            return false;
        }

        int[] ownerByEvent = new int[eventCount];
        Arrays.fill(ownerByEvent, -1);
        for (int rootSlot = 0; rootSlot < slotCount; rootSlot++) {
            boolean[] visitedSlots = new boolean[slotCount];
            boolean[] visitedEvents = new boolean[eventCount];
            int[] parentSlot = new int[slotCount];
            int[] parentEvent = new int[slotCount];
            Arrays.fill(parentSlot, -1);
            Arrays.fill(parentEvent, -1);

            ArrayDeque<Integer> pendingSlots = new ArrayDeque<>();
            visitedSlots[rootSlot] = true;
            pendingSlots.add(rootSlot);
            int terminalSlot = -1;
            int freeEvent = -1;
            while (!pendingSlots.isEmpty() && freeEvent < 0) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new java.util.concurrent.CancellationException(
                            "unordered TERM matching interrupted");
                }
                int slot = pendingSlots.removeFirst();
                for (int event = 0; event < eventCount; event++) {
                    if (visitedEvents[event]
                            || !events.get(left + event).eligibleSlots().get(slot)) {
                        continue;
                    }
                    visitedEvents[event] = true;
                    if (ownerByEvent[event] < 0) {
                        terminalSlot = slot;
                        freeEvent = event;
                        break;
                    }
                    int owner = ownerByEvent[event];
                    if (!visitedSlots[owner]) {
                        visitedSlots[owner] = true;
                        parentSlot[owner] = slot;
                        parentEvent[owner] = event;
                        pendingSlots.addLast(owner);
                    }
                }
            }
            if (freeEvent < 0) {
                return false;
            }

            int slot = terminalSlot;
            int event = freeEvent;
            while (slot >= 0) {
                ownerByEvent[event] = slot;
                event = parentEvent[slot];
                slot = parentSlot[slot];
            }
        }
        return true;
    }

    private static void enumerateWitnesses(
            List<List<Interval>> alternatives,
            int slot,
            boolean ordered,
            long maxGaps,
            ArrayList<Interval> selected,
            Set<Interval> covers) {
        if (ordered) {
            enumerateOrderedWitnesses(alternatives, slot, maxGaps, selected, covers);
            return;
        }
        int[] indexes = new int[alternatives.size()];
        int currentSlot = slot;
        while (currentSlot >= slot) {
            if (Thread.currentThread().isInterrupted()) {
                throw new java.util.concurrent.CancellationException(
                        "occurrence witness traversal interrupted");
            }
            if (currentSlot == alternatives.size()) {
                if (witnessGapCount(selected) <= maxGaps) {
                    covers.add(witnessCover(selected));
                }
                currentSlot--;
                if (currentSlot >= slot) {
                    selected.removeLast();
                }
                continue;
            }
            List<Interval> available = alternatives.get(currentSlot);
            boolean advanced = false;
            while (indexes[currentSlot] < available.size()) {
                Interval candidate = available.get(indexes[currentSlot]++);
                if (selected.contains(candidate)) {
                    continue;
                }
                selected.add(candidate);
                currentSlot++;
                if (currentSlot < alternatives.size()) {
                    indexes[currentSlot] = 0;
                }
                advanced = true;
                break;
            }
            if (!advanced) {
                indexes[currentSlot] = 0;
                currentSlot--;
                if (currentSlot >= slot) {
                    selected.removeLast();
                }
            }
        }
    }

    private static void enumerateOrderedWitnesses(
            List<List<Interval>> alternatives,
            int slot,
            long maxGaps,
            ArrayList<Interval> selected,
            Set<Interval> covers) {
        int[] indexes = new int[alternatives.size()];
        long[] partialGaps = new long[alternatives.size() + 1];
        int currentSlot = slot;
        while (currentSlot >= slot) {
            if (Thread.currentThread().isInterrupted()) {
                throw new java.util.concurrent.CancellationException(
                        "occurrence witness traversal interrupted");
            }
            if (currentSlot == alternatives.size()) {
                covers.add(witnessCover(selected));
                currentSlot--;
                if (currentSlot >= slot) {
                    selected.removeLast();
                }
                continue;
            }

            List<Interval> available = alternatives.get(currentSlot);
            if (indexes[currentSlot] == 0 && !selected.isEmpty()) {
                indexes[currentSlot] = firstStartingAtOrAfter(
                        available, selected.getLast().end());
            }
            boolean advanced = false;
            while (indexes[currentSlot] < available.size()) {
                Interval candidate = available.get(indexes[currentSlot]++);
                long nextGaps = partialGaps[currentSlot];
                if (!selected.isEmpty()) {
                    Interval previous = selected.getLast();
                    if (previous.end() > candidate.start()) {
                        continue;
                    }
                    long added = candidate.start() - previous.end();
                    if (added > maxGaps - nextGaps) {
                        // Lists are ordered by start, so every remaining candidate is farther away.
                        indexes[currentSlot] = available.size();
                        break;
                    }
                    nextGaps += added;
                }
                if (selected.contains(candidate)) {
                    continue;
                }
                selected.add(candidate);
                partialGaps[currentSlot + 1] = nextGaps;
                currentSlot++;
                if (currentSlot < alternatives.size()) {
                    indexes[currentSlot] = 0;
                }
                advanced = true;
                break;
            }
            if (!advanced) {
                indexes[currentSlot] = 0;
                currentSlot--;
                if (currentSlot >= slot) {
                    selected.removeLast();
                }
            }
        }
    }

    private static int firstStartingAtOrAfter(List<Interval> intervals, long minimumStart) {
        int low = 0;
        int high = intervals.size();
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (intervals.get(middle).start() < minimumStart) {
                low = middle + 1;
            } else {
                high = middle;
            }
        }
        return low;
    }

    public static Set<Interval> minIntervals(Collection<Interval> intervals) {
        Objects.requireNonNull(intervals, "intervals");
        List<Interval> candidates = intervals.stream()
                .distinct()
                .sorted(Comparator.comparingLong(Interval::start)
                        .reversed()
                        .thenComparingLong(Interval::end))
                .toList();
        ArrayList<Interval> minimal = new ArrayList<>();
        long minimumEndAtHigherStart = Long.MAX_VALUE;
        int groupStart = 0;
        while (groupStart < candidates.size()) {
            if (Thread.currentThread().isInterrupted()) {
                throw new java.util.concurrent.CancellationException(
                        "minimum-interval reduction interrupted");
            }
            int groupEnd = groupStart + 1;
            long start = candidates.get(groupStart).start();
            while (groupEnd < candidates.size()
                    && candidates.get(groupEnd).start() == start) {
                groupEnd++;
            }
            long minimumEndAtSameStart = candidates.get(groupStart).end();
            for (int index = groupStart; index < groupEnd; index++) {
                Interval candidate = candidates.get(index);
                boolean containsProperSubset = minimumEndAtHigherStart <= candidate.end()
                        || minimumEndAtSameStart < candidate.end();
                if (!containsProperSubset) {
                    minimal.add(candidate);
                }
            }
            minimumEndAtHigherStart = Math.min(
                    minimumEndAtHigherStart, minimumEndAtSameStart);
            groupStart = groupEnd;
        }
        Collections.sort(minimal);
        return Collections.unmodifiableSet(new LinkedHashSet<>(minimal));
    }

    public static long intervalUnionLength(Collection<Interval> intervals) {
        Objects.requireNonNull(intervals, "intervals");
        if (intervals.isEmpty()) {
            return 0;
        }
        List<Interval> ordered = intervals.stream()
                .distinct()
                .sorted(Comparator.naturalOrder())
                .toList();
        long total = 0;
        long start = ordered.getFirst().start();
        long end = ordered.getFirst().end();
        for (Interval interval : ordered.subList(1, ordered.size())) {
            if (interval.start() <= end) {
                end = Math.max(end, interval.end());
            } else {
                total = Math.addExact(total, end - start);
                start = interval.start();
                end = interval.end();
            }
        }
        return Math.addExact(total, end - start);
    }

    public static Interval witnessCover(Collection<Interval> witnesses) {
        Objects.requireNonNull(witnesses, "witnesses");
        if (witnesses.isEmpty()) {
            throw new IllegalArgumentException("a witness assignment must not be empty");
        }
        return new Interval(
                witnesses.stream().mapToLong(Interval::start).min().orElseThrow(),
                witnesses.stream().mapToLong(Interval::end).max().orElseThrow());
    }

    public static long witnessGapCount(Collection<Interval> witnesses) {
        Interval cover = witnessCover(witnesses);
        return cover.length() - intervalUnionLength(witnesses);
    }
}
