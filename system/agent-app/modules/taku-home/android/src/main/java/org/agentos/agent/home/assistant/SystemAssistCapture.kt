package org.agentos.agent.home.assistant

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/** Keeps one early system hold's voice submission behind its original screen handoff. */
internal object SystemAssistCapture {
  class Failure(val code: String) : java.io.IOException("The invocation screen was not delivered")
  class Ticket internal constructor(val gestureId: String) {
    private val completion = CompletableDeferred<String>()
    @Volatile var rejected = false
      private set
    val ready get() = completion.isCompleted && !rejected

    fun retainOnHide(presentedEarly: Boolean, inFlight: Boolean) = presentedEarly && inFlight && !rejected

    @Synchronized fun received() {
      if (!rejected) completion.complete("")
    }

    @Synchronized fun reject(code: String = "CAPTURE_EXPIRED") {
      if (completion.isCompleted) return
      rejected = true
      completion.complete(code)
    }

    suspend fun awaitReceived(timeoutMs: Long = 8_000) {
      val result = withTimeoutOrNull(timeoutMs) { completion.await() }
      if (result == null) reject("SCREENSHOT_UNAVAILABLE")
      val code = result ?: "SCREENSHOT_UNAVAILABLE"
      if (code.isNotEmpty()) throw Failure(code)
    }
  }

  private var current: Ticket? = null

  @Synchronized fun begin(gestureId: String): Ticket {
    current?.reject()
    return Ticket(gestureId).also { current = it }
  }

  @Synchronized fun forGesture(gestureId: String): Ticket? = current?.takeIf { it.gestureId == gestureId }
}
