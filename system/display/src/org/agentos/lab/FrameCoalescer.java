package org.agentos.lab;

import java.util.function.LongSupplier;

/** Runs on one handler. Acquire frames only when the scheduled consumer runs. */
final class FrameCoalescer implements AutoCloseable {
    interface Scheduler {
        void postDelayed(Runnable task, long delayMs);
        void remove(Runnable task);
    }

    private final LongSupplier clock;
    private final Scheduler scheduler;
    private final Runnable consume;
    private final long intervalMs;
    private final Runnable delivery = this::deliver;
    private boolean pending, closed, delivered;
    private long lastDelivery;

    FrameCoalescer(LongSupplier clock, Scheduler scheduler, long intervalMs, Runnable consume) {
        if (intervalMs < 0) throw new IllegalArgumentException("Negative frame interval");
        this.clock = clock;
        this.scheduler = scheduler;
        this.intervalMs = intervalMs;
        this.consume = consume;
    }

    void onFrameAvailable() {
        if (closed || pending) return;
        pending = true;
        long delay = delivered ? Math.max(0, intervalMs - (clock.getAsLong() - lastDelivery)) : 0;
        scheduler.postDelayed(delivery, delay);
    }

    boolean hasPendingFrame() { return pending; }

    private void deliver() {
        if (closed) return;
        pending = false;
        delivered = true;
        lastDelivery = clock.getAsLong();
        consume.run();
    }

    @Override public void close() {
        closed = true;
        pending = false;
        scheduler.remove(delivery);
    }
}
