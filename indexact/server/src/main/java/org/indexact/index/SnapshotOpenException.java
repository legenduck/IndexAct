package org.indexact.index;

/** Snapshot-store failure mapped directly to the public OPEN_SESSION taxonomy. */
public final class SnapshotOpenException extends Exception {
    public enum Reason {
        SNAPSHOT_NOT_FOUND,
        SNAPSHOT_INCOMPATIBLE,
        INTERNAL_ERROR
    }

    private final Reason reason;

    public SnapshotOpenException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public SnapshotOpenException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
