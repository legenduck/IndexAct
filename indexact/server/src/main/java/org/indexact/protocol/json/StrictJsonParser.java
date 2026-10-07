package org.indexact.protocol.json;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Dependency-free strict UTF-8 JSON parser retaining number lexemes and insertion order. */
public final class StrictJsonParser {
    private static final int OBJECT_FIRST = 0;
    private static final int OBJECT_KEY = 1;
    private static final int OBJECT_VALUE = 2;
    private static final int OBJECT_AFTER = 3;
    private static final int ARRAY_FIRST = 4;
    private static final int ARRAY_VALUE = 5;
    private static final int ARRAY_AFTER = 6;

    private final String source;
    private final ArrayDeque<Frame> stack = new ArrayDeque<>();
    private int cursor;
    private Object result;
    private boolean hasResult;

    private static final class Frame {
        private final Object container;
        private int state;
        private String key;

        private Frame(Object container, int state) {
            this.container = container;
            this.state = state;
        }
    }

    private StrictJsonParser(String source) {
        this.source = source;
    }

    public static Object parse(byte[] utf8) throws JsonParseException {
        final String source;
        try {
            source = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(utf8))
                    .toString();
        } catch (CharacterCodingException error) {
            throw new JsonParseException("request body is not valid UTF-8", error);
        }
        StrictJsonParser parser = new StrictJsonParser(source);
        Object result = parser.valueIterative();
        parser.whitespace();
        if (parser.cursor != source.length()) {
            throw parser.error("trailing data after JSON value");
        }
        return result;
    }

    private Object valueIterative() throws JsonParseException {
        beginValue();
        while (!stack.isEmpty()) {
            advanceContainer();
        }
        return result;
    }

    private void beginValue() throws JsonParseException {
        whitespace();
        if (cursor >= source.length()) {
            throw error("expected a JSON value");
        }
        switch (source.charAt(cursor)) {
            case '{' -> {
                cursor++;
                LinkedHashMap<String, Object> object = new LinkedHashMap<>();
                accept(object);
                stack.push(new Frame(object, OBJECT_FIRST));
            }
            case '[' -> {
                cursor++;
                ArrayList<Object> array = new ArrayList<>();
                accept(array);
                stack.push(new Frame(array, ARRAY_FIRST));
            }
            case '"' -> accept(string());
            case 't' -> accept(literal("true", Boolean.TRUE));
            case 'f' -> accept(literal("false", Boolean.FALSE));
            case 'n' -> accept(literal("null", null));
            default -> accept(number());
        }
    }

    private void advanceContainer() throws JsonParseException {
        Frame frame = stack.peek();
        whitespace();
        switch (frame.state) {
            case OBJECT_FIRST -> {
                if (consume('}')) {
                    stack.pop();
                } else {
                    frame.state = OBJECT_KEY;
                }
            }
            case OBJECT_KEY -> {
                if (cursor >= source.length() || source.charAt(cursor) != '"') {
                    throw error("object property name must be a string");
                }
                String key = string();
                @SuppressWarnings("unchecked")
                Map<String, Object> object = (Map<String, Object>) frame.container;
                if (object.containsKey(key)) {
                    throw error("duplicate object property " + printable(key));
                }
                whitespace();
                require(':');
                frame.key = key;
                frame.state = OBJECT_VALUE;
            }
            case OBJECT_VALUE, ARRAY_VALUE -> beginValue();
            case OBJECT_AFTER -> {
                if (consume('}')) {
                    stack.pop();
                } else {
                    require(',');
                    frame.state = OBJECT_KEY;
                }
            }
            case ARRAY_FIRST -> {
                if (consume(']')) {
                    stack.pop();
                } else {
                    frame.state = ARRAY_VALUE;
                }
            }
            case ARRAY_AFTER -> {
                if (consume(']')) {
                    stack.pop();
                } else {
                    require(',');
                    frame.state = ARRAY_VALUE;
                }
            }
            default -> throw new IllegalStateException("invalid JSON parser frame state");
        }
    }

    private void accept(Object value) {
        if (stack.isEmpty()) {
            if (hasResult) {
                throw new IllegalStateException("multiple root JSON values");
            }
            result = value;
            hasResult = true;
            return;
        }
        Frame frame = stack.peek();
        if (frame.state == OBJECT_VALUE) {
            @SuppressWarnings("unchecked")
            Map<String, Object> object = (Map<String, Object>) frame.container;
            object.put(frame.key, value);
            frame.key = null;
            frame.state = OBJECT_AFTER;
        } else if (frame.state == ARRAY_VALUE) {
            @SuppressWarnings("unchecked")
            List<Object> array = (List<Object>) frame.container;
            array.add(value);
            frame.state = ARRAY_AFTER;
        } else {
            throw new IllegalStateException("JSON value in an invalid parser state");
        }
    }

    private String string() throws JsonParseException {
        require('"');
        StringBuilder result = new StringBuilder();
        while (cursor < source.length()) {
            char current = source.charAt(cursor++);
            if (current == '"') {
                return result.toString();
            }
            if (current < 0x20) {
                throw error("unescaped control character in string");
            }
            if (current != '\\') {
                result.append(current);
                continue;
            }
            if (cursor >= source.length()) {
                throw error("unterminated string escape");
            }
            char escaped = source.charAt(cursor++);
            switch (escaped) {
                case '"', '\\', '/' -> result.append(escaped);
                case 'b' -> result.append('\b');
                case 'f' -> result.append('\f');
                case 'n' -> result.append('\n');
                case 'r' -> result.append('\r');
                case 't' -> result.append('\t');
                case 'u' -> result.append((char) hexQuad());
                default -> throw error("invalid string escape");
            }
        }
        throw error("unterminated string");
    }

    private int hexQuad() throws JsonParseException {
        if (cursor + 4 > source.length()) {
            throw error("short Unicode escape");
        }
        int value = 0;
        for (int index = 0; index < 4; index++) {
            int digit = Character.digit(source.charAt(cursor++), 16);
            if (digit < 0) {
                throw error("invalid Unicode escape");
            }
            value = value * 16 + digit;
        }
        return value;
    }

    private JsonNumber number() throws JsonParseException {
        int start = cursor;
        consume('-');
        if (cursor >= source.length()) {
            throw error("incomplete JSON number");
        }
        if (consume('0')) {
            if (cursor < source.length() && isDigit(source.charAt(cursor))) {
                throw error("leading zero in JSON number");
            }
        } else {
            if (!isOneToNine(source.charAt(cursor))) {
                throw error("invalid JSON number");
            }
            while (cursor < source.length() && isDigit(source.charAt(cursor))) {
                cursor++;
            }
        }
        if (consume('.')) {
            int fractionStart = cursor;
            while (cursor < source.length() && isDigit(source.charAt(cursor))) {
                cursor++;
            }
            if (cursor == fractionStart) {
                throw error("JSON fraction requires digits");
            }
        }
        if (cursor < source.length() && (source.charAt(cursor) == 'e' || source.charAt(cursor) == 'E')) {
            cursor++;
            if (cursor < source.length() && (source.charAt(cursor) == '+' || source.charAt(cursor) == '-')) {
                cursor++;
            }
            int exponentStart = cursor;
            while (cursor < source.length() && isDigit(source.charAt(cursor))) {
                cursor++;
            }
            if (cursor == exponentStart) {
                throw error("JSON exponent requires digits");
            }
        }
        return new JsonNumber(source.substring(start, cursor));
    }

    private Object literal(String spelling, Object value) throws JsonParseException {
        if (!source.startsWith(spelling, cursor)) {
            throw error("invalid JSON literal");
        }
        cursor += spelling.length();
        return value;
    }

    private void whitespace() {
        while (cursor < source.length()) {
            char value = source.charAt(cursor);
            if (value != ' ' && value != '\t' && value != '\r' && value != '\n') {
                return;
            }
            cursor++;
        }
    }

    private void require(char expected) throws JsonParseException {
        if (!consume(expected)) {
            throw error("expected '" + expected + "'");
        }
    }

    private boolean consume(char expected) {
        if (cursor < source.length() && source.charAt(cursor) == expected) {
            cursor++;
            return true;
        }
        return false;
    }

    private JsonParseException error(String message) {
        return new JsonParseException(message + " at UTF-16 offset " + cursor);
    }

    private static boolean isDigit(char value) {
        return value >= '0' && value <= '9';
    }

    private static boolean isOneToNine(char value) {
        return value >= '1' && value <= '9';
    }

    private static String printable(String value) {
        return '"' + value + '"';
    }
}
