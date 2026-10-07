package org.indexact.protocol;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.indexact.protocol.json.CompactWireJson;
import org.indexact.protocol.json.CompactWireJson.ResponseTooLargeException;

/** Schema-ordered response construction, diagnostic bounding, and exact cap admission. */
public final class ResultCodec {
    private ResultCodec() {}

    public static byte[] encode(Object value, ServiceLimits limits) throws ResponseTooLargeException {
        return CompactWireJson.encode(value, limits.maxResponseBytes());
    }

    public static byte[] error(ProtocolFailure failure, ServiceLimits limits) {
        ProtocolFailure bounded = bounded(failure, limits);
        try {
            return encode(errorValue(bounded), limits);
        } catch (ResponseTooLargeException tooLarge) {
            ProtocolFailure minimal = new ProtocolFailure(
                    ErrorCode.RESOURCE_LIMIT_EXCEEDED, "response exceeds max_response_bytes");
            try {
                return encode(errorValue(minimal), limits);
            } catch (ResponseTooLargeException impossible) {
                throw new IllegalStateException(
                        "invalid ServiceLimits cannot encode the mandatory minimal error", impossible);
            }
        }
    }

    public static Map<String, Object> errorValue(ProtocolFailure failure) {
        LinkedHashMap<String, Object> error = new LinkedHashMap<>();
        error.put("code", failure.code().name());
        error.put("message", failure.getMessage());
        if (!failure.details().isEmpty()) {
            error.put("details", diagnostic(failure.details()));
        }
        LinkedHashMap<String, Object> response = new LinkedHashMap<>();
        response.put("error", error);
        return response;
    }

    public static ProtocolFailure bounded(ProtocolFailure failure, ServiceLimits limits) {
        String message = truncateUtf8(failure.getMessage(), limits.maxLineageStringUtf8Bytes());
        Map<String, Object> details = failure.details();
        if (!details.isEmpty()) {
            byte[] encoded = CompactWireJson.encode(diagnostic(details));
            if (encoded.length > 4096) {
                details = Map.of();
            }
        }
        return new ProtocolFailure(failure.code(), message, details);
    }

    private static Object diagnostic(Object value) {
        if (value instanceof Map<?, ?> map) {
            LinkedHashMap<String, Object> nested = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key)) {
                    throw new IllegalArgumentException("diagnostic property names must be strings");
                }
                nested.put(key, diagnostic(entry.getValue()));
            }
            return new CompactWireJson.SortedObject(nested);
        }
        if (value instanceof Iterable<?> iterable) {
            ArrayList<Object> items = new ArrayList<>();
            iterable.forEach(item -> items.add(diagnostic(item)));
            return List.copyOf(items);
        }
        return value;
    }

    private static String truncateUtf8(String message, long limit) {
        byte[] utf8 = message.getBytes(StandardCharsets.UTF_8);
        if (utf8.length <= limit) {
            return message;
        }
        int bytes = Math.toIntExact(limit);
        while (bytes > 0 && (utf8[bytes] & 0xC0) == 0x80) {
            bytes--;
        }
        return new String(utf8, 0, bytes, StandardCharsets.UTF_8);
    }
}
