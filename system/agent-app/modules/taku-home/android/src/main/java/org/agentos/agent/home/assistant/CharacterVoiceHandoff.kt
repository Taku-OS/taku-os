package org.agentos.agent.home.assistant

/** Only the first recording of a fresh, authorized system reference may delay Keep Talking. */
internal class CharacterVoiceHandoff {
  enum class Decision { Ignore, Drop, Character, Live }
  private enum class Stage { AwaitingFinal, Character }
  private data class Lease(val gesture: String, val expires: Long, var operation: Long? = null, var stage: Stage = Stage.AwaitingFinal)
  private var lease: Lease? = null

  fun begin(gesture: String, expires: Long, now: Long): Boolean {
    clear()
    if (!gesture.matches(Regex("[A-Za-z0-9_-]{8,128}")) || now < 0 || expires < now) return false
    lease = Lease(gesture, expires)
    return true
  }

  fun bind(gesture: String, operation: Long, now: Long): Boolean {
    val value = lease ?: return false
    if (value.gesture != gesture || value.stage != Stage.AwaitingFinal || operation < 0 || now > value.expires ||
        value.operation?.let { it != operation } == true) return false
    value.operation = operation
    return true
  }

  fun awaiting(): Boolean = lease?.stage == Stage.AwaitingFinal
  fun eligible(gesture: String, now: Long): Boolean = lease?.let { it.gesture == gesture && now >= 0 && now <= it.expires } == true
  fun claimed(gesture: String, now: Long): Boolean = lease?.stage == Stage.Character && eligible(gesture, now)
  fun cancelOnSurfaceStop(gesture: String, changingConfigurations: Boolean, now: Long): Boolean =
    !changingConfigurations && claimed(gesture, now)
  fun expired(now: Long): Boolean = lease?.let { now > it.expires } == true

  fun classify(gesture: String, operation: Long, text: String, now: Long): Decision {
    val value = lease ?: return Decision.Ignore
    if (value.gesture != gesture || value.operation != operation || value.stage != Stage.AwaitingFinal) return Decision.Ignore
    if (!eligible(gesture, now) || text.isBlank()) { clear(); return Decision.Drop }
    return if (CharacterTryOnPolicy.command(text)) {
      value.stage = Stage.Character
      Decision.Character
    } else { clear(); Decision.Live }
  }

  fun clear() { lease = null }
}
