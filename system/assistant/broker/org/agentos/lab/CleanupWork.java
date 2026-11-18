package org.agentos.lab;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;

/** Fixed waits for a removal attempt; other callers retain their existing lock behavior. */
final class CleanupWork implements AutoCloseable {
    static final long TIMEOUT_MS = 25_000;
    private static final ThreadLocal<Long> timeout = new ThreadLocal<>();
    private final Long previous;

    CleanupWork(long milliseconds) { previous = timeout.get(); timeout.set(milliseconds); }
    static boolean active() { return timeout.get() != null; }
    static long timeout() { return active() ? timeout.get() : TIMEOUT_MS; }
    public void close() { if (previous == null) timeout.remove(); else timeout.set(previous); }

    static final class WaitFailed extends RuntimeException {
        WaitFailed(Throwable cause) { super(cause); }
    }
    static final class Held implements AutoCloseable {
        private final ReentrantLock lock;
        Held(ReentrantLock lock) { this.lock = lock; }
        public void close() { lock.unlock(); }
    }
    static Held lock(ReentrantLock lock) {
        acquire(lock);
        return new Held(lock);
    }
    static void acquire(ReentrantLock lock) {
        if (!active()) { lock.lock(); return; }
        try {
            if (!lock.tryLock(timeout(), TimeUnit.MILLISECONDS))
                throw new WaitFailed(new TimeoutException("SESSION_CLEANUP_LOCK_TIMEOUT"));
        } catch (InterruptedException stopped) {
            Thread.currentThread().interrupt();
            throw new WaitFailed(stopped);
        }
    }
    static void rethrowTimeout(Exception error) {
        if (!active()) return;
        if (error instanceof WaitFailed) throw (WaitFailed) error;
        if (error instanceof TimeoutException || error instanceof InterruptedException)
            throw new WaitFailed(error);
    }
}
