// SPDX-License-Identifier: Apache-2.0
package org.taku.tryon.realtime;

import android.content.Context;
import android.os.Handler;
import android.os.SystemClock;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;

/** Actual adapter with a live-state fixture and paused actor; no startup/JNI/network/model work. */
public final class RtcInformationalBurstTest {
    private static int checks;
    private static final Map<String,Object> INFO = Map.of("type","x-fal-message","sequence",1);
    private static final Map<String,Object> CONTROL = Map.of("type","generation_started");
    private static final Map<String,Object> STATUS_ERROR = Map.of("type","x-fal-message","status","error","error","INTERNAL_ERROR");

    public static void main(String[] args) throws Exception {
        List<String> failures = new ArrayList<>();
        for (FalRealtimeProfile profile : FalRealtimeProfile.values()) {
            for (boolean binary : new boolean[]{false,true}) {
                String name = profile.name() + (binary ? " binary" : " text");
                try {
                    burst(profile,binary);
                    interleavedControls(profile,binary);
                    statusError(profile,binary,false);
                    statusError(profile,binary,true);
                    finalErrorBeforeInvalid(profile,binary);
                    realControlFlood(profile,binary);
                } catch (AssertionError failure) { failures.add(name + ": " + failure.getMessage()); }
            }
            malformedBinary(profile);
        }
        if (!failures.isEmpty()) throw new AssertionError("Informational-burst regression failed in " + failures.size() + " cases: " + failures);
        System.out.println("RtcInformationalBurstTest: " + checks + " actual adapter burst/FIFO/terminal/limit assertions passed; no JNI/network/model");
    }

    private static void burst(FalRealtimeProfile profile,boolean binary) throws Exception {
        Fixture f = new Fixture(profile);
        try {
            for (int n = 0; n < 40; n++) f.send(INFO,binary);
            Map<String,Object> evidence = f.rtc.diagnosticSnapshot();
            System.out.println("informational_burst profile=" + profile.name() + " encoding=" + (binary ? "binary" : "text")
                    + " offered=40 failure=" + evidence.get("errorCode") + " source=" + evidence.getOrDefault("backpressureSource","none")
                    + " inbound=" + evidence.getOrDefault("backpressureInboundPending",-1)
                    + " received=" + evidence.get("signalMessagesReceived"));
            check(f.failures.isEmpty() && !((AtomicBoolean)field(f.rtc,"stopped")).get(),
                    "40 legal nonterminal x-fal-message entries must not terminate an active session while its control actor is paused");
            f.actor.drain();
            evidence = f.rtc.diagnosticSnapshot();
            check(f.failures.isEmpty(),"processing the informational burst must not create a transport failure");
            check(number(evidence,"signalFalMessageCount") == 40 && number(evidence,"signalMessagesReceived") == 40,
                    "all informational arrivals remain represented by the fixed diagnostic counters");
            check(!evidence.containsKey("backpressureSource"),"a harmless informational burst has no pressure origin");
        } finally { f.close(); }
    }

    private static void interleavedControls(FalRealtimeProfile profile,boolean binary) throws Exception {
        Fixture f = new Fixture(profile);
        try {
            f.pauseSdp();
            List<Map<String,Object>> controls = List.of(Map.of("type","answer","sdp","synthetic SDP not passed to JNI"),
                    Map.of("type","prompt_ack","success",true),Map.of("type","set_image_ack","success",true),CONTROL);
            for (Map<String,Object> control : controls) {
                for (int n = 0; n < 10; n++) f.send(INFO,binary);
                f.send(control,binary);
            }
            check(f.failures.isEmpty() && ((AtomicInteger)field(f.rtc,"inboundPending")).get() == 4,
                    "40 informational entries do not consume the four real control admissions");
            f.actor.drain();
            ArrayDeque<?> queue = (ArrayDeque<?>)field(f.rtc,"messages");
            List<String> types = new ArrayList<>();
            for (Object value : queue) types.add((String)((Map<?,?>)value).get("type"));
            check(types.equals(List.of("answer","prompt_ack","set_image_ack","generation_started")),
                    "answer/ACK/generation retain their original FIFO through actual decode and actor admission");
            Map<String,Object> evidence = f.rtc.diagnosticSnapshot();
            check(number(evidence,"signalMessagesReceived") == 44 && number(evidence,"signalFalMessageCount") == 40
                    && number(evidence,"signalAnswerCount") == 1 && number(evidence,"signalPromptAckCount") == 1
                    && number(evidence,"signalSetImageAckCount") == 1 && number(evidence,"signalGenerationStartedCount") == 1,
                    "informational and control signals are counted once across reader and actor");
            set(f.rtc,"processing",false);set(f.rtc,"processingType","none");
            invoke(f.rtc,"pump",new Class<?>[0]); // No peer exists, so the actual answer handler cannot call JNI.
            check(queue.isEmpty() && f.states.equals(List.of("generation_started")) && f.failures.isEmpty(),
                    "actual handlers consume the retained controls in order without replaying informational traffic");
        } finally { f.close(); }
    }

    private static void statusError(FalRealtimeProfile profile,boolean binary,boolean full) throws Exception {
        Fixture f = new Fixture(profile);
        try {
            if (full) f.fillDecoded(32);
            for (int n = 0; n < 40; n++) f.send(INFO,binary);
            f.send(STATUS_ERROR,binary);
            f.rtc.signalClosed(1006,true);
            f.actor.drain();
            Map<String,Object> evidence = f.rtc.diagnosticSnapshot();
            check(f.failures.equals(List.of("fal_endpoint_error")),"status:error inside x-fal-message must never take the informational fast path");
            check("INTERNAL_ERROR".equals(evidence.get("serverErrorCode")) && "status".equals(evidence.get("serverErrorEnvelope")),
                    "the actual terminal machine code and envelope survive even with 32 decoded entries pending");
            check(!evidence.containsKey("backpressureSource") && !evidence.containsKey("websocketCloseCode"),
                    "neither a full decoded queue nor a later close can overwrite the terminal server error");
            check(number(evidence,"signalFalMessageCount") == 41,"the terminal fal-message is counted rather than silently dropped");
        } finally { f.close(); }
    }

    private static void finalErrorBeforeInvalid(FalRealtimeProfile profile,boolean binary) throws Exception {
        Fixture f = new Fixture(profile);
        try {
            f.send(STATUS_ERROR,binary);
            if (binary) f.rtc.receiveSignal(ByteBuffer.wrap(new byte[]{(byte)0xc1}));
            else f.rtc.receiveSignal("{broken-json");
            f.rtc.signalClosed(1006,true);
            f.actor.drain();
            check(f.failures.equals(List.of("fal_endpoint_error")) && "INTERNAL_ERROR".equals(f.rtc.diagnosticSnapshot().get("serverErrorCode")),
                    "reader-side parse failure is queued behind an earlier terminal control instead of replacing it");
        } finally { f.close(); }
    }

    private static void realControlFlood(FalRealtimeProfile profile,boolean binary) throws Exception {
        Fixture f = new Fixture(profile);
        try {
            for (int n = 0; n < 9; n++) f.send(CONTROL,binary);
            Map<String,Object> evidence = f.rtc.diagnosticSnapshot();
            check(f.failures.equals(List.of("signal_backpressure")) && "inbound_pending".equals(evidence.get("backpressureSource"))
                    && number(evidence,"backpressureInboundPending") == 9,"the original eight-task upper bound still protects real controls");
        } finally { f.close(); }
    }

    private static void malformedBinary(FalRealtimeProfile profile) throws Exception {
        byte[] valid = FalSignalCodec.encode(INFO);
        byte[] trailing = Arrays.copyOf(valid,valid.length + 1);
        for (byte[] bytes : new byte[][]{trailing,FalSignalCodec.encode(List.of(INFO)),new byte[FalSignalCodec.MAX_BYTES + 1]}) {
            Fixture f = new Fixture(profile);
            try {
                f.rtc.receiveSignal(ByteBuffer.wrap(bytes));f.actor.drain();
                Map<String,Object> evidence = f.rtc.diagnosticSnapshot();
                check(f.failures.equals(List.of("invalid_signal")) && number(evidence,"signalFalMessageCount") == 0,
                        "a valid-looking informational prefix cannot bypass trailing-byte/map-root/size validation");
            } finally { f.close(); }
        }
    }

    private static final class Fixture {
        final List<String> failures = new ArrayList<>(), states = new ArrayList<>();
        final CallTryOnRtc rtc;
        final Handler actor;
        int cleanups;
        Fixture(FalRealtimeProfile profile) throws Exception {
            rtc = new CallTryOnRtc(new Context(),new CallTryOnRtc.Listener() {
                public void onState(String state) { states.add(state); }
                public void onRemoteFrame(byte[] jpeg,int width,int height) { throw new AssertionError("no JNI/video in actor fixture"); }
                public void onFailure(String code) { failures.add(code); }
                public void onCleanup(boolean confirmed) { check(confirmed,"cleanup completes");cleanups++; }
            },profile);
            actor = (Handler)field(rtc,"control");
            ((AtomicBoolean)field(rtc,"started")).set(true);((AtomicBoolean)field(rtc,"firstFrame")).set(true);
            set(rtc,"startedElapsedMs",SystemClock.elapsedRealtime());
            ((CallTryOnRtcDiagnostics)field(rtc,"diagnostics")).stage("video_live");
        }
        void send(Map<String,Object> message,boolean binary) throws Exception {
            if (binary) rtc.receiveSignal(ByteBuffer.wrap(FalSignalCodec.encode(message)));
            else rtc.receiveSignal(new JSONObject(message).toString());
        }
        void pauseSdp() throws Exception { set(rtc,"processing",true);set(rtc,"processingType","answer"); }
        void fillDecoded(int count) throws Exception {
            pauseSdp();for (int n = 0; n < count; n++) invoke(rtc,"enqueue",new Class<?>[]{Map.class},CONTROL);
        }
        void close() {
            rtc.stop();actor.drain();
            check(cleanups == 1 && actor.hasQuit(),"fixture releases its actual actor and executors exactly once");
        }
    }
    private static Object field(Object target,String name) throws Exception {
        Field field = CallTryOnRtc.class.getDeclaredField(name);field.setAccessible(true);return field.get(target);
    }
    private static void set(Object target,String name,Object value) throws Exception {
        Field field = CallTryOnRtc.class.getDeclaredField(name);field.setAccessible(true);field.set(target,value);
    }
    private static Object invoke(Object target,String name,Class<?>[] types,Object... values) throws Exception {
        Method method = CallTryOnRtc.class.getDeclaredMethod(name,types);method.setAccessible(true);return method.invoke(target,values);
    }
    private static long number(Map<String,Object> value,String key) { return ((Number)value.get(key)).longValue(); }
    private static void check(boolean value,String message) { checks++;if(!value)throw new AssertionError(message); }
}
