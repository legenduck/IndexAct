package org.indexact.read;

import java.util.List;
import java.util.Objects;

/** Deterministic semantic-budget verdict containing no Evidence. */
public record ReadBudgetExceeded(
        ReadBudget budget,
        List<ExceededLimit> exceededLimits,
        long targetDocs,
        long selectedDocs,
        boolean evaluationComplete,
        PageSelection page,
        long observedOutputCodePointsLowerBound,
        long evaluatedDocsCount,
        long regionDocsLowerBound,
        long evidenceCountLowerBound,
        Long requiredOutputCodePoints,
        Long regionDocs,
        Long evidenceCount)
        implements ReadResult {
    public ReadBudgetExceeded {
        Objects.requireNonNull(budget, "budget");
        exceededLimits = List.copyOf(Objects.requireNonNull(exceededLimits, "exceededLimits"));
        if (exceededLimits.isEmpty()
                || exceededLimits.stream().distinct().count() != exceededLimits.size()) {
            throw new IllegalArgumentException("exceededLimits must be a non-empty set-like list");
        }
        ReadValues.requireNonNegativeSafe(targetDocs, "targetDocs");
        ReadValues.requireNonNegativeSafe(selectedDocs, "selectedDocs");
        ReadValues.requireNonNegativeSafe(
                observedOutputCodePointsLowerBound, "observedOutputCodePointsLowerBound");
        ReadValues.requireNonNegativeSafe(evaluatedDocsCount, "evaluatedDocsCount");
        ReadValues.requireNonNegativeSafe(regionDocsLowerBound, "regionDocsLowerBound");
        ReadValues.requireNonNegativeSafe(evidenceCountLowerBound, "evidenceCountLowerBound");
        if (selectedDocs > targetDocs
                || evaluatedDocsCount > selectedDocs
                || regionDocsLowerBound > evaluatedDocsCount) {
            throw new IllegalArgumentException("inconsistent READ budget lower bounds");
        }
        if (page != null && page.docKeys().size() != selectedDocs) {
            throw new IllegalArgumentException("page size does not equal selectedDocs");
        }
        if (evaluationComplete) {
            if (requiredOutputCodePoints == null || regionDocs == null || evidenceCount == null) {
                throw new IllegalArgumentException("complete evaluation requires exact counters");
            }
            ReadValues.requireNonNegativeSafe(requiredOutputCodePoints, "requiredOutputCodePoints");
            ReadValues.requireNonNegativeSafe(regionDocs, "regionDocs");
            ReadValues.requireNonNegativeSafe(evidenceCount, "evidenceCount");
            if (evaluatedDocsCount != selectedDocs
                    || !requiredOutputCodePoints.equals(observedOutputCodePointsLowerBound)
                    || !regionDocs.equals(regionDocsLowerBound)
                    || !evidenceCount.equals(evidenceCountLowerBound)) {
                throw new IllegalArgumentException("complete evaluation counters must be exact");
            }
        } else if (requiredOutputCodePoints != null || regionDocs != null || evidenceCount != null) {
            throw new IllegalArgumentException("incomplete evaluation must omit exact counters");
        }
        boolean outputExceeded = observedOutputCodePointsLowerBound > budget.maxOutputCodePoints();
        boolean evidenceExceeded = evidenceCountLowerBound > budget.maxEvidenceCount();
        List<ExceededLimit> canonicalLimits = outputExceeded
                ? (evidenceExceeded
                        ? List.of(ExceededLimit.OUTPUT_CODEPOINTS, ExceededLimit.EVIDENCE_COUNT)
                        : List.of(ExceededLimit.OUTPUT_CODEPOINTS))
                : (evidenceExceeded ? List.of(ExceededLimit.EVIDENCE_COUNT) : List.of());
        if (!exceededLimits.equals(canonicalLimits)) {
            throw new IllegalArgumentException("exceededLimits disagree with proven lower bounds");
        }
    }

    @Override
    public String status() {
        return "budget_exceeded";
    }
}
