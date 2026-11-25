package org.agentos.agent.home.assistant

import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject

enum class VoiceTarget { Hold, Dictation }

enum class VoicePhase { Idle, Preparing, Capturing, Finalizing, Closed }

sealed interface VoiceOutcome {
  data class Success(val text: String) : VoiceOutcome
  data class Failed(val code: String, val message: String? = null) : VoiceOutcome
  data class Cancelled(val reason: String) : VoiceOutcome
}

data class VoiceSessionInfo(
  val settingsScope: String? = null,
  val settingsRevision: Long? = null,
  val requestedProfileId: String? = null,
  val profileId: String? = null,
  val language: String? = null,
  val processing: String? = null,
  val fallbackUsed: Boolean = false,
  val fallbackReason: String? = null,
  val maxCaptureMs: Long? = null,
  val drainTimeoutMs: Long? = null,
  val drainDeadlineMonotonicMs: Long? = null,
) {
  val summary: String?
    get() {
      fun backend(id: String?): String? = when (id) {
        null -> null
        "zipformer" -> "Zipformer"
        "fal-whisper" -> "fal Whisper"
        else -> id
      }
      val place = when (processing) {
        "CLOUD_AUDIO" -> "Cloud recognition"
        "LOCAL", "LOCAL_ONLY" -> "On-device recognition"
        else -> null
      }
      val actual = listOfNotNull(backend(profileId), place).joinToString(" · ")
      if (actual.isEmpty()) return null
      if (!fallbackUsed) return actual
      val reason = when (fallbackReason) {
        "MODEL_MISSING" -> "local model missing"
        "PROVIDER_NOT_CONFIGURED" -> "fal key missing"
        "UNSUPPORTED_LANGUAGE" -> "language unsupported"
        else -> fallbackReason
      }
      return if (reason.isNullOrBlank()) "$actual · fallback" else "$actual · fallback: $reason"
    }

  companion object {
    /** Flat Engine metadata from an open result or event; absent keys keep [previous]. */
    fun from(data: JSONObject, previous: VoiceSessionInfo? = null): VoiceSessionInfo {
      fun string(key: String): String? =
        if (data.has(key) && !data.isNull(key)) data.optString(key).ifBlank { null } else previous?.field(key)
      fun long(key: String): Long? =
        if (data.has(key) && !data.isNull(key)) data.optLong(key) else previous?.longField(key)
      val requested = string("requestedProfileId")
      val actual = string("profileId")
      return VoiceSessionInfo(
        settingsScope = string("settingsScope"),
        settingsRevision = long("settingsRevision"),
        requestedProfileId = requested,
        profileId = actual,
        language = string("language"),
        processing = string("processing"),
        fallbackUsed = if (data.has("fallbackUsed") && !data.isNull("fallbackUsed")) data.optBoolean("fallbackUsed")
        else previous?.fallbackUsed ?: (requested != null && actual != null && requested != actual),
        fallbackReason = string("fallbackReason"),
        maxCaptureMs = long("maxCaptureMs"),
        drainTimeoutMs = long("drainTimeoutMs"),
        drainDeadlineMonotonicMs = long("drainDeadlineMonotonicMs"),
      )
    }

    private fun VoiceSessionInfo.field(key: String): String? = when (key) {
      "settingsScope" -> settingsScope
      "requestedProfileId" -> requestedProfileId
      "profileId" -> profileId
      "language" -> language
      "processing" -> processing
      "fallbackReason" -> fallbackReason
      else -> null
    }

    private fun VoiceSessionInfo.longField(key: String): Long? = when (key) {
      "settingsRevision" -> settingsRevision
      "maxCaptureMs" -> maxCaptureMs
      "drainTimeoutMs" -> drainTimeoutMs
      "drainDeadlineMonotonicMs" -> drainDeadlineMonotonicMs
      else -> null
    }
  }
}

data class VoiceState(
  val operationId: Long? = null,
  val target: VoiceTarget? = null,
  val phase: VoicePhase = VoicePhase.Idle,
  val sessionId: String? = null,
  val preview: String = "",
  val levels: List<Float> = emptyList(),
  val outcome: VoiceOutcome? = null,
  val session: VoiceSessionInfo? = null,
) {
  init {
    require((phase == VoicePhase.Closed) == (outcome != null)) { "only Closed carries an outcome" }
    require(phase == VoicePhase.Idle || operationId != null) { "an operation needs an id" }
  }

  val emptySuccess: Boolean get() = (outcome as? VoiceOutcome.Success)?.text?.isBlank() == true

  val ready: Boolean get() = phase == VoicePhase.Capturing
  val receivingAudio: Boolean get() = ready && levels.isNotEmpty()
  val startingHold: Boolean get() =
    phase == VoicePhase.Idle || target == VoiceTarget.Hold && (phase == VoicePhase.Preparing || ready && !receivingAudio)
  val listening: Boolean get() = target != null && phase in ACTIVE
  val final: Boolean get() = phase == VoicePhase.Closed
  val transcript: String get() = (outcome as? VoiceOutcome.Success)?.text ?: preview
  val error: String? get() = (outcome as? VoiceOutcome.Failed)?.code

  companion object {
    val ACTIVE = setOf(VoicePhase.Preparing, VoicePhase.Capturing, VoicePhase.Finalizing)
    /** [levels] holds one measured reading per 100 ms of captured PCM in the newest history window. */
    const val LEVEL_HISTORY = 48
    /** What the wave shapes follow: the Engine's measured PCM amplitude, never recognition text. */
    const val LEVEL_SOURCE = "pcm-rms"
  }
}

/** Implementations must be safe to call from the main thread. */
interface VoiceInput {
  val state: StateFlow<VoiceState>
  fun start(target: VoiceTarget)
  /** Stop taking audio and wait for the tail. No-op unless capture is running. */
  fun finish()
  /** Drop the undelivered result and release the microphone. */
  fun cancel()
}

fun voiceOutcome(reason: String, completion: String, restored: Boolean, text: String): VoiceOutcome = when {
  reason == "completed" && completion == "complete" && restored -> VoiceOutcome.Success(text)
  reason == "canceled" -> VoiceOutcome.Cancelled(reason)
  reason == "completed" && !restored -> VoiceOutcome.Failed("RESTORE_FAILED")
  else -> VoiceOutcome.Failed(reason.ifBlank { "ENGINE_UNAVAILABLE" })
}

fun VoiceOutcome.toJson(): JSONObject = when (this) {
  is VoiceOutcome.Success -> JSONObject().put("kind", "Success").put("text", text)
  is VoiceOutcome.Failed -> JSONObject().put("kind", "Failed").put("code", code).put("message", message ?: JSONObject.NULL)
  is VoiceOutcome.Cancelled -> JSONObject().put("kind", "Cancelled").put("reason", reason)
}
