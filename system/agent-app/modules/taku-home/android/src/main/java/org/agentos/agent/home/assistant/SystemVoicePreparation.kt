package org.agentos.agent.home.assistant

/** Only the framework capture owner can release a pending system hold to the recorder. */
internal class SystemVoicePreparation(private val gesture: VoiceGesture) {
  private var owner: String? = null
  private var presented = false
  private var showable = false
  private var ready = false

  private fun current(id: String, now: Long, available: Boolean): Boolean = available &&
    gesture.id == id && gesture.source == "system" && gesture.phase == VoiceGesture.Phase.Pending &&
    !gesture.expired(now) && now >= gesture.startedAt && now - gesture.startedAt <= 120_000

  fun reserve(id: String, now: Long, available: Boolean): Boolean {
    if (!current(id, now, available)) return false
    if (owner == id) return true
    owner = id; presented = false; showable = false; ready = false
    return true
  }

  /** onShow is reached only after the framework has captured this invocation's pixels. */
  fun allowPresentation(id: String, now: Long, available: Boolean): Boolean {
    if (owner != id || !current(id, now, available)) return false
    showable = true
    return true
  }

  fun present(id: String, now: Long, available: Boolean): Boolean {
    if (!showable || owner != id || !current(id, now, available)) return false
    presented = true
    return true
  }

  fun complete(id: String, now: Long, available: Boolean): Boolean {
    if (owner != id || !current(id, now, available)) return false
    ready = true
    return true
  }

  fun activate(id: String, now: Long, available: Boolean): Boolean {
    if (!ready || owner != id || !current(id, now, available) || !gesture.activate(id)) return false
    clear()
    return true
  }

  fun owns(id: String): Boolean = owner == id
  fun visible(): Boolean = owner != null && presented
  fun clear() { owner = null; presented = false; showable = false; ready = false }

  companion object { const val MESSAGE = "Getting ready…" }
}
