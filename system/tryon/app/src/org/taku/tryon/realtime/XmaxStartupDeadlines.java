// SPDX-License-Identifier: Apache-2.0
package org.taku.tryon.realtime;

/** Monotonic startup windows: generation preparation never moves the original session deadline. */
final class XmaxStartupDeadlines {
    private final long sessionMs, firstFrameMs;
    private long sessionStartedNs, generationStartedNs;
    private boolean sessionStarted, generationStarted, firstFrame, firstFrameTimedOut, stopped;

    XmaxStartupDeadlines(long sessionMs, long firstFrameMs) {
        if (sessionMs <= 0 || sessionMs > 60000 || firstFrameMs <= 0 || firstFrameMs > sessionMs)
            throw new IllegalArgumentException("invalid_request");
        this.sessionMs = sessionMs; this.firstFrameMs = firstFrameMs;
    }
    long sessionDelayMs() { return sessionMs; }
    long firstFrameDelayMs() { return firstFrameMs; }
    synchronized boolean startSession(long nowNs) {
        if (stopped || sessionStarted) return false;
        sessionStarted = true; sessionStartedNs = nowNs; return true;
    }
    /** Reserve exactly one generation wait, immediately before submitting its start signal. */
    synchronized boolean startGeneration(long nowNs) {
        if (stopped || !sessionStarted || generationStarted || firstFrame || firstFrameTimedOut || sessionExpired(nowNs)) return false;
        generationStarted = true; generationStartedNs = nowNs; return true;
    }
    synchronized boolean sessionExpired(long nowNs) {
        return !stopped && sessionStarted && elapsed(nowNs, sessionStartedNs, sessionMs);
    }
    /** Claims failure atomically against accepting a first frame; an old callback cannot undo live video. */
    synchronized boolean claimFirstFrameTimeout(long nowNs) {
        if (stopped || firstFrame || firstFrameTimedOut || !generationStarted || !elapsed(nowNs, generationStartedNs, firstFrameMs)) return false;
        firstFrameTimedOut = true; return true;
    }
    /** All return frames pass this gate, so a timeout winner cannot subsequently publish a late frame. */
    synchronized boolean acceptFrame() {
        if (stopped || firstFrameTimedOut || !generationStarted) return false;
        firstFrame = true; return true;
    }
    synchronized void stop() { stopped = true; }
    private static boolean elapsed(long nowNs, long startedNs, long durationMs) {
        return nowNs - startedNs >= durationMs * 1_000_000L;
    }
}
