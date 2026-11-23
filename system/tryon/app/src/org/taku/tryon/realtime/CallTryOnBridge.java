// SPDX-License-Identifier: Apache-2.0
package org.taku.tryon.realtime;
import org.json.JSONObject;
/** Bounded frame descriptor for this application's own Camera2 preview. */
final class CallTryOnBridge {
    static final int MAX_JPEG = 400 * 1024;
    static final class Failure extends Exception {
        final String code;
        Failure(String code) { super(code); this.code = code; }
    }
    static final class Frame {
        final byte[] jpeg;
        final long sequence, camera, stream, generation, copiedNs;
        final int width, height;
        Frame(byte[] jpeg, JSONObject json) throws Exception {
            this.jpeg = jpeg;
            sequence = number(json, "sourceSeq"); camera = number(json, "camera");
            stream = number(json, "stream"); generation = number(json, "generation");
            copiedNs = number(json, "copiedNs");
            width = dimension(json, "width"); height = dimension(json, "height");
        }
        boolean sameIdentity(Frame other) {
            return other != null && camera == other.camera && stream == other.stream && generation == other.generation;
        }
    }
    private static long number(JSONObject json, String key) throws Exception {
        Object raw = json.get(key);
        if (!(raw instanceof Long) && !(raw instanceof Integer)) throw new IllegalArgumentException();
        long value = ((Number)raw).longValue();
        if (value < 0) throw new IllegalArgumentException();
        return value;
    }
    private static int dimension(JSONObject json, String key) throws Exception {
        long value = number(json,key);
        if (value < 1 || value > 1920) throw new IllegalArgumentException();
        return (int)value;
    }
}
