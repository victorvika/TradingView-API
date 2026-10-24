package com.example.tvchart;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal JSON reader/writer for the TradingView socket layer.
 *
 * <p>Android ships {@code org.json}, but keeping this dependency-free means the
 * protocol package ({@link TvProtocol}) compiles and runs on a plain JVM, so
 * {@code ./gradlew test} can check it without an emulator. Integers are decoded
 * as {@link Long}, anything with a fraction or exponent as {@link Double} -
 * the same split JavaScript makes between whole numbers and floats when
 * re-serialising.
 */
public final class TvJson {

    private TvJson() {
    }

    /* ------------------------------------------------------------------ write */

    /** Serialises Maps, Iterables, arrays, Strings, Numbers, Booleans and null. */
    public static String write(Object value) {
        StringBuilder out = new StringBuilder(64);
        writeValue(out, value);
        return out.toString();
    }

    private static void writeValue(StringBuilder out, Object value) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof String) {
            writeString(out, (String) value);
        } else if (value instanceof Boolean || value instanceof Number) {
            out.append(value);
        } else if (value instanceof Map) {
            out.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                writeString(out, String.valueOf(entry.getKey()));
                out.append(':');
                writeValue(out, entry.getValue());
            }
            out.append('}');
        } else if (value instanceof Iterable) {
            writeArray(out, ((Iterable<?>) value).iterator());
        } else if (value instanceof Object[]) {
            writeArray(out, java.util.Arrays.asList((Object[]) value).iterator());
        } else {
            throw new IllegalArgumentException("Cannot encode " + value.getClass().getName());
        }
    }

    private static void writeArray(StringBuilder out, java.util.Iterator<?> items) {
        out.append('[');
        boolean first = true;
        while (items.hasNext()) {
            if (!first) {
                out.append(',');
            }
            first = false;
            writeValue(out, items.next());
        }
        out.append(']');
    }

    private static void writeString(StringBuilder out, String value) {
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"': out.append("\\\""); break;
                case '\\': out.append("\\\\"); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                case '\b': out.append("\\b"); break;
                case '\f': out.append("\\f"); break;
                default:
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
            }
        }
        out.append('"');
    }

    /* ------------------------------------------------------------------- read */

    /** Parses one JSON value. Throws {@link IllegalArgumentException} on bad input. */
    public static Object parse(String text) {
        if (text == null) {
            throw new IllegalArgumentException("Cannot parse null");
        }
        Parser parser = new Parser(text);
        Object value = parser.readValue();
        parser.skipWhitespace();
        if (!parser.eof()) {
            throw new IllegalArgumentException("Unexpected trailing input at " + parser.position);
        }
        return value;
    }

    /** Narrows a parsed value to an object, or returns null. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> asObject(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : null;
    }

    /** Narrows a parsed value to a number, or returns null. */
    public static Double asDouble(Object value) {
        return value instanceof Number ? ((Number) value).doubleValue() : null;
    }

    /** Narrows a parsed value to a string, or returns null. */
    public static String asString(Object value) {
        return value instanceof String ? (String) value : null;
    }

    /** Narrows a parsed value to a list, or returns null. */
    @SuppressWarnings("unchecked")
    public static List<Object> asArray(Object value) {
        return value instanceof List ? (List<Object>) value : null;
    }

    private static final class Parser {
        private final String text;
        private int position;

        Parser(String text) {
            this.text = text;
        }

        boolean eof() {
            return position >= text.length();
        }

        void skipWhitespace() {
            while (position < text.length()) {
                char c = text.charAt(position);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    position++;
                } else {
                    return;
                }
            }
        }

        Object readValue() {
            skipWhitespace();
            char c = peek();
            switch (c) {
                case '{': return readObject();
                case '[': return readArray();
                case '"': return readString();
                case 't': return readLiteral("true", Boolean.TRUE);
                case 'f': return readLiteral("false", Boolean.FALSE);
                case 'n': return readLiteral("null", null);
                default: return readNumber();
            }
        }

        private Object readLiteral(String literal, Object value) {
            if (!text.startsWith(literal, position)) {
                throw new IllegalArgumentException("Bad literal at " + position);
            }
            position += literal.length();
            return value;
        }

        private Map<String, Object> readObject() {
            Map<String, Object> object = new LinkedHashMap<>();
            position++; // '{'
            skipWhitespace();
            if (peek() == '}') {
                position++;
                return object;
            }
            while (true) {
                skipWhitespace();
                if (peek() != '"') {
                    throw new IllegalArgumentException("Expected a key at " + position);
                }
                String key = readString();
                skipWhitespace();
                if (peek() != ':') {
                    throw new IllegalArgumentException("Expected ':' at " + position);
                }
                position++;
                object.put(key, readValue());
                skipWhitespace();
                char separator = peek();
                position++;
                if (separator == '}') {
                    return object;
                }
                if (separator != ',') {
                    throw new IllegalArgumentException("Expected ',' or '}' at " + (position - 1));
                }
            }
        }

        private List<Object> readArray() {
            List<Object> array = new java.util.ArrayList<>();
            position++; // '['
            skipWhitespace();
            if (peek() == ']') {
                position++;
                return array;
            }
            while (true) {
                array.add(readValue());
                skipWhitespace();
                char separator = peek();
                position++;
                if (separator == ']') {
                    return array;
                }
                if (separator != ',') {
                    throw new IllegalArgumentException("Expected ',' or ']' at " + (position - 1));
                }
            }
        }

        private String readString() {
            position++; // opening quote
            StringBuilder out = new StringBuilder();
            while (true) {
                if (eof()) {
                    throw new IllegalArgumentException("Unterminated string");
                }
                char c = text.charAt(position++);
                if (c == '"') {
                    return out.toString();
                }
                if (c != '\\') {
                    out.append(c);
                    continue;
                }
                if (eof()) {
                    throw new IllegalArgumentException("Unterminated escape");
                }
                char escape = text.charAt(position++);
                switch (escape) {
                    case '"': out.append('"'); break;
                    case '\\': out.append('\\'); break;
                    case '/': out.append('/'); break;
                    case 'b': out.append('\b'); break;
                    case 'f': out.append('\f'); break;
                    case 'n': out.append('\n'); break;
                    case 'r': out.append('\r'); break;
                    case 't': out.append('\t'); break;
                    case 'u':
                        if (position + 4 > text.length()) {
                            throw new IllegalArgumentException("Truncated \\u escape");
                        }
                        out.append((char) Integer.parseInt(text.substring(position, position + 4), 16));
                        position += 4;
                        break;
                    default:
                        throw new IllegalArgumentException("Bad escape \\" + escape);
                }
            }
        }

        private Object readNumber() {
            int start = position;
            boolean floating = false;
            if (!eof() && text.charAt(position) == '-') {
                position++;
            }
            while (!eof()) {
                char c = text.charAt(position);
                if (c >= '0' && c <= '9') {
                    position++;
                } else if (c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
                    floating |= (c == '.' || c == 'e' || c == 'E');
                    position++;
                } else {
                    break;
                }
            }
            String raw = text.substring(start, position);
            if (raw.isEmpty() || raw.equals("-")) {
                throw new IllegalArgumentException("Bad number at " + start);
            }
            try {
                return floating ? (Object) Double.valueOf(raw) : (Object) Long.valueOf(raw);
            } catch (NumberFormatException error) {
                throw new IllegalArgumentException("Bad number '" + raw + "'", error);
            }
        }

        private char peek() {
            if (eof()) {
                throw new IllegalArgumentException("Unexpected end of JSON");
            }
            return text.charAt(position);
        }
    }
}
