package org.taku.plugins.host;

import java.util.*;

final class Json {
    private final String text;
    private int position;
    private Json(String text) { this.text = text; }
    static Object parse(String text) {
        Json parser = new Json(text);
        Object value = parser.value();
        parser.space();
        if (parser.position != text.length()) throw new IllegalArgumentException("trailing JSON");
        return value;
    }
    private void space() { while (position < text.length() && " \n\r\t".indexOf(text.charAt(position)) >= 0) position++; }
    private char take() { if (position >= text.length()) throw new IllegalArgumentException("unexpected end of JSON"); return text.charAt(position++); }
    private Object value() {
        space();
        if (position >= text.length()) throw new IllegalArgumentException("missing JSON value");
        char next = text.charAt(position);
        if (next == '"') return string();
        if (next == '{') {
            position++;
            Map<String,Object> result = new LinkedHashMap<>();
            space();
            if (position < text.length() && text.charAt(position) == '}') { position++; return result; }
            while (true) {
                space();
                if (position >= text.length() || text.charAt(position) != '"') throw new IllegalArgumentException("object key must be string");
                String key = string();
                space();
                if (take() != ':') throw new IllegalArgumentException("missing colon");
                if (result.containsKey(key)) throw new IllegalArgumentException("duplicate key " + key);
                result.put(key, value());
                space();
                char separator = take();
                if (separator == '}') return result;
                if (separator != ',') throw new IllegalArgumentException("missing comma");
            }
        }
        if (next == '[') {
            position++;
            List<Object> result = new ArrayList<>();
            space();
            if (position < text.length() && text.charAt(position) == ']') { position++; return result; }
            while (true) {
                result.add(value()); space(); char separator = take();
                if (separator == ']') return result;
                if (separator != ',') throw new IllegalArgumentException("missing comma");
            }
        }
        if (text.startsWith("true", position)) { position += 4; return true; }
        if (text.startsWith("false", position)) { position += 5; return false; }
        if (text.startsWith("null", position)) { position += 4; return null; }
        int start = position;
        while (position < text.length() && "-0123456789.eE+".indexOf(text.charAt(position)) >= 0) position++;
        if (start == position) throw new IllegalArgumentException("invalid JSON value");
        String number = text.substring(start, position);
        if (!number.matches("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")) throw new IllegalArgumentException("invalid number");
        if (number.contains(".") || number.contains("e") || number.contains("E")) return Double.valueOf(number);
        return Long.valueOf(number);
    }
    private String string() {
        if (take() != '"') throw new IllegalArgumentException("expected string");
        StringBuilder result = new StringBuilder();
        while (true) {
            char character = take();
            if (character == '"') return result.toString();
            if (character < 32) throw new IllegalArgumentException("control character in string");
            if (character != '\\') { result.append(character); continue; }
            char escaped = take();
            switch (escaped) {
                case '"': case '\\': case '/': result.append(escaped); break;
                case 'b': result.append('\b'); break;
                case 'f': result.append('\f'); break;
                case 'n': result.append('\n'); break;
                case 'r': result.append('\r'); break;
                case 't': result.append('\t'); break;
                case 'u':
                    if (position + 4 > text.length()) throw new IllegalArgumentException("bad unicode escape");
                    try { result.append((char)Integer.parseInt(text.substring(position, position + 4), 16)); }
                    catch (NumberFormatException error) { throw new IllegalArgumentException("bad unicode escape"); }
                    position += 4; break;
                default: throw new IllegalArgumentException("bad escape");
            }
        }
    }
    static String write(Object value) {
        if (value == null) return "null";
        if (value instanceof String) {
            StringBuilder result = new StringBuilder("\"");
            for (char character : ((String)value).toCharArray()) {
                switch (character) {
                    case '"': result.append("\\\""); break;
                    case '\\': result.append("\\\\"); break;
                    case '\n': result.append("\\n"); break;
                    case '\r': result.append("\\r"); break;
                    case '\t': result.append("\\t"); break;
                    default: if (character < 32) result.append(String.format("\\u%04x", (int)character)); else result.append(character);
                }
            }
            return result.append('"').toString();
        }
        if (value instanceof Number || value instanceof Boolean) return value.toString();
        if (value instanceof Map) {
            StringJoiner result = new StringJoiner(",", "{", "}");
            ((Map<?,?>)value).forEach((key, item) -> result.add(write(key.toString()) + ":" + write(item)));
            return result.toString();
        }
        if (value instanceof Collection) {
            StringJoiner result = new StringJoiner(",", "[", "]");
            ((Collection<?>)value).forEach(item -> result.add(write(item)));
            return result.toString();
        }
        throw new IllegalArgumentException("cannot encode JSON value");
    }
}
