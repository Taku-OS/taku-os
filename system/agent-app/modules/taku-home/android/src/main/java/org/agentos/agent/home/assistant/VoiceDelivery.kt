package org.agentos.agent.home.assistant

import org.json.JSONObject

internal enum class VoiceDestination { AwaitGesture, Draft, Submit, Live }

internal enum class VoiceDeliveryState { Pending, Consumed, Retained, Revoked }

internal data class VoiceDelivery(
  val operationId: Long,
  val generation: Long,
  var destination: VoiceDestination,
  var state: VoiceDeliveryState = VoiceDeliveryState.Pending,
)

data class RetainedVoiceResult(val operationId: Long, val text: String, val reason: String) {
  fun toJson(): JSONObject = JSONObject().put("operationId", operationId).put("text", text).put("reason", reason)
}

internal sealed interface VoiceEffect {
  data object None : VoiceEffect
  data class Draft(val text: String) : VoiceEffect
  data class Send(val text: String) : VoiceEffect
  data class Live(val operationId: Long, val text: String) : VoiceEffect
  data class Retained(val result: RetainedVoiceResult) : VoiceEffect
  data class Failed(val code: String) : VoiceEffect
  data object CloseEntry : VoiceEffect
}

internal class VoiceDeliveryMachine(private val maxText: Int) {
  var generation: Long = 0L
    private set
  var lastOperationId: Long? = null
    private set
  private var delivery: VoiceDelivery? = null

  val current: VoiceDelivery? get() = delivery

  fun newGeneration() {
    generation++
    delivery?.state = VoiceDeliveryState.Revoked
    delivery = null
  }

  fun begin(operationId: Long, destination: VoiceDestination) {
    newGeneration()
    lastOperationId = operationId
    delivery = VoiceDelivery(operationId, generation, destination)
  }

  fun aim(destination: VoiceDestination) {
    delivery?.takeIf { it.state == VoiceDeliveryState.Pending && it.destination == VoiceDestination.AwaitGesture }?.destination = destination
  }
  fun handoff(): Long? {
    val record = delivery?.takeIf { it.state == VoiceDeliveryState.Pending && it.destination != VoiceDestination.Draft } ?: return null
    record.destination = VoiceDestination.Live
    return record.operationId
  }


  /** Revokes now and returns the operation the caller must clean up, if there was one. */
  fun revoke(): Long? {
    val operation = delivery?.operationId ?: lastOperationId
    newGeneration()
    return operation
  }

  fun settle(voice: VoiceState, draft: String): VoiceEffect {
    val record = delivery ?: return VoiceEffect.None
    if (voice.phase == VoicePhase.Idle) {
      record.state = VoiceDeliveryState.Revoked
      delivery = null
      return VoiceEffect.None
    }
    if (record.generation != generation || voice.operationId != record.operationId) return VoiceEffect.None
    when (record.state) {
      VoiceDeliveryState.Consumed, VoiceDeliveryState.Retained, VoiceDeliveryState.Revoked -> return VoiceEffect.None
      VoiceDeliveryState.Pending -> {}
    }
    return when (val outcome = voice.outcome) {
      null -> VoiceEffect.None
      is VoiceOutcome.Failed -> {
        record.state = VoiceDeliveryState.Consumed
        VoiceEffect.Failed(outcome.code)
      }
      is VoiceOutcome.Cancelled -> {
        record.state = VoiceDeliveryState.Consumed
        if (record.destination == VoiceDestination.Live) VoiceEffect.Live(record.operationId, "")
        else if (voice.target == VoiceTarget.Hold) VoiceEffect.CloseEntry else VoiceEffect.None
      }
      is VoiceOutcome.Success -> when (record.destination) {
        VoiceDestination.AwaitGesture -> VoiceEffect.None
        VoiceDestination.Live -> {
          record.state = VoiceDeliveryState.Consumed
          VoiceEffect.Live(record.operationId, outcome.text)
        }
        VoiceDestination.Submit -> {
          record.state = VoiceDeliveryState.Consumed
          if (outcome.text.isBlank()) VoiceEffect.CloseEntry else VoiceEffect.Send(outcome.text)
        }
        VoiceDestination.Draft -> {
          record.state = VoiceDeliveryState.Consumed
          draftEffect(record.operationId, outcome.text, draft)
        }
      }
    }
  }

  private fun draftEffect(operationId: Long, heard: String, draft: String): VoiceEffect {
    val text = heard.trim()
    if (text.isEmpty()) return VoiceEffect.None
    val merged = DictationTranscript.join(draft.trimEnd(), text)
    if (merged.length > maxText) {
      delivery?.state = VoiceDeliveryState.Retained
      return VoiceEffect.Retained(RetainedVoiceResult(operationId, text, REASON_DRAFT_FULL))
    }
    return VoiceEffect.Draft(merged)
  }

  companion object {
    const val REASON_DRAFT_FULL = "DRAFT_FULL"
  }
}
