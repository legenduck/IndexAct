package org.indexact.read;

final class ReadValues {
    static final long MAX_SAFE_INTEGER = 9_007_199_254_740_991L;

    private ReadValues() {}

    static long requireNonNegativeSafe(long value, String name) {
        if (value < 0 || value > MAX_SAFE_INTEGER) {
            throw new IllegalArgumentException(
                    name + " must be in [0," + MAX_SAFE_INTEGER + "]");
        }
        return value;
    }

    static long requirePositiveSafe(long value, String name) {
        requireNonNegativeSafe(value, name);
        if (value == 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }
}
