package org.agentos.agent.bridge

/**
 * Whether leaving the full chat hands its running turn to Home's island (BUG-0027). Pure, so the
 * rules are tested without Android; [TakuAgentModule] gathers the inputs when the chat stops.
 */
internal object HandoffDecision {
  /**
   * The conversation the chat shows while its turn runs; JS keeps it current. [knownTurnIds] are
   * the conversation's turns before the send when the running turn is not known yet (raw JSON
   * array, passed to Home as is); null when not reported.
   */
  data class Candidate(val threadId: String, val turnId: String?, val taskId: String?, val knownTurnIds: String? = null)

  enum class Skip {
    /** Rotation or a theme change recreates the chat. */
    CONFIGURATION_CHANGE,
    /** The screen turned off; the user comes back to the same chat. */
    SCREEN_OFF,
    /** The chat opened the system attachment picker itself. */
    PICKER_OPEN,
    /** The chat opened another of this app's screens (fal setup, for example). */
    OWN_ACTIVITY,
  }

  const val CONVERSATION = "org.agentos.agent.MainActivity"
  const val HOME = "org.agentos.agent.home.HomeActivity"

  /**
   * How long a leave waits for a send still on its way to name its conversation: creating a new
   * chat's thread can take up to 40 s. After that, nothing started later pops a card.
   */
  const val PENDING_LEAVE_MS = 60_000L

  /** Attempts for a handoff Home could not take yet (starting, unavailable); a busy Home is final. */
  const val MAX_ATTEMPTS = 3

  /**
   * Null when this stop is the user leaving the chat. [lastResumed] is the class of the last of
   * this app's activities to resume: another app leaves it at the chat itself, Home at [HOME].
   */
  fun leave(changingConfigurations: Boolean, interactive: Boolean, pickerOpen: Boolean, lastResumed: String?): Skip? = when {
    changingConfigurations -> Skip.CONFIGURATION_CHANGE
    !interactive -> Skip.SCREEN_OFF
    pickerOpen -> Skip.PICKER_OPEN
    lastResumed != null && lastResumed != CONVERSATION && lastResumed != HOME -> Skip.OWN_ACTIVITY
    else -> null
  }

  /** JS passes empty strings for "none"; a blank thread clears the candidate. */
  fun candidate(threadId: String, turnId: String, taskId: String, knownTurnIds: String = ""): Candidate? =
    threadId.trim().takeIf { it.isNotEmpty() }?.let {
      Candidate(it, turnId.trim().ifEmpty { null }, taskId.trim().ifEmpty { null }, knownTurnIds.trim().ifEmpty { null })
    }

  /**
   * Until when a leave keeps waiting for its conversation, or 0 for not at all. Only the chat's
   * own send still on its way (no conversation to name yet) waits; a leave with nothing running
   * never hands off a turn that starts later from somewhere else.
   */
  fun pendingUntil(skip: Skip?, candidate: Candidate?, sending: Boolean, now: Long): Long =
    if (skip == null && candidate == null && sending) now + PENDING_LEAVE_MS else 0L

  /** Hand off now: a pending leave, still hidden, learns a conversation not yet handed off. */
  fun handOffLater(candidate: Candidate?, pendingUntil: Long, now: Long, visible: Boolean, handedOffThread: String?): Boolean =
    candidate != null && now <= pendingUntil && !visible && candidate.threadId != handedOffThread

  /** Whether a handoff Home refused with [code] (null: no answer) is worth another attempt. */
  fun retry(code: String?, attempt: Int): Boolean =
    attempt < MAX_ATTEMPTS && code != "HOME_BUSY" && code != "INVALID_ARGUMENT"
}
