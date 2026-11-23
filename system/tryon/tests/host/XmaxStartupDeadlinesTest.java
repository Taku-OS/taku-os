// SPDX-License-Identifier: Apache-2.0
package org.taku.tryon.realtime;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Fake monotonic time and synchronized claims; no sleeping, Android, network or model. */
public final class XmaxStartupDeadlinesTest {
    private static int checks;
    public static void main(String[] args) throws Exception {
        preparationDoesNotConsumeGenerationWindow(); generationWaitIsReservedOnce(); firstFrameStopsOnlyItsWait();
        stopFencesEveryLateCallback(); hardSessionDeadlineNeverMoves(); monotonicArithmetic();
        queuedStartDoesNotConsumeGenerationWindow(); orderedClaimsFenceOldCallbacks(); concurrentBoundaryClaims();
        System.out.println("XmaxStartupDeadlinesTest: " + checks + " checks passed; fake clock, no model/network/device");
    }
    private static XmaxStartupDeadlines deadlines() { return new XmaxStartupDeadlines(60000, 30000); }
    private static void preparationDoesNotConsumeGenerationWindow() {
        FakeClock clock = new FakeClock(); XmaxStartupDeadlines d = deadlines();
        check(d.startSession(clock.now()), "session begins once at original start");
        check(d.sessionDelayMs() == 60000 && d.firstFrameDelayMs() == 30000, "original total limit and official generation wait retained");
        clock.advance(7000);
        check(!d.claimFirstFrameTimeout(clock.now()), "preparation has not armed a generation timeout");
        check(d.startGeneration(clock.now()), "connection-ready send point reserves the wait");
        clock.advance(23000);
        check(!d.claimFirstFrameTimeout(clock.now()), "old start-plus-30s boundary cannot prematurely fail generation");
        clock.advance(6999);
        check(!d.claimFirstFrameTimeout(clock.now()), "full 30-second generation window is available after preparation");
        clock.advance(1);
        check(d.claimFirstFrameTimeout(clock.now()), "generation wait expires exactly 30 seconds after start submission");
        check(!d.sessionExpired(clock.now()), "generation timeout does not redefine total session deadline");
    }
    private static void generationWaitIsReservedOnce() {
        FakeClock clock = new FakeClock(); XmaxStartupDeadlines d = deadlines(); int generationTimers = 0;
        check(!d.startGeneration(clock.now()), "no generation timer before the session begins");
        d.startSession(clock.now()); clock.advance(29000);
        check(!d.claimFirstFrameTimeout(clock.now()), "no second timer while REST/room preparation is still pending");
        if (d.startGeneration(clock.now())) generationTimers++;
        clock.advance(1000);
        if (d.startGeneration(clock.now())) generationTimers++;
        check(generationTimers == 1, "only the first actual start can arm a generation timer");
        check(!d.startSession(clock.now()), "repeated start cannot reset the total limit");
        clock.advance(28999);
        check(!d.claimFirstFrameTimeout(clock.now()), "repeat callbacks do not shorten or restart the original generation wait");
        clock.advance(1);
        check(d.claimFirstFrameTimeout(clock.now()), "single reserved wait remains anchored to original send point");
    }
    private static void firstFrameStopsOnlyItsWait() {
        FakeClock clock = new FakeClock(); XmaxStartupDeadlines d = deadlines();
        d.startSession(clock.now()); clock.advance(7000); d.startGeneration(clock.now()); clock.advance(29999); d.acceptFrame();
        clock.advance(1);
        check(!d.claimFirstFrameTimeout(clock.now()), "valid first frame prevents pending timeout callback");
        check(!d.startGeneration(clock.now()), "first-frame or other late callbacks cannot start a second generation wait");
        clock.advance(23000);
        check(d.sessionExpired(clock.now()), "receiving video does not extend the 60-second total limit");
        check(!d.claimFirstFrameTimeout(clock.now()), "late generation callback stays harmless after live video");
    }
    private static void stopFencesEveryLateCallback() {
        for (boolean armed : new boolean[]{false, true}) {
            FakeClock clock = new FakeClock(); XmaxStartupDeadlines d = deadlines();
            d.startSession(clock.now()); clock.advance(7000); if (armed) d.startGeneration(clock.now());
            d.stop(); clock.advance(60000); d.acceptFrame();
            check(!d.startGeneration(clock.now()), "cancel prevents late generation arm, including preparation cancellation");
            check(!d.startSession(clock.now()), "stopped lifecycle cannot be revived by a late session callback");
            check(!d.claimFirstFrameTimeout(clock.now()) && !d.sessionExpired(clock.now()), "cancel makes queued deadlines inert");
        }
        XmaxStartupDeadlines beforeStart = deadlines(); beforeStart.stop();
        check(!beforeStart.startSession(0) && !beforeStart.startGeneration(0), "stop before start stays terminal");
    }
    private static void hardSessionDeadlineNeverMoves() {
        FakeClock clock = new FakeClock(); XmaxStartupDeadlines d = deadlines();
        d.startSession(clock.now()); clock.advance(50000); check(d.startGeneration(clock.now()), "late preparation still reserves only one generation wait");
        clock.advance(9999);
        check(!d.sessionExpired(clock.now()), "total session remains active before original boundary");
        clock.advance(1);
        check(d.sessionExpired(clock.now()), "total limit stays start-plus-60s despite later generation start");
        check(!d.claimFirstFrameTimeout(clock.now()), "hard deadline can end the run before a later first-frame deadline");
        d.stop(); clock.advance(20000);
        check(!d.claimFirstFrameTimeout(clock.now()), "late first-frame timer cannot revive a run stopped by total deadline");
        XmaxStartupDeadlines noGeneration = deadlines(); noGeneration.startSession(0);
        check(noGeneration.sessionExpired(60_000_000_000L) && !noGeneration.claimFirstFrameTimeout(60_000_000_000L),
                "preparation-only session still ends at total deadline without manufacturing generation timeout");
        check(!noGeneration.startGeneration(60_000_000_000L), "cannot send a new generation after total budget already elapsed");
    }
    private static void monotonicArithmetic() {
        XmaxStartupDeadlines d = deadlines(); d.startSession(-10_000_000_000L); d.startGeneration(-9_000_000_000L);
        check(!d.claimFirstFrameTimeout(-9_000_000_001L), "regressed clock cannot appear expired");
        check(d.claimFirstFrameTimeout(21_000_000_000L), "nanoTime's arbitrary negative origin is supported");
    }
    private static void queuedStartDoesNotConsumeGenerationWindow() {
        FakeClock clock = new FakeClock(); XmaxStartupDeadlines d = deadlines(); d.startSession(clock.now());
        clock.advance(7000); // Generation is reserved and the start packets are queued, not yet submitted.
        clock.advance(9000);
        check(!d.claimFirstFrameTimeout(clock.now()), "waiting in schedule(0)/worker queues cannot spend generation time");
        check(d.startGeneration(clock.now()), "first actual start packet arms the wait at worker submission");
        clock.advance(29999);
        check(!d.claimFirstFrameTimeout(clock.now()), "full window remains available after both preparation and worker delay");
        clock.advance(1);
        check(d.claimFirstFrameTimeout(clock.now()), "timeout uses first packet submission rather than maybeGenerate time");
        check(!d.claimFirstFrameTimeout(clock.now()), "only one timer callback can claim failure");
        check(!d.acceptFrame(), "frame after timeout claim is rejected even before transport finish runs");
    }
    private static void orderedClaimsFenceOldCallbacks() throws Exception {
        XmaxStartupDeadlines timeoutFirst = armedDeadlines();
        CountDownLatch claimed = new CountDownLatch(1), finish = new CountDownLatch(1);
        AtomicBoolean failure = new AtomicBoolean(), live = new AtomicBoolean();
        Thread timer = new Thread(() -> {
            boolean won = timeoutFirst.claimFirstFrameTimeout(37_000_000_000L);
            claimed.countDown(); await(finish);
            if (won) failure.set(true); // Pause between claim and the transport's fail() to reproduce the old TOCTOU.
        });
        timer.start();
        check(claimed.await(2, TimeUnit.SECONDS), "timeout thread pauses after its atomic claim");
        try {
            if (timeoutFirst.acceptFrame()) live.set(true);
            check(!live.get(), "late output cannot report live while a claimed timeout is waiting to finish");
        } finally { finish.countDown(); }
        timer.join(2000); check(!timer.isAlive() && failure.get(), "timeout winner reaches failure exactly once");
        check(!timeoutFirst.acceptFrame() && !timeoutFirst.claimFirstFrameTimeout(38_000_000_000L), "timeout winner rejects every later claim");

        XmaxStartupDeadlines frameFirst = armedDeadlines();
        check(frameFirst.acceptFrame(), "output wins first-frame claim at the timeout boundary");
        check(!frameFirst.claimFirstFrameTimeout(37_000_000_000L), "previously queued timeout cannot fail after a first-frame winner");
        check(frameFirst.acceptFrame(), "subsequent live frames remain accepted");
        check(frameFirst.sessionExpired(60_000_000_000L), "first-frame winner still cannot extend total deadline");
        frameFirst.stop();
        check(!frameFirst.acceptFrame() && !frameFirst.claimFirstFrameTimeout(61_000_000_000L), "total stop fences both paths");
    }
    private static void concurrentBoundaryClaims() throws Exception {
        for (int index = 0; index < 64; index++) {
            XmaxStartupDeadlines d = armedDeadlines();
            CountDownLatch ready = new CountDownLatch(2), run = new CountDownLatch(1);
            AtomicBoolean timeout = new AtomicBoolean(), frame = new AtomicBoolean();
            Thread timer = new Thread(() -> { ready.countDown(); await(run); timeout.set(d.claimFirstFrameTimeout(37_000_000_000L)); });
            Thread output = new Thread(() -> { ready.countDown(); await(run); frame.set(d.acceptFrame()); });
            timer.start(); output.start();
            check(ready.await(2, TimeUnit.SECONDS), "both boundary claimants ready"); run.countDown();
            timer.join(2000); output.join(2000);
            check(!timer.isAlive() && !output.isAlive(), "boundary claimants terminate");
            check(timeout.get() != frame.get(), "exactly one first-frame/timeout outcome can win");
            check(!d.claimFirstFrameTimeout(38_000_000_000L), "late timeout cannot duplicate failure or override a frame winner");
            check(d.acceptFrame() == frame.get(), "late frame follows the same winner and cannot override timeout");
        }
    }
    private static XmaxStartupDeadlines armedDeadlines() {
        XmaxStartupDeadlines d = deadlines(); d.startSession(0); d.startGeneration(7_000_000_000L); return d;
    }
    private static void await(CountDownLatch latch) {
        try { if (!latch.await(2, TimeUnit.SECONDS)) throw new AssertionError("claim test did not release"); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new AssertionError(error); }
    }
    private static final class FakeClock {
        private long nowNs = 1_000_000_000L;
        long now() { return nowNs; }
        void advance(long milliseconds) { nowNs += milliseconds * 1_000_000L; }
    }
    private static void check(boolean result, String reason) { if (!result) throw new AssertionError(reason); checks++; }
}
