package org.agentos.agent.home.island

import org.agentos.agent.home.assistant.*
import org.json.JSONArray
import org.json.JSONObject

/** Frozen identity of the source and execution, independent of any model-authored status text. */
internal data class PollSendOwner(
  val generation: Long,
  val executionThreadId: String?,
  val intentId: String,
  val source: PollPreparationSource,
) {
  fun owns(state: AssistantState): Boolean = from(state) == this

  fun actionIdentity(action: String): String =
    "taku-island://poll/$action/$generation/${executionThreadId ?: "entry"}/${PollSendRestoration.hash(PollSendRestoration.binding(source))}"

  companion object {
    fun from(state: AssistantState): PollSendOwner? {
      val source = state.pollSource.takeIf { state.pollPreparation } ?: return null
      val intent = source.sendIntentId.takeIf { it.isNotBlank() } ?: return null
      if (source.target.isBlank()) return null
      return PollSendOwner(state.requestGeneration, state.threadId, intent, source)
    }
  }
}

/** A status result is usable only for its exact immutable source, intent and execution. */
internal data class PollSendObservation(val owner: PollSendOwner, val receipt: PollSendReceipt) {
  companion object {
    fun from(owner: PollSendOwner, native: JSONObject): PollSendObservation? {
      val source = owner.source
      if (!PollSendRestoration.matches(source, native) ||
        native.optString("sourceThreadId") != source.threadId || native.optString("question") != source.question ||
        native.optJSONArray("options")?.toString() != JSONArray(source.options.items.map { it.label }).toString()) return null
      val execution = native.optString("executionThreadId")
      if ((execution.isNotBlank() && execution != owner.executionThreadId) ||
        (native.optString("status") != "registered" && execution != owner.executionThreadId)) return null
      val envelope = JSONObject().put("ok", true).put("result", native)
      val receipt = PollSendReceipt.from(envelope, owner.intentId, source.target) ?: return null
      return PollSendObservation(owner, receipt)
    }
  }
}

/** Read failures revoke unsent action authority; only an already proven send remains a fact. */
internal fun retainPollSendObservation(owner: PollSendOwner, previous: PollSendObservation?,
  current: PollSendObservation?): PollSendObservation? = current?.takeIf { it.owner == owner }
    ?: previous?.takeIf { it.owner == owner && it.receipt.sent }

internal data class PollSendPresentation(val owner: PollSendOwner, val body: String, val working: Boolean,
  val actions: List<Pair<String, String>>)

/** Journal facts select the headline; task completion and long submission prompts never do. */
internal fun pollSendPresentation(state: AssistantState, observation: PollSendObservation?): PollSendPresentation? {
  val owner = PollSendOwner.from(state) ?: return null
  // A new recording, capture or character invocation keeps its existing presentation owner.
  if (state.entry == EntryMode.Voice) return null
  val receipt = observation?.takeIf { it.owner == owner }?.receipt
  val waiting = state.needsUser
  val sending = state.working && !waiting && receipt?.sent != true && receipt?.status != "unknown"
  val headline = when {
    waiting -> "Sending to “${owner.source.target}” needs your confirmation."
    receipt?.sent == true || receipt?.status == "unknown" -> receipt.headline(owner.source.target)!!
    sending -> "Sending to “${owner.source.target}”…"
    receipt?.notSent == true -> "Poll not sent"
    state.error != null -> "The poll could not be sent. Delivery is not confirmed."
    else -> "Poll status is unavailable. Try again shortly."
  }
  val reason = pollSendFailureReason(state, receipt).takeIf { !state.needsUser }
  val actions = buildList {
    if (waiting) return@buildList
    if (pollSendActionAvailable(state, receipt, false)) add("pollContinue" to "Continue sending")
    if (pollSendActionAvailable(state, receipt, true)) add("pollCheck" to "Check delivery")
  }
  return PollSendPresentation(owner, listOfNotNull(headline, reason).joinToString("\n"), sending, actions)
}
