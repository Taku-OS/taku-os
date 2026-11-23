// SPDX-License-Identifier: Apache-2.0
package org.taku.tryon.realtime;

import android.content.Context;
import android.os.Handler;
import android.os.SystemClock;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.java_websocket.WebSocketImpl;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.json.JSONObject;

/** Actual RTC guard/termination paths with fake actors and an unconnected socket; no JNI/network/model. */
public final class RtcBackpressureTest {
    private static int checks;
    private static final String PRIVATE = "fixture-secret-not-for-diagnostics";
    private static final Map<String,Object> NOTICE = Map.of("type", "generation_started", "detail", PRIVATE);
    private static final Map<String,Object> ERROR = Map.of("type", "x-fal-error", "error", "INTERNAL_ERROR", "reason", PRIVATE);

    public static void main(String[] args) throws Exception {
        for (FalRealtimeProfile profile : FalRealtimeProfile.values()) {
            inbound(profile);
            decoded(profile);
            outbound(profile, false);
            outbound(profile, true);
            exactOutboundBoundary(profile);
            terminalBeforeClose(profile, false);
            terminalBeforeClose(profile, true);
        }
        diagnosticBounds();
        storeBounds();
        System.out.println("RtcBackpressureTest: " + checks + " actual RTC queue/source/FIFO/cleanup checks passed; no JNI/network/model");
    }

    private static void inbound(FalRealtimeProfile profile) throws Exception {
        Fixture f = new Fixture(profile);
        f.socket.queue(7);
        byte[] encoded = FalSignalCodec.encode(NOTICE);
        for (int n = 0; n < 8; n++) f.rtc.receiveSignal(ByteBuffer.wrap(encoded));
        check(f.failures.isEmpty(), "eight raw inbound tasks remain permitted");
        f.rtc.receiveSignal(ByteBuffer.wrap(encoded));
        assertPressure(f, "inbound_pending", 9, 0, 1, 7);
    }

    private static void decoded(FalRealtimeProfile profile) throws Exception {
        Fixture f = new Fixture(profile);
        f.pending().set(2);
        f.fillDecoded(32);
        check(f.failures.isEmpty(), "32 decoded entries remain permitted while SDP is pending");
        f.enqueue(NOTICE);
        assertPressure(f, "decoded_messages", 2, 32, 0, 0);
    }

    private static void outbound(FalRealtimeProfile profile, boolean bytes) throws Exception {
        Fixture f = new Fixture(profile);
        f.pending().set(3);
        f.fillDecoded(4);
        if (bytes) { f.socket.queue(512 * 1024); f.socket.queue(512 * 1024); f.socket.queue(1); }
        else for (int n = 0; n < 129; n++) f.socket.queue(1);
        check(!f.send(), "guard rejects before encoding or sending another signal");
        check(f.socket.sends == 0, "overflow sends no extra signal");
        assertPressure(f, "outbound_queue", 3, 4, bytes ? 3 : 129, bytes ? 1024 * 1024 + 1 : 129);
    }

    private static void exactOutboundBoundary(FalRealtimeProfile profile) throws Exception {
        Fixture f = new Fixture(profile);
        for (int n = 0; n < 128; n++) f.socket.queue(8192);
        check(f.send() && f.socket.sends == 1 && f.failures.isEmpty(), "128 entries and exactly 1 MiB retain the existing inclusive boundary");
        check(!f.rtc.diagnosticSnapshot().containsKey("backpressureSource"), "ordinary sends do not invent a pressure event");
        f.rtc.stop(); f.actor.drain();
        check(f.cleanups == 1 && f.failures.isEmpty(), "normal close still cleans once without a failure");
    }

    private static void terminalBeforeClose(FalRealtimeProfile profile, boolean full) throws Exception {
        Fixture f = new Fixture(profile);
        if (full) f.fillDecoded(32);
        f.rtc.receiveSignal(ByteBuffer.wrap(FalSignalCodec.encode(ERROR)));
        f.rtc.signalClosed(1006, true);
        f.actor.drain();
        check(f.failures.equals(List.of("fal_endpoint_error")), "server terminal control wins before the queued socket close");
        Map<String,Object> result = f.rtc.diagnosticSnapshot();
        check("INTERNAL_ERROR".equals(result.get("serverErrorCode")), "actual server machine code survives FIFO termination");
        check(!result.containsKey("backpressureSource"), "a full decoded queue cannot replace a terminal server error with pressure");
        check(!result.containsKey("websocketCloseCode"), "later close callback cannot overwrite terminal server evidence");
        check(f.cleanups == 1 && f.actor.hasQuit() && f.socket.closes == 1, "terminal server path still performs the same one-time cleanup");
        check(!result.toString().contains(PRIVATE), "server details and queued messages never leak into a diagnostic snapshot");
    }

    private static void assertPressure(Fixture f, String source, int inbound, int decoded, int outbound, long bytes) throws Exception {
        check(f.failures.equals(List.of("signal_backpressure")), "original public error code is unchanged");
        check(f.snapshots.size() == 1, "winning termination publishes its snapshot before listener cleanup");
        Map<String,Object> before = f.snapshots.get(0);
        check(source.equals(before.get("backpressureSource")), "fixed source identifies the exact guard");
        check(number(before,"backpressureInboundPending") == inbound && number(before,"backpressureDecodedMessages") == decoded,
                "snapshot captures actual inbound and decoded counts at rejection");
        check(number(before,"backpressureOutboundMessages") == outbound && number(before,"backpressureOutboundBytes") == bytes,
                "snapshot captures actual outbound count and bytes at rejection");
        check(number(before,"diagnosticSchemaVersion") == 2 && "signal_backpressure".equals(before.get("errorCode")),
                "schema and terminal error are on the same authoritative RTC snapshot");
        check(("inbound_pending".equals(source) ? "unknown" : "answer").equals(before.get("processingType")),
                "processing metadata is fixed and identifies the pending SDP handler without message contents");
        check(!before.toString().contains(PRIVATE) && !before.toString().contains("fixture.invalid"), "no message text or endpoint escapes");
        JSONObject safe = CallTryOnDiagnosticStore.sanitize(new JSONObject().put("rtc",before)).getJSONObject("rtc");
        for (String key : new String[]{"backpressureSource","processingType","backpressureInboundPending","backpressureDecodedMessages",
                "backpressureOutboundMessages","backpressureOutboundBytes","diagnosticSchemaVersion","errorCode"})
            check(before.get(key).equals(safe.get(key)), "the real Store sanitizer preserves each authoritative pressure field");
        JSONObject roundTrip = CallTryOnDiagnosticStore.sanitize(new JSONObject(new JSONObject().put("rtc",safe).toString())).getJSONObject("rtc");
        check(source.equals(roundTrip.getString("backpressureSource")) && roundTrip.getLong("backpressureOutboundBytes") == bytes,
                "serialized provider/disk shape retains the same trigger evidence");
        f.actor.drain();
        check(f.cleanups == 1 && f.actor.hasQuit() && f.socket.closes == 1, "pressure closes socket and actor exactly once");
        check(((ArrayDeque<?>)field(f.rtc,"messages")).isEmpty() && field(f.rtc,"socket") == null,
                "cleanup clears the real message queue and socket ownership");
        check(((AtomicInteger)field(f.rtc,"sourceState")).get() == 3, "source gate is permanently closed");
        for (String key : new String[]{"input","output","watchdog"})
            check(((ExecutorService)field(f.rtc,key)).isShutdown(), "owned executors are shut down");
        f.rtc.receiveSignal(ByteBuffer.wrap(FalSignalCodec.encode(NOTICE))); f.rtc.signalClosed(1000, true); f.rtc.stop(); f.actor.drain();
        Map<String,Object> after = f.rtc.diagnosticSnapshot();
        for (String key : new String[]{"backpressureSource","backpressureInboundPending","backpressureDecodedMessages",
                "backpressureOutboundMessages","backpressureOutboundBytes","processingType","errorCode"})
            check(before.get(key).equals(after.get(key)), "late input/close/stop cannot overwrite terminal pressure evidence");
        check(f.cleanups == 1 && f.failures.size() == 1, "late calls cannot duplicate failure or cleanup");
    }

    private static void diagnosticBounds() {
        CallTryOnRtcDiagnostics d = new CallTryOnRtcDiagnostics();
        d.signalBackpressure(PRIVATE, "unknown", 9, 32, 129, 1024); d.signalBackpressure(null, "unknown", 9, 32, 129, 1024);
        check(!d.snapshot().containsKey("backpressureSource"), "unknown or null sources are rejected");
        d.signalBackpressure("inbound_pending", "unknown", 9, 0, -1, -1);
        Map<String,Object> value = d.snapshot();
        check(!value.containsKey("backpressureOutboundMessages") && !value.containsKey("backpressureOutboundBytes"), "unavailable queues remain unknown rather than invented zeroes");
        d.signalBackpressure("outbound_queue", PRIVATE, 1000001, -1, 129, Long.MAX_VALUE);
        check("inbound_pending".equals(d.snapshot().get("backpressureSource")), "first pressure origin is fixed");
        d.finish("signal_backpressure"); d.signalBackpressure("decoded_messages", "answer", 0, 32, 0, 0);
        check("inbound_pending".equals(d.snapshot().get("backpressureSource")), "terminal snapshots are immutable");
        CallTryOnRtcDiagnostics bounded = new CallTryOnRtcDiagnostics();
        bounded.signalBackpressure("outbound_queue", PRIVATE, 1000001, -1, 129, Long.MAX_VALUE);
        value = bounded.snapshot();
        check(!value.containsKey("backpressureInboundPending") && !value.containsKey("backpressureDecodedMessages")
                && !value.containsKey("backpressureOutboundBytes") && number(value,"backpressureOutboundMessages") == 129,
                "invalid numeric evidence is omitted without weakening valid bounded evidence");
        check("unknown".equals(value.get("processingType")) && !value.toString().contains(PRIVATE), "unknown handler types never escape diagnostic enum bounds");
    }

    private static void storeBounds() throws Exception {
        JSONObject raw = new JSONObject().put("rtc", Map.of("backpressureSource",PRIVATE,"processingType",PRIVATE,
                "backpressureInboundPending",1000001L,"backpressureDecodedMessages",-1L,"backpressureOutboundMessages",129.0,
                "backpressureOutboundBytes",Long.MAX_VALUE,"diagnosticSchemaVersion",3L));
        JSONObject safe = CallTryOnDiagnosticStore.sanitize(raw).getJSONObject("rtc");
        for (String key : new String[]{"backpressureSource","processingType","backpressureInboundPending","backpressureDecodedMessages",
                "backpressureOutboundMessages","backpressureOutboundBytes","diagnosticSchemaVersion"})
            check(!safe.has(key), "Store rejects arbitrary source/type and invalid numeric evidence");
        JSONObject legacy = CallTryOnDiagnosticStore.sanitize(new JSONObject().put("rtc",Map.of("state","failed","errorCode","signal_backpressure"))).getJSONObject("rtc");
        check("signal_backpressure".equals(legacy.getString("errorCode")) && !legacy.has("diagnosticSchemaVersion") && !legacy.has("backpressureSource"),
                "old saved reports remain readable without inventing a previously unknown pressure origin");
        safe = CallTryOnDiagnosticStore.sanitize(new JSONObject().put("rtc",Map.of("backpressureSource","outbound_queue","processingType","none",
                "backpressureInboundPending",1000000,"backpressureDecodedMessages",0,"backpressureOutboundMessages",129,
                "backpressureOutboundBytes",9007199254740991L,"diagnosticSchemaVersion",2))).getJSONObject("rtc");
        check(safe.getLong("backpressureInboundPending") == 1000000 && safe.getLong("backpressureOutboundBytes") == 9007199254740991L
                && "none".equals(safe.getString("processingType")), "Store accepts exact documented numeric and handler boundaries");
    }

    private static final class Fixture {
        final List<String> failures = new ArrayList<>();
        final List<Map<String,Object>> snapshots = new ArrayList<>();
        final CallTryOnRtc rtc;
        final Handler actor;
        final OfflineSocket socket = new OfflineSocket();
        int cleanups;
        Fixture(FalRealtimeProfile profile) throws Exception {
            rtc = new CallTryOnRtc(new Context(), new CallTryOnRtc.Listener() {
                public void onState(String state) {}
                public void onRemoteFrame(byte[] jpeg,int w,int h) { throw new AssertionError("no frame/JNI work is allowed"); }
                public void onFailure(String code) { failures.add(code); }
                public void onDiagnostic(Map<String,Object> snapshot) { snapshots.add(snapshot); }
                public void onCleanup(boolean confirmed) { check(confirmed,"fake-actor cleanup completes");cleanups++; }
            },profile);
            actor = (Handler)field(rtc,"control");
            ((AtomicBoolean)field(rtc,"started")).set(true);
            set(rtc,"startedElapsedMs",SystemClock.elapsedRealtime());
            set(rtc,"socket",socket);
        }
        AtomicInteger pending() throws Exception { return (AtomicInteger)field(rtc,"inboundPending"); }
        void enqueue(Map<String,Object> message) throws Exception { invoke(rtc,"enqueue",new Class<?>[]{Map.class},message); }
        void fillDecoded(int count) throws Exception {
            set(rtc,"processing",true); set(rtc,"processingType","answer"); // Model an unresolved asynchronous SDP callback without calling JNI.
            for (int n = 0; n < count; n++) enqueue(NOTICE);
        }
        boolean send() throws Exception { return (Boolean)invoke(rtc,"send",new Class<?>[]{Map.class},NOTICE); }
    }
    private static final class OfflineSocket extends WebSocketClient {
        int sends, closes;
        OfflineSocket() { super(URI.create("wss://fixture.invalid/no-network")); }
        void queue(int bytes) { ((WebSocketImpl)getConnection()).outQueue.add(ByteBuffer.allocate(bytes)); }
        @Override public boolean isOpen() { return true; }
        @Override public void send(byte[] bytes) { sends++; }
        @Override public void closeConnection(int code,String reason) { closes++; }
        @Override public void connect() { throw new AssertionError("network is forbidden"); }
        public void onOpen(ServerHandshake handshake) { throw new AssertionError(); }
        public void onMessage(String message) { throw new AssertionError(); }
        public void onClose(int code,String reason,boolean remote) {}
        public void onError(Exception error) { throw new AssertionError(error); }
    }
    private static Object field(Object target,String name) throws Exception {
        Field field = CallTryOnRtc.class.getDeclaredField(name); field.setAccessible(true); return field.get(target);
    }
    private static void set(Object target,String name,Object value) throws Exception {
        Field field = CallTryOnRtc.class.getDeclaredField(name); field.setAccessible(true); field.set(target,value);
    }
    private static Object invoke(Object target,String name,Class<?>[] types,Object... values) throws Exception {
        Method method = CallTryOnRtc.class.getDeclaredMethod(name,types); method.setAccessible(true); return method.invoke(target,values);
    }
    private static long number(Map<String,Object> value,String key) { return ((Number)value.get(key)).longValue(); }
    private static void check(boolean value,String message) { checks++; if(!value)throw new AssertionError(message); }
}
