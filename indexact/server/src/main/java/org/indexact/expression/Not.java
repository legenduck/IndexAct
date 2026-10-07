package org.indexact.expression;

import java.util.Objects;

/** Document-level negation. Its complement universe is supplied by the state operation. */
public record Not(TextCondition child) implements TextCondition {
    public Not {
        Objects.requireNonNull(child, "NOT child");
    }
}
