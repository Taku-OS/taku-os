// SPDX-License-Identifier: Apache-2.0
package org.taku.tryon.realtime;

import java.util.concurrent.atomic.AtomicLong;

/** Fixed-cadence lease gate: stopping or rotating invalidates asynchronous encoding work. */
final class CameraFrameLease {
    static final int TARGET_FPS = 15;
    static final long INTERVAL_NS = 1_000_000_000L / TARGET_FPS;
    private static final AtomicLong GENERATIONS = new AtomicLong();
    private static final AtomicLong SEQUENCES = new AtomicLong();
    private long camera, stream, generation, lastObservedNs, nextDeadlineNs;
    private int orientation;
    private int[] geometry;
    private boolean active, sampled, deadlineExhausted;
    private Lease held;

    static final class Lease {
        final long camera, stream, generation, copiedNs;
        final int orientation;
        private Lease(long camera, long stream, long generation, long copiedNs, int orientation) {
            this.camera = camera; this.stream = stream; this.generation = generation;
            this.copiedNs = copiedNs; this.orientation = orientation;
        }
    }

    synchronized long open(long camera, long stream) {
        if (camera < 0 || stream < 0) throw new IllegalArgumentException("identity");
        this.camera = camera; this.stream = stream; generation = GENERATIONS.incrementAndGet();
        active = true; sampled = false; deadlineExhausted = false;
        lastObservedNs = 0; nextDeadlineNs = 0; held = null; geometry = null; return generation;
    }

    synchronized long orientation(int clockwiseDegrees) {
        if (clockwiseDegrees < 0 || clockwiseDegrees >= 360 || clockwiseDegrees % 90 != 0)
            throw new IllegalArgumentException("orientation");
        if (orientation != clockwiseDegrees) {
            orientation = clockwiseDegrees; generation = GENERATIONS.incrementAndGet();
            held = null;
        }
        return generation;
    }

    synchronized void close() {
        active = false; held = null; sampled = false; deadlineExhausted = false;
        generation = GENERATIONS.incrementAndGet();
    }

    synchronized long geometry(int width, int height, int left, int top, int right, int bottom) {
        if (width < 2 || height < 2 || left < 0 || top < 0 || right > width || bottom > height ||
                right - left < 2 || bottom - top < 2) throw new IllegalArgumentException("geometry");
        int[] next = {width, height, left, top, right, bottom};
        if (!java.util.Arrays.equals(geometry, next)) {
            geometry = next; generation = GENERATIONS.incrementAndGet(); held = null;
        }
        return generation;
    }

    synchronized Lease acquire(long nowNs) {
        if (!active || nowNs < 0 || sampled && nowNs < lastObservedNs) return null;
        lastObservedNs = nowNs;
        if (sampled && (deadlineExhausted || nowNs < nextDeadlineNs)) return null;
        // Consume this beat even when the encoder is busy. Nothing is queued for a later release.
        advanceDeadline(nowNs);
        if (held != null) return null;
        sampled = true;
        held = new Lease(camera, stream, generation, nowNs, orientation); return held;
    }

    private void advanceDeadline(long nowNs) {
        // The next grid point is strictly after now, skipping every missed beat without a burst.
        // Both timestamps are nonnegative and now >= deadline, so this subtraction cannot wrap.
        long step = sampled ? INTERVAL_NS - (nowNs - nextDeadlineNs) % INTERVAL_NS : INTERVAL_NS;
        if (nowNs > Long.MAX_VALUE - step) {
            deadlineExhausted = true;
        } else {
            nextDeadlineNs = nowNs + step;
        }
    }

    synchronized boolean current(Lease lease) {
        return lease != null && active && lease.generation == generation && lease.camera == camera && lease.stream == stream;
    }

    synchronized long publish(Lease lease) {
        if (held != lease) return 0;
        held = null; return current(lease) ? SEQUENCES.incrementAndGet() : 0;
    }

    synchronized void discard(Lease lease) { if (held == lease) held = null; }
}
