package org.indexact.read;

/** Strict half-open raw-code-point interval for a direct DocKey target. */
public record RangeRegion(long start, long end) implements ReadRegion {
    public RangeRegion {
        ReadValues.requireNonNegativeSafe(start, "start");
        ReadValues.requireNonNegativeSafe(end, "end");
        if (start > end) {
            throw new IllegalArgumentException("start must not exceed end");
        }
    }
}
