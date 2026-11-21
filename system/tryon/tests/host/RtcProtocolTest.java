// SPDX-License-Identifier: Apache-2.0
package org.taku.tryon.realtime;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** Pure JVM tests: no Android, fal account, network or camera. */
public final class RtcProtocolTest {
    static int assertions;
    interface Action { void run(); }
    static void check(boolean value) { assertions++; if (!value) throw new AssertionError("check " + assertions); }
    static void reject(Action action) {
        boolean rejected = false;
        try { action.run(); } catch (IllegalArgumentException expected) { rejected = true; }
        check(rejected);
    }
    static byte[] hex(String value) {
        byte[] result = new byte[value.length() / 2];
        for (int n = 0; n < result.length; n++) result[n] = (byte) Integer.parseInt(value.substring(n * 2, n * 2 + 2), 16);
        return result;
    }
    static int distance(int a, int b) {
        return Math.max(Math.abs((a >> 16 & 255) - (b >> 16 & 255)),
                Math.max(Math.abs((a >> 8 & 255) - (b >> 8 & 255)), Math.abs((a & 255) - (b & 255))));
    }
    public static void main(String[] args) {
        Map<String, Object> first = FalSignalPolicy.initial("navy jacket", null);
        // Golden bytes produced by the existing browser harness encodeSignaling.
        check(Arrays.equals(FalSignalCodec.encode(first), hex("82a670726f6d7074ab6e617679206a61636b6574b37265666572656e63655f696d6167655f75726cc0")));
        check(FalSignalCodec.decode(FalSignalCodec.encode(first)).equals(first));
        Map<String, Object> candidate = FalSignalPolicy.map("type", "icecandidate", "candidate",
                FalSignalPolicy.map("candidate", "candidate:1 1 udp 123 192.0.2.1 1234 typ host", "sdpMid", "0", "sdpMLineIndex", 0L));
        check(FalSignalCodec.decode(FalSignalCodec.encode(candidate)).equals(candidate));
        for (Object value : Arrays.asList(null, true, false, 0L, 127L, 128L, 255L, 256L, 65535L, 65536L,
                4294967295L, -1L, -32L, -33.5d, 1.25d, "你好，换衣服", "x".repeat(256), List.of(1L, "a"))) {
            Object decoded = FalSignalCodec.decode(FalSignalCodec.encode(value));
            check(java.util.Objects.equals(value, decoded));
        }
        check(FalSignalCodec.decode(hex("d0df")).equals(-33L));
        check(FalSignalCodec.decode(hex("d1ff00")).equals(-256L));
        check(FalSignalCodec.decode(hex("d2ffffff00")).equals(-256L));
        check(FalSignalCodec.decode(hex("cf001fffffffffffff")).equals(9007199254740991L));
        check(FalSignalCodec.decode(hex("d3ffe0000000000001")).equals(-9007199254740991L));
        for (String wire : List.of("c1", "a2fffe", "d901", "dbffffffff", "ddffffffff", "df00040000", "81a161", "82a16101a16102", "01ff", "cf0020000000000000", "d38000000000000000", "cb7ff0000000000000"))
            reject(() -> FalSignalCodec.decode(hex(wire)));
        reject(() -> FalSignalCodec.decode(new byte[0]));
        reject(() -> FalSignalCodec.decode(new byte[FalSignalCodec.MAX_BYTES + 1]));
        reject(() -> FalSignalCodec.encode("x".repeat(FalSignalCodec.MAX_BYTES)));
        reject(() -> FalSignalCodec.encode(Double.NaN));
        reject(() -> FalSignalCodec.encode(Double.POSITIVE_INFINITY));
        reject(() -> FalSignalCodec.encode(new Object()));
        reject(() -> FalSignalCodec.encode(Map.of(5, "x")));
        List<Object> cycle = new java.util.ArrayList<>(); cycle.add(cycle);
        reject(() -> FalSignalCodec.encode(cycle));
        reject(() -> FalSignalCodec.decode(hex("91".repeat(34) + "01")));
        check(FalSignalPolicy.signalUri("short.jwt.token").getHost().equals("fal.run"));
        check(FalSignalPolicy.signalUri("short.jwt.token").getScheme().equals("wss"));
        check(FalSignalPolicy.signalUri("short.jwt.token").getPath().equals("/decart/lucy2-vton/realtime"));
        for (String jwt : List.of("", "key\nheader", "a&other=x", "x".repeat(16385), "https://host/token")) reject(() -> FalSignalPolicy.signalUri(jwt));
        check(FalSignalPolicy.initial("white shirt", "data:image/jpeg;base64,AAAA").containsKey("reference_image_url"));
        reject(() -> FalSignalPolicy.initial("", null));
        reject(() -> FalSignalPolicy.initial("x".repeat(4001), null));
        reject(() -> FalSignalPolicy.initial("shirt", "https://other/garment.jpg"));
        reject(() -> FalSignalPolicy.initial("shirt", "file:///private/garment.jpg"));
        reject(() -> FalSignalPolicy.initial("shirt", "data:image/svg+xml;base64,AAAA"));
        reject(() -> FalSignalPolicy.initial("shirt", "data:image/jpeg;base64," + "A".repeat(512001)));
        check(FalSignalPolicy.urls("stun:stun.example:3478").size() == 1);
        check(FalSignalPolicy.urls(List.of("turn:turn.example:3478?transport=udp", "turns:turn.example:5349")).size() == 2);
        for (Object value : List.of("https://host/", "turn:user@host", "turn:host\n", "turn:host#evil", List.of(), java.util.Collections.nCopies(9, "turn:host")))
            reject(() -> FalSignalPolicy.urls(value));
        check(FalSignalPolicy.initialServers(Map.of("ice_servers", List.of())) instanceof List<?>);
        check(FalSignalPolicy.candidate(((Map<?, ?>) candidate).get("candidate")).get("sdpMLineIndex").equals(0));
        reject(() -> FalSignalPolicy.candidate(FalSignalPolicy.map("candidate", "bad", "sdpMLineIndex", 0)));
        reject(() -> FalSignalPolicy.candidate(FalSignalPolicy.map("candidate", "candidate:x", "sdpMLineIndex", .5)));
        reject(() -> FalSignalPolicy.candidate(FalSignalPolicy.map("candidate", "candidate:x", "sdpMLineIndex", true)));
        reject(() -> FalSignalPolicy.candidate(FalSignalPolicy.map("candidate", "candidate:x\nsecret", "sdpMLineIndex", 0)));
        check(Arrays.equals(FalSignalPolicy.contain(480, 640, 0), new int[]{480, 640}));
        check(Arrays.equals(FalSignalPolicy.contain(1920, 1080, 90), new int[]{960, 540}));
        check(Arrays.equals(FalSignalPolicy.contain(1920, 1080, 0), new int[]{640, 360}));
        check(Arrays.equals(FalSignalPolicy.contain(1080, 1920, 270), new int[]{360, 640}));
        reject(() -> FalSignalPolicy.contain(5000, 300, 0));
        reject(() -> FalSignalPolicy.contain(640, 960, 45));
        check(FalSignalPolicy.FIRST_FRAME_MS == 30000 && FalSignalPolicy.SESSION_MS == 60000);
        for (String reason : List.of("Concurrent session limit reached.", "Concurrent session limit reached",
                "CONCURRENT_SESSION_LIMIT", "concurrent_session_limit_reached")) {
            check("lucy_session_busy".equals(FalSignalPolicy.terminalCode(Map.of("type", "x-fal-error",
                    "error", "INTERNAL_ERROR", "reason", reason))));
            check("lucy_session_busy".equals(FalSignalPolicy.terminalCode(Map.of("type", "error",
                    "detail", Map.of("reason", reason)))));
        }
        for (String reason : List.of("PRIVATE Concurrent session limit reached.", "concurrent_session_limit_extra",
                "Concurrent session limit reached. PRIVATE", "x".repeat(97)))
            check("fal_endpoint_error".equals(FalSignalPolicy.terminalCode(Map.of("type", "x-fal-error", "reason", reason))));
        check("fal_endpoint_error".equals(FalSignalPolicy.terminalCode(Map.of("type", "x-fal-error",
                "detail", Map.of("detail", Map.of("detail", Map.of("reason", "concurrent_session_limit")))))));
        Map<String, Object> cyclicReason = new java.util.HashMap<>(); cyclicReason.put("type", "x-fal-error"); cyclicReason.put("reason", cyclicReason);
        check("fal_endpoint_error".equals(FalSignalPolicy.terminalCode(cyclicReason)));
        check(FalSignalPolicy.terminalCode(Map.of("type", "x-fal-error", "error", "TIMEOUT",
                "reason", "concurrent_session_limit")) == null);
        // Odd dimensions plus padded planes catch stride mistakes and chroma overrun.
        for (int color : new int[]{0xff000000, 0xffffffff, 0xffff0000, 0xff00ff00, 0xff0000ff, 0xff7f7f7f, 0xff243579}) {
            int width = 7, height = 5, yStride = 10, cStride = 6;
            int[] pixels = new int[width * height]; Arrays.fill(pixels, color);
            ByteBuffer y = ByteBuffer.allocate(yStride * height), u = ByteBuffer.allocate(cStride * 3), v = ByteBuffer.allocate(cStride * 3);
            RtcPixelConversion.argbToI420(pixels, width, height, y, yStride, u, cStride, v, cStride);
            int[] decoded = RtcPixelConversion.i420ToArgb(width, height, y, yStride, u, cStride, v, cStride);
            for (int pixel : decoded) check(distance(pixel, color) <= 3);
            check(y.get(9) == 0 && u.get(5) == 0 && v.get(5) == 0);
        }
        System.out.println("RTC protocol/pixels: " + assertions + " assertions passed; no network/device/model use");
    }
}
