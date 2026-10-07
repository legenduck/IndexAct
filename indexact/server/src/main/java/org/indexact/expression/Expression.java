package org.indexact.expression;

/** Root of the parsed, typed public expression hierarchy. */
public sealed interface Expression permits TextCondition, ScoringExpression {}
