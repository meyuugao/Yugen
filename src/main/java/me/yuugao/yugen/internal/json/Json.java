package me.yuugao.yugen.internal.json;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal RFC 8259 JSON parser and writer with no external dependencies.
 *
 * <p>Parsed values map onto plain Java types:
 * <ul>
 *   <li>object → {@code LinkedHashMap<String, Object>} (insertion order preserved)</li>
 *   <li>array → {@code ArrayList<Object>}</li>
 *   <li>string → {@code String}</li>
 *   <li>number → {@code Long} when integral, otherwise {@code Double}</li>
 *   <li>{@code true}/{@code false} → {@code Boolean}, {@code null} → {@code null}</li>
 * </ul>
 *
 * <p>Deliberate simplifications: duplicate keys keep the last value, and
 * number grammar is validated by parse attempts rather than a strict
 * up-front state machine. Both are safe for provider wire formats and keep
 * the implementation small enough to audit by reading.
 */
public final class Json {

    private Json() {
    }

    /** Parses a complete JSON document; trailing non-whitespace input fails. */
    public static Object parse(String source) {
        if (source == null) {
            throw new JsonParseException("Source is null");
        }
        Parser parser = new Parser(source);
        parser.skipWhitespace();
        Object value = parser.readValue();
        parser.skipWhitespace();
        if (!parser.isAtEnd()) {
            throw parser.error("Trailing characters after JSON value");
        }
        return value;
    }

    /** Serializes Maps, Iterables/arrays, Strings, Numbers, Booleans and null. */
    public static String write(Object value) {
        StringBuilder sb = new StringBuilder();
        writeValue(value, sb);
        return sb.toString();
    }

    private static void writeValue(Object value, StringBuilder sb) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof String s) {
            writeString(s, sb);
        } else if (value instanceof Boolean || value instanceof Long
                || value instanceof Integer || value instanceof Short || value instanceof Byte) {
            sb.append(value);
        } else if (value instanceof Double || value instanceof Float) {
            double d = ((Number) value).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                throw new JsonParseException("NaN and Infinity are not valid JSON numbers");
            }
            sb.append(d);
        } else if (value instanceof Map<?, ?> map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                writeString(String.valueOf(entry.getKey()), sb);
                sb.append(':');
                writeValue(entry.getValue(), sb);
            }
            sb.append('}');
        } else if (value instanceof List<?> list) {
            sb.append('[');
            boolean first = true;
            for (Object item : list) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                writeValue(item, sb);
            }
            sb.append(']');
        } else if (value instanceof Object[] array) {
            writeValue(Arrays.asList(array), sb);
        } else {
            throw new JsonParseException("Unsupported type for serialization: " + value.getClass().getName());
        }
    }

    private static void writeString(String s, StringBuilder sb) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }

    /** Raised for malformed input and unsupported serialization types. */
    public static final class JsonParseException extends RuntimeException {

        public JsonParseException(String message) {
            super(message);
        }
    }

    private static final class Parser {
        private final String src;
        private int pos;

        Parser(String src) {
            this.src = src;
        }

        boolean isAtEnd() {
            return pos >= src.length();
        }

        char peek() {
            if (isAtEnd()) {
                throw error("Unexpected end of input");
            }
            return src.charAt(pos);
        }

        char next() {
            char c = peek();
            pos++;
            return c;
        }

        JsonParseException error(String message) {
            return new JsonParseException(message + " at position " + pos);
        }

        void skipWhitespace() {
            while (!isAtEnd()) {
                char c = src.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    pos++;
                } else {
                    break;
                }
            }
        }

        Object readValue() {
            skipWhitespace();
            char c = peek();
            return switch (c) {
                case '{' -> readObject();
                case '[' -> readArray();
                case '"' -> readString();
                case 't' -> readLiteral("true", Boolean.TRUE);
                case 'f' -> readLiteral("false", Boolean.FALSE);
                case 'n' -> readLiteral("null", null);
                default -> readNumber();
            };
        }

        private Map<String, Object> readObject() {
            next();
            Map<String, Object> result = new LinkedHashMap<>();
            skipWhitespace();
            if (!isAtEnd() && peek() == '}') {
                next();
                return result;
            }
            while (true) {
                skipWhitespace();
                if (peek() != '"') {
                    throw error("Expected object key string");
                }
                String key = readString();
                skipWhitespace();
                if (next() != ':') {
                    throw error("Expected ':' after object key");
                }
                result.put(key, readValue());
                skipWhitespace();
                char c = next();
                if (c == '}') {
                    return result;
                }
                if (c != ',') {
                    throw error("Expected ',' or '}' in object");
                }
            }
        }

        private List<Object> readArray() {
            next();
            List<Object> result = new ArrayList<>();
            skipWhitespace();
            if (!isAtEnd() && peek() == ']') {
                next();
                return result;
            }
            while (true) {
                result.add(readValue());
                skipWhitespace();
                char c = next();
                if (c == ']') {
                    return result;
                }
                if (c != ',') {
                    throw error("Expected ',' or ']' in array");
                }
            }
        }

        private String readString() {
            next();
            StringBuilder sb = new StringBuilder();
            while (true) {
                char c = next();
                if (c == '"') {
                    return sb.toString();
                }
                if (c == '\\') {
                    char esc = next();
                    switch (esc) {
                        case '"' -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        case '/' -> sb.append('/');
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'u' -> sb.append(readUnicodeEscape());
                        default -> throw error("Invalid escape '\\" + esc + "'");
                    }
                } else if (c < 0x20) {
                    throw error("Unescaped control character in string");
                } else {
                    sb.append(c);
                }
            }
        }

        private char readUnicodeEscape() {
            if (pos + 4 > src.length()) {
                throw error("Invalid unicode escape");
            }
            String hex = src.substring(pos, pos + 4);
            try {
                char result = (char) Integer.parseInt(hex, 16);
                pos += 4;
                return result;
            } catch (NumberFormatException e) {
                throw error("Invalid unicode escape '\\u" + hex + "'");
            }
        }

        private Object readLiteral(String literal, Object value) {
            if (!src.startsWith(literal, pos)) {
                throw error("Invalid literal");
            }
            pos += literal.length();
            return value;
        }

        private Object readNumber() {
            int start = pos;
            if (!isAtEnd() && src.charAt(pos) == '-') {
                pos++;
            }
            while (!isAtEnd()) {
                char c = src.charAt(pos);
                if ((c >= '0' && c <= '9') || c == '+' || c == '-' || c == '.' || c == 'e' || c == 'E') {
                    pos++;
                } else {
                    break;
                }
            }
            if (pos == start) {
                throw error("Invalid JSON value");
            }
            String token = src.substring(start, pos);
            try {
                if (token.indexOf('.') < 0 && token.indexOf('e') < 0 && token.indexOf('E') < 0) {
                    return Long.parseLong(token);
                }
                return Double.parseDouble(token);
            } catch (NumberFormatException e) {
                throw error("Invalid number '" + token + "'");
            }
        }
    }
}
