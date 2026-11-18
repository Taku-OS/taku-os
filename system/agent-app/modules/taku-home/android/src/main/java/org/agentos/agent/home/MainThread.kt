package org.agentos.agent.home

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Runs work that touches main-thread-owned state from Binder (content provider) threads. */
object MainThread {
  private val handler by lazy { Handler(Looper.getMainLooper()) }

  /**
   * Runs [block] on the main thread and returns its result, rethrowing what it throws. Runs
   * inline when already on the main thread. Throws [onTimeout]'s exception if the main thread
   * does not finish within [timeoutMs].
   */
  fun <T> call(timeoutMs: Long = 5_000, onTimeout: () -> Exception, block: () -> T): T {
    if (Looper.myLooper() == Looper.getMainLooper()) return block()
    val result = AtomicReference<Result<T>>()
    val done = CountDownLatch(1)
    handler.post { result.set(runCatching(block)); done.countDown() }
    if (!done.await(timeoutMs, TimeUnit.MILLISECONDS)) throw onTimeout()
    return result.get().getOrThrow()
  }
}
