package org.taku.weather;

/** Shared per-process limit for GUI, widgets and Agent requests; clock is monotonic. */
final class RequestInterval {
    private final long interval;
    private long last = -1;
    RequestInterval(long interval) { this.interval = interval; }
    synchronized long remaining(long now) { return last < 0 ? 0 : Math.max(0, interval - (now - last)); }
    synchronized boolean acquire(long now) {
        if (remaining(now) > 0) return false;
        last = now;
        return true;
    }
}
