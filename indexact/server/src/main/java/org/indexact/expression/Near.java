package org.indexact.expression;

import java.util.List;

/** Gap-sensitive positional conjunction. */
public record Near(List<LexicalExpression> children, boolean ordered, long maxGaps)
        implements LexicalExpression {
    public Near {
        children = ExpressionValidation.children(children, "NEAR");
        maxGaps = ExpressionValidation.nonNegativeSafeInteger(maxGaps, "NEAR max_gaps");
    }

    public Near(
            boolean ordered,
            long maxGaps,
            LexicalExpression first,
            LexicalExpression second,
            LexicalExpression... rest) {
        this(ExpressionLists.of(first, second, rest), ordered, maxGaps);
    }
}
