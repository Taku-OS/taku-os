package org.agentos.agent.home.assistant

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import org.json.JSONObject

/**
 * Agent access to the home AI entry and Dynamic Island:
 * `content call --uri content://org.agentos.agent.home-assistant --method assistant/get`.
 * Every method acts on the same [AssistantSession] the GUI renders.
 */
class HomeAssistantProvider : ContentProvider() {
  override fun onCreate() = true

  override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
    val out = Bundle()
    val callerUid = Binder.getCallingUid()
    val appCtx = context
    if (appCtx != null && !org.agentos.agent.home.TakuCallerAccess.isAllowed(appCtx, callerUid)) {
      out.putString("result", JSONObject().put("ok", false).put("code", "PERMISSION_DENIED").put("message", "Assistant access denied by App Permission Center").toString())
      return out
    }
    val identity = Binder.clearCallingIdentity()
    try {
      val ctx = context ?: throw AssistantException("UNAVAILABLE", "provider not attached")
      val p = if (arg.isNullOrBlank()) JSONObject() else JSONObject(arg)
      val result = onMain {
        val session = AssistantSession.get(ctx)
        when (method) {
          "assistant/get" -> {}
          "system/open" -> ctx.startActivity(SystemAssistantEntry.home(ctx).putExtra(SystemAssistantEntry.EXTRA_OPEN, true))
          "system/capture" -> SystemAssistantService.requestCapture(p.getString("token"), p.optBoolean("manual", false))
          "assistant/hint" -> session.setHint(p.getBoolean("expanded"))
          "gesture/event" -> session.gestureEvent(p)
          "gesture/activate" -> session.activateHold(p.getString("id"))
          "assistant/voice-continue" -> session.keepTalking()
          "assistant/live-start" -> session.startLiveTalk(p.optString("threadId"))
          "assistant/live-stop" -> session.stopLiveTalk()
          "assistant/live-toggle-mic" -> session.toggleLiveMic()
          "assistant/open" -> {
            val mode = when (p.optString("mode", "text")) {
              "text" -> EntryMode.Text
              "voice" -> EntryMode.Voice
              else -> throw AssistantException("INVALID_ARGUMENT", "mode must be text or voice")
            }
            session.open(mode)
            // Agent calls have no held pointer; use the same released/latching path as the GUI.
            if (mode == EntryMode.Voice) session.releaseHold()
          }
          "assistant/close-input" -> session.closeEntry()
          "assistant/draft" -> session.setDraft(p.optString("text"))
          "assistant/choose-prompt" -> session.choosePrompt(p.optString("text"))
          "assistant/app-build" -> session.setAppBuild(p.getString("threadId"), p.getString("turnId"), p.getBoolean("active"))
          "assistant/ask" -> session.send(p.optString("text"))
          "assistant/retry" -> session.retry()
          "assistant/reply" -> session.reply(p.optString("suggestionId"))
          "assistant/stop" -> session.stop()
          "assistant/expand" -> session.expand(p.optBoolean("full", false))
          "assistant/collapse" -> session.collapseIsland()
          "assistant/dismiss" -> session.dismiss()
          "assistant/reset" -> session.reset()
          "assistant/open-conversation" -> session.openConversation()
          "assistant/open-camera" -> session.openConversation(openCamera = true)
          // The full chat's collapse button (2-1 → 1-1). home=false when the caller shows Home itself.
          "assistant/resume" -> {
            session.resume(p.optString("threadId"))
            if (p.optBoolean("home", true)) ctx.startActivity(Intent().setClassName(ctx.packageName, HOME_ACTIVITY).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
          }
          "assistant/voice-start" -> {
            if (session.state.value.entry != EntryMode.Text) {
              session.open(EntryMode.Voice); session.releaseHold()
            } else session.startDictation()
          }
          "assistant/voice-finish" -> if (session.voice.state.value.target == VoiceTarget.Dictation) session.stopDictation() else session.finishVoice()
          "assistant/voice-cancel" -> session.cancelVoice()
          "assistant/voice-to-text" -> session.voiceToText()
          else -> throw AssistantException("UNKNOWN_METHOD", "unknown method $method")
        }
        session.toJson()
      }
      out.putString("result", JSONObject().put("ok", true).put("result", result).toString())
    } catch (e: AssistantException) {
      out.putString("result", JSONObject().put("ok", false).put("code", e.code).put("message", e.message).toString())
    } catch (e: Exception) {
      out.putString("result", JSONObject().put("ok", false).put("code", "INVALID_ARGUMENT").put("message", e.message ?: e.javaClass.simpleName).toString())
    } finally {
      Binder.restoreCallingIdentity(identity)
    }
    return out
  }

  /** Session and voice state are main-thread owned, as in the GUI. */
  private fun <T> onMain(block: () -> T) =
    org.agentos.agent.home.MainThread.call(onTimeout = { AssistantException("TIMEOUT", "home did not respond") }, block = block)

  override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
  override fun getType(uri: Uri): String? = null
  override fun insert(uri: Uri, values: ContentValues?): Uri? = null
  override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
  override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0

  private companion object {
    const val HOME_ACTIVITY = "org.agentos.agent.home.HomeActivity"
  }
}
