package org.agentos.agent.home.assistant

import org.taku.cosplay.api.CosplayContract

/** Main-thread ownership of one system text entry; Intent extras can only adopt an existing lease. */
internal class CharacterTextInvocation {
  private enum class Stage { Capturing, Ready, Active, Submitted }
  private data class Lease(val id: String, val invoked: Long, var stage: Stage)
  private var lease: Lease? = null

  fun reserve(id: String, invoked: Long, now: Long): Boolean {
    clear()
    if (!id.matches(Regex("[A-Za-z0-9_-]{8,128}")) || invoked < 0 || now < invoked ||
        now - invoked > CosplayContract.MAX_INVOCATION_AGE_MS) return false
    lease = Lease(id, invoked, Stage.Capturing)
    return true
  }

  fun ready(id: String, now: Long): Boolean = current(id, now)?.let {
    if (it.stage != Stage.Capturing) false else { it.stage = Stage.Ready; true }
  } ?: false

  fun activate(id: String, now: Long): Boolean = current(id, now)?.let {
    if (it.stage !in setOf(Stage.Ready, Stage.Active)) false else { it.stage = Stage.Active; true }
  } ?: false

  fun transfer(id: String, invoked: Long, now: Long): Boolean =
    reserve(id, invoked, now) && ready(id, now) && activate(id, now)

  fun submit(now: Long): Boolean {
    val value = lease ?: return false
    if (current(value.id, now)?.stage != Stage.Active) return false
    value.stage = Stage.Submitted
    return true
  }

  fun matches(id: String, now: Long): Boolean = current(id, now) != null
  fun active(now: Long): Boolean = lease?.let { current(it.id, now)?.stage == Stage.Active } == true
  fun activeId(now: Long): String? = lease?.takeIf { active(now) }?.id
  fun submitted(): Boolean = lease?.stage == Stage.Submitted
  /** Availability checking is still text input; only an actual confirmation/error replaces it. */
  fun closesTextInput(phase: CharacterTryOnPhase?): Boolean = submitted() && phase != null && phase != CharacterTryOnPhase.Checking
  fun pending(id: String): Boolean = lease?.let { it.id == id && it.stage == Stage.Capturing } == true
  fun clear() { lease = null }

  private fun current(id: String, now: Long): Lease? = lease?.takeIf {
    it.id == id && now >= it.invoked && now - it.invoked <= CosplayContract.MAX_INVOCATION_AGE_MS
  }
}
