// SPDX-License-Identifier: Apache-2.0
package org.agentos.audio

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock

/**
 * Start/stop handshake for a driver's audio route, free of lock-order inversion with
 * [AudioRuntime] (issue #26 #2).
 *
 * The runtime calls [AudioDriver.close] while holding its own monitor, and the driver worker
 * reports [AudioDriver.Sink.ready] which needs that monitor. So:
 * - the worker never calls into the runtime while holding the route lock ([start] returns
 *   first, the caller reports ready afterwards), and
 * - [cancel] never blocks on the route lock: if the worker holds it, the worker sees the
 *   cancel flag right after releasing it and stops the route itself.
 */
class RouteGate {
    private val lock=ReentrantLock()
    private val canceled=AtomicBoolean()
    val isCanceled: Boolean get()=canceled.get()

    /**
     * Runs [startRoute] under the route lock unless already canceled. Returns false when the
     * session was canceled before or during the start; [stopRoute] has then been called for a
     * route that was started. Never call the runtime from [startRoute].
     */
    fun start(startRoute: ()->Unit,stopRoute: ()->Unit): Boolean {
        var started=false
        lock.lock()
        try {
            if(canceled.get())return false
            startRoute();started=true
        } finally { lock.unlock() }
        // A cancel that found the lock held could not stop the route; do it here.
        if(canceled.get()){if(started)stopRoute();return false}
        return true
    }

    /** Non-blocking; safe to call while holding the runtime monitor. */
    fun cancel(stopRoute: ()->Unit) {
        canceled.set(true)
        if(lock.tryLock()) {
            try { stopRoute() } finally { lock.unlock() }
        }
        // Otherwise the worker is inside start(): it re-checks the flag after unlocking.
    }
}
