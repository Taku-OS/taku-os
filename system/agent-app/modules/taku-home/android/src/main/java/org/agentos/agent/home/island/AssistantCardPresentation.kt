package org.agentos.agent.home.island

import org.agentos.agent.home.assistant.*

/** A retained hold page can show a completed reply; it is not itself a listening state. */
fun assistantCardListening(s: AssistantState, v: VoiceState): Boolean =
  s.entry == EntryMode.Voice && s.error == null && !s.working &&
    (v.phase in setOf(VoicePhase.Idle, VoicePhase.Preparing, VoicePhase.Capturing) || v.emptySuccess)

/** Status line for the island and its collapsed pill; never invented beyond what the session reports. */
fun assistantCardHeadline(s: AssistantState, v: VoiceState): String = when {
  s.error != null -> errorText(s.error)
  s.needsUser -> "Taku needs your confirmation to continue."
  v.target == VoiceTarget.Hold && v.phase == VoicePhase.Finalizing -> "Taku is finishing that, one moment."
  // The microphone is not recording yet: say nothing rather than invite words that cannot be heard.
  s.entry == EntryMode.Voice && v.target == VoiceTarget.Hold && v.phase == VoicePhase.Preparing -> ""
  assistantCardListening(s, v) -> "Taku is listening..."
  s.turn?.reply?.isNotBlank() == true && !s.working -> s.turn.reply
  s.turn?.headline != null -> s.turn.headline
  s.working -> s.pending?.let { "“$it”" } ?: s.turn?.userText?.let { "“$it”" } ?: "Taku is working"
  s.entry == EntryMode.Voice -> "Taku is listening..."
  else -> "Taku is here, how can I help you?"
}

private fun errorText(code: String) = when (code) {
  "THREAD_BUSY", "THREAD_QUEUED", "BUSY" -> "Taku is still finishing the last request. Try again in a moment."
  "TASK_FAILED", "TURN_FAILED" -> "Taku couldn’t finish that."
  "GATEWAY_UNAVAILABLE" -> "Taku isn’t available right now."
  "NETWORK_UNAVAILABLE" -> "No network connection, so Taku stopped. Your request is kept."
  "VOICE_DISABLED" -> "Voice input is turned off in Taku AI settings."
  "VOICE_PERMISSION_REQUIRED" -> "Allow microphone access in Taku Audio to use voice input."
  "VOICE_BUSY" -> "The microphone is in use. Try again in a moment."
  "VOICE_NETWORK_FAILED" -> "Voice recognition lost its connection. Please try again."
  "VOICE_TIMEOUT" -> "Voice recognition took too long. Please try again."
  "VOICE_AUTH_FAILED" -> "Check your voice service credentials in Taku Audio settings."
  "VOICE_PROVIDER_UNAVAILABLE" -> "The voice service is temporarily unavailable. Please try again."
  "VOICE_UNAVAILABLE" -> "Voice input isn’t available right now."
  else -> "Taku couldn’t send that. Try again."
}
