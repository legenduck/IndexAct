package org.indexact.protocol.json;

/** Invalid UTF-8/JSON, including duplicate object properties. */
public final class JsonParseException extends Exception {
    public JsonParseException(String message) {
        super(message);
    }

    public JsonParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
