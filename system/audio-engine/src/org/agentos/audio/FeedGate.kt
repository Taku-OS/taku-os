// SPDX-License-Identifier: Apache-2.0
package org.agentos.audio

import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

/**
 * Guards a native resource fed from other threads, e.g. a recognizer whose `accept()` runs on
 * call-capture threads (issue #26 #3). Calls go through [use]; [retire] refuses new calls,
 * waits until no call is inside the resource, and only then runs the release. A release is
 * never run concurrently with, or before the end of, an in-flight call.
 */
class FeedGate {
    private val lock=ReentrantLock()
    private val idle=lock.newCondition()
    private var inFlight=0
    private var retired=false
    val isRetired: Boolean get()=lock.withLock { retired }

    /** Runs [block] unless retired; throws [InterruptedException] once retired (input closed). */
    fun <T> use(block: ()->T): T {
        lock.withLock { if(retired)throw InterruptedException("input closed");inFlight++ }
        try { return block() } finally {
            lock.withLock { if(--inFlight==0)idle.signalAll() }
        }
    }

    /**
     * Refuses new calls, waits up to [timeoutMs] for in-flight calls to return, then runs
     * [release]. Returns false (and does not release; the resource is leaked rather than freed
     * under a live caller) if a call is still running at the deadline. Run on a worker thread,
     * not the main or Binder thread. Interrupts do not shorten the wait; the flag is restored.
     */
    fun retire(timeoutMs: Long=5000,release: ()->Unit): Boolean {
        var interrupted=false
        val wait=lock.withLock {
            retired=true
            val deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(timeoutMs)
            // A cancel interrupting the cleanup thread must not cut the wait short.
            while(inFlight>0) {
                val left=deadline-System.nanoTime()
                if(left<=0)break
                try{idle.awaitNanos(left)}catch(_: InterruptedException){interrupted=true}
            }
            inFlight==0
        }
        try { if(wait)release() } finally { if(interrupted)Thread.currentThread().interrupt() }
        return wait
    }

    private inline fun <T> ReentrantLock.withLock(block: ()->T): T { lock(); try { return block() } finally { unlock() } }
}
