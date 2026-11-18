package org.taku.weather;

public final class RequestIntervalTest {
    public static void main(String[] args) throws Exception {
        RequestInterval limit = new RequestInterval(30_000);
        check(limit.acquire(0));
        check(!limit.acquire(0));
        check(limit.remaining(29_999) == 1);
        check(limit.acquire(30_000));
        check(!limit.acquire(30_001));
        // Parallel callers share one budget; rejected calls don't postpone the next slot.
        java.util.concurrent.atomic.AtomicInteger accepted = new java.util.concurrent.atomic.AtomicInteger();
        Thread[] threads = new Thread[20];
        for (int i = 0; i < threads.length; i++) {
            threads[i] = new Thread(() -> { if (limit.acquire(60_000)) accepted.incrementAndGet(); });
            threads[i].start();
        }
        for (Thread thread : threads) thread.join();
        check(accepted.get() == 1);
        check(limit.remaining(60_001) == 29_999);
        System.out.println("RequestInterval: boundaries and concurrent callers passed");
    }
    private static void check(boolean value) { if (!value) throw new AssertionError(); }
}
