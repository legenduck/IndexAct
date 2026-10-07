package org.indexact.expression;

/** An expression with a document-level truth value. */
public sealed interface TextCondition extends Expression permits LexicalExpression, And, Or, Not {}
