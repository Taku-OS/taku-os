package org.agentos.agent.home

import android.content.Intent
import expo.modules.kotlin.exception.CodedException
import expo.modules.kotlin.exception.Exceptions
import expo.modules.kotlin.functions.Queues
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.agentos.agent.home.assistant.AssistantException
import org.agentos.agent.home.assistant.AssistantSession
import org.agentos.agent.home.assistant.ChatDictation
import org.agentos.agent.home.assistant.ChatVoiceSession
import org.agentos.agent.home.assistant.ReplySpeech
import org.agentos.agent.home.assistant.EngineVoiceInput
import org.agentos.agent.home.assistant.VoiceInput
import org.agentos.agent.home.assistant.VoiceOutcome
import org.agentos.agent.home.assistant.VoiceState

class TakuVoiceModule : Module() {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
  private var voiceWatcher: Job? = null
  private var speechWatcher: Job? = null
  private var chatOwner: String? = null
  private var ownedChat: ChatVoiceSession? = null
  private var liveWatcher: Job? = null

  private val context
    get() = appContext.reactContext ?: throw Exceptions.ReactContextLost()

  private val chat get() = ChatVoiceSession.get(context)

  private fun <T> voiceCall(action: () -> T): T = try {
    action()
  } catch (error: AssistantException) {
    throw CodedException(error.code, error.message, error)
  }

  override fun definition() = ModuleDefinition {
    Name("TakuVoice")
    Events("onVoice", "onLiveTalk", "onReplySpeech")

    AsyncFunction("enterChat") { owner: String, threadId: String ->
      val session = chat
      session.enter(owner, threadId)
      ownedChat = session
      chatOwner = owner
    }.runOnQueue(Queues.MAIN)
    AsyncFunction("leaveChat") { owner: String ->
      chat.leave(owner)
      if (chatOwner == owner) chatOwner = null
    }.runOnQueue(Queues.MAIN)

    OnStartObserving("onVoice") {
      voiceWatcher?.cancel()
      voiceWatcher = scope.launch {
        chat.dictation.collect { if (it != null) sendEvent("onVoice", it.snapshot()) }
      }
    }
    OnStopObserving("onVoice") { voiceWatcher?.cancel(); voiceWatcher = null }
    OnStartObserving("onReplySpeech") {
      speechWatcher?.cancel()
      speechWatcher = scope.launch {
        chat.speech.collect { sendEvent("onReplySpeech", mapOf("speech" to it?.snapshot())) }
      }
    }
    OnStopObserving("onReplySpeech") { speechWatcher?.cancel(); speechWatcher = null }

    AsyncFunction("liveState") { AssistantSession.get(context).liveSnapshotJson().toString() }.runOnQueue(Queues.MAIN)
    AsyncFunction("liveAction") { method: String, params: String ->
      require(method in setOf("assistant/live-start", "assistant/live-stop", "assistant/live-toggle-mic", "assistant/live-set-mic",
        "assistant/live-set-video", "assistant/live-get", "assistant/live-standby"))
      context.contentResolver.call(android.net.Uri.parse("content://org.agentos.agent.home-assistant"), method, params, null)?.getString("result")
    }.runOnQueue(Queues.MAIN)
    OnStartObserving("onLiveTalk") {
      liveWatcher?.cancel()
      liveWatcher = scope.launch {
        val session = AssistantSession.get(context)
        session.liveState.collect {
          sendEvent("onLiveTalk", mapOf("json" to session.liveSnapshotJson().toString()))
        }
      }
    }
    OnStopObserving("onLiveTalk") { liveWatcher?.cancel(); liveWatcher = null }

    Function("isAvailable") { EngineVoiceInput.available(context) }
    AsyncFunction("openSettings") {
      val activity = appContext.currentActivity
        ?: throw CodedException("VOICE_UNAVAILABLE", "Open the conversation first", null)
      activity.startActivity(Intent().setClassName("org.agentos.audio", "org.agentos.audio.DictationSettingsActivity"))
    }.runOnQueue(Queues.MAIN)
    AsyncFunction("start") { owner: String, threadId: String -> voiceCall { chat.start(threadId, owner).snapshot() } }.runOnQueue(Queues.MAIN)
    AsyncFunction("finish") { operationId: Long -> chat.finish(operationId) }.runOnQueue(Queues.MAIN)
    AsyncFunction("cancel") { operationId: Long -> chat.cancel(operationId) }.runOnQueue(Queues.MAIN)
    AsyncFunction("speak") { threadId: String, replyId: String, text: String ->
      voiceCall { chat.speak(threadId, replyId, text) }
    }.runOnQueue(Queues.MAIN)
    AsyncFunction("stopSpeech") { threadId: String, replyId: String -> chat.stopSpeech(threadId, replyId) }.runOnQueue(Queues.MAIN)

    OnDestroy {
      val owner = chatOwner
      val session = ownedChat
      if (owner != null && session != null) android.os.Handler(android.os.Looper.getMainLooper()).post {
        session.leave(owner)
      }
      scope.cancel()
    }
  }
}

internal fun ReplySpeech.snapshot(): Map<String, Any?> = mapOf(
  "threadId" to threadId, "replyId" to replyId, "phase" to phase, "error" to error,
)

internal fun ChatDictation.snapshot(): Map<String, Any?> {
  val s = voice
  return mapOf(
    "owner" to owner,
    "threadId" to threadId,
    "active" to (s.target != null),
    "operationId" to s.operationId,
    "sessionId" to s.sessionId,
    "phase" to s.phase.name,
    "listening" to s.listening,
    "ready" to s.ready,
    "transcript" to s.transcript,
    "preview" to s.preview,
    "levels" to s.levels.takeLast(VoiceState.LEVEL_HISTORY).map { it.toDouble() },
    "levelSource" to VoiceState.LEVEL_SOURCE,
    "final" to s.final,
    "error" to s.error,
    "outcome" to when (val outcome = s.outcome) {
      null -> null
      is VoiceOutcome.Success -> mapOf("kind" to "Success", "text" to outcome.text)
      is VoiceOutcome.Failed -> mapOf("kind" to "Failed", "code" to outcome.code, "message" to outcome.message)
      is VoiceOutcome.Cancelled -> mapOf("kind" to "Cancelled", "reason" to outcome.reason)
    },
    "session" to s.session?.let { info ->
      mapOf(
        "settingsScope" to info.settingsScope,
        "settingsRevision" to info.settingsRevision,
        "requestedProfileId" to info.requestedProfileId,
        "profileId" to info.profileId,
        "language" to info.language,
        "processing" to info.processing,
        "fallbackUsed" to info.fallbackUsed,
        "fallbackReason" to info.fallbackReason,
        "maxCaptureMs" to info.maxCaptureMs,
        "drainTimeoutMs" to info.drainTimeoutMs,
        "drainDeadlineMonotonicMs" to info.drainDeadlineMonotonicMs,
      )
    },
  )
}

internal fun VoiceInput.finish(operationId: Long) {
  if (state.value.operationId == operationId) finish()
}

internal fun VoiceInput.cancel(operationId: Long) {
  if (state.value.operationId == operationId) cancel()
}
