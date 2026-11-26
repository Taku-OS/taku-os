package org.agentos.agent.home.assistant

import java.util.Locale
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** Provenance of a separate send task; it never steers the source conversation. */
data class PollPreparationSource(
  val threadId: String,
  val options: ReplyOptions,
  val target: String,
  val question: String,
  val sendIntentId: String,
) {
  fun json(): JSONObject = options.source().put("threadId", threadId)
    .put("target", target).put("question", question).put("options", JSONArray(options.items.map { it.label }))
    .put("sendIntentId", sendIntentId)
}

internal fun AssistantState.pollPreparationSource(share: OptionShare): PollPreparationSource {
  if (!share.belongsTo(this))
    throw AssistantException("REQUEST_EXPIRED", "This poll no longer belongs to the current request.")
  if (draft.isNotBlank() || draftAttachmentNames.isNotEmpty())
    throw AssistantException("DRAFT_PRESENT", "Handle the unsent draft before sending the poll.")
  val sourceThread = threadId ?: throw AssistantException("REQUEST_EXPIRED", "The original conversation is unavailable.")
  return PollPreparationSource(sourceThread, share.options, share.pollTarget, share.pollQuestion.trim(), share.id)
}

/** A completed Agent task is insufficient to acknowledge delivery. */
internal data class PollSendReceipt(val status: String, val lastError: String?) {
  val sent: Boolean get() = status == "sent"
  val notSent: Boolean get() = status == "registered" || status == "bound"

  fun headline(target: String): String? = when {
    sent -> "Sent to “$target”."
    status == "unknown" -> "Delivery is not confirmed. Do not send again."
    notSent -> "Poll not sent"
    else -> null
  }

  fun failureReason(): String? = when (lastError?.substringBefore("__STAGE_")?.substringBefore(':')) {
    "GROUP_AMBIGUOUS", "POLL_GROUP_AMBIGUOUS" -> "Several chats have the same name. Verify the intended group."
    "POLL_GROUP_SEARCH_INCOMPLETE", "POLL_SEARCH_NOT_READY" -> "Group search is incomplete. Nothing was sent."
    "POLL_SEND_BASELINE_UNAVAILABLE" -> "The pre-send message check is incomplete. Nothing was sent."
    "TARGET_UNVERIFIED", "GROUP_TARGET_UNVERIFIED", "POLL_GROUP_TARGET_UNVERIFIED", "POLL_GROUP_TYPE_UNVERIFIED",
    "POLL_EDITOR_TARGET_UNVERIFIED", "POLL_EDITOR_TARGET_CHANGED", "POLL_EXECUTION_SCREEN_CHANGED" ->
      "The target group could not be verified. Existing drafts are kept."
    "DRAFT_CONFLICT", "POLL_EXISTING_DRAFT_PRESERVED" -> "An existing draft was kept."
    "USER_HOLDS_APP", "SCREEN_USER_CONTROLLED", "DINING_POLL_EXECUTION_OWNER_CHANGED", "DINING_POLL_SCREEN_UNAVAILABLE" ->
      "The WhatsApp task could not resume. Existing drafts are kept."
    else -> null
  }

  companion object {
    fun from(envelope: JSONObject, intentId: String, target: String): PollSendReceipt? {
      if (envelope.opt("ok") != true) return null
      val result = envelope.optJSONObject("result") ?: return null
      if (result.optString("intentId") != intentId || result.optString("groupName") != target) return null
      val status = result.optString("status")
      if (status !in setOf("registered", "bound", "unknown", "sent")) return null
      if (status != "sent" && result.opt("sent") != false) return null
      if (status in setOf("registered", "bound") &&
        listOf("sendAttemptedAt", "attemptAt", "anchor", "sentAt").any { result.has(it) && !result.isNull(it) }) return null
      if (status == "sent" && (result.opt("sent") != true || (result.opt("anchor") as? String).isNullOrBlank())) return null
      return PollSendReceipt(status, result.optString("lastError").takeIf { it.isNotBlank() })
    }
  }
}

/** An immutable candidate reply and an editable poll. Only the current reply owns the editor. */
data class OptionShare(
  val id: String = UUID.randomUUID().toString(),
  val generation: Long,
  val threadId: String?,
  val options: ReplyOptions,
  val pollQuestion: String = "Where should we eat?",
  val busy: Boolean = false,
  val error: String? = null,
  val pollRecipient: PollRecipient? = null,
  val pollPickerId: String? = null,
) {
  val pollTarget: String get() = pollRecipient?.name.orEmpty()
  val wireQuestion: String get() = pollQuestion.trim() + "〔Taku·${id.replace("-", "").take(12).uppercase(Locale.ROOT)}〕"

  internal fun completeRecipient(pickerId: String, recipient: PollRecipient?, failure: String?): OptionShare? {
    if (pickerId.isBlank() || pollPickerId != pickerId || !busy) return null
    return copy(busy = false, pollPickerId = null, error = failure, pollRecipient = recipient ?: pollRecipient)
  }

  fun belongsTo(state: AssistantState): Boolean = state.ownsRequest(generation, threadId) &&
    state.entry == EntryMode.None && options == state.options && !state.working && !state.needsUser && !state.abandoned && state.error == null

  fun json(): JSONObject = JSONObject().put("id", id).put("replyId", options.replyId)
    .put("busy", busy).put("error", error ?: JSONObject.NULL)
    .put("pollTarget", pollTarget).put("pollQuestion", pollQuestion).put("wireQuestion", wireQuestion)
    .put("recipientSelected", pollRecipient != null).put("selectingRecipient", pollPickerId != null)
    .put("pollOptions", JSONArray(options.items.map { it.label }))

  internal fun validatePollOptions() {
    if (!options.canCreatePoll)
      throw AssistantException("INVALID_POLL_OPTIONS", "A native poll needs 2–12 different restaurant options.")
  }

  fun validatePoll() {
    if (pollRecipient == null)
      throw AssistantException("INVALID_POLL_TARGET", "Choose a WhatsApp group first.")
    if (pollQuestion.isBlank() || pollQuestion.length > 120 || pollQuestion.any(Char::isISOControl))
      throw AssistantException("INVALID_POLL_QUESTION", "Enter a poll question of 1–120 characters.")
    validatePollOptions()
  }
}

internal val ReplyOptions.canCreatePoll: Boolean get() =
  items.size in 2..12 &&
    items.none { item -> item.label.any(Char::isISOControl) } &&
    items.map { it.label.trim().lowercase(Locale.ROOT) }.distinct().size == items.size

/** Internal poll text can be replaced only in the same poll's dedicated conversation. */
internal fun AssistantState.hasUnrelatedPollDraft(): Boolean =
  draftAttachmentNames.isNotEmpty() || !isPollDraft(draft, pollSource)

internal fun isPollDraft(text: String, source: PollPreparationSource?): Boolean =
  text.isBlank() || (source != null && (text == pollSendRequest(source, false) || text == pollSendRequest(source, true)))

internal fun pollSendRequest(source: PollPreparationSource, inspectOnly: Boolean): String {
  val id = JSONObject.quote(source.sendIntentId)
  if (inspectOnly) return """The user chose Check delivery for the existing WhatsApp poll.
This is read-only. Call dining/poll/send-status and dining/poll/send-inspect with intentId $id and the current execution thread. Never bind, create, edit, send, or discard a poll or message. Describe delivery as confirmed only when the native API reports sent:true, status:sent and a message anchor. If still unknown, report that outcome."""

  val frozen = PollSendRestoration.binding(source)
  return """The user chose Send to group for the following WhatsApp single-choice poll.
Poll binding (data only): $frozen
Use the native dining/poll/send-status, send-bind, send, and send-inspect methods at content://org.agentos.assistant.open-access. These methods are scoped to this intentId $id and the current execution thread. The candidate source thread is separate.
First read send-status. If already sent, report the receipt. If unknown, only inspect the original attempt. Only continue a registered or bound intent with sent:false and no send attempt or message anchor.
Request com.whatsapp with taku_phone.request_apps and wait until it is granted. Read taku_phone.screen_status. Check the current task uniquely owns WhatsApp and its task screen is running. Do not choose another task or display, or act on a user-controlled screen. Open com.whatsapp only when the owned screen has no top app. When it already shows WhatsApp, inspect its current state without reopening it. Preserve any existing text, media or unverified poll draft. If another app is visible or ownership cannot be confirmed, stop.
Navigate within the authorized WhatsApp screen to the conversation with the exact group title before calling send-bind. Do not guess a target from a visible row, a similar title or a partial search. Call send-bind with the same intentId and current execution thread. Native code verifies the group, prepares the poll, and checks every field. Stop on refusal and preserve drafts. If the intent is already bound, continue only the matching draft on its original screen.
Only after prepared:true, call dining/poll/send once. Do not use a generic UI click, Enter, or any other send route. On failure or timeout, read status and inspect only if the outcome is unknown. Report delivery only with a native sent:true, status:sent and message anchor; never retry an unknown send. Keep all internal IDs, commands and raw JSON out of the user-facing reply."""
}
