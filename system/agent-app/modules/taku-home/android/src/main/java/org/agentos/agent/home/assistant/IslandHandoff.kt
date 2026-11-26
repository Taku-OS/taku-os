package org.agentos.agent.home.assistant

/**
 * A conversation the full chat left running (BUG-0027): Home follows it with nothing on screen and
 * opens the island card once when the turn has something for the user. [turnId] is the turn the
 * chat was showing as active. When the chat did not know it yet (a queued task, or a message still
 * being sent), [knownTurnIds] are the turns the conversation had before that send: the first other
 * turn is the one to wait for, even if it has already finished. Without them, the first turn seen
 * running is. [errorAtStart] keeps an error the session already showed from counting as this
 * turn's failure.
 */
data class Handoff(
  val threadId: String,
  val turnId: String? = null,
  val taskId: String? = null,
  val errorAtStart: String? = null,
  val knownTurnIds: Set<String>? = null,
)

/** The pure rules of the chat-to-island handoff; [AssistantSession] applies them. */
object IslandHandoff {
  /** What Home is doing when the chat asks to hand [Handoff.threadId] over. */
  data class Home(
    val threadId: String?,
    /** The session asks the island to show its thread (expanded, full or capsule). */
    val islandRequested: Boolean,
    /** SystemUI is drawing Home's card expanded right now. */
    val cardExpanded: Boolean,
    val working: Boolean,
    val needsUser: Boolean,
    val entryOpen: Boolean,
    val microphoneBusy: Boolean,
    val liveTalk: Boolean,
  )

  enum class Admission { HANDED_OFF, ALREADY_SHOWN }

  /** Thread and turn ids come from the gateway; anything else is rejected before it can retarget Home. */
  private val threadPattern = Regex("[A-Za-z0-9._:-]{1,128}")

  fun validThread(id: String?): Boolean = id != null && threadPattern.matches(id)

  /** At most this many earlier turns are carried; older ones can never be a new turn anyway. */
  const val MAX_KNOWN_TURNS = 200

  /** The earlier turns the chat reports, or INVALID_ARGUMENT. Null means "not reported". */
  fun knownTurns(ids: List<Any?>?): Set<String>? {
    if (ids == null) return null
    if (ids.size > MAX_KNOWN_TURNS || ids.any { it !is String || !validThread(it) })
      throw AssistantException("INVALID_ARGUMENT", "knownTurnIds must be at most $MAX_KNOWN_TURNS turn ids")
    return ids.map { it as String }.toSet()
  }

  /**
   * Whether Home may follow [threadId]. Whatever Home is already doing wins: a running Home turn,
   * a microphone, typing or a card being read is never replaced. Throws `HOME_BUSY`.
   */
  fun admit(home: Home, threadId: String): Admission {
    if (!validThread(threadId)) throw AssistantException("INVALID_ARGUMENT", "threadId must be a conversation id")
    if (home.threadId == threadId && home.islandRequested) return Admission.ALREADY_SHOWN
    val other = home.threadId != null && home.threadId != threadId
    if (home.microphoneBusy || home.liveTalk || home.entryOpen ||
        (other && (home.working || home.needsUser || home.cardExpanded)))
      throw AssistantException("HOME_BUSY", "Home is showing another conversation")
    return Admission.HANDED_OFF
  }

  /**
   * Whether Home returning to its default state (a Home press, or Home resuming after the screen
   * went dark) also dismisses the island card. A handed-off result SystemUI has not drawn yet
   * ([revealPending]) is never dismissed that way: nobody has seen it, so it is not the user's
   * dismissal. Resuming after screen-off ([afterScreenOff]) only puts away the card that was up
   * when the screen went dark; one requested since ([expansionRequest] moved on from
   * [expansionAtScreenOff]) is news, including a reveal delivered a moment before Home resumed.
   */
  fun homeResetDismisses(islandRequested: Boolean, revealPending: Boolean, afterScreenOff: Boolean,
    expansionAtScreenOff: Long, expansionRequest: Long): Boolean =
    islandRequested && !revealPending && !(afterScreenOff && expansionRequest != expansionAtScreenOff)

  enum class Outcome { WAIT, REVEAL, DROP }

  /** One evaluation of a pending handoff; [handoff] carries the turn it now waits for. */
  data class Step(val outcome: Outcome, val handoff: Handoff?)

  /**
   * Decides from the session's current view of the thread. The card opens for a finished reply,
   * a question or approval, or a new failure; a cancelled task opens nothing. An older turn the
   * stream shows before the awaited one starts never counts as its reply.
   */
  fun step(handoff: Handoff, threadId: String?, turn: TurnView?, task: String?, taskId: String?, error: String?): Step {
    if (threadId != handoff.threadId) return Step(Outcome.DROP, null)
    val known = handoff.knownTurnIds
    val awaited = turn?.takeIf { if (known != null) it.turnId !in known else it.active }
    val armed = if (handoff.turnId == null && awaited != null) handoff.copy(turnId = awaited.turnId) else handoff
    if (task == "needs_user") return Step(Outcome.REVEAL, null)
    if (error != null && error != handoff.errorAtStart) return Step(Outcome.REVEAL, null)
    // Only this send's own task: a thread can still report an earlier task that was cancelled.
    if (task == "cancelled" && handoff.taskId != null && taskId == handoff.taskId && turn?.active != true)
      return Step(Outcome.DROP, null)
    if (armed.turnId != null && turn != null && turn.turnId == armed.turnId && !turn.active) return Step(Outcome.REVEAL, null)
    return Step(Outcome.WAIT, armed)
  }
}
