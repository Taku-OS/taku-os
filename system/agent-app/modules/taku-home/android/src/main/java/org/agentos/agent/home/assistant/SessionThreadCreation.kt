package org.agentos.agent.home.assistant

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Main-thread-owned binding shared by voice submission and the Live Talk handoff. */
internal class SessionThreadCreation {
  private val mutex = Mutex()
  var generation = 0L
    private set

  fun invalidate() { generation++ }

  suspend fun resolve(expected: Long, current: () -> String?, create: suspend () -> String,
    bind: (String) -> Unit): String = mutex.withLock {
    checkGeneration(expected)
    current() ?: create().also { id ->
      currentCoroutineContext().ensureActive()
      checkGeneration(expected)
      // Bind before releasing the lock, not after the UI transition delay.
      bind(id)
    }
  }

  private fun checkGeneration(expected: Long) {
    if (generation != expected) throw CancellationException("Assistant session changed")
  }
}
