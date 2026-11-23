// SPDX-License-Identifier: Apache-2.0
package org.taku.tryon.realtime;

import android.os.SystemClock;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/** App-owned Camera2 to RTC to overlay transport. It never reads or modifies system camera state. */
final class LocalShoppingFrameBridge implements TryOnFrameBridge {
    public interface Source {
        CallTryOnBridge.Frame latest(long afterSequence);
        void begin(long generation);
        boolean offer(byte[] jpeg, int width, int height, long generation);
        void end(long generation);
    }

    private static final AtomicLong GENERATIONS = new AtomicLong();
    private static final long MAX_FRAME_AGE_MS = 2000;
    private final Source source;
    private final LongSupplier clock;
    private long generation, expiresAt, startedAt, lastReturnSequence;
    private boolean active;

    // Keep this clock local: D8 may share a method-reference synthetic with another class.
    private static final class ElapsedClock implements LongSupplier {
        @Override public long getAsLong() { return SystemClock.elapsedRealtime(); }
    }

    LocalShoppingFrameBridge(Source source) { this(source, new ElapsedClock()); }
    LocalShoppingFrameBridge(Source source, LongSupplier clock) {
        if (source == null || clock == null) throw new IllegalArgumentException("missing_camera_source");
        this.source = source; this.clock = clock;
    }

    @Override public synchronized String start(long durationMs) throws CallTryOnBridge.Failure {
        if (durationMs < 1 || durationMs > 60_000 || active) throw new CallTryOnBridge.Failure("bridge_invalid_duration");
        generation = GENERATIONS.incrementAndGet(); startedAt = clock.getAsLong();
        expiresAt = startedAt + durationMs; lastReturnSequence = 0; active = true;
        try { source.begin(generation); }
        catch (RuntimeException failure) {
            active = false;
            try { source.end(generation); } catch (RuntimeException ignored) { }
            throw new CallTryOnBridge.Failure("bridge_unavailable");
        }
        return UUID.randomUUID().toString().replace("-", "");
    }

    @Override public synchronized CallTryOnBridge.Frame source(long afterSequence) throws CallTryOnBridge.Failure {
        if (!current()) return null;
        final CallTryOnBridge.Frame frame;
        try { frame = source.latest(afterSequence); }
        catch (RuntimeException unavailable) { throw new CallTryOnBridge.Failure("bridge_unavailable"); }
        if (frame == null || frame.sequence <= afterSequence) return null;
        long capturedMs = frame.copiedNs / 1_000_000L, now = clock.getAsLong();
        if (capturedMs < startedAt || capturedMs > now || now - capturedMs > MAX_FRAME_AGE_MS) return null;
        if (!validJpeg(frame.jpeg) || !validSize(frame.width, frame.height))
            throw new CallTryOnBridge.Failure("bridge_invalid_frame");
        return frame;
    }

    @Override public synchronized boolean submit(byte[] jpeg, int width, int height, long sequence,
            CallTryOnBridge.Frame identity) throws CallTryOnBridge.Failure {
        if (!current() || identity == null) return false;
        if (!validJpeg(jpeg) || !validSize(width, height) || sequence <= 0)
            throw new CallTryOnBridge.Failure("bridge_invalid_return");
        if (sequence <= lastReturnSequence) return false;
        final CallTryOnBridge.Frame latest;
        try { latest = source.latest(-1); }
        catch (RuntimeException unavailable) { throw new CallTryOnBridge.Failure("bridge_unavailable"); }
        if (latest == null || !latest.sameIdentity(identity)) return false;
        long age = clock.getAsLong() - latest.copiedNs / 1_000_000L;
        if (age < 0 || age > MAX_FRAME_AGE_MS) return false;
        try { if (!source.offer(jpeg, width, height, generation)) return false; }
        catch (RuntimeException unavailable) { throw new CallTryOnBridge.Failure("bridge_invalid_return"); }
        lastReturnSequence = sequence; return true;
    }

    @Override public synchronized boolean stop() {
        if (!active) return true;
        active = false;
        try { source.end(generation); return true; }
        catch (RuntimeException unavailable) { return false; }
    }

    private boolean current() {
        if (!active) return false;
        if (clock.getAsLong() < expiresAt) return true;
        stop(); return false;
    }
    private static boolean validSize(int width, int height) {
        return width > 0 && height > 0 && width <= 1920 && height <= 1920
                && (long) width * height <= 1920L * 1920;
    }
    private static boolean validJpeg(byte[] jpeg) {
        return jpeg != null && jpeg.length >= 4 && jpeg.length <= CallTryOnBridge.MAX_JPEG
                && (jpeg[0] & 255) == 255 && (jpeg[1] & 255) == 216
                && (jpeg[jpeg.length - 2] & 255) == 255 && (jpeg[jpeg.length - 1] & 255) == 217;
    }
}
