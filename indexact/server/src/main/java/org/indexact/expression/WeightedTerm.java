package org.indexact.expression;

import java.util.Objects;

/** One admitted finite non-negative binary64 weight and direct TERM. */
public record WeightedTerm(double weight, Term term) {
    public WeightedTerm {
        Objects.requireNonNull(term, "WEIGHT TERM");
        if (!Double.isFinite(weight) || weight < 0.0) {
            throw new IllegalArgumentException("weight must be finite and non-negative");
        }
        if (weight == 0.0) {
            weight = 0.0;
        }
    }
}
