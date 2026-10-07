package org.indexact.expression;

import java.util.List;
import java.util.Objects;
import org.indexact.analysis.UnicodeScalar;

final class ExpressionValidation {
    static final long MAX_SAFE_INTEGER = 9_007_199_254_740_991L;

    private ExpressionValidation() {}

    static String lineageString(String value, String label) {
        Objects.requireNonNull(value, label);
        int[] codePoints = UnicodeScalar.toCodePoints(value);
        for (int codePoint : codePoints) {
            if (codePoint >= 0xFDD0 && codePoint <= 0xFDEF
                    || (codePoint & 0xFFFE) == 0xFFFE) {
                throw new IllegalArgumentException(label + " contains a Unicode noncharacter");
            }
        }
        return value;
    }

    static <T> List<T> children(List<? extends T> children, String constructor) {
        Objects.requireNonNull(children, constructor + " children");
        if (children.size() < 2) {
            throw new IllegalArgumentException(constructor + " requires at least two children");
        }
        for (T child : children) {
            Objects.requireNonNull(child, constructor + " child");
        }
        return List.copyOf(children);
    }

    static long nonNegativeSafeInteger(long value, String label) {
        if (value < 0 || value > MAX_SAFE_INTEGER) {
            throw new IllegalArgumentException(
                    label + " must be in [0," + MAX_SAFE_INTEGER + "]");
        }
        return value;
    }
}
