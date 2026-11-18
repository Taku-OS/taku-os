package org.agentos.agent.home

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
import kotlinx.coroutines.flow.combine
import org.agentos.agent.home.assistant.EngineVoiceInput
import org.agentos.agent.home.assistant.VoiceInput
import org.agentos.agent.home.assistant.VoiceOutcome
import org.agentos.agent.home.assistant.VoiceState
import org.agentos.agent.home.assistant.VoiceTarget

/**
 * Voice input for the full chat's composer (Figma 1-5 in 2-1): the same [EngineVoiceInput] the
 * home uses, as unscoped DICTATION so the chat keeps its own language choice. JavaScript starts,
 * finishes and cancels by operation ID. Start returns that operation's snapshot, and `onVoice`
 * carries subsequent recognition phases and explicit terminal outcomes. Only a successful
 * outcome carries text the chat may put in its draft.
 */
class TakuVoiceModule : Module() {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
  private var voice: EngineVoiceInput? = null
  private var watcher: Job? = null
  private var lastOperationId: Long? = null
  private var liveWatcher: Job? = null

  private val context
    get() = appContext.reactContext ?: throw Exceptions.ReactContextLost()

  private fun input(): EngineVoiceInput = voice ?: EngineVoiceInput(context).also { created ->
    voice = created
    watcher = scope.launch {
      created.state.collect { s ->
        if (s.operationId != null) lastOperationId = s.operationId
        // Idle revokes the operation that ended, even if its event reaches JS after a new start.
        sendEvent("onVoice", snapshot(s, lastOperationId))
      }
    }
  }

  private fun snapshot(s: VoiceState, operationId: Long? = s.operationId): Map<String, Any?> = mapOf(
    "active" to (s.target != null),
    "operationId" to operationId,
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

  override fun definition() = ModuleDefinition {
    Name("TakuVoice")
    Events("onVoice", "onLiveTalk")

    AsyncFunction("liveState") { org.agentos.agent.home.assistant.AssistantSession.get(context).toJson().toString() }.runOnQueue(Queues.MAIN)
    AsyncFunction("liveAction") { method: String, params: String ->
      require(method in setOf("assistant/live-start", "assistant/live-stop", "assistant/live-toggle-mic"))
      context.contentResolver.call(android.net.Uri.parse("content://org.agentos.agent.home-assistant"), method, params, null)?.getString("result")
    }.runOnQueue(Queues.MAIN)
    OnStartObserving("onLiveTalk") {
      liveWatcher?.cancel()
      liveWatcher = scope.launch {
        val session = org.agentos.agent.home.assistant.AssistantSession.get(context)
        combine(session.state, session.voice.state) { _, _ -> session.toJson().toString() }.collect {
          sendEvent("onLiveTalk", mapOf("json" to it))
        }
      }
    }
    OnStopObserving("onLiveTalk") { liveWatcher?.cancel(); liveWatcher = null }

    Function("isAvailable") { EngineVoiceInput.available(context) }

    /** [language] is the engine tag (zh-CN, en-US); empty follows the device. */
    AsyncFunction("start") { language: String ->
      if (!EngineVoiceInput.available(context)) throw VoiceUnavailable()
      val started = input().apply {
        this.language = language.ifBlank { null }
        start(VoiceTarget.Dictation)
      }.state.value
      lastOperationId = started.operationId
      snapshot(started)
    }.runOnQueue(Queues.MAIN)

    AsyncFunction("finish") { operationId: Long -> voice?.finish(operationId); Unit }.runOnQueue(Queues.MAIN)

    AsyncFunction("cancel") { operationId: Long -> voice?.cancel(operationId); Unit }.runOnQueue(Queues.MAIN)

    OnDestroy {
      liveWatcher?.cancel()
      // Voice state is main-thread owned; a reload must not leave the microphone running.
      val last = voice
      android.os.Handler(android.os.Looper.getMainLooper()).post { last?.cancel() }
      watcher?.cancel()
      scope.cancel()
    }
  }

  private class VoiceUnavailable : expo.modules.kotlin.exception.CodedException("VOICE_UNAVAILABLE", "The Taku Audio Engine is not installed", null)
}

internal fun VoiceInput.finish(operationId: Long) {
  if (state.value.operationId == operationId) finish()
}

internal fun VoiceInput.cancel(operationId: Long) {
  if (state.value.operationId == operationId) cancel()
}
