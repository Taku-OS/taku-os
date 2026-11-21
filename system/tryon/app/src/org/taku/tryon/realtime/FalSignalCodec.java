// SPDX-License-Identifier: Apache-2.0
package org.taku.tryon.realtime;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Bounded JSON-like MessagePack subset used by fal's official realtime protocol. */
public final class FalSignalCodec {
    public static final int MAX_BYTES = 512 * 1024;
    private static final int MAX_NODES = 16384, MAX_DEPTH = 32;
    private FalSignalCodec() {}

    public static byte[] encode(Object value) {
        Writer writer = new Writer();
        writer.write(value, 0);
        return writer.output.toByteArray();
    }

    public static Object decode(byte[] data) {
        if (data == null || data.length == 0 || data.length > MAX_BYTES) throw bad();
        Reader reader = new Reader(data);
        Object value = reader.read(0);
        if (reader.offset != data.length) throw bad();
        return value;
    }

    private static IllegalArgumentException bad() { return new IllegalArgumentException("invalid_signal"); }

    private static final class Writer {
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        int nodes;
        void put(int value) {
            if (output.size() >= MAX_BYTES) throw bad();
            output.write(value);
        }
        void number(long value, int bytes) { for (int n = bytes - 1; n >= 0; n--) put((int) (value >>> (n * 8)) & 255); }
        void size(int size, int fixed, int fixLimit, int u8, int u16, int u32) {
            if (size < fixLimit) put(fixed | size);
            else if (u8 >= 0 && size <= 255) { put(u8); number(size, 1); }
            else if (size <= 65535) { put(u16); number(size, 2); }
            else { put(u32); number(size, 4); }
        }
        void write(Object item, int depth) {
            if (++nodes > MAX_NODES || depth > MAX_DEPTH) throw bad();
            if (item == null) put(0xc0);
            else if (item instanceof Boolean) put((Boolean) item ? 0xc3 : 0xc2);
            else if (item instanceof String) {
                byte[] data = ((String) item).getBytes(StandardCharsets.UTF_8);
                size(data.length, 0xa0, 32, 0xd9, 0xda, 0xdb);
                for (byte value : data) put(value);
            } else if (item instanceof Number) {
                double value = ((Number) item).doubleValue();
                if (!Double.isFinite(value) || Math.abs(value) > 9007199254740991d) throw bad();
                long integer = ((Number) item).longValue();
                if (value == integer && integer >= 0 && integer <= 0xffffffffL) {
                    if (integer < 128) put((int) integer);
                    else if (integer <= 255) { put(0xcc); number(integer, 1); }
                    else if (integer <= 65535) { put(0xcd); number(integer, 2); }
                    else { put(0xce); number(integer, 4); }
                } else if (value == integer && integer >= -32 && integer < 0) put((int) integer);
                else { put(0xcb); number(Double.doubleToLongBits(value), 8); }
            } else if (item instanceof List<?>) {
                List<?> list = (List<?>) item;
                size(list.size(), 0x90, 16, -1, 0xdc, 0xdd);
                for (Object value : list) write(value, depth + 1);
            } else if (item instanceof Map<?, ?>) {
                Map<?, ?> map = (Map<?, ?>) item;
                size(map.size(), 0x80, 16, -1, 0xde, 0xdf);
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    if (!(entry.getKey() instanceof String)) throw bad();
                    write(entry.getKey(), depth + 1); write(entry.getValue(), depth + 1);
                }
            } else throw bad();
        }
    }

    private static final class Reader {
        final byte[] data;
        int offset, nodes;
        Reader(byte[] data) { this.data = data; }
        long number(int bytes) {
            need(bytes);
            long result = 0;
            for (int n = 0; n < bytes; n++) result = (result << 8) | (data[offset++] & 255);
            return result;
        }
        void need(int bytes) { if (bytes < 0 || bytes > data.length - offset) throw bad(); }
        int size(int bytes) {
            long value = number(bytes);
            if (value > MAX_BYTES) throw bad();
            return (int) value;
        }
        String text(int size) {
            need(size);
            try {
                String value = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(data, offset, size)).toString();
                offset += size;
                return value;
            } catch (CharacterCodingException failure) { throw bad(); }
        }
        List<Object> array(int size, int depth) {
            if (size > MAX_NODES || size > data.length - offset) throw bad();
            List<Object> result = new ArrayList<>(size);
            for (int n = 0; n < size; n++) result.add(read(depth + 1));
            return result;
        }
        Map<String, Object> map(int size, int depth) {
            if (size > MAX_NODES || size > (data.length - offset) / 2) throw bad();
            Map<String, Object> result = new LinkedHashMap<>();
            for (int n = 0; n < size; n++) {
                Object key = read(depth + 1);
                if (!(key instanceof String) || result.containsKey((String) key)) throw bad();
                result.put((String) key, read(depth + 1));
            }
            return result;
        }
        Object read(int depth) {
            if (++nodes > MAX_NODES || depth > MAX_DEPTH) throw bad();
            int tag = (int) number(1);
            if (tag < 128) return (long) tag;
            if (tag >= 224) return (long) tag - 256;
            if ((tag & 0xe0) == 0xa0) return text(tag & 31);
            if ((tag & 0xf0) == 0x90) return array(tag & 15, depth);
            if ((tag & 0xf0) == 0x80) return map(tag & 15, depth);
            switch (tag) {
                case 0xc0: return null;
                case 0xc2: return false;
                case 0xc3: return true;
                case 0xcc: return number(1);
                case 0xcd: return number(2);
                case 0xce: return number(4);
                case 0xd0: return (long) (byte) number(1);
                case 0xd1: return (long) (short) number(2);
                case 0xd2: return (long) (int) number(4);
                case 0xcf: {
                    long value = number(8);
                    if (value < 0 || value > 9007199254740991L) throw bad();
                    return value;
                }
                case 0xd3: {
                    long value = number(8);
                    if (value < -9007199254740991L || value > 9007199254740991L) throw bad();
                    return value;
                }
                case 0xca: {
                    float value = Float.intBitsToFloat((int) number(4));
                    if (!Float.isFinite(value)) throw bad();
                    return (double) value;
                }
                case 0xcb: {
                    double value = Double.longBitsToDouble(number(8));
                    if (!Double.isFinite(value)) throw bad();
                    return value;
                }
                case 0xd9: return text(size(1));
                case 0xda: return text(size(2));
                case 0xdb: return text(size(4));
                case 0xdc: return array(size(2), depth);
                case 0xdd: return array(size(4), depth);
                case 0xde: return map(size(2), depth);
                case 0xdf: return map(size(4), depth);
                default: throw bad();
            }
        }
    }
}
