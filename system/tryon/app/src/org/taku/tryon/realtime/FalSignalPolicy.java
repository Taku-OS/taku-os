// SPDX-License-Identifier: Apache-2.0
package org.taku.tryon.realtime;

import java.net.URI;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Pure deterministic protocol validation shared by the native client and host tests. */
final class FalSignalPolicy {
    static final int MAX_JPEG = 400 * 1024, MAX_WIDTH = 640, MAX_HEIGHT = 960;
    static final int MAX_SOURCE_EDGE = 1280, MAX_SOURCE_PIXELS = 1280 * 960;
    static final long FIRST_FRAME_MS = 30000, SESSION_MS = 60000;
    private FalSignalPolicy() {}
    static URI signalUri(String jwt) {
        return signalUri(jwt, FalRealtimeProfile.LEGACY_VTON);
    }
    static URI signalUri(String jwt, FalRealtimeProfile profile) {
        if (profile == null) throw invalid();
        if (jwt == null || jwt.isEmpty() || jwt.length() > 16384 || !jwt.matches("[A-Za-z0-9_.=-]+"))
            throw invalid();
        try {
            // String/charset-name overload also exists on the app's Android 11 minimum.
            return URI.create("wss://fal.run/" + profile.endpointId + "?fal_jwt_token="
                    + URLEncoder.encode(jwt, "UTF-8") + "&max_buffering=2");
        } catch (java.io.UnsupportedEncodingException impossible) { throw invalid(); }
    }
    static Map<String, Object> map(Object... pairs) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int n = 0; n < pairs.length; n += 2) result.put((String) pairs[n], pairs[n + 1]);
        return result;
    }
    static String boundedText(Object value, int max, boolean nullable) {
        if (nullable && value == null) return null;
        if (!(value instanceof String) || ((String) value).isEmpty() || ((String) value).length() > max
                || ((String) value).indexOf('\u0000') >= 0) throw invalid();
        return (String) value;
    }
    static Map<String, Object> initial(String prompt, String reference) {
        return initial(prompt, reference, FalRealtimeProfile.LEGACY_VTON);
    }
    static Map<String, Object> initial(String prompt, String reference, FalRealtimeProfile profile) {
        if (profile == null) throw invalid();
        boundedText(prompt, 4000, false);
        if (reference != null && (reference.length() > 500 * 1024
                || !reference.matches("data:image/(jpeg|png|webp);base64,[A-Za-z0-9+/=]+"))) throw invalid();
        Map<String, Object> result = map("prompt", prompt, "reference_image_url", reference);
        if (profile.expandInitialPrompt) result.put("enable_prompt_expansion", true);
        return result;
    }
    static Map<String, Object> update(String prompt, String reference, boolean imageChanged) {
        Map<String, Object> result = initial(prompt, reference);
        // Omitting an unchanged image avoids waiting for an image ACK that the
        // endpoint need not emit for prompt-only updates. Clearing uses null.
        if (!imageChanged) result.remove("reference_image_url");
        return result;
    }
    static String type(Map<String, Object> message) {
        Object value = message.get("type");
        return value instanceof String ? ((String) value).toLowerCase(java.util.Locale.ROOT) : "";
    }
    /** Terminal controls do not depend on SDP completion. Only fixed classification leaves the parser. */
    static String terminalCode(Map<String, Object> message) {
        String type = type(message);
        if (type.equals("x-fal-error") && "TIMEOUT".equals(message.get("error"))) return null;
        if (!(type.equals("error") || type.equals("x-fal-error") || "error".equals(message.get("status")))) return null;
        if (concurrentSessionLimit(message, 0, new int[]{0})) return "lucy_session_busy";
        return type.equals("x-fal-error") || "error".equals(message.get("status"))
                ? "fal_endpoint_error" : "model_reported_error";
    }
    private static boolean concurrentSessionLimit(Object value, int depth, int[] nodes) {
        if (value == null || depth > 3 || nodes[0]++ >= 16) return false;
        if (value instanceof String) {
            String text = (String) value;
            return concurrentSessionLimitCode(text);
        }
        if (!(value instanceof Map<?, ?>)) return false;
        Map<?, ?> values = (Map<?, ?>) value;
        for (String key : new String[]{"code", "error_code", "error", "detail", "reason"})
            if (concurrentSessionLimit(values.get(key), depth + 1, nodes)) return true;
        return false;
    }
    /** Exact existing provider codes only; never search arbitrary reason prose. */
    static boolean concurrentSessionLimitCode(String text) {
        return text != null && text.length() <= 96 && (text.equalsIgnoreCase("Concurrent session limit reached.")
                || text.equalsIgnoreCase("Concurrent session limit reached")
                || text.equalsIgnoreCase("concurrent_session_limit")
                || text.equalsIgnoreCase("concurrent_session_limit_reached"));
    }
    static List<String> urls(Object value) {
        List<?> source = value instanceof String ? List.of(value) : value instanceof List<?> ? (List<?>) value : null;
        if (source == null || source.isEmpty() || source.size() > 8) throw invalid();
        List<String> result = new ArrayList<>();
        for (Object item : source) {
            String url = boundedText(item, 2048, false);
            if (!url.matches("(?i)(stun|stuns|turn|turns):[^\\s\\u0000-\\u001f\\u007f]+")
                    || url.indexOf('@') >= 0 || url.indexOf('#') >= 0) throw invalid();
            result.add(url);
        }
        return result;
    }
    static Object initialServers(Map<String, Object> message) {
        for (String key : List.of("iceServers", "iceservers", "ice_servers"))
            if (message.containsKey(key)) return message.get(key);
        return null;
    }
    static Map<String, Object> candidate(Object value) {
        if (!(value instanceof Map<?, ?>)) throw invalid();
        Map<?, ?> source = (Map<?, ?>) value;
        String candidate = boundedText(source.get("candidate"), 8192, false);
        if (!candidate.startsWith("candidate:") || candidate.indexOf('\n') >= 0 || candidate.indexOf('\r') >= 0) throw invalid();
        Object rawMid = source.get("sdpMid"), rawIndex = source.get("sdpMLineIndex");
        String mid = rawMid == null ? null : boundedText(rawMid, 256, false);
        if (!(rawIndex instanceof Number)) throw invalid();
        double number = ((Number) rawIndex).doubleValue();
        if (!Double.isFinite(number) || number != Math.rint(number) || number < 0 || number > 32) throw invalid();
        return map("candidate", candidate, "sdpMid", mid, "sdpMLineIndex", (int) number);
    }
    static int[] contain(int width, int height, int rotation) {
        if (width < 1 || height < 1 || width > 4096 || height > 4096
                || (long) width * height > 8_000_000 || rotation % 90 != 0) throw invalid();
        boolean turn = Math.floorMod(rotation, 180) != 0;
        int rotatedWidth = turn ? height : width, rotatedHeight = turn ? width : height;
        double ratio = Math.min(1, Math.min(MAX_WIDTH / (double) rotatedWidth, MAX_HEIGHT / (double) rotatedHeight));
        return new int[]{Math.max(1, (int) Math.floor(width * ratio)), Math.max(1, (int) Math.floor(height * ratio))};
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("invalid_signal"); }
}
