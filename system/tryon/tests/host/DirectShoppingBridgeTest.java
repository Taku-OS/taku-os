// SPDX-License-Identifier: Apache-2.0
package org.taku.tryon.realtime;

import android.os.SystemClock;
import java.util.concurrent.atomic.AtomicLong;
import org.json.JSONObject;

/** Actual app-owned bridge with synthetic JPEG markers, fake source and injected monotonic time. */
public final class DirectShoppingBridgeTest {
    private static final byte[] JPEG = {(byte)255, (byte)216, 1, (byte)255, (byte)217};
    private static int checks;
    public static void main(String[] args) throws Exception {
        defaultClockAdmitsFramesAndExpires();
        boundedStartAndStop();
        sourceSequenceFreshnessAndMetadata();
        outputLeaseTupleAndSequence();
        expiryIsAbsolute();
        stopInvalidatesBeforeSourceEnd();
        providerOrNetworkNeverNeeded();
        System.out.println("DirectShoppingBridgeTest: " + checks + " checks passed; synthetic bytes only, no native provider/camera/cloud");
    }
    private static void defaultClockAdmitsFramesAndExpires() throws Exception {
        SystemClock.nowMs = 1000;
        Source source = new Source();
        LocalShoppingFrameBridge bridge = new LocalShoppingFrameBridge(source);
        bridge.start(100);
        source.frame = frame(JPEG, 1, 10, 20, 30, 1_000_000_000L, 640, 480);
        check(bridge.source(0) == source.frame, "default clock uses elapsed realtime to admit a fresh camera frame");
        SystemClock.nowMs = 1099;
        check(bridge.submit(JPEG, 480, 640, 1, source.frame), "default clock keeps the live source/output lease before expiry");
        SystemClock.nowMs = 1100;
        check(bridge.source(0) == null && source.ends == 1, "default clock expires at the original absolute deadline");
        check(bridge.stop() && source.ends == 1, "default clock cleanup remains idempotent");
    }
    private static void boundedStartAndStop() throws Exception {
        AtomicLong clock = new AtomicLong(1000); Source source = new Source();
        LocalShoppingFrameBridge bridge = new LocalShoppingFrameBridge(source, clock::get);
        check(bridge.source(-1) == null, "unstarted bridge cannot request source pixels");
        check(!bridge.submit(JPEG, 4, 4, 1, null), "unstarted bridge cannot deliver an output");
        check(source.latestCalls == 0 && source.offers == 0, "inactive bridge never touches source or renderer");
        fails("bridge_invalid_duration", () -> bridge.start(0));
        fails("bridge_invalid_duration", () -> bridge.start(60001));
        String token = bridge.start(60000);
        check(token.matches("[0-9a-f]{32}"), "local run retains the opaque bounded-session token contract");
        check(source.begins == 1 && source.active > 0, "explicit start opens one renderer lease");
        fails("bridge_invalid_duration", () -> bridge.start(60000));
        check(source.begins == 1, "duplicate start cannot open another renderer lease");
        check(bridge.stop() && source.ends == 1 && source.active == 0, "stop ends its exact source lease");
        check(bridge.stop() && source.ends == 1, "stop is idempotent");
        long old = source.lastBegin;
        check(!token.equals(bridge.start(1)) && source.lastBegin > old, "explicit new run gets a fresh globally increasing lease");
        bridge.stop();
        try { new LocalShoppingFrameBridge(null, clock::get); throw new AssertionError("null source accepted"); }
        catch (IllegalArgumentException expected) { checks++; }
    }
    private static void sourceSequenceFreshnessAndMetadata() throws Exception {
        AtomicLong clock = new AtomicLong(1000); Source source = new Source();
        LocalShoppingFrameBridge bridge = new LocalShoppingFrameBridge(source, clock::get); bridge.start(60000);
        source.frame = frame(JPEG, 7, 10, 20, 30, 999_000_000L, 640, 480);
        check(bridge.source(0) == null, "cached frame from before start is never sent to RTC");
        source.frame = frame(JPEG, 7, 10, 20, 30, 1_001_000_000L, 640, 480);
        check(bridge.source(0) == null, "future timestamp cannot satisfy freshness");
        source.frame = frame(JPEG, 7, 10, 20, 30, 1_000_000_000L, 640, 480);
        CallTryOnBridge.Frame accepted = bridge.source(6);
        check(accepted == source.frame && accepted.width == 640 && accepted.height == 480, "source preserves exact captured tuple/dimensions rather than window dimensions");
        check(bridge.source(7) == null && bridge.source(8) == null, "bridge independently rejects duplicate/regressed source sequences");
        clock.set(3000); check(bridge.source(6) == source.frame, "two-second source freshness boundary is inclusive");
        clock.set(3001); check(bridge.source(6) == null, "source older than two seconds is rejected");
        source.frame = frame(new byte[]{1,2,3,4}, 8, 10, 20, 30, 3_001_000_000L, 640, 480);
        fails("bridge_invalid_frame", () -> bridge.source(7));
        bridge.stop();
    }
    private static void outputLeaseTupleAndSequence() throws Exception {
        AtomicLong clock = new AtomicLong(1000); Source source = new Source();
        LocalShoppingFrameBridge bridge = new LocalShoppingFrameBridge(source, clock::get); bridge.start(60000);
        source.frame = frame(JPEG, 1, 10, 20, 30, 1_000_000_000L, 640, 480);
        CallTryOnBridge.Frame identity = bridge.source(0);
        check(bridge.submit(JPEG, 480, 640, 1, identity), "valid output is offered using encoded output size and the exact renderer lease");
        check(source.outputWidth == 480 && source.outputHeight == 640 && source.lastOffer == source.lastBegin, "output width/height are not overwritten by camera/view size");
        check(!bridge.submit(JPEG, 480, 640, 1, identity) && !bridge.submit(JPEG, 480, 640, 0, null), "duplicate output and missing identity never redeliver");
        check(source.offers == 1, "duplicate output does not reach the renderer");
        for (int field = 0; field < 3; field++) {
            CallTryOnBridge.Frame changed = frame(JPEG, 2, field == 0 ? 11 : 10, field == 1 ? 21 : 20, field == 2 ? 31 : 30,
                    1_000_000_000L, 640, 480);
            source.frame = changed;
            check(!bridge.submit(JPEG, 480, 640, 2, identity), "camera/stream/generation change rejects old return tuple " + field);
        }
        source.frame = frame(JPEG, 3, 10, 20, 30, 1_000_000_000L, 640, 480);
        source.allowOffers = false;
        check(!bridge.submit(JPEG, 480, 640, 2, identity), "renderer rejection propagates without advancing output sequence");
        source.allowOffers = true;
        check(bridge.submit(JPEG, 480, 640, 2, identity), "retrying an unaccepted output sequence is still allowed");
        clock.set(3001); check(!bridge.submit(JPEG, 480, 640, 3, identity), "stalled camera tuple cannot receive generated output");
        source.frame = frame(JPEG, 4, 10, 20, 30, 3_002_000_000L, 640, 480);
        check(!bridge.submit(JPEG, 480, 640, 3, identity), "future source timestamp cannot authorize output");
        source.frame = frame(JPEG, 5, 10, 20, 30, 3_001_000_000L, 640, 480);
        fails("bridge_invalid_return", () -> bridge.submit(JPEG, 0, 640, 3, identity));
        fails("bridge_invalid_return", () -> bridge.submit(JPEG, 1921, 640, 3, identity));
        fails("bridge_invalid_return", () -> bridge.submit(JPEG, 480, 640, -1, identity));
        fails("bridge_invalid_return", () -> bridge.submit(new byte[]{1,2,3,4}, 480, 640, 3, identity));
        byte[] oversized = new byte[400 * 1024 + 1]; oversized[0]=(byte)255; oversized[1]=(byte)216; oversized[oversized.length-2]=(byte)255; oversized[oversized.length-1]=(byte)217;
        fails("bridge_invalid_return", () -> bridge.submit(oversized, 480, 640, 3, identity));
        bridge.stop();
    }
    private static void expiryIsAbsolute() throws Exception {
        AtomicLong clock = new AtomicLong(1000); Source source = new Source();
        LocalShoppingFrameBridge bridge = new LocalShoppingFrameBridge(source, clock::get); bridge.start(100);
        source.frame = frame(JPEG, 1, 1, 2, 3, 1_099_000_000L, 640, 480);
        clock.set(1099); CallTryOnBridge.Frame identity = bridge.source(0);
        check(identity != null && bridge.submit(JPEG, 640, 480, 1, identity), "source/output remain allowed one millisecond before deadline");
        clock.set(1100);
        check(bridge.source(0) == null && source.ends == 1, "exact deadline ends source lease without resetting on activity");
        int offers = source.offers;
        check(!bridge.submit(JPEG, 640, 480, 2, identity) && source.offers == offers, "queued output after absolute deadline is not offered");
        check(bridge.stop() && source.ends == 1, "post-expiry cleanup does not end the lease twice");
    }
    private static void stopInvalidatesBeforeSourceEnd() throws Exception {
        AtomicLong clock = new AtomicLong(1000); Source source = new Source();
        LocalShoppingFrameBridge bridge = new LocalShoppingFrameBridge(source, clock::get); bridge.start(60000);
        source.frame = frame(JPEG, 1, 1, 2, 3, 1_000_000_000L, 640, 480);
        CallTryOnBridge.Frame identity = bridge.source(0);
        source.duringEnd = () -> {
            try {
                check(bridge.source(-1) == null, "stop invalidates source before calling external end");
                check(!bridge.submit(JPEG, 640, 480, 1, identity), "reentrant late return during end cannot repaint");
            } catch (Exception failure) { throw new AssertionError(failure); }
        };
        check(bridge.stop(), "stop can finish after invalidating reentrant source callbacks");
        int latestCalls = source.latestCalls;
        check(bridge.source(0) == null && source.latestCalls == latestCalls, "stopped bridge does not ask for more source frames");
        check(!bridge.submit(JPEG, 640, 480, 2, identity), "stopped bridge rejects old network callback");
        check(source.offers == 0, "no return was delivered during or after stop");
    }
    private static void providerOrNetworkNeverNeeded() throws Exception {
        AtomicLong clock = new AtomicLong(1000); Source source = new Source();
        source.failBegin = true;
        LocalShoppingFrameBridge bridge = new LocalShoppingFrameBridge(source, clock::get);
        fails("bridge_unavailable", () -> bridge.start(60000));
        check(source.ends == 1 && bridge.source(0) == null && bridge.stop(), "failed local preparation cleans lease and remains stopped");
    }
    static CallTryOnBridge.Frame frame(byte[] jpeg, long sequence, long camera, long stream, long generation, long copiedNs, int width, int height) throws Exception {
        return new CallTryOnBridge.Frame(jpeg, new JSONObject().put("sourceSeq", sequence).put("camera", camera).put("stream", stream)
                .put("generation", generation).put("copiedNs", copiedNs).put("width", width).put("height", height));
    }
    private static final class Source implements LocalShoppingFrameBridge.Source {
        CallTryOnBridge.Frame frame;
        long active, lastBegin, lastOffer;
        int begins, ends, offers, latestCalls, outputWidth, outputHeight;
        boolean allowOffers = true, failBegin;
        Runnable duringEnd;
        public CallTryOnBridge.Frame latest(long after) { latestCalls++; return frame; }
        public void begin(long generation) { begins++; active=lastBegin=generation; if (failBegin) throw new IllegalStateException("synthetic source failure"); }
        public boolean offer(byte[] jpeg, int width, int height, long generation) {
            offers++; lastOffer=generation; outputWidth=width; outputHeight=height;
            return allowOffers && active == generation;
        }
        public void end(long generation) { ends++; if (active == generation) active=0; if (duringEnd != null) duringEnd.run(); }
    }
    private interface Throwing { Object run() throws Exception; }
    private static void fails(String code, Throwing action) throws Exception {
        checks++; try { action.run(); } catch (CallTryOnBridge.Failure expected) { if (code.equals(expected.code)) return; throw new AssertionError("wrong sanitized bridge code", expected); }
        throw new AssertionError("expected bridge failure " + code);
    }
    private static void check(boolean okay, String message) { checks++; if (!okay) throw new AssertionError(message); }
}
