package org.agentos.agent.home.assistant

import org.json.JSONObject

/** Frozen ownership and native-journal checks for an explicitly requested continuation. */
internal data class PollSendContinuation(val generation: Long, val executionThreadId: String, val source: PollPreparationSource) {
  val intentId: String get() = source.sendIntentId

  fun owns(state: AssistantState): Boolean = state.ownsRequest(generation, executionThreadId) &&
    state.pollPreparation && state.pollSource == source

  fun request(envelope: JSONObject, session: JSONObject, inspectOnly: Boolean): String {
    val native = envelope.optJSONObject("result")
    val receipt = PollSendReceipt.from(envelope, intentId, source.target)
      ?: throw AssistantException("POLL_STATUS_UNAVAILABLE", "Poll status is unavailable. Try again shortly.")
    if (receipt.sent) throw AssistantException("POLL_ALREADY_SENT", "This poll already has a delivery receipt and will not be sent again.")
    if (inspectOnly && receipt.status != "unknown") throw AssistantException("POLL_CHECK_NOT_NEEDED", "Poll status changed. Check the latest result.")
    if (!inspectOnly && !receipt.notSent) throw AssistantException("POLL_NOT_REPLAYABLE", "Delivery is not confirmed. Only the original result can be checked; the poll cannot be resent.")
    if (native == null || native.optString("sourceThreadId") != source.threadId ||
      native.optString("question") != source.question || native.optJSONArray("options")?.toString() !=
      org.json.JSONArray(source.options.items.map { it.label }).toString() || !PollSendRestoration.matches(source, native))
      throw AssistantException("POLL_SOURCE_CHANGED", "The current poll does not match the original request. Sending was not resumed.")
    val boundThread = native.optString("executionThreadId")
    if ((native.optString("status") != "registered" && boundThread != executionThreadId) ||
      (boundThread.isNotBlank() && boundThread != executionThreadId))
      throw AssistantException("POLL_EXECUTION_CHANGED", "The original poll task changed. Sending was not resumed.")
    if (session.optJSONObject("thread")?.optString("id") != executionThreadId)
      throw AssistantException("POLL_EXECUTION_UNAVAILABLE", "The original task could not be verified. Try again shortly.")
    val task = session.optJSONObject("task")?.optString("state")
    if (task in setOf("queued", "running", "needs_user") ||
      session.optJSONObject("native")?.optString("activeTurnId").orEmpty().isNotBlank())
      throw AssistantException("POLL_EXECUTION_BUSY", "The original task is still running. Wait for it to finish.")
    return pollSendRequest(source, inspectOnly)
  }
}

internal fun AssistantState.pollSendContinuation(): PollSendContinuation {
  val source = pollSource.takeIf { pollPreparation }
    ?: throw AssistantException("POLL_REQUEST_EXPIRED", "This poll no longer belongs to the current request.")
  val thread = threadId?.takeIf { it.isNotBlank() }
    ?: throw AssistantException("POLL_EXECUTION_UNAVAILABLE", "The original poll task is unavailable.")
  if (source.sendIntentId.isBlank()) throw AssistantException("POLL_INTENT_UNAVAILABLE", "The original poll delivery identity is unavailable.")
  if (working || needsUser || turn?.active == true || task in setOf("queued", "running", "needs_user"))
    throw AssistantException("POLL_EXECUTION_BUSY", "The original task is still running. Wait for it to finish.")
  if (hasUnrelatedPollDraft()) throw AssistantException("DRAFT_PRESENT", "Handle the unsent text in the composer first.")
  return PollSendContinuation(requestGeneration, thread, source)
}

internal fun AssistantState.finishPollRetry(operation: String): AssistantState =
  if (pollRetryOperation == operation) copy(sending = false, pollRetryOperation = null) else this

internal fun pollSendFailureReason(state: AssistantState, receipt: PollSendReceipt?): String? =
  if (receipt?.notSent == true && !state.working)
    receipt.failureReason() ?: "The last step did not finish. Continuing will check the original request and draft."
  else null

internal fun pollSendActionAvailable(state: AssistantState, receipt: PollSendReceipt?, inspectOnly: Boolean): Boolean =
  receipt != null && (if (inspectOnly) receipt.status == "unknown" else receipt.notSent) &&
    runCatching { state.pollSendContinuation() }.isSuccess
