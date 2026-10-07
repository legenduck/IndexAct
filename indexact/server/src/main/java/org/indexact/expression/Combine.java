package org.indexact.expression;

import java.util.List;
import java.util.Objects;

/** Flat unit-weight list of direct TERM atoms. */
public record Combine(List<Term> terms) implements ScoringExpression {
    public Combine {
        Objects.requireNonNull(terms, "COMBINE terms");
        if (terms.isEmpty()) {
            throw new IllegalArgumentException("COMBINE requires at least one TERM");
        }
        terms.forEach(term -> Objects.requireNonNull(term, "COMBINE TERM"));
        terms = List.copyOf(terms);
    }

    public Combine(Term first, Term... rest) {
        this(java.util.stream.Stream.concat(java.util.stream.Stream.of(first), List.of(rest).stream())
                .toList());
    }
}
