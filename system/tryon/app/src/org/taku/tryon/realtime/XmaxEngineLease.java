// SPDX-License-Identifier: Apache-2.0
package org.taku.tryon.realtime;

import java.util.function.BooleanSupplier;

/** Serializes Agora's process-global engine across retiring media segments. No Android or network state. */
final class XmaxEngineLease {
    private Object owner;
    private boolean retiring;
    synchronized boolean acquire(Object candidate, BooleanSupplier cancelled, long timeoutMs) throws InterruptedException {
        long limit = System.nanoTime() + timeoutMs * 1_000_000L;
        while (owner != null) {
            if (cancelled.getAsBoolean() || !retiring) return false;
            long remaining = (limit - System.nanoTime()) / 1_000_000L;
            if (remaining <= 0) return false;
            wait(Math.min(remaining, 40L));
        }
        if (cancelled.getAsBoolean()) return false;
        owner = candidate; retiring = false; return true;
    }
    synchronized void retire(Object candidate) { if (owner == candidate) { retiring = true; notifyAll(); } }
    synchronized boolean owns(Object candidate) { return owner == candidate; }
    synchronized void release(Object candidate) { if (owner == candidate) { owner = null; retiring = false; notifyAll(); } }
}
