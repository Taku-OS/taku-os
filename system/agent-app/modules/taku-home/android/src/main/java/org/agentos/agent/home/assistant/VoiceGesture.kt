package org.agentos.agent.home.assistant

class AssistantException(val code: String, message: String) : Exception(message)

/** Main-thread protocol shared by the desktop, Quickstep and Agent commands. No audio side effects. */
class VoiceGesture(private val rememberStart: (Long) -> Unit = {}, lastStart: Long = -1) {
  enum class Phase { Idle, Pending, Holding, Listening, Continuous, Ended, Cancelled }
  enum class Zone { None, Cancel, KeepTalking }
  var id = ""; private set
  var source = ""; private set
  var phase = Phase.Idle; private set
  var zone = Zone.None; private set
  var sequence = -1L; private set
  var updatedAt = 0L; private set
  var startedAt = lastStart; private set
  val held get() = phase == Phase.Pending || phase == Phase.Holding

  fun prepare(token: String, origin: String, start: Long, now: Long): Boolean {
    if (!token.matches(Regex("[A-Za-z0-9_-]{8,128}"))) throw AssistantException("INVALID_ARGUMENT", "invalid gesture id")
    if (origin !in listOf("desktop", "system", "agent")) throw AssistantException("INVALID_ARGUMENT", "invalid gesture source")
    if (token == id || start <= startedAt) return false
    if (start > now || now - start > 5_000) throw AssistantException("STALE_GESTURE", "gesture start is stale")
    if (held || phase == Phase.Listening || phase == Phase.Continuous) throw AssistantException("VOICE_BUSY", "another gesture owns voice")
    rememberStart(start)
    id = token; source = origin; startedAt = start; updatedAt = now
    sequence = 0; phase = Phase.Pending; zone = Zone.None
    return true
  }

  fun activate(token: String): Boolean {
    if (token != id || phase != Phase.Pending) return false
    phase = Phase.Holding
    return true
  }

  /** Reject duplicate, late and out-of-order events before they can mutate audio or the UI. */
  fun event(token: String, seq: Long, time: Long, now: Long): Boolean {
    if (token != id || !held || seq <= sequence) return false
    if (time < startedAt || time > now || now - time > 5_000) throw AssistantException("STALE_GESTURE", "gesture event is stale")
    sequence = seq; updatedAt = now
    return true
  }

  fun move(next: Zone) { if (held) zone = next }
  /** The opening gesture ends; the listening page and recording keep their ownership. */
  fun latch(): Boolean {
    if (phase != Phase.Holding) return false
    phase = Phase.Listening; zone = Zone.None
    return true
  }
  fun keepTalking(): Boolean {
    if (phase != Phase.Holding && phase != Phase.Listening) return false
    phase = Phase.Continuous; zone = Zone.None
    return true
  }
  fun returnToListening() { if (phase == Phase.Continuous) phase = Phase.Listening }
  /** Returns the previous phase, so releasing before UI activation never starts or sends audio. */
  fun end(cancel: Boolean): Phase {
    val previous = phase
    if (held || phase == Phase.Listening || phase == Phase.Continuous) phase = if (cancel) Phase.Cancelled else Phase.Ended
    zone = Zone.None
    return previous
  }
  fun expired(now: Long) = held && now - updatedAt > 3_000
}
