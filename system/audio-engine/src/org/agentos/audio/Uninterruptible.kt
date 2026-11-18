// SPDX-License-Identifier: Apache-2.0
package org.agentos.audio

import java.util.concurrent.TimeUnit

/**
 * Waits for [thread] to end, up to [timeoutMs] (0 = no limit), even if the caller is
 * interrupted meanwhile; the interrupt flag is restored afterwards. Cleanup uses this so a
 * cancel arriving mid-teardown (close() interrupts the driver worker) cannot skip a wait and
 * release a resource another thread is still using. Returns true once the thread has ended.
 */
fun joinUninterruptibly(thread: Thread, timeoutMs: Long = 0): Boolean {
    if (thread === Thread.currentThread()) return false
    val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
    var interrupted = false
    try {
        while (thread.isAlive) {
            val left = if (timeoutMs <= 0) 0L else TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
            if (timeoutMs > 0 && left <= 0) break
            try { thread.join(left) } catch (_: InterruptedException) { interrupted = true }
        }
    } finally {
        if (interrupted) Thread.currentThread().interrupt()
    }
    return !thread.isAlive
}
