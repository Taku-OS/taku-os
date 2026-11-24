package org.agentos.agent.home.assistant

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Looper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.agentos.agent.home.snapshot
import org.agentos.agent.home.panels.PanelNavigation

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
      val onUiThread = Looper.myLooper() == Looper.getMainLooper()
      val panelId = if (method == "assistant/open" || method == "assistant/resume") p.optionalString("panelId") else null
      if (panelId != null && panelId.isBlank()) throw AssistantException("INVALID_ARGUMENT", "panelId must be nonempty")
      val writesDraft = method == "assistant/draft" || method == "assistant/release"
      val result = if (writesDraft || panelId != null || (method == "assistant/get" && !onUiThread)) {
        if (onUiThread) throw AssistantException("BUSY", "Draft and Panel commands must be called off the UI thread")
        runBlocking {
          withContext(Dispatchers.Main.immediate) {
            val session = AssistantSession.get(ctx)
            when (method) {
              "assistant/draft" -> session.commitDraft(p.requiredString("text"))
              "assistant/release" -> session.releaseSubmission()
              "assistant/open" -> session.openPanel(p.entryMode(), checkNotNull(panelId))
              "assistant/resume" -> {
                val panel = checkNotNull(panelId)
                if (session.resumePanel(p.requiredString("threadId"), panel) && p.optBoolean("home", true))
                  ctx.startActivity(Intent(PanelNavigation.ACTION_OPEN_PANEL).setClassName(ctx.packageName, HOME_ACTIVITY)
                    .putExtra(PanelNavigation.EXTRA_PANEL_ID, panel)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP))
              }
              else -> try { session.readDraftProjection() } catch (_: GatewayException) { }
            }
            session.toJson()
          }
        }
      } else onMain {
        if (method.startsWith("assistant/chat-voice-") || method.startsWith("assistant/reply-")) {
          val chat = ChatVoiceSession.get(ctx)
          when (method) {
            "assistant/chat-voice-get" -> Unit
            "assistant/chat-voice-start" -> chat.start(p.threadId())
            "assistant/chat-voice-finish" -> chat.finish(p.operationId())
            "assistant/chat-voice-cancel" -> chat.cancel(p.operationId())
            "assistant/reply-speak" -> chat.speak(p.threadId(), p.requiredString("replyId"), p.requiredString("text"))
            "assistant/reply-stop" -> chat.stopSpeech(p.threadId(), p.requiredString("replyId"))
            else -> throw AssistantException("UNKNOWN_METHOD", "unknown method $method")
          }
          return@onMain JSONObject()
            .put("dictation", chat.dictation.value?.let { JSONObject(it.snapshot()) } ?: JSONObject.NULL)
            .put("speech", chat.speech.value?.let { JSONObject(it.snapshot()) } ?: JSONObject.NULL)
        }
        val session = AssistantSession.get(ctx)
        when (method) {
          "assistant/get" -> {}
          "system/open" -> ctx.startActivity(SystemAssistantEntry.home(ctx).putExtra(SystemAssistantEntry.EXTRA_OPEN, true))
          "system/capture" -> SystemAssistantService.requestCapture(p.getString("token"), p.optBoolean("manual", false))
          "assistant/hint" -> session.setHint(p.getBoolean("expanded"))
          "gesture/event" -> session.gestureEvent(p)
          "gesture/activate" -> session.activateHold(p.getString("id"))
          "assistant/voice-continue" -> session.keepTalking()
          "assistant/live-start" -> session.startLiveTalk(p.optionalString("threadId").orEmpty())
          "assistant/live-stop" -> session.stopLiveTalk(p.optionalString("threadId"), p.optionalString("sessionId"))
          "assistant/live-toggle-mic" -> session.toggleLiveMic(p.optionalString("threadId"))
          "assistant/live-set-mic" -> session.setLiveMic(p.requiredString("sessionId"), p.opt("muted") as? Boolean
            ?: throw AssistantException("INVALID_ARGUMENT", "muted must be a boolean"))
          "assistant/open" -> session.open(p.entryMode())
          "assistant/close-input" -> session.closeEntry()
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
          // The full chat's collapse button (2-1 → 1-1). home=false when the caller shows Home itself.
          "assistant/resume" -> {
            session.resume(p.optString("threadId"))
            if (p.optBoolean("home", true)) ctx.startActivity(Intent().setClassName(ctx.packageName, HOME_ACTIVITY).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
          }
          "assistant/voice-start" -> {
            // Outside text entry: a press with no finger to lift; assistant/voice-finish releases and sends.
            if (session.state.value.entry != EntryMode.Text) session.open(EntryMode.Voice)
            else session.startDictation()
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
    } catch (e: GatewayException) {
      out.putString("result", JSONObject().put("ok", false).put("code", e.code).put("message", e.message).toString())
    } catch (e: Exception) {
      out.putString("result", JSONObject().put("ok", false).put("code", "INVALID_ARGUMENT").put("message", e.message ?: e.javaClass.simpleName).toString())
    } finally {
      Binder.restoreCallingIdentity(identity)
    }
    return out
  }

  private fun JSONObject.entryMode() = when (optString("mode", "text")) {
    "text" -> EntryMode.Text
    "voice" -> EntryMode.Voice
    else -> throw AssistantException("INVALID_ARGUMENT", "mode must be text or voice")
  }

  private fun JSONObject.requiredString(key: String): String = opt(key) as? String
    ?: throw AssistantException("INVALID_ARGUMENT", "$key must be a string")

  private fun JSONObject.optionalString(key: String): String? = if (has(key)) requiredString(key) else null

  private fun JSONObject.threadId(): String = requiredString("threadId")

  private fun JSONObject.operationId(): Long = when (val value = opt("operationId")) {
    is Long -> value
    is Int -> value.toLong()
    else -> throw AssistantException("INVALID_ARGUMENT", "operationId must be an integer")
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
