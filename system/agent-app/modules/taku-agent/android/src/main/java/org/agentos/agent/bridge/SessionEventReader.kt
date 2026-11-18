package org.agentos.agent.bridge

import android.content.ContentResolver
import android.net.Uri
import android.os.Handler
import org.json.JSONObject
import java.io.InputStream
import java.io.IOException
import java.nio.charset.StandardCharsets

/** One read-only lease. JS acknowledges by reconnecting from its last applied cursor. */
internal class SessionEventReader(
  private val resolver: ContentResolver,
  private val authority: String,
  private val handler: Handler,
  private val clientId: String,
  private val params: String,
  private val emit: (Map<String, Any>) -> Unit,
) {
  @Volatile private var stopped = false
  @Volatile private var input: InputStream? = null
  @Volatile private var serverId = ""
  private val pending = ArrayList<String>()
  private var pendingBytes = 0
  private var scheduled = false
  private var emittedFirstDelta = false
  private val flushTask = Runnable { flush() }
  private val threadId = JSONObject(params).getString("threadId")

  fun start() {
    Thread({ read() }, "TakuSessionEvents").apply { isDaemon = true; start() }
  }

  @Synchronized fun stop() {
    if (stopped) return
    stopped = true
    synchronized(pending) { pending.clear(); pendingBytes = 0; scheduled = false }
    handler.removeCallbacks(flushTask)
    // Closing a blocked pipe / Binder lease belongs off the JS and UI threads.
    Thread({ closeLease() }, "TakuSessionClose").apply { isDaemon = true; start() }
  }

  private fun call(action: String, value: String): JSONObject {
    val bundle = resolver.call(Uri.parse("content://$authority"), action, value, null)
      ?: throw IOException("Gateway unavailable")
    val envelope = JSONObject(bundle.getString("result") ?: throw IOException("Invalid gateway response"))
    if (!envelope.optBoolean("ok")) throw IOException(envelope.optString("code", "STREAM_UNAVAILABLE"))
    return envelope.getJSONObject("result")
  }

  private fun closeLease() {
    try { input?.close() } catch (_: Exception) {}
    input = null
    val id = serverId
    serverId = ""
    if (id.isNotEmpty()) try {
      call("unsubscribeSession", JSONObject().put("subscriptionId", id).toString())
    } catch (_: Exception) {}
  }

  private fun read() {
    var problem = "STREAM_CLOSED"
    try {
      val lease = call("subscribeSession", params)
      serverId = lease.getString("subscriptionId")
      if (stopped) return
      val uri = Uri.parse(lease.getString("streamUri"))
      require(uri.scheme == "content" && uri.authority == authority && uri.path?.startsWith("/events/") == true) {
        "Invalid session stream URI"
      }
      val stream = resolver.openInputStream(uri) ?: throw IOException("Session lease expired")
      input = stream
      if (stopped) return
      // BufferedReader decodes UTF-8 across arbitrary pipe boundaries, including CJK/emoji.
      stream.bufferedReader(StandardCharsets.UTF_8).use { reader ->
        while (!stopped) {
          val line = reader.readLine() ?: break
          if (line.isEmpty()) continue
          if (line.length > 8 * 1024 * 1024) throw IOException("STREAM_EVENT_TOO_LARGE")
          val event = JSONObject(line)
          if (event.optString("schema") != "taku.session-events/1" || event.optString("threadId") != threadId)
            throw IOException("STREAM_IDENTITY_MISMATCH")
          val method = event.optString("method")
          if (method == "session/heartbeat") continue
          val firstDelta = method == "item/agentMessage/delta" && !emittedFirstDelta
          if (firstDelta) emittedFirstDelta = true
          val urgent = firstDelta || method != "item/agentMessage/delta"
          enqueue(line, urgent)
          if (method == "session/resync") { problem = "STREAM_RESYNC"; break }
        }
      }
    } catch (failure: Exception) {
      problem = failure.message ?: "STREAM_UNAVAILABLE"
    } finally {
      closeLease()
      if (!stopped) handler.post {
        if (!stopped) {
          flush()
          emit(mapOf("subscriptionId" to clientId, "threadId" to threadId, "events" to "", "closed" to true, "error" to problem))
        }
      }
    }
  }

  private fun enqueue(line: String, urgent: Boolean) {
    synchronized(pending) {
      if (stopped) return
      // Never drop text silently. EOF causes replay from the JS cursor, not the reader cursor.
      if (pending.size >= 512 || pendingBytes + line.length > 12 * 1024 * 1024)
        throw IOException("STREAM_BACKPRESSURE")
      pending.add(line)
      pendingBytes += line.length
      if (urgent) {
        handler.removeCallbacks(flushTask)
        scheduled = true
        handler.post(flushTask)
      } else if (!scheduled) {
        scheduled = true
        handler.postDelayed(flushTask, 40)
      }
    }
  }

  private fun flush() {
    val events = synchronized(pending) {
      scheduled = false
      if (stopped || pending.isEmpty()) return
      val lines = pending.joinToString("\n")
      pending.clear(); pendingBytes = 0
      lines
    }
    if (!stopped) emit(mapOf("subscriptionId" to clientId, "threadId" to threadId, "events" to events, "closed" to false))
  }
}
