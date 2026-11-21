// SPDX-License-Identifier: Apache-2.0
package android.os;
public final class HandlerThread extends Thread {
    private final Looper looper = new Looper();
    public HandlerThread(String name) { super(name); }
    @Override public synchronized void start() { }
    public Looper getLooper() { return looper; }
    public boolean quitSafely() { looper.quit = true; return true; }
}
