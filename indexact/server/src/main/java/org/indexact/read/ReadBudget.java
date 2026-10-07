package org.indexact.read;

/** The two independent, inclusive semantic READ limits. */
public record ReadBudget(long maxOutputCodePoints, long maxEvidenceCount) {
    public ReadBudget {
        ReadValues.requireNonNegativeSafe(maxOutputCodePoints, "maxOutputCodePoints");
        ReadValues.requireNonNegativeSafe(maxEvidenceCount, "maxEvidenceCount");
    }
}
