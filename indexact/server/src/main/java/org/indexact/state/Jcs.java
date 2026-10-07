package org.indexact.state;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.indexact.analysis.UnicodeScalar;
import org.indexact.protocol.json.CompactWireJson;

/** RFC 8785 JSON Canonicalization Scheme for admitted lineage values. */
public final class Jcs {
    private static final long MAX_SAFE_INTEGER = 9_007_199_254_740_991L;

    private Jcs() {}

    public static byte[] encode(Object value) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        write(value, output);
        return output.toByteArray();
    }

    private record WriteTask(int kind, Object value) {}

    private static void write(Object value, ByteArrayOutputStream output) {
        java.util.ArrayDeque<WriteTask> pending = new java.util.ArrayDeque<>();
        pending.push(new WriteTask(0, value));
        while (!pending.isEmpty()) {
            WriteTask task = pending.pop();
            Object current = task.value();
            if (task.kind() == 1) {
                ascii(output, (String) current);
            } else if (task.kind() == 2) {
                string((String) current, output);
            } else if (current == null) {
                ascii(output, "null");
            } else if (current instanceof Boolean bool) {
                ascii(output, bool ? "true" : "false");
            } else if (current instanceof Byte || current instanceof Short
                    || current instanceof Integer || current instanceof Long) {
                long integer = ((Number) current).longValue();
                if (integer < -MAX_SAFE_INTEGER || integer > MAX_SAFE_INTEGER) {
                    throw new IllegalArgumentException(
                            "lineage integer is outside the I-JSON safe range");
                }
                ascii(output, Long.toString(integer));
            } else if (current instanceof Float number) {
                ascii(output, CompactWireJson.shortestDouble(number.doubleValue()));
            } else if (current instanceof Double number) {
                ascii(output, CompactWireJson.shortestDouble(number));
            } else if (current instanceof String string) {
                string(string, output);
            } else if (current instanceof Map<?, ?> map) {
                List<Map.Entry<String, ?>> entries = new ArrayList<>();
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    if (!(entry.getKey() instanceof String key)) {
                        throw new IllegalArgumentException("lineage object keys must be strings");
                    }
                    entries.add(new java.util.AbstractMap.SimpleImmutableEntry<>(
                            key, entry.getValue()));
                }
                entries.sort((left, right) -> left.getKey().compareTo(right.getKey()));
                pending.push(new WriteTask(1, "}"));
                for (int index = entries.size() - 1; index >= 0; index--) {
                    Map.Entry<String, ?> entry = entries.get(index);
                    if (index + 1 < entries.size()) {
                        pending.push(new WriteTask(1, ","));
                    }
                    pending.push(new WriteTask(0, entry.getValue()));
                    pending.push(new WriteTask(1, ":"));
                    pending.push(new WriteTask(2, entry.getKey()));
                }
                pending.push(new WriteTask(1, "{"));
            } else if (current instanceof Iterable<?> iterable) {
                ArrayList<Object> items = new ArrayList<>();
                iterable.forEach(items::add);
                pending.push(new WriteTask(1, "]"));
                for (int index = items.size() - 1; index >= 0; index--) {
                    if (index + 1 < items.size()) {
                        pending.push(new WriteTask(1, ","));
                    }
                    pending.push(new WriteTask(0, items.get(index)));
                }
                pending.push(new WriteTask(1, "["));
            } else if (current instanceof Object[] array) {
                pending.push(new WriteTask(0, List.of(array)));
            } else {
                throw new IllegalArgumentException(
                        "value is outside the lineage JSON domain: "
                                + current.getClass().getName());
            }
        }
    }

    private static void string(String value, ByteArrayOutputStream output) {
        int[] codePoints = UnicodeScalar.toCodePoints(value);
        for (int codePoint : codePoints) {
            if (isNoncharacter(codePoint)) {
                throw new IllegalArgumentException("lineage strings must exclude Unicode noncharacters");
            }
        }
        ascii(output, "\"");
        for (int codePoint : codePoints) {
            switch (codePoint) {
                case '"' -> ascii(output, "\\\"");
                case '\\' -> ascii(output, "\\\\");
                case '\b' -> ascii(output, "\\b");
                case '\t' -> ascii(output, "\\t");
                case '\n' -> ascii(output, "\\n");
                case '\f' -> ascii(output, "\\f");
                case '\r' -> ascii(output, "\\r");
                default -> {
                    if (codePoint < 0x20) {
                        ascii(output, String.format("\\u%04x", codePoint));
                    } else {
                        output.writeBytes(new String(Character.toChars(codePoint))
                                .getBytes(StandardCharsets.UTF_8));
                    }
                }
            }
        }
        ascii(output, "\"");
    }

    private static boolean isNoncharacter(int codePoint) {
        return codePoint >= 0xFDD0 && codePoint <= 0xFDEF
                || codePoint <= 0x10FFFF && (codePoint & 0xFFFF) >= 0xFFFE;
    }

    private static void ascii(ByteArrayOutputStream output, String value) {
        output.writeBytes(value.getBytes(StandardCharsets.US_ASCII));
    }
}
