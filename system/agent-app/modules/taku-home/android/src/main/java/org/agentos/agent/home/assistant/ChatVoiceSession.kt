package org.agentos.agent.home.assistant

import android.app.KeyguardManager
import android.content.Context
import android.os.PowerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.agentos.audio.AudioReader

internal data class ChatDictation(val owner: String, val threadId: String, val voice: VoiceState)
internal data class ReplySpeech(val threadId: String, val replyId: String, val phase: String, val error: String? = null)

/** Main-thread owner shared by Expo and the Android provider. */
internal class ChatVoiceSession private constructor(private val context: Context) {
  private data class Focus(val owner: String, val threadId: String)
  private data class Recording(val focus: Focus, val operationId: Long, var engineId: Long? = null)

  private val speechFlow = MutableStateFlow<ReplySpeech?>(null)
  val speech = speechFlow.asStateFlow()
  private val dictationFlow = MutableStateFlow<ChatDictation?>(null)
  val dictation = dictationFlow.asStateFlow()
  private val engine = EngineVoiceInput(context, settingsScope = AssistantSession.DESKTOP_SCOPE, localOnly = true)
  private var focus: Focus? = null
  private var recording: Recording? = null
  private var nextOperation = 0L
  private var reader: AudioReader? = null
  private var pendingSpeech: ReplySpeech? = null

  init {
    CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).launch {
      engine.state.collect { voice ->
        val run = recording ?: return@collect
        if (run.engineId == null) return@collect
        if (voice.phase == VoicePhase.Idle) cancel(run.operationId)
        else if (voice.operationId == run.engineId) publish(run, voice)
      }
    }
  }

  fun enter(owner: String, threadId: String) {
    val next = Focus(owner, threadId)
    if (focus == next) return
    release()
    focus = next
  }

  fun leave(owner: String) {
    if (focus?.owner != owner) return
    focus = null
    release()
  }

  fun start(threadId: String, owner: String? = null): ChatDictation {
    val visible = requireVisible(threadId)
    if (owner != null && owner != visible.owner) throw AssistantException("CHAT_NOT_VISIBLE", "Open the target conversation first")
    if (!EngineVoiceInput.available(context)) throw AssistantException("VOICE_UNAVAILABLE", "The Taku Audio Engine is not installed")
    val home = AssistantSession.get(context)
    if (home.voiceStartPending || home.state.value.voiceContinuous || home.voice.state.value.listening)
      throw AssistantException("VOICE_BUSY", "Another voice session is active")
    if (dictationFlow.value?.voice?.listening == true)
      throw AssistantException("VOICE_BUSY", "Dictation is already active")
    val run = Recording(visible, ++nextOperation)
    recording = run
    publish(run, VoiceState(operationId = run.operationId, target = VoiceTarget.Dictation, phase = VoicePhase.Preparing))
    afterSpeech {
      if (recording === run) {
        engine.start(VoiceTarget.Dictation)
        val started = engine.state.value
        run.engineId = started.operationId
        publish(run, started)
      }
    }
    return checkNotNull(dictationFlow.value)
  }

  fun finish(operationId: Long) {
    val run = recording?.takeIf { it.operationId == operationId } ?: return
    if (run.engineId == null) cancel(operationId) else engine.finish()
  }

  fun cancel(operationId: Long) {
    val run = recording?.takeIf { it.operationId == operationId } ?: return
    recording = null
    engine.cancel()
    publish(run, VoiceState())
  }

  fun cancelDictation() { recording?.let { cancel(it.operationId) } }

  private fun publish(run: Recording, voice: VoiceState) {
    dictationFlow.value = ChatDictation(run.focus.owner, run.focus.threadId, voice.copy(operationId = run.operationId))
  }

  fun speak(threadId: String, replyId: String, text: String) {
    requireVisible(threadId)
    if (replyId.isBlank() || text.isBlank() || text.length > 12_000)
      throw AssistantException("INVALID_ARGUMENT", "Choose a reply with up to 12000 characters to read aloud")
    if (!EngineVoiceInput.available(context)) throw AssistantException("VOICE_UNAVAILABLE", "The Taku Audio Engine is not installed")
    val home = AssistantSession.get(context)
    if (home.voiceStartPending || home.state.value.voiceContinuous || home.voice.state.value.listening || dictationFlow.value?.voice?.listening == true)
      throw AssistantException("VOICE_BUSY", "Stop the microphone before reading a reply")
    val request = ReplySpeech(threadId, replyId, "preparing")
    afterSpeech(prepare = { pendingSpeech = request; speechFlow.value = request }) {
      if (pendingSpeech === request && focus?.threadId == threadId) {
        pendingSpeech = null
        val language = if (text.codePoints().anyMatch { Character.UnicodeScript.of(it) == Character.UnicodeScript.HAN }) "zh-CN" else "en-US"
        lateinit var next: AudioReader
        next = AudioReader(context, object : AudioReader.Listener {
          override fun state(active: Boolean, message: String) {
            if (reader !== next) return
            if (!active) reader = null
            if (pendingSpeech != null || speechFlow.value?.let { it.threadId == threadId && it.replyId == replyId } != true) return
            speechFlow.value = when {
              active -> request.copy(phase = if (message == "Reading…") "speaking" else "preparing")
              message == "Reading complete" || message == "Reading stopped" -> null
              else -> request.copy(phase = "failed", error = message)
            }
          }
        })
        reader = next
        speechFlow.value = request
        try {
          next.start(text, language)
        } catch (error: Exception) {
          reader = null
          speechFlow.value = request.copy(phase = "failed", error = error.message ?: "Reading did not start")
          runCatching { next.stop() }
        }
      }
    }
  }

  fun stopSpeech(threadId: String, replyId: String) {
    if (speechFlow.value?.let { it.threadId == threadId && it.replyId == replyId } == true) afterSpeech {}
  }

  fun afterSpeech(action: () -> Unit) = afterSpeech({}, action)

  private fun afterSpeech(prepare: () -> Unit, action: () -> Unit) {
    pendingSpeech = null
    speechFlow.value = null
    prepare()
    val old = reader
    if (old == null) action() else old.stop(Runnable(action))
  }

  private fun release() {
    cancelDictation()
    afterSpeech {}
  }

  private fun requireVisible(threadId: String): Focus {
    val visible = focus?.takeIf { it.threadId == threadId }
      ?: throw AssistantException("CHAT_NOT_VISIBLE", "Open the target conversation first")
    if (context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true ||
      context.getSystemService(PowerManager::class.java)?.isInteractive == false)
      throw AssistantException("DEVICE_LOCKED", "Wake and unlock the device before starting audio")
    return visible
  }

  companion object {
    private var instance: ChatVoiceSession? = null
    fun get(context: Context): ChatVoiceSession = instance ?: ChatVoiceSession(context.applicationContext).also { instance = it }
    fun interrupt() {
      val current = instance ?: return
      if (current.recording != null || current.speech.value != null) current.release()
    }
  }
}
