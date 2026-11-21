// SPDX-License-Identifier: Apache-2.0
package android.os;
/** Deterministic FIFO; delayed tasks are not run by this host fixture. */
public final class Handler {
    private final Looper looper;
    public Handler(Looper looper) { this.looper = looper; }
    public boolean post(Runnable task) { if (looper.quit) return false; looper.tasks.add(task); return true; }
    public boolean postDelayed(Runnable task, long delay) { return !looper.quit; }
    public void removeCallbacks(Runnable task) { looper.tasks.removeIf(value -> value == task); }
    public void removeCallbacksAndMessages(Object token) { looper.tasks.clear(); }
    public void drain() { int count = 0; while (!looper.tasks.isEmpty()) {
        if (++count > 1000) throw new AssertionError("unbounded actor loop"); looper.tasks.remove().run();
    } }
    public boolean hasQuit() { return looper.quit; }
}
