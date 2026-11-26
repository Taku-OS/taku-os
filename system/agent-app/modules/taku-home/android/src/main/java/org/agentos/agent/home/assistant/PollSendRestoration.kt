package org.agentos.agent.home.assistant

import android.content.Context
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

/** Durable references only; native reply and immutable intent are revalidated before restoration. */
internal object PollSendResumeStore {
  fun record(thread: String, source: PollPreparationSource): JSONObject = JSONObject()
    .put("intentId", source.sendIntentId).put("executionThreadId", thread)
    .put("sourceRef", source.options.source().put("threadId", source.threadId))

  fun save(context: Context, thread: String, source: PollPreparationSource) {
    if (!context.getSharedPreferences("poll-send-resume", 0).edit().putString(thread, record(thread, source).toString()).commit())
      throw AssistantException("POLL_RESUME_SAVE_FAILED", "The original poll could not be saved. Try again shortly.")
  }

  fun read(context: Context, thread: String): JSONObject? = runCatching {
    context.getSharedPreferences("poll-send-resume", 0).getString(thread, null)?.let(::JSONObject)
  }.getOrNull()
}

internal object PollSendRestoration {
  fun binding(source: PollPreparationSource): JSONObject = JSONObject().put("intentId", source.sendIntentId)
    .put("sourceThreadId", source.threadId).put("sourceReply", source.options.source())
    .put("groupName", source.target).put("question", source.question).put("options", JSONArray(source.options.items.map { it.label }))

  fun matches(source: PollPreparationSource, native: JSONObject): Boolean = runCatching {
    native.getString("contentHash") == hash(binding(source))
  }.getOrDefault(false)

  fun owns(before: AssistantState, current: AssistantState, execution: JSONObject): Boolean =
    before.requestGeneration == current.requestGeneration && before.threadId == current.threadId &&
      before.draft == current.draft && (!current.sending || before.sending) &&
      (current.taskId == before.taskId || current.taskId == execution.optJSONObject("task")?.optString("id"))

  fun replyQuery(record: JSONObject): JSONObject {
    val ref = record.getJSONObject("sourceRef")
    return JSONObject().put("threadId", ref.getString("threadId")).put("turnId", ref.getString("turnId")).put("replyId", ref.getString("replyId"))
  }

  fun restore(record: JSONObject, envelope: JSONObject, canonicalReply: JSONObject, execution: JSONObject): PollPreparationSource {
    val id = record.getString("intentId"); val thread = record.getString("executionThreadId"); val ref = record.getJSONObject("sourceRef")
    val uuid = Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")
    if (!uuid.matches(id) || !uuid.matches(thread) || !uuid.matches(ref.getString("threadId"))) fail()
    val native = envelope.optJSONObject("result") ?: fail()
    if (envelope.opt("ok") != true || native.optString("intentId") != id || native.optString("sourceThreadId") != ref.optString("threadId")) fail()
    if (native.optString("executionThreadId").let { it.isNotBlank() && it != thread } ||
      (native.optString("status") != "registered" && native.optString("executionThreadId") != thread)) fail()
    if (canonicalReply.opt("ok") != true || canonicalReply.optString("threadId") != ref.optString("threadId") || canonicalReply.optString("turnId") != ref.optString("turnId")) fail()
    val options = canonicalReply.optJSONObject("reply")?.let { ReplyOptions.from(it, ref.getString("threadId"), ref.getString("turnId")) } ?: fail()
    if (options.replyId != ref.optString("replyId") || options.revision != ref.optLong("revision") || options.contractHash != ref.optString("contractHash") || ref.optString("kind") != "primary") fail()
    val target = native.getString("groupName"); val question = native.getString("question")
    if (JSONArray(options.items.map { it.label }).toString() != native.getJSONArray("options").toString()) fail()
    if (PollSendReceipt.from(envelope, id, target) == null) fail()
    val binding = JSONObject().put("intentId", id).put("sourceThreadId", ref.getString("threadId")).put("sourceReply", options.source())
      .put("groupName", target).put("question", question).put("options", native.getJSONArray("options"))
    val expected = native.getString("contentHash")
    if (hash(binding) != expected || execution.optJSONObject("thread")?.optString("id") != thread) fail()
    // This is the gateway's actual current task request, not an Agent final reply.
    val prompt = execution.optJSONObject("request")?.optString("prompt").orEmpty()
    val source = PollPreparationSource(ref.getString("threadId"), options, target, question, id)
    val initial = prompt == pollSendRequest(source, false)
    val inspection = native.optString("status") in setOf("unknown", "sent") && prompt == pollSendRequest(source, true)
    if (!initial && !inspection) fail()
    return source
  }

  fun hash(value: JSONObject): String = MessageDigest.getInstance("SHA-256").digest(canonical(value).toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }
  private fun canonical(value: Any?): String = when (value) {
    is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(",", "{", "}") { JSONObject.quote(it) + ":" + canonical(value.get(it)) }
    is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonical(value.get(it)) }
    is String -> JSONObject.quote(value)
    else -> value.toString()
  }
  private fun fail(): Nothing = throw AssistantException("POLL_RESTORE_UNVERIFIED", "The original poll and task could not be verified. Sending was not resumed.")
}
