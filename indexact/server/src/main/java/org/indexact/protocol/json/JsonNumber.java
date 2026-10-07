package org.indexact.protocol.json;

import java.math.BigDecimal;
import java.math.BigInteger;

/** A syntactically valid JSON number retaining its exact input decimal spelling. */
public record JsonNumber(String lexeme) {
    public JsonNumber {
        if (lexeme == null || lexeme.isEmpty()) {
            throw new IllegalArgumentException("empty JSON number");
        }
    }

    public BigDecimal exactDecimal() {
        return new BigDecimal(lexeme);
    }

    public boolean isIntegerSyntax() {
        return lexeme.indexOf('.') < 0 && lexeme.indexOf('e') < 0 && lexeme.indexOf('E') < 0;
    }

    public BigInteger exactInteger() {
        if (!isIntegerSyntax()) {
            throw new ArithmeticException("JSON number is not integer syntax");
        }
        return new BigInteger(lexeme);
    }

    public boolean exactNegativeNonzero() {
        BigDecimal exact = exactDecimal();
        return exact.signum() < 0;
    }

    public double binary64() {
        return exactDecimal().doubleValue();
    }
}
