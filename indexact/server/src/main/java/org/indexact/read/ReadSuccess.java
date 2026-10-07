package org.indexact.read;

import java.util.List;
import java.util.Objects;

/** Complete READ materialization admitted by both semantic budgets. */
public record ReadSuccess(
        long targetDocs,
        long selectedDocs,
        long regionDocs,
        long evidenceCount,
        long requiredOutputCodePoints,
        PageSelection page,
        List<Evidence> evidence)
        implements ReadResult {
    public ReadSuccess {
        ReadValues.requireNonNegativeSafe(targetDocs, "targetDocs");
        ReadValues.requireNonNegativeSafe(selectedDocs, "selectedDocs");
        ReadValues.requireNonNegativeSafe(regionDocs, "regionDocs");
        ReadValues.requireNonNegativeSafe(evidenceCount, "evidenceCount");
        ReadValues.requireNonNegativeSafe(requiredOutputCodePoints, "requiredOutputCodePoints");
        evidence = List.copyOf(Objects.requireNonNull(evidence, "evidence"));
        if (selectedDocs > targetDocs
                || regionDocs > selectedDocs
                || evidenceCount != evidence.size()) {
            throw new IllegalArgumentException("inconsistent READ success counters");
        }
        long measured = evidence.stream().mapToLong(item -> item.end() - item.start()).sum();
        if (measured != requiredOutputCodePoints) {
            throw new IllegalArgumentException("Evidence length does not equal required output");
        }
        if (page != null && page.docKeys().size() != selectedDocs) {
            throw new IllegalArgumentException("page size does not equal selectedDocs");
        }
    }

    @Override
    public String status() {
        return "success";
    }
}
