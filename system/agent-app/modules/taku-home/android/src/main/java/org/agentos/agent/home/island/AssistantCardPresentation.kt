package org.agentos.agent.home.island

import org.agentos.agent.home.assistant.*

/** A retained hold page can show a completed reply; it is not itself a listening state. */
fun assistantCardListening(s: AssistantState, v: VoiceState): Boolean =
  s.entry == EntryMode.Voice && s.error == null && !s.working && v.ready

private fun assistantCardStarting(s: AssistantState, v: VoiceState): Boolean =
  s.entry == EntryMode.Voice && !s.working && v.phase in setOf(VoicePhase.Idle, VoicePhase.Preparing)

/** Notification title, glyph and native phase share the same actual-capture boundary. */
fun assistantCardTitle(s: AssistantState, v: VoiceState): String = when {
  s.error == AssistantSession.VOICE_FAL_KEY_MISSING -> "fal API key missing"
  s.error != null -> "Taku couldn’t finish"
  s.needsUser -> "Taku needs your confirmation"
  assistantCardStarting(s, v) -> "Getting ready…"
  v.ready -> "Taku is listening..."
  s.working -> "Taku is working"
  else -> "Taku"
}

fun assistantCardAnimation(s: AssistantState, v: VoiceState): String =
  if (s.working) "working" else if (v.ready) "listening" else "none"

fun assistantCardPhase(s: AssistantState, v: VoiceState): String =
  if (assistantCardListening(s, v)) "listening" else if (s.working) "working" else "message"

/** Status line for the island and its collapsed pill; never invented beyond what the session reports. */
fun assistantCardHeadline(s: AssistantState, v: VoiceState): String = when {
  s.error != null -> errorText(s.error)
  s.needsUser -> "Taku needs your confirmation to continue."
  v.target == VoiceTarget.Hold && v.phase == VoicePhase.Finalizing -> "Taku is finishing that, one moment."
  // Entry may open before Audio.start, while the preceding spoken reply is still stopping.
  assistantCardStarting(s, v) -> "Getting ready…"
  assistantCardListening(s, v) -> "Taku is listening..."
  s.entry == EntryMode.Voice && !s.working && v.emptySuccess -> ""
  s.turn?.reply?.isNotBlank() == true && !s.working -> s.turn.reply
  s.turn?.headline != null -> s.turn.headline
  s.working -> s.pending?.let { "“$it”" } ?: s.turn?.userText?.let { "“$it”" } ?: "Taku is working"
  s.entry == EntryMode.Voice -> ""
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
  AssistantSession.VOICE_FAL_KEY_MISSING -> "Voice input needs a fal API key, and none is set up yet. Tap here to add your key."
  "VOICE_UNAVAILABLE" -> "Voice input isn’t available right now."
  else -> "Taku couldn’t send that. Try again."
}
