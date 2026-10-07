package org.indexact.read;

/** Semantic READ failure for later mapping to the protocol error taxonomy. */
public final class ReadSemanticException extends Exception {
    public enum Reason {
        DOCUMENT_NOT_FOUND,
        INVALID_STATE_REF,
        INVALID_ARGUMENT,
        TYPE_MISMATCH
    }

    private final Reason reason;

    public ReadSemanticException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public ReadSemanticException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
