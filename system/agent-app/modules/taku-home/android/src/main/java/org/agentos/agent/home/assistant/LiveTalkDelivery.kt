package org.agentos.agent.home.assistant

import android.content.Context
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

internal data class LiveInput(val itemId: String, val text: String)

internal data class LiveRequest(
  val sessionId: String,
  val threadId: String,
  val callId: String,
  val version: Long,
  val inputs: List<LiveInput>,
  val requestId: String = UUID.randomUUID().toString(),
  val createdAt: Long = System.currentTimeMillis(),
) {
  val text = inputs.joinToString("\n", transform = LiveInput::text)
  fun arguments() = JSONObject().put("threadId", threadId).put("requestId", requestId).put("text", text)
  fun taskArguments(taskId: String) = arguments().put("taskId", taskId)
}

internal data class LiveReceipt(val state: String, val taskId: String? = null, val code: String? = null) {
  val pending get() = state == "deliveryUnknown"
  val accepted get() = state == "queued" || state == "accepted"
}

internal class LiveTalkDelivery(context: Context, private val gateway: AgentGateway, private val worker: CoroutineDispatcher) {
  /**
   * Only requests whose delivery is still unknown are kept: they block a duplicate resend and are
   * reconciled when Live Talk reopens. Resolved requests are removed immediately.
   */
  private val receipts = context.getSharedPreferences("live-talk-delivery", Context.MODE_PRIVATE)
  private val pendingKey = "pending-requests"
  @Volatile private var pruned = false

  suspend fun createThread(): String = withContext(worker) { HomeComposer.createThread(gateway) }

  suspend fun reconcileArchived(threadId: String, sessionId: String): LiveRequest? = withContext(worker) {
    unresolved(threadId).filter { it.sessionId != sessionId }.sortedByDescending { it.createdAt }.firstOrNull {
      lookup(it).also { receipt -> if (!receipt.pending) persist(it, receipt) }.pending
    }
  }

  suspend fun submit(request: LiveRequest, allowed: () -> Boolean): LiveReceipt = withContext(worker) {
    if (!allowed()) return@withContext LiveReceipt("notSent", code = "INPUT_REVOKED")
    if (unresolved(request.threadId).any { pending ->
        pending.text == request.text && lookup(pending).also { if (!it.pending) persist(pending, it) }.pending
      }) return@withContext LiveReceipt("notSent", code = "UNKNOWN_DELIVERY")
    if (!allowed()) return@withContext LiveReceipt("notSent", code = "INPUT_REVOKED")
    persist(request, LiveReceipt("deliveryUnknown"))
    if (!allowed()) return@withContext LiveReceipt("notSent", code = "INPUT_REVOKED").also { persist(request, it) }
    val receipt = try {
      gateway.call("sendMessage", request.arguments()).receipt(request)
    } catch (failure: Exception) {
      if (HomeComposer.deliveryCouldBeUnknown(failure)) LiveReceipt("deliveryUnknown")
      else LiveReceipt("notSent", code = (failure as? GatewayException)?.code)
    }
    val reconciled = if (receipt.pending) lookup(request) else receipt
    persist(request, reconciled)
    reconciled
  }

  suspend fun reconcile(request: LiveRequest): LiveReceipt = withContext(worker) {
    lookup(request).also { if (!it.pending) persist(request, it) }
  }

  suspend fun task(method: String, request: LiveRequest, taskId: String, allowed: () -> Boolean = { true }): JSONObject = withContext(worker) {
    if (!allowed()) throw GatewayException("INPUT_REVOKED", "The voice request is no longer current")
    gateway.call(method, request.taskArguments(taskId)).also {
      if (it.opt("taskId") != taskId) throw GatewayException("LIVE_TASK_MISMATCH", "The task ownership changed")
    }
  }

  private fun lookup(request: LiveRequest): LiveReceipt = try {
    gateway.call("composer/receipt", request.arguments().put("action", "sendMessage")).receipt(request)
  } catch (_: Exception) {
    LiveReceipt("deliveryUnknown")
  }

  private fun JSONObject.receipt(request: LiveRequest): LiveReceipt {
    if (opt("threadId") != request.threadId || opt("requestId") != request.requestId)
      return LiveReceipt("deliveryUnknown")
    val task = (opt("taskId") as? String)?.takeIf { it.isNotBlank() }
    val code = opt("code") as? String
    return when (val state = optString("state")) {
      "notSent" -> LiveReceipt(state, code = code)
      "queued", "accepted" -> if (task != null) LiveReceipt(state, task) else LiveReceipt("deliveryUnknown")
      else -> LiveReceipt("deliveryUnknown")
    }
  }

  private fun unresolved(threadId: String): List<LiveRequest> = pending().mapNotNull { key ->
    val item = receipts.getString(key, null) ?: return@mapNotNull null
    runCatching {
      val value = JSONObject(item)
      if (value.optString("threadId") != threadId || value.optString("state") != "deliveryUnknown") return@mapNotNull null
      val inputs = value.getJSONArray("inputs").let { items -> List(items.length()) { index ->
        val input = items.getJSONObject(index)
        LiveInput(input.getString("itemId"), input.getString("text"))
      } }
      LiveRequest(value.getString("sessionId"), threadId, value.getString("callId"), value.getLong("version"),
        inputs, value.getString("requestId"), value.optLong("createdAt"))
        .takeIf { it.text == value.getString("text") && it.requestId == key }
    }.getOrNull()
  }

  private fun persist(request: LiveRequest, receipt: LiveReceipt) {
    val pending = pending()
    val edit = receipts.edit()
    if (receipt.pending) {
      val inputs = JSONArray(request.inputs.map { JSONObject().put("itemId", it.itemId).put("text", it.text) })
      val value = request.arguments().put("sessionId", request.sessionId).put("callId", request.callId)
        .put("version", request.version).put("inputs", inputs).put("state", receipt.state)
        .put("createdAt", request.createdAt)
        .put("taskId", receipt.taskId ?: JSONObject.NULL).put("code", receipt.code ?: JSONObject.NULL)
      // Bound the unknown set: the oldest unresolved request is the least likely to be retried.
      val kept = (pending + request.requestId).sortedByDescending { key ->
        if (key == request.requestId) Long.MAX_VALUE
        else receipts.getString(key, null)?.let { runCatching { JSONObject(it).optLong("createdAt") }.getOrNull() } ?: 0L
      }.take(MAX_PENDING).toSet()
      (pending - kept).forEach(edit::remove)
      edit.putString(request.requestId, value.toString()).putStringSet(pendingKey, kept)
    } else {
      edit.remove(request.requestId).putStringSet(pendingKey, pending - request.requestId)
    }
    if (!edit.commit()) throw GatewayException("RECEIPT_SAVE_FAILED", "Could not save the Live Talk receipt")
  }

  /** Pending request IDs. The first read also drops records earlier builds never cleaned up. */
  private fun pending(): Set<String> {
    val pending = receipts.getStringSet(pendingKey, null).orEmpty().toSet()
    if (!pruned) {
      val stale = receipts.all.keys.filter { it != pendingKey && it !in pending }
      if (stale.isEmpty() || receipts.edit().apply { stale.forEach { remove(it) } }.commit()) pruned = true
    }
    return pending
  }

  private companion object {
    const val MAX_PENDING = 32
  }
}
