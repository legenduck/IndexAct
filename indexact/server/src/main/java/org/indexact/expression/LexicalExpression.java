package org.indexact.expression;

/** A text condition that also denotes concrete token occurrence intervals. */
public sealed interface LexicalExpression extends TextCondition permits Term, AnyOf, Phrase, Near {}
