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
    /** Nothing is running in the chat. */
    NO_CANDIDATE,
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
   * Null when the chat should hand [candidate] off. [lastResumed] is the class of the last of this
   * app's activities to resume: another app leaves it at the chat itself, Home at [HOME].
   */
  fun decide(candidate: Candidate?, changingConfigurations: Boolean, interactive: Boolean,
             pickerOpen: Boolean, lastResumed: String?): Skip? =
    if (candidate == null) Skip.NO_CANDIDATE else leave(changingConfigurations, interactive, pickerOpen, lastResumed)

  /**
   * Whether this stop is the user leaving the chat, whatever is running. A leave stays eligible
   * until the chat shows again: a send still on its way reports its conversation afterwards.
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

  /** Hand off when a leave is pending and a conversation not yet handed off becomes known. */
  fun handOffLater(candidate: Candidate?, leaving: Boolean, visible: Boolean, handedOffThread: String?): Boolean =
    candidate != null && leaving && !visible && candidate.threadId != handedOffThread
}
