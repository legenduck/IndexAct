package org.indexact.execution;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.concurrent.atomic.LongAdder;
import org.apache.lucene.index.PostingsEnum;
import org.apache.lucene.index.NumericDocValues;
import org.indexact.analysis.ContractAnalyzer;
import org.indexact.expression.Combine;
import org.indexact.expression.ScoringExpression;
import org.indexact.expression.Term;
import org.indexact.expression.Weight;
import org.indexact.expression.WeightedTerm;
import org.indexact.index.Snapshot;

/** Document RANK arithmetic with the serving process's BM25 parameters. */
public final class ScoringPlan {
    public record Materialization(List<String> order, List<Double> scores) {
        public Materialization {
            order = List.copyOf(order);
            scores = List.copyOf(scores);
        }
    }

    private record ScoredDocument(String docKey, double score) {}

    @FunctionalInterface
    private interface PostingsAccessor {
        PostingsEnum open() throws IOException;
    }

    public static final class Atom {
        private final String token;
        private final byte[] tokenUtf8;
        private final double weight;
        private final long canonicalWeightBits;
        private final int documentFrequency;
        private final double idf;
        private final PostingsAccessor postingsAccessor;

        Atom(
                String token,
                double weight,
                int documentFrequency,
                double idf,
                PostingsAccessor postingsAccessor) {
            this.token = token;
            this.tokenUtf8 = token.getBytes(StandardCharsets.UTF_8);
            this.weight = weight == 0.0 ? 0.0 : weight;
            this.canonicalWeightBits = Double.doubleToRawLongBits(this.weight);
            this.documentFrequency = documentFrequency;
            this.idf = idf;
            this.postingsAccessor = postingsAccessor;
        }

        public String token() {
            return token;
        }

        public byte[] tokenUtf8() {
            return tokenUtf8.clone();
        }

        public double weight() {
            return weight;
        }

        public long canonicalWeightBits() {
            return canonicalWeightBits;
        }

        public int documentFrequency() {
            return documentFrequency;
        }

        public double idf() {
            return idf;
        }
    }

    private static final Comparator<Atom> ATOM_ORDER = (left, right) -> {
        int tokenOrder = Arrays.compareUnsigned(left.tokenUtf8, right.tokenUtf8);
        return tokenOrder != 0
                ? tokenOrder
                : Long.compareUnsigned(left.canonicalWeightBits, right.canonicalWeightBits);
    };

    private final Snapshot snapshot;
    private final List<Atom> atoms;
    private final Bm25Parameters bm25;
    private final LongAdder postingsCursorOpens = new LongAdder();

    private static final Comparator<ScoredDocument> BEST_FIRST = Comparator
            .comparingDouble(ScoredDocument::score)
            .reversed()
            .thenComparing(ScoredDocument::docKey, org.indexact.index.SnapshotBuilder.CODE_POINT_ORDER);

    private ScoringPlan(Snapshot snapshot, List<Atom> atoms, Bm25Parameters bm25) {
        this.snapshot = snapshot;
        this.atoms = List.copyOf(atoms);
        this.bm25 = java.util.Objects.requireNonNull(bm25, "bm25");
    }

    public static ScoringPlan build(
            Snapshot snapshot, ContractAnalyzer analyzer, ScoringExpression expression)
            throws IOException {
        return build(snapshot, analyzer, expression, Bm25Parameters.WIKIPEDIA_18);
    }

    public static ScoringPlan build(
            Snapshot snapshot, ContractAnalyzer analyzer, ScoringExpression expression,
            Bm25Parameters bm25)
            throws IOException {
        List<WeightedTerm> submitted = submittedAtoms(expression);
        List<Atom> canonical = new ArrayList<>(submitted.size());
        long documentCount = snapshot.manifest().documentCount();
        for (WeightedTerm atom : submitted) {
            String token = analyzer.analyzeTerm(atom.term().surface()).text();
            int df = snapshot.documentFrequency(token);
            canonical.add(new Atom(
                    token,
                    atom.weight(),
                    df,
                    idf(documentCount, df),
                    () -> snapshot.termFrequencyPostings(token)));
        }
        canonical.sort(ATOM_ORDER);
        ScoringPlan result = new ScoringPlan(snapshot, canonical, bm25);
        result.validateFiniteUpperBound();
        return result;
    }

    public List<Atom> atoms() {
        return atoms;
    }

    public Materialization materialize(StateMembership membership) throws IOException {
        List<ScoredDocument> scored = scan(membership, -1);
        scored.sort(BEST_FIRST);
        return materialization(scored);
    }

    public Materialization topK(StateMembership membership, int k) throws IOException {
        if (k == 0) {
            return new Materialization(List.of(), List.of());
        }
        List<ScoredDocument> scored = scan(membership, k);
        scored.sort(BEST_FIRST);
        return materialization(scored);
    }

    /** Diagnostic count: one cursor per canonical atom per physical scan. */
    public long postingsCursorOpenCount() {
        return postingsCursorOpens.sum();
    }

    private List<ScoredDocument> scan(StateMembership membership, int heapLimit) throws IOException {
        if (!snapshot.snapshotId().equals(membership.snapshotId())) {
            throw new IllegalArgumentException("membership belongs to another snapshot");
        }
        PostingsEnum[] postings = new PostingsEnum[atoms.size()];
        for (int index = 0; index < atoms.size(); index++) {
            postings[index] = atoms.get(index).postingsAccessor.open();
            postingsCursorOpens.increment();
        }
        List<ScoredDocument> all = heapLimit < 0 ? new ArrayList<>() : null;
        PriorityQueue<ScoredDocument> heap = heapLimit < 0
                ? null
                : new PriorityQueue<>(Math.max(1, heapLimit), BEST_FIRST.reversed());
        NumericDocValues lengths = snapshot.documentLengths();
        java.util.PrimitiveIterator.OfInt documents = membership.iterator();
        int visited = 0;
        while (documents.hasNext()) {
            if ((visited++ & 1023) == 0 && Thread.currentThread().isInterrupted()) {
                throw new java.io.InterruptedIOException("ranking scan interrupted");
            }
            int document = documents.nextInt();
            if (lengths == null || !lengths.advanceExact(document)) {
                throw new IOException("snapshot is missing len_tokens for internal document");
            }
            long length = lengths.longValue();
            double score = 0.0;
            for (int index = 0; index < atoms.size(); index++) {
                PostingsEnum cursor = postings[index];
                int frequency = 0;
                if (cursor != null) {
                    int current = cursor.docID();
                    if (current < document) {
                        current = cursor.advance(document);
                    }
                    if (current == document) {
                        frequency = cursor.freq();
                    }
                }
                double contribution = atoms.get(index).weight * termScore(atoms.get(index), frequency, length);
                score = score + contribution;
            }
            score = score == 0.0 ? 0.0 : score;
            if (!Double.isFinite(score) || score < 0.0) {
                throw new ArithmeticException("admitted scoring plan produced an invalid score");
            }
            String docKey = snapshot.docKeyForInternalDocument(document);
            if (heapLimit < 0) {
                all.add(new ScoredDocument(docKey, score));
            } else if (heap.size() < heapLimit) {
                heap.add(new ScoredDocument(docKey, score));
            } else if (score > heap.peek().score()
                    || (score == heap.peek().score()
                        && org.indexact.index.SnapshotBuilder.CODE_POINT_ORDER
                            .compare(docKey, heap.peek().docKey()) < 0)) {
                heap.poll();
                heap.add(new ScoredDocument(docKey, score));
            }
        }
        if (Thread.currentThread().isInterrupted()) {
            throw new java.io.InterruptedIOException("ranking scan interrupted");
        }
        return heapLimit < 0 ? all : new ArrayList<>(heap);
    }

    private static Materialization materialization(List<ScoredDocument> documents) {
        return new Materialization(
                documents.stream().map(ScoredDocument::docKey).toList(),
                documents.stream().map(ScoredDocument::score).toList());
    }

    private double termScore(Atom atom, int frequency, long documentLength) {
        if (frequency == 0) {
            return 0.0;
        }
        double f64 = (double) frequency;
        double len64 = (double) documentLength;
        double lenRatio = len64 / snapshot.manifest().avgdl();
        double normTail = bm25.b() * lenRatio;
        double norm = (1.0 - bm25.b()) + normTail;
        double satTail = bm25.k1() * norm;
        double denominator = f64 + satTail;
        double numerator = f64 * (bm25.k1() + 1.0);
        double tfPart = numerator / denominator;
        return atom.idf * tfPart;
    }

    private void validateFiniteUpperBound() {
        double k1Plus1 = bm25.k1() + 1.0;
        double bound = 0.0;
        for (Atom atom : atoms) {
            double contributionBound;
            if (atom.weight == 0.0 || atom.documentFrequency == 0) {
                contributionBound = 0.0;
            } else {
                double termBound = atom.idf * k1Plus1;
                contributionBound = atom.weight * termBound;
            }
            if (!Double.isFinite(contributionBound)) {
                throw new IllegalArgumentException("scoring contribution upper bound is non-finite");
            }
            bound = bound + contributionBound;
            if (!Double.isFinite(bound)) {
                throw new IllegalArgumentException("scoring accumulator upper bound is non-finite");
            }
        }
    }

    static double idf(long documentCount, long documentFrequency) {
        long difference = Math.subtractExact(documentCount, documentFrequency);
        double idfNumerator = (double) difference + 0.5;
        double idfDenominator = (double) documentFrequency + 0.5;
        double idfRatio = idfNumerator / idfDenominator;
        double idfArgument = 1.0 + idfRatio;
        return StrictMath.log(idfArgument);
    }

    private static List<WeightedTerm> submittedAtoms(ScoringExpression expression) {
        if (expression instanceof Term term) {
            return List.of(new WeightedTerm(1.0, term));
        }
        if (expression instanceof Combine combine) {
            return combine.terms().stream().map(term -> new WeightedTerm(1.0, term)).toList();
        }
        if (expression instanceof Weight weight) {
            return weight.atoms();
        }
        throw new IllegalArgumentException("unsupported scoring expression");
    }
}
