package org.indexact.protocol.json;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.AbstractMap;
import java.util.List;
import java.util.Map;
import org.indexact.analysis.UnicodeScalar;
import org.indexact.index.SnapshotBuilder;

/** Exact CompactWireJSON encoder with a hard response-body byte cap. */
public final class CompactWireJson {
    /** Marks a map-like diagnostic object whose properties use code-point sort order. */
    public record SortedObject(Map<String, ?> values) {
        public SortedObject {
            values = Map.copyOf(values);
        }
    }

    public static final class ResponseTooLargeException extends Exception {
        private final long limit;

        ResponseTooLargeException(long limit) {
            super("CompactWireJSON response exceeds " + limit + " bytes");
            this.limit = limit;
        }

        public long limit() {
            return limit;
        }
    }

    private CompactWireJson() {}

    public static byte[] encode(Object value, long maxBytes) throws ResponseTooLargeException {
        if (maxBytes < 0) {
            throw new IllegalArgumentException("maxBytes must be non-negative");
        }
        Output output = new Output(maxBytes);
        write(value, output);
        return output.bytes();
    }

    public static byte[] encode(Object value) {
        try {
            return encode(value, Long.MAX_VALUE);
        } catch (ResponseTooLargeException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private record WriteTask(int kind, Object value) {}

    private static void write(Object value, Output output) throws ResponseTooLargeException {
        java.util.ArrayDeque<WriteTask> pending = new java.util.ArrayDeque<>();
        pending.push(new WriteTask(0, value));
        while (!pending.isEmpty()) {
            WriteTask task = pending.pop();
            Object current = task.value();
            if (task.kind() == 1) {
                output.ascii((String) current);
            } else if (task.kind() == 2) {
                string((String) current, output);
            } else if (current == null) {
                output.ascii("null");
            } else if (current instanceof Boolean bool) {
                output.ascii(bool ? "true" : "false");
            } else if (current instanceof Byte || current instanceof Short
                    || current instanceof Integer || current instanceof Long) {
                output.ascii(current.toString());
            } else if (current instanceof Float number) {
                output.ascii(shortestDouble(number.doubleValue()));
            } else if (current instanceof Double number) {
                output.ascii(shortestDouble(number));
            } else if (current instanceof String string) {
                string(string, output);
            } else if (current instanceof SortedObject sorted) {
                List<Map.Entry<String, ?>> entries = new ArrayList<>(
                        sorted.values().entrySet());
                entries.sort((left, right) -> SnapshotBuilder.CODE_POINT_ORDER.compare(
                        left.getKey(), right.getKey()));
                scheduleObject(entries, pending);
            } else if (current instanceof Map<?, ?> map) {
                List<Map.Entry<String, ?>> entries = new ArrayList<>();
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    if (!(entry.getKey() instanceof String key)) {
                        throw new IllegalArgumentException("JSON object keys must be strings");
                    }
                    entries.add(new AbstractMap.SimpleImmutableEntry<>(key, entry.getValue()));
                }
                scheduleObject(entries, pending);
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
            } else if (current.getClass().isArray() && current instanceof Object[] array) {
                pending.push(new WriteTask(0, List.of(array)));
            } else {
                throw new IllegalArgumentException(
                        "value is not CompactWireJSON-compatible: "
                                + current.getClass().getName());
            }
        }
    }

    private static void scheduleObject(
            List<? extends Map.Entry<String, ?>> entries,
            java.util.ArrayDeque<WriteTask> pending) {
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
    }

    private static void string(String value, Output output) throws ResponseTooLargeException {
        int[] codePoints = UnicodeScalar.toCodePoints(value);
        output.ascii("\"");
        for (int codePoint : codePoints) {
            switch (codePoint) {
                case '"' -> output.ascii("\\\"");
                case '\\' -> output.ascii("\\\\");
                case '\b' -> output.ascii("\\b");
                case '\t' -> output.ascii("\\t");
                case '\n' -> output.ascii("\\n");
                case '\f' -> output.ascii("\\f");
                case '\r' -> output.ascii("\\r");
                default -> {
                    if (codePoint < 0x20) {
                        output.ascii(String.format("\\u%04x", codePoint));
                    } else {
                        output.utf8(new String(Character.toChars(codePoint)));
                    }
                }
            }
        }
        output.ascii("\"");
    }

    /** ECMAScript/JCS thresholds over the shortest decimal round-tripping to the input. */
    public static String shortestDouble(double value) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("JSON binary64 must be finite");
        }
        if (value == 0.0) {
            return "0";
        }
        boolean negative = value < 0.0;
        double magnitude = Math.abs(value);
        BigDecimal exact = new BigDecimal(magnitude);
        BigDecimal shortest = null;
        for (int precision = 1; precision <= 17; precision++) {
            BigDecimal candidate = exact.round(new MathContext(precision, RoundingMode.HALF_EVEN))
                    .stripTrailingZeros();
            if (Double.doubleToRawLongBits(candidate.doubleValue())
                    == Double.doubleToRawLongBits(magnitude)) {
                shortest = candidate;
                break;
            }
        }
        if (shortest == null) {
            throw new AssertionError("no round-tripping decimal for binary64");
        }

        String digits = shortest.unscaledValue().abs().toString();
        int exponent = digits.length() - shortest.scale() - 1;
        String unsigned;
        if (magnitude >= 1e-6 && magnitude < 1e21) {
            unsigned = shortest.toPlainString();
        } else {
            String mantissa = digits.length() == 1
                    ? digits
                    : digits.charAt(0) + "." + digits.substring(1);
            unsigned = mantissa + "e" + (exponent >= 0 ? "+" : "") + exponent;
        }
        return negative ? "-" + unsigned : unsigned;
    }

    private static final class Output {
        private final long limit;
        private final ByteArrayOutputStream stream = new ByteArrayOutputStream();

        private Output(long limit) {
            this.limit = limit;
        }

        private void ascii(String value) throws ResponseTooLargeException {
            write(value.getBytes(StandardCharsets.US_ASCII));
        }

        private void utf8(String value) throws ResponseTooLargeException {
            write(value.getBytes(StandardCharsets.UTF_8));
        }

        private void write(byte[] bytes) throws ResponseTooLargeException {
            if ((long) stream.size() + bytes.length > limit) {
                throw new ResponseTooLargeException(limit);
            }
            stream.writeBytes(bytes);
        }

        private byte[] bytes() {
            return stream.toByteArray();
        }
    }
}
