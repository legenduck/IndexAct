package org.indexact.expression;

import java.util.List;
import java.util.Objects;

/** Flat submitted-order weighted TERM multiset. */
public record Weight(List<WeightedTerm> atoms) implements ScoringExpression {
    public Weight {
        Objects.requireNonNull(atoms, "WEIGHT atoms");
        if (atoms.isEmpty()) {
            throw new IllegalArgumentException("WEIGHT requires at least one atom");
        }
        atoms.forEach(atom -> Objects.requireNonNull(atom, "WEIGHT atom"));
        atoms = List.copyOf(atoms);
    }

    public Weight(WeightedTerm first, WeightedTerm... rest) {
        this(java.util.stream.Stream.concat(java.util.stream.Stream.of(first), List.of(rest).stream())
                .toList());
    }
}
