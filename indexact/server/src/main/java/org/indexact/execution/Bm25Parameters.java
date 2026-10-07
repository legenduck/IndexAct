package org.indexact.execution;

/** Immutable, server-scoped ranking parameters for the experiment corpora. */
public record Bm25Parameters(double k1, double b) {
    public static final Bm25Parameters BCPLUS = new Bm25Parameters(25.0, 1.0);
    public static final Bm25Parameters WIKIPEDIA_18 = new Bm25Parameters(1.2, 0.75);

    public Bm25Parameters {
        if (!Double.isFinite(k1) || k1 < 0.0
                || !Double.isFinite(b) || b < 0.0 || b > 1.0) {
            throw new IllegalArgumentException("invalid BM25 parameters");
        }
    }

    public static Bm25Parameters forDataset(String dataset) {
        return switch (dataset) {
            case "bcplus" -> BCPLUS;
            case "wikipedia-18" -> WIKIPEDIA_18;
            default -> throw new IllegalArgumentException(
                    "unknown dataset: " + dataset + "; expected bcplus or wikipedia-18");
        };
    }
}
