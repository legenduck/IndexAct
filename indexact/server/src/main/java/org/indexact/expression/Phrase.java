package org.indexact.expression;

import java.util.List;

/** PHRASE is retained in the public AST and executes as ordered NEAR with zero gaps. */
public record Phrase(List<LexicalExpression> children) implements LexicalExpression {
    public Phrase {
        children = ExpressionValidation.children(children, "PHRASE");
    }

    public Phrase(LexicalExpression first, LexicalExpression second, LexicalExpression... rest) {
        this(ExpressionLists.of(first, second, rest));
    }
}
