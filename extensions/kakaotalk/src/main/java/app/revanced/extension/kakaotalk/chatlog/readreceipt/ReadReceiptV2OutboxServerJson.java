package app.revanced.extension.kakaotalk.chatlog.readreceipt;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class ReadReceiptV2OutboxServerJson {
    private static final int MAX_DEPTH = 32;

    private ReadReceiptV2OutboxServerJson() {
    }

    static Object parse(byte[] value) {
        if (value == null) throw rejected();
        String decoded;
        try {
            CharBuffer characters = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(value));
            decoded = characters.toString();
        } catch (CharacterCodingException exception) {
            throw rejected();
        }
        Parser parser = new Parser(decoded);
        Object result = parser.value(0);
        parser.whitespace();
        if (!parser.finished()) throw rejected();
        return result;
    }

    static byte[] encode(Object value) {
        StringBuilder output = new StringBuilder();
        write(output, value, 0);
        return output.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void write(StringBuilder output, Object value, int depth) {
        if (depth > MAX_DEPTH) throw rejected();
        if (value == null) {
            output.append("null");
        } else if (value instanceof String) {
            string(output, (String) value);
        } else if (value instanceof Long || value instanceof Integer) {
            output.append(value);
        } else if (value instanceof Boolean) {
            output.append(((Boolean) value) ? "true" : "false");
        } else if (value instanceof Map) {
            output.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                if (!(entry.getKey() instanceof String)) throw rejected();
                if (!first) output.append(',');
                first = false;
                string(output, (String) entry.getKey());
                output.append(':');
                write(output, entry.getValue(), depth + 1);
            }
            output.append('}');
        } else if (value instanceof List) {
            output.append('[');
            boolean first = true;
            for (Object element : (List<?>) value) {
                if (!first) output.append(',');
                first = false;
                write(output, element, depth + 1);
            }
            output.append(']');
        } else {
            throw rejected();
        }
    }

    private static void string(StringBuilder output, String value) {
        output.append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"':
                    output.append("\\\"");
                    break;
                case '\\':
                    output.append("\\\\");
                    break;
                case '\b':
                    output.append("\\b");
                    break;
                case '\f':
                    output.append("\\f");
                    break;
                case '\n':
                    output.append("\\n");
                    break;
                case '\r':
                    output.append("\\r");
                    break;
                case '\t':
                    output.append("\\t");
                    break;
                default:
                    if (character < 0x20) {
                        output.append("\\u");
                        String hex = Integer.toHexString(character);
                        for (int pad = hex.length(); pad < 4; pad++) output.append('0');
                        output.append(hex);
                    } else {
                        output.append(character);
                    }
            }
        }
        output.append('"');
    }

    private static IllegalArgumentException rejected() {
        return new IllegalArgumentException("json rejected");
    }

    private static final class Parser {
        private final String input;
        private int index;

        Parser(String input) {
            this.input = input;
        }

        Object value(int depth) {
            if (depth > MAX_DEPTH) throw rejected();
            whitespace();
            if (finished()) throw rejected();
            char next = input.charAt(index);
            if (next == '{') return object(depth + 1);
            if (next == '[') return array(depth + 1);
            if (next == '"') return string();
            if (next == 't') return literal("true", Boolean.TRUE);
            if (next == 'f') return literal("false", Boolean.FALSE);
            if (next == 'n') return literal("null", null);
            if (next == '-' || next >= '0' && next <= '9') return number();
            throw rejected();
        }

        private Map<String, Object> object(int depth) {
            index++;
            LinkedHashMap<String, Object> result = new LinkedHashMap<>();
            whitespace();
            if (take('}')) return result;
            while (true) {
                whitespace();
                if (finished() || input.charAt(index) != '"') throw rejected();
                String key = string();
                if (result.containsKey(key)) throw rejected();
                whitespace();
                require(':');
                result.put(key, value(depth));
                whitespace();
                if (take('}')) return result;
                require(',');
            }
        }

        private List<Object> array(int depth) {
            index++;
            List<Object> result = new ArrayList<>();
            whitespace();
            if (take(']')) return result;
            while (true) {
                result.add(value(depth));
                whitespace();
                if (take(']')) return result;
                require(',');
            }
        }

        private String string() {
            require('"');
            StringBuilder result = new StringBuilder();
            while (!finished()) {
                char character = input.charAt(index++);
                if (character == '"') return result.toString();
                if (character < 0x20) throw rejected();
                if (character != '\\') {
                    if (Character.isLowSurrogate(character)) throw rejected();
                    if (Character.isHighSurrogate(character)) {
                        if (finished()) throw rejected();
                        char low = input.charAt(index++);
                        if (!Character.isLowSurrogate(low)) throw rejected();
                        result.append(character).append(low);
                    } else {
                        result.append(character);
                    }
                    continue;
                }
                if (finished()) throw rejected();
                char escaped = input.charAt(index++);
                switch (escaped) {
                    case '"':
                    case '\\':
                    case '/':
                        result.append(escaped);
                        break;
                    case 'b':
                        result.append('\b');
                        break;
                    case 'f':
                        result.append('\f');
                        break;
                    case 'n':
                        result.append('\n');
                        break;
                    case 'r':
                        result.append('\r');
                        break;
                    case 't':
                        result.append('\t');
                        break;
                    case 'u':
                        appendUnicode(result);
                        break;
                    default:
                        throw rejected();
                }
            }
            throw rejected();
        }

        private void appendUnicode(StringBuilder result) {
            char first = unicodeUnit();
            if (Character.isLowSurrogate(first)) throw rejected();
            if (!Character.isHighSurrogate(first)) {
                result.append(first);
                return;
            }
            if (index + 2 > input.length() || input.charAt(index) != '\\'
                    || input.charAt(index + 1) != 'u') {
                throw rejected();
            }
            index += 2;
            char second = unicodeUnit();
            if (!Character.isLowSurrogate(second)) throw rejected();
            result.append(first).append(second);
        }

        private char unicodeUnit() {
            if (index + 4 > input.length()) throw rejected();
            int value = 0;
            for (int count = 0; count < 4; count++) {
                int digit = Character.digit(input.charAt(index++), 16);
                if (digit < 0) throw rejected();
                value = value * 16 + digit;
            }
            return (char) value;
        }

        private Object literal(String expected, Object value) {
            if (!input.startsWith(expected, index)) throw rejected();
            index += expected.length();
            return value;
        }

        private Long number() {
            int start = index;
            if (take('-') && finished()) throw rejected();
            if (take('0')) {
                if (!finished() && Character.isDigit(input.charAt(index))) throw rejected();
            } else {
                if (finished() || input.charAt(index) < '1' || input.charAt(index) > '9') {
                    throw rejected();
                }
                while (!finished() && Character.isDigit(input.charAt(index))) index++;
            }
            if (!finished()) {
                char suffix = input.charAt(index);
                if (suffix == '.' || suffix == 'e' || suffix == 'E' || suffix == '+') {
                    throw rejected();
                }
            }
            try {
                return Long.valueOf(input.substring(start, index));
            } catch (NumberFormatException exception) {
                throw rejected();
            }
        }

        void whitespace() {
            while (!finished()) {
                char character = input.charAt(index);
                if (character != ' ' && character != '\n' && character != '\r'
                        && character != '\t') return;
                index++;
            }
        }

        boolean finished() {
            return index == input.length();
        }

        private boolean take(char expected) {
            if (!finished() && input.charAt(index) == expected) {
                index++;
                return true;
            }
            return false;
        }

        private void require(char expected) {
            if (!take(expected)) throw rejected();
        }
    }
}
