package org.agentos.agent.home.assistant

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.speech.RecognitionService
import android.speech.SpeechRecognizer

/** The real selected Android assistant lives in Taku Agent, independent of the backend UI. */
class SystemAssistantService : VoiceInteractionService() {
  override fun onReady() { super.onReady(); active = this }
  override fun onShutdown() { if (active === this) active = null; super.onShutdown() }
  override fun onDestroy() { if (active === this) active = null; super.onDestroy() }

  companion object {
    @Volatile private var active: SystemAssistantService? = null

    fun requestCapture(token: String, manual: Boolean) {
      if (!token.matches(Regex("[a-f0-9-]{36}"))) throw AssistantException("INVALID_ARGUMENT", "invalid capture token")
      val service = active ?: throw AssistantException("UNAVAILABLE", "system assistant is not ready")
      service.showSession(Bundle().apply {
        putString("jevTestToken", token)
        putBoolean("manualScreen", manual)
      }, VoiceInteractionSession.SHOW_WITH_ASSIST or VoiceInteractionSession.SHOW_WITH_SCREENSHOT)
    }
  }
}

class SystemAssistantSessionService : VoiceInteractionSessionService() {
  override fun onNewSession(args: Bundle?): VoiceInteractionSession = SystemAssistantSession(this)
}

/** Android requires this metadata; actual microphone/ASR ownership stays in Audio Engine. */
class SystemRecognitionService : RecognitionService() {
  override fun onStartListening(intent: Intent?, callback: Callback) { runCatching { callback.error(SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED) } }
  override fun onStopListening(callback: Callback) {}
  override fun onCancel(callback: Callback) {}
}

/** Shared by Android system entry and the device-local Agent API. */
object SystemAssistantEntry {
  const val ACTION_VOICE_ASSIST = "android.intent.action.VOICE_ASSIST"
  const val EXTRA_OPEN = "org.agentos.agent.extra.OPEN_ASSISTANT"
  const val HOME = "org.agentos.agent.home.HomeActivity"
  const val SERVICE = "org.agentos.agent.home.assistant.SystemAssistantService"
  const val TRIGGER = "org.agentos.assistant.extra.TRIGGER" // Existing gesture protocol stays compatible.
  const val GESTURE_ID = "org.agentos.assistant.extra.GESTURE_ID"

  fun home(context: Context): Intent = Intent(Intent.ACTION_MAIN)
    .addCategory(Intent.CATEGORY_HOME).setClassName(context.packageName, HOME)
    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)

  fun launch(context: Context, args: Bundle?, flags: Int = 0): Intent {
    val trigger = args?.getString(TRIGGER).orEmpty()
    return if (trigger == "nav_handle_long_press" || trigger == "nav_handle_double_tap") {
      Intent(context, AssistantOverlayActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        .putExtra("mode", if (trigger == "nav_handle_long_press") "voice" else "text")
        .putExtra("gestureId", args?.getString(GESTURE_ID).orEmpty())
    } else home(context).putExtra(EXTRA_OPEN, true).apply {
      if (flags and VoiceInteractionSession.SHOW_SOURCE_PUSH_TO_TALK != 0) {
        action = ACTION_VOICE_ASSIST
        removeCategory(Intent.CATEGORY_HOME)
      }
    }
  }
}
