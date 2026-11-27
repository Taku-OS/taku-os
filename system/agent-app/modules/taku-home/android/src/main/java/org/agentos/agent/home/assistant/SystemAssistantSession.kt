package org.agentos.agent.home.assistant

import android.app.KeyguardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.service.voice.VoiceInteractionSession
import android.util.Log
import java.io.File
import java.util.concurrent.Executors
import org.json.JSONObject

/** System invocation has no legacy drawer: hardware entries always present the new UI. */
class SystemAssistantSession(private val ctx: Context) : VoiceInteractionSession(ctx) {
  private val main = Handler(Looper.getMainLooper())
  private var request: Bundle? = null
  private var showFlags = 0
  private var capture: Bitmap? = null
  private var screenshotReceived = false
  private var assistReceived = false
  private var assistBlocked = false
  private var foreground: String? = null
  private var shown = false
  /** The assistant UI is already open for this invocation; the capture follows it to the gateway. */
  private var uiStarted = false
  @Volatile private var generation = 0
  @Volatile private var invocationToken: String? = null

  override fun onPrepareShow(args: Bundle?, flags: Int) {
    super.onPrepareShow(args, flags)
    val version = ++generation
    request = args?.let(::Bundle) ?: Bundle()
    showFlags = flags
    capture = null
    screenshotReceived = false
    assistReceived = false
    assistBlocked = false
    foreground = args?.getParcelableArrayList(KEY_FOREGROUND_ACTIVITIES, ComponentName::class.java)
      ?.firstOrNull()?.flattenToShortString()
    shown = false
    uiStarted = false
    invocationToken = null
    setUiEnabled(false)
    if (request?.containsKey("jevTestToken") != true) {
      val capturedAt = SystemClock.elapsedRealtime()
      val autoScreenshot = SystemAssistantEntry.routeFor(args?.getString(SystemAssistantEntry.TRIGGER)).autoScreenshot
      IO.execute {
        if (version != generation) return@execute
        try {
          val begin = JSONObject().put("operation", "begin").put("capturedAt", capturedAt)
          if (autoScreenshot) begin.put("autoScreenshot", true)
          val result = gateway("assistInvocation", begin)
          if (version == generation) invocationToken = result.getString("token")
        } catch (failure: Exception) { Log.w("TakuSystemAssistant", "Invocation capture authorization unavailable", failure) }
      }
    }
  }

  override fun onShow(args: Bundle?, flags: Int) {
    super.onShow(args, flags)
    shown = true
    if (ctx.getSystemService(KeyguardManager::class.java).isKeyguardLocked) { hide(); return }
    // The UI does not wait for the page context: the gateway only reads the capture when a message
    // is sent, so the handoff below may finish after the entry is on screen (BUG-0037). It waits
    // only for the screenshot itself, briefly, so the overlay is never what gets captured.
    if (!screenshotPending()) startUi()
    if (captureReady()) deliverCapture()
    else {
      val version = generation
      main.postDelayed({ if (shown && version == generation) startUi() }, SCREENSHOT_GRACE_MS)
      main.postDelayed({ if (shown && version == generation) deliverCapture() }, CAPTURE_TIMEOUT_MS)
    }
  }

  /** The system has yet to deliver the screenshot it was asked for. */
  private fun screenshotPending(): Boolean = showFlags and SHOW_WITH_SCREENSHOT != 0 && !screenshotReceived

  /**
   * Opens the assistant at once. The session stays shown (its own UI is disabled) until the capture
   * is handed off, because a hidden session stops receiving it. Test suggestions still open after
   * the capture: their destination carries the capture's id.
   */
  private fun startUi() {
    val args = request ?: return
    if (uiStarted || !shown || args.containsKey("jevTestToken")) return
    uiStarted = true
    try { startAssistantActivity(SystemAssistantEntry.launch(ctx, args)) }
    catch (failure: RuntimeException) {
      Log.w("TakuSystemAssistant", "Assistant UI unavailable", failure)
      android.widget.Toast.makeText(ctx, "Assistant could not open. Retry from Taku", android.widget.Toast.LENGTH_LONG).show()
      hide()
    }
  }

  override fun onHandleAssist(state: AssistState) {
    super.onHandleAssist(state)
    if (!state.isFocused || request == null) return
    assistReceived = true
    val structure = state.assistStructure
    structure?.activityComponent?.flattenToShortString()?.let { foreground = it }
    if (structure != null) {
      assistBlocked = (0 until structure.windowNodeCount).any { index ->
        structure.getWindowNodeAt(index).rootViewNode.isAssistBlocked
      }
    }
    if (shown && captureReady()) deliverCapture()
  }

  override fun onHandleScreenshot(screenshot: Bitmap?) {
    super.onHandleScreenshot(screenshot)
    if (request == null) return
    capture = screenshot
    screenshotReceived = true
    if (shown) startUi()
    if (shown && captureReady()) deliverCapture()
  }

  private fun captureReady(): Boolean = showFlags and SHOW_WITH_SCREENSHOT == 0 ||
    assistBlocked || screenshotReceived && (capture == null || assistReceived || showFlags and SHOW_WITH_ASSIST == 0)

  private fun deliverCapture() {
    val args = request ?: return
    val image = capture?.takeUnless { assistBlocked || showFlags and SHOW_WITH_ASSIST != 0 && !assistReceived }
    capture = null
    val version = generation
    val page = foreground ?: "Current app"
    val suggestion = args.containsKey("jevTestToken")
    val opened = uiStarted
    request = null
    shown = false
    IO.execute {
      var file: File? = null
      var target: Intent? = if (suggestion || opened) null else SystemAssistantEntry.launch(ctx, args)
      try {
        if (version != generation) return@execute
        val payload = if (suggestion) JSONObject().put("jevTestToken", args.getString("jevTestToken"))
          .put("manualScreen", args.getBoolean("manualScreen")).put("captureForeground", page)
        else JSONObject().put("operation", "receive").put("token", invocationToken ?: error("CAPTURE_UNAVAILABLE"))
          .put("foreground", page)
        val extras = Bundle()
        if (image != null) {
          file = File.createTempFile("assist-capture-", ".png", ctx.cacheDir)
          file.outputStream().use { if (!image.compress(Bitmap.CompressFormat.PNG, 100, it)) error("capture encoding failed") }
        }
        val descriptor = file?.let { ParcelFileDescriptor.open(it, ParcelFileDescriptor.MODE_READ_ONLY) }
        descriptor.use {
          if (version != generation) return@use
          if (it != null) extras.putParcelable("capture", it)
          val result = gateway(if (suggestion) "assistCapture" else "assistInvocation", payload, extras)
          if (suggestion) {
            val captureId = result.getString("captureId")
            target = Intent(Intent.ACTION_VIEW,
              Uri.Builder().scheme("taku-agent").authority("").path("/assistant-suggestion")
                .appendQueryParameter("captureId", captureId).build())
              .setClassName(ctx.packageName, "org.agentos.agent.MainActivity")
              .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
          }
        }
      } catch (failure: Exception) {
        Log.w("TakuSystemAssistant", "Native capture handoff failed", failure)
        main.post {
          if (version == generation) {
            val message = if (failure.message == "CAPTURE_EXPIRED") "Screen capture authorization expired. Invoke the assistant again."
              else "The system screenshot is unavailable. Invoke the assistant again to retry."
            android.widget.Toast.makeText(ctx, message, android.widget.Toast.LENGTH_SHORT).show()
          }
        }
      } finally {
        file?.delete()
        main.post {
          if (version == generation) {
            try { target?.let(::startAssistantActivity) }
            catch (failure: RuntimeException) {
              Log.w("TakuSystemAssistant", "Assistant UI unavailable", failure)
              android.widget.Toast.makeText(ctx, "Assistant could not open. Retry from Taku", android.widget.Toast.LENGTH_LONG).show()
            } finally { hide() }
          }
        }
      }
    }
  }

  private fun gateway(method: String, payload: JSONObject, extras: Bundle? = null): JSONObject {
    val response = ctx.contentResolver.call(Uri.parse("content://org.agentos.assistant.agent-gateway"), method, payload.toString(), extras)
    val result = JSONObject(response?.getString("result") ?: "{}")
    if (!result.optBoolean("ok")) error(result.optString("code", "UNAVAILABLE"))
    return result.getJSONObject("result")
  }

  override fun onHide() {
    generation++; shown = false; uiStarted = false; request = null; capture = null; screenshotReceived = false; invocationToken = null
    super.onHide()
  }
  override fun onDestroy() { main.removeCallbacksAndMessages(null); generation++; super.onDestroy() }

  companion object {
    private val IO = Executors.newSingleThreadExecutor()
    /** Longest the UI waits for the system screenshot, so the screenshot shows the user's screen. */
    private const val SCREENSHOT_GRACE_MS = 300L
    /** Longest the capture handoff waits for the screenshot and page context. */
    private const val CAPTURE_TIMEOUT_MS = 5_000L
  }
}
