package org.indexact.read;

import java.util.Objects;
import org.indexact.expression.LexicalExpression;

/** A raw-code-point window around occurrences of a lexical anchor. */
public record AroundRegion(
        LexicalExpression anchor, OccurrenceSelector selector, long before, long after)
        implements ReadRegion {
    public AroundRegion {
        Objects.requireNonNull(anchor, "anchor");
        Objects.requireNonNull(selector, "selector");
        ReadValues.requireNonNegativeSafe(before, "before");
        ReadValues.requireNonNegativeSafe(after, "after");
    }
}
