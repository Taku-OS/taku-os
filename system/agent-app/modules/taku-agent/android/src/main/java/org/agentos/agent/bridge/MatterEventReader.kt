package org.agentos.agent.bridge

import android.content.ContentResolver
import android.net.Uri
import android.os.Handler
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets

/** One surface lease's matter reader; removing this lease never cancels a task, rule or another reader. */
internal class MatterEventReader(
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
  private val flushTask = Runnable { flush() }
  private val ids = JSONObject(params).getJSONArray("matterIds").let { values ->
    (0 until values.length()).map { values.getString(it) }.toSet()
  }

  fun start() { Thread({ read() }, "TakuMatterEvents").apply { isDaemon = true; start() } }

  @Synchronized fun stop() {
    if (stopped) return
    stopped = true
    synchronized(pending) { pending.clear(); pendingBytes = 0; scheduled = false }
    handler.removeCallbacks(flushTask)
    Thread({ closeLease() }, "TakuMatterClose").apply { isDaemon = true; start() }
  }

  private fun call(action: String, params: String): JSONObject {
    val result = resolver.call(Uri.parse("content://$authority"), action, params, null)
      ?: throw IOException("GATEWAY_UNAVAILABLE")
    val envelope = JSONObject(result.getString("result") ?: throw IOException("INVALID_GATEWAY_RESPONSE"))
    if (!envelope.optBoolean("ok")) throw IOException(envelope.optString("code", "STREAM_UNAVAILABLE"))
    return envelope.getJSONObject("result")
  }

  private fun closeLease() {
    try { input?.close() } catch (_: Exception) {}
    input = null
    val id = serverId
    serverId = ""
    if (id.isNotEmpty()) try {
      call("unsubscribeMatter", JSONObject().put("subscriptionId", id).toString())
    } catch (_: Exception) {}
  }

  private fun read() {
    var problem = "STREAM_CLOSED"
    try {
      val lease = call("subscribeMatter", params)
      serverId = lease.getString("subscriptionId")
      if (stopped) return
      val uri = Uri.parse(lease.getString("streamUri"))
      require(uri.scheme == "content" && uri.authority == authority && uri.path?.startsWith("/matter-events/") == true) {
        "INVALID_MATTER_STREAM_URI"
      }
      val stream = resolver.openInputStream(uri) ?: throw IOException("STREAM_EXPIRED")
      input = stream
      if (stopped) return
      stream.bufferedReader(StandardCharsets.UTF_8).use { reader ->
        while (!stopped) {
          val line = reader.readLine() ?: break
          if (line.isEmpty()) continue
          if (line.length > 256_000) throw IOException("STREAM_EVENT_TOO_LARGE")
          val event = JSONObject(line)
          if (event.optString("schema") != "taku.matter-events/1") throw IOException("INVALID_MATTER_EVENT")
          val method = event.getString("method")
          if (method !in setOf("matter/snapshot", "matter/resync", "matter/heartbeat")) throw IOException("INVALID_MATTER_EVENT")
          if (method == "matter/snapshot" && (!ids.contains(event.optString("matterId")) || event.getJSONObject("snapshot").optString("matterId") != event.getString("matterId")))
            throw IOException("STREAM_IDENTITY_MISMATCH")
          enqueue(line)
          if (method == "matter/resync") { problem = "STREAM_RESYNC"; break }
        }
      }
    } catch (failure: Exception) {
      problem = failure.message ?: "STREAM_UNAVAILABLE"
    } finally {
      closeLease()
      if (!stopped) handler.post {
        if (!stopped) { flush(); emit(mapOf("subscriptionId" to clientId, "events" to "", "closed" to true, "error" to problem)) }
      }
    }
  }

  private fun enqueue(line: String) {
    synchronized(pending) {
      if (stopped) return
      if (pending.size >= 64 || pendingBytes + line.length > 4 * 1024 * 1024) throw IOException("STREAM_BACKPRESSURE")
      pending.add(line); pendingBytes += line.length
      if (!scheduled) { scheduled = true; handler.post(flushTask) }
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
    if (!stopped) emit(mapOf("subscriptionId" to clientId, "events" to events, "closed" to false))
  }
}
