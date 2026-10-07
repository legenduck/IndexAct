package org.indexact.execution;

/** Non-empty half-open occurrence interval in dense analyzed-token coordinates. */
public record Interval(long start, long end) implements Comparable<Interval> {
    public static final long MAX_SAFE_INTEGER = 9_007_199_254_740_991L;

    public Interval {
        if (start < 0 || end <= start || end > MAX_SAFE_INTEGER) {
            throw new IllegalArgumentException("invalid occurrence interval [" + start + "," + end + ")");
        }
    }

    public long length() {
        return end - start;
    }

    @Override
    public int compareTo(Interval other) {
        int byStart = Long.compare(start, other.start);
        return byStart != 0 ? byStart : Long.compare(end, other.end);
    }
}
