package org.indexact.index;

/** Explicit snapshot-build failure; invalid input is never truncated or partially published. */
public final class BuildError extends Exception {
    public enum Reason {
        INVALID_DOC_KEY,
        DUPLICATE_DOC_KEY,
        INVALID_RAW_TEXT,
        INVALID_INPUT_RECORD,
        TOKEN_TOO_LONG,
        NUMERIC_LIMIT_EXCEEDED,
        INDEX_IO_FAILURE
    }

    private final Reason reason;

    public BuildError(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public BuildError(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
