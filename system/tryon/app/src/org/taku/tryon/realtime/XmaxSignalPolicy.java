// SPDX-License-Identifier: Apache-2.0
package org.taku.tryon.realtime;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/** X2.1 room events and Agora's 1 KiB data-channel packet budget, fixed to SDK 1.1.0. */
final class XmaxSignalPolicy {
    private XmaxSignalPolicy() {}
    static boolean freshSource(long queuedAtNs, long nowNs) { return queuedAtNs > 0 && nowNs >= queuedAtNs && nowNs - queuedAtNs <= 750_000_000L; }
    static String event(String event, String user, String task) {
        JSONObject result = XmaxSessionClient.object("event", event, "user_id", user,
                "runtime", XmaxSessionClient.object("platform", "android", "os_version", "unknown",
                        "sdk_version", "taku-xmax-protocol-1.1.0", "device_model", "TakuOS"));
        try { if (task != null) { result.put("uid", task); result.put("session_uid", task); } return result.toString(); }
        catch (Exception ignored) { throw new IllegalArgumentException("invalid_request"); }
    }
    static String start(String user, String task, String prompt, String reference, int width, int height) {
        try {
            JSONObject result = new JSONObject(event("start", user, task));
            int[] canvas = canvas(width, height);
            JSONObject params = XmaxSessionClient.object("model", "default", "size", new JSONArray().put(canvas[0]).put(canvas[1]),
                    "prompt", prompt, "face_replace", false, "mirror", false, "min_bitrate", 500, "max_bitrate", 2500,
                    "padding", "contain", "target_size", new JSONArray().put(width).put(height));
            if (reference != null) params.put("ref_image_path", reference);
            return result.put("params", params).toString();
        } catch (Exception ignored) { throw new IllegalArgumentException("invalid_request"); }
    }
    static int[] canvas(int width, int height) {
        int[][] formats = {{1920,1080},{1080,1920},{1408,1408},{1152,1536},{1536,1152}};
        double ratio = width / (double) height, best = Double.POSITIVE_INFINITY; int[] selected = formats[0];
        for (int[] format : formats) { double difference = Math.abs(Math.log(ratio / (format[0] / (double) format[1]))); if (difference < best) { best = difference; selected = format; } }
        return selected.clone();
    }
    static List<byte[]> packets(String message) {
        if (message.getBytes(StandardCharsets.UTF_8).length > 256 * 1024) throw new IllegalArgumentException("invalid_request");
        if (message.getBytes(StandardCharsets.UTF_8).length <= 800) return List.of(message.getBytes(StandardCharsets.UTF_8));
        List<String> parts = new ArrayList<>(); StringBuilder part = new StringBuilder(); int bytes = 0;
        for (int offset = 0; offset < message.length();) {
            int point = message.codePointAt(offset); String character = new String(Character.toChars(point));
            int cost = point == '"' || point == '\\' ? 2 : point < 32 ? 6 : character.getBytes(StandardCharsets.UTF_8).length;
            if (bytes + cost > 600 && part.length() > 0) { parts.add(part.toString()); part.setLength(0); bytes = 0; }
            part.append(character); bytes += cost; offset += Character.charCount(point);
        }
        if (part.length() > 0) parts.add(part.toString());
        String id = UUID.randomUUID().toString().replace("-", ""); List<byte[]> result = new ArrayList<>();
        for (int i = 0; i < parts.size(); i++) result.add(XmaxSessionClient.object("event", "__agora_chunk__", "eventId", id,
                "index", i, "count", parts.size(), "data", parts.get(i)).toString().getBytes(StandardCharsets.UTF_8));
        return result;
    }
}
