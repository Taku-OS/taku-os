/* Copyright 2026 Taku OS. SPDX-License-Identifier: Apache-2.0 */
package android.util;

import java.io.Closeable;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Host-test stand-in for the strict subset of android.util.JsonReader that IslandPayload uses.
 * Strict RFC 8259 only (lenient mode is unsupported). Never shipped in SystemUI.
 */
public final class JsonReader implements Closeable {
    private enum Scope { EMPTY_ARRAY, ARRAY, EMPTY_OBJECT, DANGLING_NAME, OBJECT, EMPTY_DOCUMENT, DOCUMENT }
    private final String in;
    private int pos;
    private final Deque<Scope> stack = new ArrayDeque<>();
    private JsonToken peeked;
    private String value;

    public JsonReader(Reader reader) {
        StringBuilder text = new StringBuilder();
        char[] buffer = new char[4096];
        try {
            for (int n; (n = reader.read(buffer)) > 0; ) text.append(buffer, 0, n);
        } catch (IOException error) { throw new IllegalStateException(error); }
        in = text.toString();
        stack.push(Scope.EMPTY_DOCUMENT);
    }

    public void setLenient(boolean lenient) { if (lenient) throw new UnsupportedOperationException("lenient"); }

    public JsonToken peek() throws IOException {
        if (peeked != null) return peeked;
        Scope scope = stack.peek();
        switch (scope) {
            case EMPTY_ARRAY: stack.pop(); stack.push(Scope.ARRAY);
                if (skip() == ']') { pos++; return peeked = JsonToken.END_ARRAY; }
                return peeked = value();
            case ARRAY: {
                char c = skip();
                if (c == ']') { pos++; return peeked = JsonToken.END_ARRAY; }
                expect(','); return peeked = value();
            }
            case EMPTY_OBJECT: case OBJECT: {
                char c = skip();
                if (c == '}') { pos++; return peeked = JsonToken.END_OBJECT; }
                if (scope == Scope.OBJECT) { expect(','); c = skip(); }
                if (c != '"') throw syntax("name");
                pos++; value = string(); stack.pop(); stack.push(Scope.DANGLING_NAME);
                return peeked = JsonToken.NAME;
            }
            case DANGLING_NAME: expect(':'); stack.pop(); stack.push(Scope.OBJECT); return peeked = value();
            case EMPTY_DOCUMENT: stack.pop(); stack.push(Scope.DOCUMENT); return peeked = value();
            default:
                if (skip() != 0) throw syntax("trailing data");
                return peeked = JsonToken.END_DOCUMENT;
        }
    }

    public void beginObject() throws IOException { consume(JsonToken.BEGIN_OBJECT); stack.push(Scope.EMPTY_OBJECT); }
    public void endObject() throws IOException { consume(JsonToken.END_OBJECT); stack.pop(); }
    public void beginArray() throws IOException { consume(JsonToken.BEGIN_ARRAY); stack.push(Scope.EMPTY_ARRAY); }
    public void endArray() throws IOException { consume(JsonToken.END_ARRAY); stack.pop(); }
    public boolean hasNext() throws IOException { JsonToken t = peek(); return t != JsonToken.END_OBJECT && t != JsonToken.END_ARRAY; }
    public String nextName() throws IOException { consume(JsonToken.NAME); return value; }
    public String nextString() throws IOException {
        JsonToken t = peek();
        if (t != JsonToken.STRING && t != JsonToken.NUMBER) throw new IllegalStateException("Expected string but was " + t);
        peeked = null; return value;
    }
    public boolean nextBoolean() throws IOException { consume(JsonToken.BOOLEAN); return value.equals("true"); }
    public void nextNull() throws IOException { consume(JsonToken.NULL); }
    @Override public void close() {}

    private void consume(JsonToken wanted) throws IOException {
        if (peek() != wanted) throw new IllegalStateException("Expected " + wanted + " but was " + peeked);
        peeked = null;
    }

    private JsonToken value() throws IOException {
        char c = skip();
        switch (c) {
            case '{': pos++; return JsonToken.BEGIN_OBJECT;
            case '[': pos++; return JsonToken.BEGIN_ARRAY;
            case '"': pos++; value = string(); return JsonToken.STRING;
            case 't': literal("true"); value = "true"; return JsonToken.BOOLEAN;
            case 'f': literal("false"); value = "false"; return JsonToken.BOOLEAN;
            case 'n': literal("null"); return JsonToken.NULL;
            default:
                int start = pos;
                while (pos < in.length() && "+-0123456789.eE".indexOf(in.charAt(pos)) >= 0) pos++;
                if (start == pos) throw syntax("value");
                value = in.substring(start, pos);
                return JsonToken.NUMBER;
        }
    }

    private String string() throws IOException {
        StringBuilder out = new StringBuilder();
        while (true) {
            if (pos >= in.length()) throw syntax("unterminated string");
            char c = in.charAt(pos++);
            if (c == '"') return out.toString();
            if (c < 0x20) throw syntax("control character");
            if (c != '\\') { out.append(c); continue; }
            if (pos >= in.length()) throw syntax("escape");
            char e = in.charAt(pos++);
            switch (e) {
                case '"': case '\\': case '/': out.append(e); break;
                case 'b': out.append('\b'); break;
                case 'f': out.append('\f'); break;
                case 'n': out.append('\n'); break;
                case 'r': out.append('\r'); break;
                case 't': out.append('\t'); break;
                case 'u':
                    if (pos + 4 > in.length()) throw syntax("unicode escape");
                    out.append((char) Integer.parseInt(in.substring(pos, pos + 4), 16)); pos += 4; break;
                default: throw syntax("escape");
            }
        }
    }

    private void literal(String word) throws IOException {
        if (!in.startsWith(word, pos)) throw syntax("literal");
        pos += word.length();
    }

    private void expect(char c) throws IOException {
        if (skip() != c) throw syntax("expected " + c);
        pos++;
    }

    private char skip() {
        while (pos < in.length() && " \t\n\r".indexOf(in.charAt(pos)) >= 0) pos++;
        return pos < in.length() ? in.charAt(pos) : 0;
    }

    private IOException syntax(String what) { return new java.io.EOFException("Malformed JSON: " + what + " at " + pos); }
}
