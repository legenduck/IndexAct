package org.indexact.expression;

/** Flat v2.2 scoring syntax; TERM is shared with lexical expressions. */
public sealed interface ScoringExpression extends Expression permits Term, Combine, Weight {}
