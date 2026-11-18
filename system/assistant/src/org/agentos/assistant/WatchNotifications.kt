package org.agentos.assistant

import java.util.ArrayList
import java.util.HashMap
import java.util.concurrent.Executor

/** Ordered invalidations on an asynchronous serial executor. Closing cancels queued callbacks;
 * an already executing callback may finish. No observer runs under the registry or owner's lock. */
internal class WatchNotifications(private val dispatcher: Executor) {
    private val watches = HashMap<String, List<Watch>>()

    private class Watch(val callback: Runnable) {
        @Volatile var closed = false
    }

    @Synchronized
    fun watch(id: String, callback: Runnable): AutoCloseable {
        val watch = Watch(callback)
        val current = watches[id]
        val next = if (current == null) ArrayList<Watch>(1) else ArrayList(current)
        next.add(watch)
        watches[id] = next
        return AutoCloseable {
            synchronized(this@WatchNotifications) {
                if (watch.closed) return@AutoCloseable
                watch.closed = true
                val currentWatches = watches[id]
                if (currentWatches != null) {
                    val remaining = ArrayList(currentWatches)
                    remaining.remove(watch)
                    if (remaining.isEmpty()) watches.remove(id) else watches[id] = remaining
                }
            }
        }
    }

    @Synchronized
    fun changed(id: String) {
        // Subscription changes replace lists; queued events keep their original snapshot.
        val batch = watches[id] ?: return
        dispatcher.execute {
            for (watch in batch) if (!watch.closed) {
                try { watch.callback.run() } catch (ignored: RuntimeException) { }
            }
        }
    }
}
