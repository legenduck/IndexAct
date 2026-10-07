package org.indexact.expression;

/** TERM preserves its submitted surface; analysis is snapshot-bound execution validation. */
public record Term(String surface) implements LexicalExpression, ScoringExpression {
    public Term {
        surface = ExpressionValidation.lineageString(surface, "TERM surface");
    }
}
