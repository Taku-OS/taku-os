package org.agentos.agent.home.assistant

import android.app.KeyguardManager
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.service.voice.VoiceInteractionSession
import android.util.Log
import java.io.File
import java.util.concurrent.Executors
import org.json.JSONObject

/** System invocation has no legacy drawer: hardware entries always present the new UI. */
class SystemAssistantSession(private val ctx: Context) : VoiceInteractionSession(ctx) {
  private val main = Handler(Looper.getMainLooper())
  private var request: Bundle? = null
  private var capture: Bitmap? = null
  private var foreground = "Current app"
  private var shown = false
  @Volatile private var generation = 0

  override fun onPrepareShow(args: Bundle?, flags: Int) {
    super.onPrepareShow(args, flags)
    generation++
    request = args?.let(::Bundle)
    capture = null
    foreground = "Current app"
    shown = false
    setUiEnabled(false)
  }

  override fun onShow(args: Bundle?, flags: Int) {
    super.onShow(args, flags)
    shown = true
    if (ctx.getSystemService(KeyguardManager::class.java).isKeyguardLocked) { hide(); return }
    if (request?.containsKey("jevTestToken") == true) {
      if (capture != null) deliverCapture()
      else {
        val version = generation
        main.postDelayed({ if (shown && version == generation) deliverCapture() }, 5_000)
      }
      return
    }
    try { startAssistantActivity(SystemAssistantEntry.launch(ctx, args, flags)) }
    catch (failure: RuntimeException) { Log.w("TakuSystemAssistant", "New assistant UI unavailable", failure) }
    finally { hide() }
  }

  override fun onHandleAssist(state: AssistState) {
    super.onHandleAssist(state)
    foreground = state.assistStructure?.activityComponent?.flattenToShortString() ?: "Current app"
  }

  override fun onHandleScreenshot(screenshot: Bitmap?) {
    super.onHandleScreenshot(screenshot)
    capture = screenshot
    if (shown && request?.containsKey("jevTestToken") == true) deliverCapture()
  }

  private fun deliverCapture() {
    val args = request ?: return
    val image = capture
    val version = generation
    request = null
    shown = false
    val payload = JSONObject().put("jevTestToken", args.getString("jevTestToken"))
      .put("manualScreen", args.getBoolean("manualScreen")).put("captureForeground", foreground)
    IO.execute {
      var file: File? = null
      try {
        if (version != generation) return@execute
        val extras = Bundle()
        if (image != null) {
          file = File.createTempFile("assist-capture-", ".png", ctx.cacheDir)
          file.outputStream().use { if (!image.compress(Bitmap.CompressFormat.PNG, 100, it)) error("capture encoding failed") }
        }
        val descriptor = file?.let { ParcelFileDescriptor.open(it, ParcelFileDescriptor.MODE_READ_ONLY) }
        descriptor.use {
          if (version != generation) return@use
          if (it != null) extras.putParcelable("capture", it)
          val response = ctx.contentResolver.call(Uri.parse("content://org.agentos.assistant.agent-gateway"),
            "assistCapture", payload.toString(), extras)
          val result = JSONObject(response?.getString("result") ?: "{}")
          if (!result.optBoolean("ok")) error(result.optString("code", "UNAVAILABLE"))
        }
      } catch (failure: Exception) {
        Log.w("TakuSystemAssistant", "Native capture handoff failed", failure)
        main.post {
          if (version == generation) android.widget.Toast.makeText(ctx, "Screenshot analysis is temporarily unavailable. Retry", android.widget.Toast.LENGTH_SHORT).show()
        }
      }
      finally { file?.delete(); main.post { if (version == generation) hide() } }
    }
  }

  override fun onHide() { generation++; shown = false; request = null; capture = null; super.onHide() }
  override fun onDestroy() { main.removeCallbacksAndMessages(null); generation++; super.onDestroy() }

  companion object { private val IO = Executors.newSingleThreadExecutor() }
}
