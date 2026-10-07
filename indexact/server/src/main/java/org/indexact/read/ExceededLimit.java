package org.indexact.read;

/** Stable wire-order names for the two semantic READ budgets. */
public enum ExceededLimit {
    OUTPUT_CODEPOINTS("output_codepoints"),
    EVIDENCE_COUNT("evidence_count");

    private final String wireName;

    ExceededLimit(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }
}
