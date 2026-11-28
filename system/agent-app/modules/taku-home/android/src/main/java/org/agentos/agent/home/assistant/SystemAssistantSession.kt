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
import java.util.UUID
import java.util.concurrent.Executors
import org.json.JSONObject

/**
 * The latest system invocation in this process. Home's composer names it on every submit, and the
 * gateway attaches an invocation screenshot only when its capture belongs to that invocation.
 */
internal object SystemInvocation {
  @Volatile var latest: String? = null
  @Volatile private var supported = true

  /** What a submit names: nothing for a gateway that does not know invocation IDs. */
  val forSubmit: String? get() = latest?.takeIf { supported }

  fun unsupported(id: String) { supported = false; if (latest == id) latest = null }
}

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
  /** A handoff for an already open assistant is running; a hide must not abort it. */
  private var handoffInFlight = false
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
    handoffInFlight = false
    invocationToken = null
    setUiEnabled(false)
    if (request?.containsKey("jevTestToken") != true) {
      val capturedAt = SystemClock.elapsedRealtime()
      val autoScreenshot = SystemAssistantEntry.routeFor(args?.getString(SystemAssistantEntry.TRIGGER)).autoScreenshot
      // Named before the UI can open: a message sent before this invocation's capture reaches the
      // gateway names it too, so the gateway sends it as plain text instead of reading the
      // previous invocation's screenshot.
      val invocationId = UUID.randomUUID().toString()
      SystemInvocation.latest = invocationId
      IO.execute {
        if (version != generation) return@execute
        try {
          val result = try { gateway("assistInvocation", begin(capturedAt, autoScreenshot, invocationId)) }
          catch (rejected: IllegalStateException) {
            // A gateway without the one-shot grant (#57) or invocation IDs answers INVALID_ARGUMENT
            // to the field; the screenshot is still worth delivering, the user can attach it themselves.
            if (rejected.message != "INVALID_ARGUMENT") throw rejected
            Log.w("TakuSystemAssistant", "Gateway refused the invocation fields; requesting a plain capture")
            SystemInvocation.unsupported(invocationId)
            gateway("assistInvocation", begin(capturedAt, false, null))
          }
          if (version == generation) invocationToken = result.getString("token")
        } catch (failure: Exception) { Log.w("TakuSystemAssistant", "Invocation capture authorization unavailable", failure) }
      }
    }
  }

  override fun onShow(args: Bundle?, flags: Int) {
    super.onShow(args, flags)
    shown = true
    if (ctx.getSystemService(KeyguardManager::class.java).isKeyguardLocked) { hide(); return }
    if (captureReady()) proceed()
    else {
      // The UI does not wait for slow or missing page context (BUG-0037): it opens after a short
      // grace, which normally covers the screenshot, so the overlay is not what gets captured.
      val version = generation
      main.postDelayed({ if (shown && version == generation) startUi() }, CAPTURE_GRACE_MS)
      main.postDelayed({ if (shown && version == generation) deliverCapture() }, CAPTURE_TIMEOUT_MS)
    }
  }

  /** Everything the system was asked for has arrived: open the assistant and hand the capture off. */
  private fun proceed() {
    startUi()
    deliverCapture()
  }

  /**
   * Opens the assistant. The capture handoff is independent of it: the gateway only reads the
   * capture when a message is sent, and a failed handoff only means no screenshot (BUG-0046). Test
   * suggestions still open after the capture: their destination carries the capture's id.
   */
  private fun startUi() {
    val args = request ?: return
    if (uiStarted || args.containsKey("jevTestToken")) return
    uiStarted = true
    try { startAssistantActivity(SystemAssistantEntry.launch(ctx, args)) }
    catch (failure: RuntimeException) {
      uiStarted = false
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
    if (shown && captureReady()) proceed()
  }

  override fun onHandleScreenshot(screenshot: Bitmap?) {
    super.onHandleScreenshot(screenshot)
    if (request == null) return
    capture = screenshot
    screenshotReceived = true
    if (shown && captureReady()) proceed()
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
    handoffInFlight = opened
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
        // Once the assistant is open the screenshot is optional: no retry prompt over a usable input.
        if (!opened) main.post {
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
            handoffInFlight = false
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

  private fun begin(capturedAt: Long, autoScreenshot: Boolean, invocationId: String?): JSONObject =
    JSONObject().put("operation", "begin").put("capturedAt", capturedAt)
      .apply { if (autoScreenshot) put("autoScreenshot", true) }
      .apply { if (invocationId != null) put("invocationId", invocationId) }

  private fun gateway(method: String, payload: JSONObject, extras: Bundle? = null): JSONObject {
    val response = ctx.contentResolver.call(Uri.parse("content://org.agentos.assistant.agent-gateway"), method, payload.toString(), extras)
    val result = JSONObject(response?.getString("result") ?: "{}")
    if (!result.optBoolean("ok")) error(result.optString("code", "UNAVAILABLE"))
    return result.getJSONObject("result")
  }

  override fun onHide() {
    // Starting the assistant hides this session, and Android cancels the outstanding screenshot
    // and page requests with it: hand off what arrived (the handoff keeps its generation), and
    // never let the hide take the open assistant's invocation down with it.
    if (uiStarted && (request != null || handoffInFlight)) {
      deliverCapture()
      shown = false
      super.onHide()
      return
    }
    generation++; shown = false; uiStarted = false; request = null; capture = null; screenshotReceived = false; invocationToken = null
    super.onHide()
  }
  override fun onDestroy() { main.removeCallbacksAndMessages(null); generation++; super.onDestroy() }

  companion object {
    private val IO = Executors.newSingleThreadExecutor()
    /** Longest the UI waits for the screenshot and page context, so the screenshot shows the user's screen. */
    private const val CAPTURE_GRACE_MS = 300L
    /** Longest the capture handoff waits for the screenshot and page context. */
    private const val CAPTURE_TIMEOUT_MS = 5_000L
  }
}
