package org.agentos.agent.home.assistant

import android.content.Context
import android.os.Bundle
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.io.File

internal class HomeComposer(
  context: Context,
  private val gateway: AgentGateway,
  private val worker: CoroutineDispatcher,
) {
  data class Attachment(val id: String, val name: String)
  data class Draft(val threadId: String, val revision: String, val text: String, val attachments: List<Attachment>, val pending: Request?)

  class MenuSubmission(val text: String, private val photo: File, val invocationId: String? = null) {
    private val handoffId = UUID.randomUUID().toString()
    var threadId: String? = null
      private set

    fun canRetryAfter(code: String) = code != "ATTACHMENT_UNAVAILABLE"

    suspend fun prepare(
      create: suspend () -> String,
      handoff: suspend (String, String, String) -> Draft,
      attach: suspend (Draft, File) -> Draft,
    ): Draft {
      val id = threadId ?: create().also { threadId = it }
      val draft = handoff(id, handoffId, text)
      if (draft.text != text || draft.attachments.isNotEmpty() && draft.attachments.singleOrNull()?.name != MENU_PHOTO_NAME)
        throw GatewayException("DRAFT_REVISION_CONFLICT", "The menu draft changed")
      // If attach committed but its reply was lost, the same handoff returns that revision.
      return if (draft.attachments.isEmpty()) attach(draft, photo) else draft
    }
  }

  data class Request(
    val threadId: String,
    val revision: String,
    val id: String,
    val text: String,
    val action: String,
    val turnId: String?,
    val attachmentIds: List<String>,
    /** The system invocation this composer belongs to; the gateway matches its screenshot by it. */
    val invocationId: String? = null,
  ) {
    fun key() = JSONObject().put("threadId", threadId).put("requestId", id)

    fun submission(): JSONObject = key().put("revision", revision).put("action", action)
      .put("attachmentIds", JSONArray(attachmentIds)).apply { turnId?.let { put("expectedTurnId", it) } }
      .apply { invocationId?.let { put("invocationId", it) } }

    companion object {
      fun new(draft: Draft, turnId: String?, invocationId: String? = null) = Request(draft.threadId, draft.revision,
        UUID.randomUUID().toString(), draft.text.trim(), if (turnId == null) "sendMessage" else "steer", turnId,
        draft.attachments.map(Attachment::id), invocationId)

      fun parse(value: JSONObject) = Request(value.getString("threadId"), value.getString("revision"),
        value.getString("requestId"), value.getString("text"), value.getString("action"),
        value.optString("expectedTurnId").ifEmpty { null }, value.getJSONArray("attachmentIds").let { ids ->
          List(ids.length(), ids::getString)
        }, value.optString("invocationId").ifEmpty { null })
    }
  }

  private val receipts = context.getSharedPreferences("home-composer-delivery", Context.MODE_PRIVATE)

  suspend fun read(threadId: String): Draft = call("composer/read", thread(threadId)).draft(threadId)

  suspend fun write(draft: Draft, text: String): Draft = call("composer/write",
    thread(draft.threadId).put("revision", draft.revision).put("text", text)).draft(draft.threadId)

  suspend fun move(draft: Draft, target: String): Draft = call("composer/move",
    thread(draft.threadId).put("revision", draft.revision).put("targetThreadId", target)).draft(target)

  suspend fun handoff(threadId: String, id: String, text: String): Draft {
    val params = thread(threadId).put("handoffId", id).put("text", text)
    val result = try {
      call("composer/handoff", params).draft(threadId)
    } catch (failure: Exception) {
      if (!deliveryCouldBeUnknown(failure)) throw failure
      call("composer/handoff", params).draft(threadId)
    }
    return result
  }

  suspend fun attachMenuPhoto(draft: Draft, file: File): Draft = withContext(worker) {
    val params = thread(draft.threadId).put("expectedRevision", draft.revision)
      .put("name", MENU_PHOTO_NAME).put("mime", "image/jpeg")
    val source = try { ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY) }
    catch (_: java.io.FileNotFoundException) { throw GatewayException("ATTACHMENT_UNAVAILABLE", "The scanned menu photo expired") }
    source.use { photo ->
      val extras = Bundle().apply { putParcelable("attachment", photo) }
      gateway.call("composer/attach", params, extras).draft(draft.threadId)
    }
  }

  suspend fun restoreTask(draft: Draft, taskId: String): Draft = call("composer/restoreTask",
    thread(draft.threadId).put("expectedRevision", draft.revision).put("taskId", taskId)).draft(draft.threadId)

  // A dropped reply can outlive the server's pending slot when submit already finalized it.
  fun savedRequest(threadId: String): Request? = receipts.getString(threadId, null)?.let { Request.parse(JSONObject(it)) }

  suspend fun remember(request: Request) = withContext(worker) {
    if (!receipts.edit().putString(request.threadId, request.submission().put("text", request.text).toString()).commit())
      throw GatewayException("DRAFT_SAVE_FAILED", "Could not save the pending request ID")
  }

  suspend fun forget(request: Request) = withContext(worker) {
    if (savedRequest(request.threadId)?.id == request.id && !receipts.edit().remove(request.threadId).commit())
      throw GatewayException("DRAFT_SAVE_FAILED", "Could not retire the pending request ID")
  }

  suspend fun archive(request: Request) = withContext(worker) {
    val value = receipts.getString(request.threadId, null)
    if (value != null && Request.parse(JSONObject(value)).id == request.id &&
      !receipts.edit().putString("released:${request.id}", value).remove(request.threadId).commit())
      throw GatewayException("DRAFT_SAVE_FAILED", "Could not archive the pending request ID")
  }

  data class Receipt(val state: String, val code: String, val taskId: String?) {
    val terminal: Boolean get() = state == "queued" || state == "accepted" || state == "notSent"
  }

  suspend fun submit(request: Request): Receipt = call("composer/submit", request.submission()).receipt(request)

  suspend fun receipt(request: Request): Receipt = call("composer/receipt", request.key()).receipt(request)

  suspend fun settle(request: Request): Receipt = call("composer/settle", request.key()).receipt(request)

  suspend fun release(request: Request): Draft = call("composer/release", request.key()).draft(request.threadId)

  private suspend fun call(method: String, params: JSONObject) = withContext(worker) { gateway.call(method, params) }

  private fun thread(threadId: String) = JSONObject().put("threadId", threadId)

  private fun JSONObject.draft(expected: String): Draft {
    if (opt("threadId") != expected || opt("revision") !is String || opt("text") !is String)
      throw GatewayException("INVALID_RESPONSE", "Invalid composer snapshot")
    val items = getJSONArray("attachments")
    val attachments = List(items.length()) { index ->
      val item = items.getJSONObject(index)
      Attachment(item.getString("id"), item.getString("name"))
    }
    return Draft(expected, getString("revision"), getString("text"), attachments, getJSONArray("pendingReceipts")
      .optJSONObject(0)?.let { Request.parse(it) }?.also {
        if (it.threadId != expected) throw GatewayException("INVALID_RESPONSE", "Invalid composer request thread")
      })
  }

  private fun JSONObject.receipt(request: Request): Receipt {
    if (opt("threadId") != request.threadId || opt("requestId") != request.id || opt("state") !is String)
      throw GatewayException("INVALID_RESPONSE", "Invalid composer receipt")
    return Receipt(getString("state"), optString("code", "NOT_SENT"), optString("taskId").ifEmpty { null })
  }

  companion object {
    internal const val MENU_PHOTO_NAME = "Scanned menu.jpg"

    fun deliveryCouldBeUnknown(failure: Exception): Boolean {
      if (failure is CancellationException) throw failure
      return failure !is GatewayException || failure.code in setOf("GATEWAY_UNAVAILABLE", "INVALID_RESPONSE",
        "RUNTIME_UNAVAILABLE", "RESULT_EXPIRED", "RESULT_TOO_LARGE", "RESPONSE_TOO_LARGE", "DRAFT_SAVE_FAILED")
    }

    fun createThread(gateway: AgentGateway): String = gateway.call("createThread").optJSONObject("thread")
      ?.optString("id")?.takeIf { it.isNotEmpty() } ?: throw GatewayException("THREAD_UNAVAILABLE", "Missing session ID")
  }
}
