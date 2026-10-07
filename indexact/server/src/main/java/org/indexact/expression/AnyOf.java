package org.indexact.expression;

import java.util.List;

/** Positional disjunction. Submitted operand order and duplicates are retained. */
public record AnyOf(List<LexicalExpression> children) implements LexicalExpression {
    public AnyOf {
        children = ExpressionValidation.children(children, "ANY_OF");
    }

    public AnyOf(LexicalExpression first, LexicalExpression second, LexicalExpression... rest) {
        this(ExpressionLists.of(first, second, rest));
    }
}
