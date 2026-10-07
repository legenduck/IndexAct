package org.indexact.read;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.indexact.analysis.ContractAnalyzer;
import org.indexact.analysis.UnicodeScalar;
import org.indexact.execution.Interval;
import org.indexact.execution.OccurrenceEvaluator;
import org.indexact.execution.RankedState;
import org.indexact.execution.SetState;
import org.indexact.expression.AnyOf;
import org.indexact.expression.LexicalExpression;
import org.indexact.expression.Near;
import org.indexact.expression.Phrase;
import org.indexact.expression.Term;
import org.indexact.index.Snapshot;
import org.indexact.index.Snapshot.TokenSpan;
import org.indexact.read.ReadSemanticException.Reason;

/** Exact READ region and semantic-budget engine below session cursor handling. */
public final class ReadEngine implements AutoCloseable {
    private record RawRegion(long start, long end) implements Comparable<RawRegion> {
        RawRegion {
            ReadValues.requireNonNegativeSafe(start, "raw region start");
            ReadValues.requireNonNegativeSafe(end, "raw region end");
            if (start > end) {
                throw new IllegalArgumentException("raw region start must not exceed end");
            }
        }

        long length() {
            return end - start;
        }

        @Override
        public int compareTo(RawRegion other) {
            int byStart = Long.compare(start, other.start);
            return byStart != 0 ? byStart : Long.compare(end, other.end);
        }
    }

    private record SelectedDocuments(List<String> docKeys, PageSelection page) {
        SelectedDocuments {
            docKeys = List.copyOf(docKeys);
        }
    }

    private record DeterminedDocument(String docKey, List<RawRegion> regions) {
        DeterminedDocument {
            regions = List.copyOf(regions);
        }
    }

    private final Snapshot snapshot;
    private final ContractAnalyzer analyzer;
    private final RawTextAccessor rawTextAccessor;
    private final boolean ownsAnalyzer;

    /** Used only by {@link Snapshot#newReadEngine()} with its private raw-field capability. */
    public static ReadEngine owned(
            Snapshot snapshot, ContractAnalyzer analyzer, RawTextAccessor rawTextAccessor) {
        return new ReadEngine(snapshot, analyzer, rawTextAccessor, true);
    }

    /** Dependency-injection constructor for request-boundary instrumentation. */
    public ReadEngine(
            Snapshot snapshot, ContractAnalyzer analyzer, RawTextAccessor rawTextAccessor) {
        this(snapshot, analyzer, rawTextAccessor, false);
    }

    private ReadEngine(
            Snapshot snapshot,
            ContractAnalyzer analyzer,
            RawTextAccessor rawTextAccessor,
            boolean ownsAnalyzer) {
        this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
        this.analyzer = Objects.requireNonNull(analyzer, "analyzer");
        this.rawTextAccessor = Objects.requireNonNull(rawTextAccessor, "rawTextAccessor");
        this.ownsAnalyzer = ownsAnalyzer;
    }

    public ReadResult readDocument(
            String docKey, ReadRegion region, ReadBudget budget)
            throws IOException, ReadSemanticException {
        requireDocument(docKey);
        Objects.requireNonNull(region, "region");
        Objects.requireNonNull(budget, "budget");
        return materialize(
                List.of(docKey), 1, region, budget, null, true);
    }

    public ReadResult readSet(
            SetState target,
            StateDocumentSelection selection,
            ReadRegion region,
            ReadBudget budget)
            throws IOException, ReadSemanticException {
        if (!target.hasPhysicalMembership()
                || !target.physicalMembership().belongsTo(snapshot)) {
            requireState(target.snapshotId(), target.members());
        }
        List<String> traversal = target.orderedMembers();
        return readState(traversal, target.cardinality(), selection, region, budget);
    }

    public ReadResult readRanked(
            RankedState target,
            StateDocumentSelection selection,
            ReadRegion region,
            ReadBudget budget)
            throws IOException, ReadSemanticException {
        requireState(target.snapshotId(), target.order());
        return readState(target.order(), target.cardinality(), selection, region, budget);
    }

    private ReadResult readState(
            List<String> traversal,
            long targetDocs,
            StateDocumentSelection selection,
            ReadRegion region,
            ReadBudget budget)
            throws IOException, ReadSemanticException {
        Objects.requireNonNull(selection, "selection");
        Objects.requireNonNull(region, "region");
        Objects.requireNonNull(budget, "budget");
        if (region instanceof RangeRegion) {
            throw new ReadSemanticException(
                    Reason.TYPE_MISMATCH, "RANGE is valid only for a direct DocKey target");
        }
        SelectedDocuments selected = select(traversal, selection);
        return materialize(
                selected.docKeys(), targetDocs, region, budget, selected.page(), false);
    }

    private SelectedDocuments select(
            List<String> traversal, StateDocumentSelection selection)
            throws ReadSemanticException {
        return switch (selection) {
            case AllDocuments ignored -> new SelectedDocuments(traversal, null);
            case PageSelection page -> {
                validatePage(traversal, page);
                yield new SelectedDocuments(page.docKeys(), page);
            }
        };
    }

    private static void validatePage(List<String> traversal, PageSelection page)
            throws ReadSemanticException {
        if (page.docKeys().isEmpty()) {
            if (page.nextCursor() != null) {
                throw new ReadSemanticException(
                        Reason.INVALID_ARGUMENT, "an empty terminal page cannot have next_cursor");
            }
            return;
        }
        int start = traversal.indexOf(page.docKeys().getFirst());
        if (start < 0 || start + page.docKeys().size() > traversal.size()) {
            throw new ReadSemanticException(
                    Reason.INVALID_ARGUMENT, "PAGE selection is outside the target state");
        }
        for (int index = 0; index < page.docKeys().size(); index++) {
            if (!traversal.get(start + index).equals(page.docKeys().get(index))) {
                throw new ReadSemanticException(
                        Reason.INVALID_ARGUMENT,
                        "PAGE selection is not contiguous in target traversal order");
            }
        }
        boolean documentsRemain = start + page.docKeys().size() < traversal.size();
        if (documentsRemain != (page.nextCursor() != null)) {
            throw new ReadSemanticException(
                    Reason.INVALID_ARGUMENT,
                    "PAGE next_cursor presence disagrees with remaining target documents");
        }
    }

    private ReadResult materialize(
            List<String> selectedDocKeys,
            long targetDocs,
            ReadRegion region,
            ReadBudget budget,
            PageSelection page,
            boolean allowRange)
            throws IOException, ReadSemanticException {
        if (region instanceof RangeRegion && !allowRange) {
            throw new ReadSemanticException(
                    Reason.TYPE_MISMATCH, "RANGE is valid only for a direct DocKey target");
        }
        if (region instanceof AroundRegion around) {
            validateAnchor(around.anchor());
        }
        for (String docKey : selectedDocKeys) {
            requireDocument(docKey);
        }

        long regionDocs = 0;
        long evidenceCount = 0;
        long outputCodePoints = 0;
        List<DeterminedDocument> determined = new ArrayList<>(selectedDocKeys.size());
        for (int index = 0; index < selectedDocKeys.size(); index++) {
            String docKey = selectedDocKeys.get(index);
            List<RawRegion> regions = resolveRegions(docKey, region, allowRange);
            determined.add(new DeterminedDocument(docKey, regions));
            if (!regions.isEmpty()) {
                regionDocs++;
            }
            evidenceCount = safeAdd(evidenceCount, regions.size(), "evidence count");
            for (RawRegion resolved : regions) {
                outputCodePoints = safeAdd(
                        outputCodePoints, resolved.length(), "required output code points");
            }

            List<ExceededLimit> exceeded = exceededLimits(outputCodePoints, evidenceCount, budget);
            if (!exceeded.isEmpty()) {
                long evaluatedDocs = index + 1L;
                boolean complete = evaluatedDocs == selectedDocKeys.size();
                return new ReadBudgetExceeded(
                        budget,
                        exceeded,
                        targetDocs,
                        selectedDocKeys.size(),
                        complete,
                        page,
                        outputCodePoints,
                        evaluatedDocs,
                        regionDocs,
                        evidenceCount,
                        complete ? outputCodePoints : null,
                        complete ? regionDocs : null,
                        complete ? evidenceCount : null);
            }
        }

        // Load raw text only after both semantic budgets have admitted every selected region.
        List<Evidence> evidence = new ArrayList<>((int) evidenceCount);
        for (DeterminedDocument document : determined) {
            if (document.regions().isEmpty()) {
                continue;
            }
            String raw = rawTextAccessor.load(snapshot, document.docKey());
            int[] scalars = UnicodeScalar.toCodePoints(raw);
            if (scalars.length != snapshot.rawCodePointLength(document.docKey())) {
                throw new IOException("stored raw length disagrees with sealed snapshot metadata");
            }
            for (RawRegion resolved : document.regions()) {
                int startUtf16 = raw.offsetByCodePoints(0, Math.toIntExact(resolved.start()));
                int endUtf16 = raw.offsetByCodePoints(0, Math.toIntExact(resolved.end()));
                evidence.add(new Evidence(
                        snapshot.snapshotId(),
                        document.docKey(),
                        resolved.start(),
                        resolved.end(),
                        raw.substring(startUtf16, endUtf16)));
            }
        }
        return new ReadSuccess(
                targetDocs,
                selectedDocKeys.size(),
                regionDocs,
                evidenceCount,
                outputCodePoints,
                page,
                evidence);
    }

    private List<RawRegion> resolveRegions(
            String docKey, ReadRegion region, boolean allowRange)
            throws IOException, ReadSemanticException {
        int rawLength = snapshot.rawCodePointLength(docKey);
        return switch (region) {
            case DocumentRegion ignored -> List.of(new RawRegion(0, rawLength));
            case RangeRegion range -> {
                if (!allowRange) {
                    throw new ReadSemanticException(
                            Reason.TYPE_MISMATCH, "RANGE is valid only for a direct DocKey target");
                }
                if (range.end() > rawLength) {
                    throw new ReadSemanticException(
                            Reason.INVALID_ARGUMENT,
                            "RANGE end exceeds raw document length; bounds are not clipped");
                }
                yield List.of(new RawRegion(range.start(), range.end()));
            }
            case AroundRegion around -> aroundRegions(docKey, rawLength, around);
        };
    }

    private List<RawRegion> aroundRegions(
            String docKey, int rawLength, AroundRegion around) throws IOException {
        List<Interval> occurrences = new OccurrenceEvaluator(snapshot, docKey, analyzer)
                .occurrences(around.anchor())
                .stream()
                .sorted()
                .toList();
        List<Interval> selected = switch (around.selector()) {
            case FirstSelector ignored -> occurrences.isEmpty()
                    ? List.of()
                    : List.of(occurrences.getFirst());
            case NthSelector nth -> nth.n() > occurrences.size()
                    ? List.of()
                    : List.of(occurrences.get((int) nth.n() - 1));
            case AllSelector ignored -> occurrences;
        };

        if (selected.isEmpty()) {
            return List.of();
        }
        // One document-local load, shared across every selected match in this read.
        List<TokenSpan> tokenSpans = snapshot.tokenSpans(docKey);
        List<RawRegion> windows = new ArrayList<>(selected.size());
        for (Interval occurrence : selected) {
            if (occurrence.end() > tokenSpans.size()) {
                throw new IOException("occurrence lies outside sealed token metadata");
            }
            long rawStart = Long.MAX_VALUE;
            long rawEnd = -1;
            for (long position = occurrence.start(); position < occurrence.end(); position++) {
                TokenSpan span = tokenSpans.get((int) position);
                rawStart = Math.min(rawStart, span.rawStart());
                rawEnd = Math.max(rawEnd, span.rawEnd());
            }
            windows.add(new RawRegion(
                    Math.max(0, rawStart - around.before()),
                    Math.min((long) rawLength, rawEnd + around.after())));
        }
        windows.sort(Comparator.naturalOrder());
        return around.selector() instanceof AllSelector ? mergeWindows(windows) : List.copyOf(windows);
    }

    private static List<RawRegion> mergeWindows(List<RawRegion> windows) {
        if (windows.isEmpty()) {
            return List.of();
        }
        List<RawRegion> merged = new ArrayList<>();
        long start = windows.getFirst().start();
        long end = windows.getFirst().end();
        for (RawRegion window : windows.subList(1, windows.size())) {
            if (window.start() <= end) {
                end = Math.max(end, window.end());
            } else {
                merged.add(new RawRegion(start, end));
                start = window.start();
                end = window.end();
            }
        }
        merged.add(new RawRegion(start, end));
        return List.copyOf(merged);
    }

    private void validateAnchor(LexicalExpression expression) throws ReadSemanticException {
        try {
            switch (expression) {
                case Term term -> analyzer.analyzeTerm(term.surface());
                case AnyOf anyOf -> validateChildren(anyOf.children());
                case Phrase phrase -> validateChildren(phrase.children());
                case Near near -> validateChildren(near.children());
            }
        } catch (IllegalArgumentException error) {
            throw new ReadSemanticException(
                    Reason.INVALID_ARGUMENT, "invalid AROUND anchor TERM", error);
        }
    }

    private void validateChildren(List<LexicalExpression> children) throws ReadSemanticException {
        for (LexicalExpression child : children) {
            validateAnchor(child);
        }
    }

    private void requireDocument(String docKey) throws ReadSemanticException {
        Objects.requireNonNull(docKey, "docKey");
        try {
            UnicodeScalar.toCodePoints(docKey);
        } catch (IllegalArgumentException error) {
            throw new ReadSemanticException(Reason.INVALID_ARGUMENT, "invalid DocKey", error);
        }
        if (!snapshot.containsDocKey(docKey)) {
            throw new ReadSemanticException(Reason.DOCUMENT_NOT_FOUND, "unknown DocKey");
        }
    }

    private void requireState(String snapshotId, Iterable<String> members)
            throws ReadSemanticException, java.io.InterruptedIOException {
        if (!snapshot.snapshotId().equals(snapshotId)) {
            throw new ReadSemanticException(
                    Reason.INVALID_STATE_REF, "state belongs to another snapshot");
        }
        int checked = 0;
        for (String docKey : members) {
            if ((checked++ & 1023) == 0 && Thread.currentThread().isInterrupted()) {
                throw new java.io.InterruptedIOException("READ state validation interrupted");
            }
            if (!snapshot.containsDocKey(docKey)) {
                throw new ReadSemanticException(
                        Reason.INVALID_STATE_REF, "state contains an unknown DocKey");
            }
        }
    }

    private static List<ExceededLimit> exceededLimits(
            long outputCodePoints, long evidenceCount, ReadBudget budget) {
        List<ExceededLimit> result = new ArrayList<>(2);
        if (outputCodePoints > budget.maxOutputCodePoints()) {
            result.add(ExceededLimit.OUTPUT_CODEPOINTS);
        }
        if (evidenceCount > budget.maxEvidenceCount()) {
            result.add(ExceededLimit.EVIDENCE_COUNT);
        }
        return List.copyOf(result);
    }

    private static long safeAdd(long left, long right, String name) throws IOException {
        long result;
        try {
            result = Math.addExact(left, right);
        } catch (ArithmeticException error) {
            throw new IOException(name + " overflowed binary64 interoperable integer space", error);
        }
        if (result > ReadValues.MAX_SAFE_INTEGER) {
            throw new IOException(name + " exceeded the I-JSON safe-integer domain");
        }
        return result;
    }

    @Override
    public void close() {
        if (ownsAnalyzer) {
            analyzer.close();
        }
    }
}
