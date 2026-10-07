package org.indexact.read;

/** One-based occurrence selector. */
public record NthSelector(long n) implements OccurrenceSelector {
    public NthSelector {
        ReadValues.requirePositiveSafe(n, "n");
    }
}
