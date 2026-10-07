package org.indexact.protocol;

import java.util.Map;

/** A bounded symbolic request or operation failure before result encoding. */
public final class ProtocolFailure extends Exception {
    private final ErrorCode code;
    private final Map<String, Object> details;

    public ProtocolFailure(ErrorCode code, String message) {
        this(code, message, Map.of());
    }

    public ProtocolFailure(ErrorCode code, String message, Map<String, Object> details) {
        super(message);
        this.code = code;
        this.details = Map.copyOf(details);
    }

    public ErrorCode code() {
        return code;
    }

    public Map<String, Object> details() {
        return details;
    }
}
