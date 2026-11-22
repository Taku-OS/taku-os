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
import java.util.UUID
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
  @Volatile private var generation = 0
  @Volatile private var invocationToken: String? = null
  private var invokedAt = 0L
  private var capturedAt = 0L
  private var textInvocationId: String? = null
  private var voicePreparationId: String? = null

  override fun onPrepareShow(args: Bundle?, flags: Int) {
    super.onPrepareShow(args, flags)
    val version = ++generation
    invokedAt = SystemClock.elapsedRealtime()
    logStage("onPrepareShow")
    capturedAt = 0L
    request = args?.let(::Bundle) ?: Bundle()
    val assistant = AssistantSession.get(ctx)
    voicePreparationId?.let(assistant::cancelPendingSystemVoicePreparation)
    voicePreparationId = null
    assistant.invalidateCharacterReference()
    textInvocationId = if (CharacterTryOnPolicy.acceptsTextEntry(
        request?.getString(SystemAssistantEntry.TRIGGER), request!!.keySet(),
        flags and (SHOW_SOURCE_ASSIST_GESTURE or SHOW_SOURCE_PUSH_TO_TALK) != 0,
      )) UUID.randomUUID().toString().takeIf { assistant.reserveCharacterTextInvocation(it, invokedAt) }
      else null
    voicePreparationId = request!!.takeIf { CharacterTryOnPolicy.acceptsEntry(
        it.getString(SystemAssistantEntry.TRIGGER), it.getString(SystemAssistantEntry.GESTURE_ID), it.keySet(),
        flags and (SHOW_SOURCE_ASSIST_GESTURE or SHOW_SOURCE_PUSH_TO_TALK) != 0,
      ) }?.getString(SystemAssistantEntry.GESTURE_ID)
      ?.takeIf(assistant::reserveSystemVoicePreparation)
    showFlags = flags
    capture = null
    screenshotReceived = false
    assistReceived = false
    assistBlocked = false
    foreground = args?.getParcelableArrayList(KEY_FOREGROUND_ACTIVITIES, ComponentName::class.java)
      ?.firstOrNull()?.flattenToShortString()
    shown = false
    invocationToken = null
    setUiEnabled(false)
    if (request?.containsKey("jevTestToken") != true) {
      val capturedAt = SystemClock.elapsedRealtime()
      IO.execute {
        if (version != generation) return@execute
        try {
          val result = gateway("assistInvocation", JSONObject().put("operation", "begin").put("capturedAt", capturedAt))
          if (version == generation) invocationToken = result.getString("token")
        } catch (failure: Exception) { Log.w("TakuSystemAssistant", "Invocation capture authorization unavailable", failure) }
      }
    }
  }

  override fun onShow(args: Bundle?, flags: Int) {
    super.onShow(args, flags)
    shown = true
    logStage("onShow")
    if (ctx.getSystemService(KeyguardManager::class.java).isKeyguardLocked) { hide(); return }
    voicePreparationId?.let {
      if (!AssistantSession.get(ctx).allowSystemVoicePreparation(it)) return@let
      try {
        // The framework has already captured this invocation's pixels. Keep its session
        // alive while assist/gateway work finishes; this Activity does not open a microphone.
        startAssistantActivity(SystemAssistantEntry.launch(ctx, request, showFlags)
          .putExtra(AssistantOverlayActivity.PREPARE_ONLY, true))
      } catch (failure: RuntimeException) { Log.w("TakuSystemAssistant", "Preparing UI unavailable", failure) }
    }
    if (captureReady()) deliverCapture()
    else {
      val version = generation
      main.postDelayed({ if (shown && version == generation) deliverCapture() }, 5_000)
    }
  }

  override fun onHandleAssist(state: AssistState) {
    super.onHandleAssist(state)
    if (!state.isFocused || request == null) return
    logStage("assistCallback")
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
    logStage("screenshotCallback")
    capture = screenshot
    capturedAt = SystemClock.elapsedRealtime()
    screenshotReceived = true
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
    val flags = showFlags
    val invoked = invokedAt
    val captured = capturedAt
    val source = CharacterTryOnPolicy.source(foreground)
    val textId = textInvocationId
    val preparingId = voicePreparationId
    val characterEntry = source != null && (textId != null || CharacterTryOnPolicy.acceptsEntry(
      args.getString(SystemAssistantEntry.TRIGGER), args.getString(SystemAssistantEntry.GESTURE_ID), args.keySet(),
      flags and (SHOW_SOURCE_ASSIST_GESTURE or SHOW_SOURCE_PUSH_TO_TALK) != 0,
    ))
    val suggestion = args.containsKey("jevTestToken")
    request = null
    shown = false
    IO.execute {
      var file: File? = null
      var characterJpeg: ByteArray? = null
      var target: Intent? = if (suggestion) null else SystemAssistantEntry.launch(ctx, args, flags)
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
        // The existing PNG/gateway path above remains authoritative for generic requests.
        // Encoding failure only disables this optional local route; it never loses that handoff.
        if (characterEntry && image != null && version == generation) {
          characterJpeg = runCatching { CharacterReferenceEncoder.encode(image) }.getOrNull()
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
        logStage("gatewayDone", invoked, version)
        file?.delete()
        main.post {
          if (version == generation) {
            try {
              val assistant = AssistantSession.get(ctx)
              // Closing input while PNG/gateway work was pending also cancels the eventual UI launch.
              if (textId != null && !assistant.readyCharacterTextInvocation(textId)) return@post
              val voiceReady = preparingId == null || assistant.completeSystemVoicePreparation(preparingId)
              // A voice-to-text transfer may retain this exact frame, but it never authorizes
              // a delayed recorder or another Activity. All other terminal entries drop it.
              if (!voiceReady && (preparingId == null || !assistant.ownsCharacterTextInvocation(preparingId))) return@post
              characterJpeg?.let { jpeg ->
                assistant.offerCharacterReference(textId ?: args.getString(SystemAssistantEntry.GESTURE_ID).orEmpty(), source!!, invoked, captured, jpeg)
                characterJpeg = null
              }
              if (!voiceReady) return@post
              if (textId != null) target?.putExtra(AssistantOverlayActivity.CHARACTER_TEXT_ID, textId)
              target?.let(::startAssistantActivity)
              // The accepted Activity handoff now owns cancellation. onHide must not revoke
              // its Ready gate before onCreate/onNewIntent can activate the same hold.
              if (voicePreparationId == preparingId) voicePreparationId = null
            }
            catch (failure: RuntimeException) {
              if (textId != null && AssistantSession.get(ctx).ownsCharacterTextInvocation(textId))
                AssistantSession.get(ctx).invalidateCharacterReference()
              preparingId?.let { AssistantSession.get(ctx).cancelPendingSystemVoicePreparation(it) }
              Log.w("TakuSystemAssistant", "Assistant UI unavailable", failure)
              android.widget.Toast.makeText(ctx, "Assistant could not open. Retry from Taku", android.widget.Toast.LENGTH_LONG).show()
            } finally { characterJpeg?.fill(0); hide() }
          } else characterJpeg?.fill(0)
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

  private fun logStage(stage: String, started: Long = invokedAt, version: Int = generation) {
    Log.d("TakuSystemAssistant", "stage=$stage generation=$version elapsedMs=${SystemClock.elapsedRealtime() - started}")
  }

  override fun onHide() {
    voicePreparationId?.let { AssistantSession.get(ctx).cancelPendingSystemVoicePreparation(it) }
    voicePreparationId = null
    textInvocationId?.let { AssistantSession.get(ctx).cancelPendingCharacterTextInvocation(it) }
    textInvocationId = null
    generation++; shown = false; request = null; capture = null; screenshotReceived = false; invocationToken = null
    super.onHide()
  }
  override fun onDestroy() {
    voicePreparationId?.let { AssistantSession.get(ctx).cancelPendingSystemVoicePreparation(it) }
    voicePreparationId = null
    textInvocationId?.let { AssistantSession.get(ctx).cancelPendingCharacterTextInvocation(it) }
    main.removeCallbacksAndMessages(null); generation++; super.onDestroy()
  }

  companion object { private val IO = Executors.newSingleThreadExecutor() }
}
