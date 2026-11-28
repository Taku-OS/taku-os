package org.agentos.agent.home.assistant

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException

/** A gateway failure with its stable code (`THREAD_BUSY`, `STREAM_UNAVAILABLE`, …). */
class GatewayException(val code: String, message: String) : IOException(message)

/**
 * The system assistant's Agent UI gateway (`system/assistant/agent-gateway.md`), used by the
 * home's AI entry. Same interface and permission as the React Native conversation: this code
 * runs inside the `org.agentos.agent` package. Call off the main thread.
 */
class AgentGateway(private val resolver: ContentResolver) {
  private val uri = Uri.parse("content://$AUTHORITY")

  /** Returns `result` of a `{ok:true,result}` envelope; throws [GatewayException] otherwise. */
  fun call(action: String, params: JSONObject = JSONObject(), extras: android.os.Bundle? = null): JSONObject {
    val bundle = try {
      resolver.call(uri, action, params.toString(), extras)
    } catch (e: RuntimeException) {
      throw GatewayException("GATEWAY_UNAVAILABLE", e.message ?: "Taku Agent gateway is unavailable")
    } ?: throw GatewayException("GATEWAY_UNAVAILABLE", "Taku Agent gateway is unavailable")
    var envelope = JSONObject(bundle.getString("result") ?: throw GatewayException("INVALID_RESPONSE", "Invalid gateway response"))
    val resultUri = envelope.optString("resultUri")
    if (resultUri.isNotEmpty()) envelope = JSONObject(readResult(Uri.parse(resultUri)))
    if (!envelope.optBoolean("ok")) throw GatewayException(envelope.optString("code", "GATEWAY_ERROR"), envelope.optString("message", "Gateway error"))
    return envelope.optJSONObject("result") ?: JSONObject()
  }

  /** Opens a session stream URI returned by `subscribeSession`. */
  fun openStream(streamUri: String) = Uri.parse(streamUri).let { u ->
    if (u.scheme != "content" || u.authority != AUTHORITY || u.path?.startsWith("/events/") != true) throw GatewayException("INVALID_STREAM", "Invalid session stream URI")
    resolver.openInputStream(u) ?: throw GatewayException("STREAM_EXPIRED", "Session lease expired")
  }

  /** Reads one frame on a worker thread. */
  fun readWorkspacePreview(previewUri: String): Bitmap? {
    val u = Uri.parse(previewUri)
    if (u.scheme != "content" || u.authority != AUTHORITY || u.path?.startsWith("/preview/home/") != true) {
      throw GatewayException("INVALID_PREVIEW_URI", "Invalid workspace preview URI")
    }
    return resolver.openInputStream(u)?.use { BitmapFactory.decodeStream(it) }
  }

  private fun readResult(u: Uri): String {
    if (u.scheme != "content" || u.authority != AUTHORITY || u.path?.startsWith("/result/") != true) throw GatewayException("INVALID_RESPONSE", "Invalid gateway result URI")
    val input = resolver.openInputStream(u) ?: throw GatewayException("RESULT_EXPIRED", "Gateway result expired")
    input.use {
      val out = ByteArrayOutputStream()
      val buffer = ByteArray(8192)
      while (true) {
        val n = it.read(buffer)
        if (n < 0) break
        if (out.size() + n > MAX_RESULT) throw GatewayException("RESULT_TOO_LARGE", "Gateway result exceeds client limit")
        out.write(buffer, 0, n)
      }
      return out.toString("UTF-8")
    }
  }

  companion object {
    const val AUTHORITY = "org.agentos.assistant.agent-gateway"
    private const val MAX_RESULT = 8 * 1024 * 1024
  }
}
