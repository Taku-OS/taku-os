package org.agentos.agent.home.island

import org.agentos.agent.home.assistant.*
import org.json.JSONArray
import org.json.JSONObject

/** Data for the existing SystemUI v1 assistant renderer, never a Home-drawn island. */
internal data class AssistantEntryCard(val kind: String, val id: String, val body: String,
                                      val buttons: List<Pair<String, String>>) {
  val token: String get() = "entry:$kind:$id"
  val actions: List<Pair<String, String>> get() = listOf("open" to "Expand") + buttons
  fun actionIdentity(action: String): String = "taku-island-entry://$kind/$id/$action"

  fun payload(): JSONObject = JSONObject().put("version", 1).put("category", "assistant")
    .put("compact", "hidden").put("relevance", 1)
    .put("bigIsland", JSONObject()
      .put("leading", JSONObject().put("type", "pic").put("pic", "taku").put("animated", "none"))
      .put("trailing", JSONObject().put("type", "text").put("text", "Taku")))
    .put("expanded", JSONObject().put("template", "assistant").put("title", "Taku").put("body", body)
      .put("steps", JSONArray()).put("actions", JSONArray(actions.map { it.first })).put("primaryAction", "open")
      .put("assistant", JSONObject().put("token", token).put("phase", "message").put("heightDp", 160)
        .put("suggestions", JSONArray(buttons.map { it.first }))))
    .put("islandTimeoutS", 120).put("statusBar", "auto")

  companion object { const val INTENT_ACTION = "org.agentos.agent.island.ENTRY_ACTION" }
}

internal fun assistantEntryOwnsPresentation(state: AssistantState): Boolean =
  state.voicePreparation || state.characterTryOn != null

internal fun assistantEntryUsesFallback(state: AssistantState, nativeAvailable: Boolean): Boolean =
  !nativeAvailable && assistantEntryOwnsPresentation(state) && state.characterTryOn?.phase != CharacterTryOnPhase.Starting

/** Starting remains owned but has no waiting card; the existing capability handoff continues. */
internal fun assistantEntryCard(state: AssistantState, preparationId: String?): AssistantEntryCard? {
  state.characterTryOn?.let { character ->
    return when (character.phase) {
      CharacterTryOnPhase.Checking -> AssistantEntryCard("character", character.requestId, "Getting ready…", listOf("cancel" to "Not now"))
      CharacterTryOnPhase.Confirming -> AssistantEntryCard("character", character.requestId, "Try this character?", listOf("cancel" to "Not now", "confirm" to "Try on"))
      CharacterTryOnPhase.Failed -> AssistantEntryCard("character", character.requestId, character.message ?: "Character preview could not open.", listOf("cancel" to "Close"))
      CharacterTryOnPhase.Starting -> null
    }
  }
  return if (state.voicePreparation && !preparationId.isNullOrBlank())
    AssistantEntryCard("preparation", preparationId, "Getting ready…", listOf("cancel" to "Cancel")) else null
}

/** One main-thread admission path for native buttons and their safe body/expand action. */
internal fun dispatchAssistantEntryAction(state: AssistantState, preparationId: String?, kind: String, id: String,
    action: String, confirm: (String) -> Unit, cancel: (String) -> Unit,
    cancelPreparation: (String) -> Unit, expand: (String) -> Unit): Boolean {
  val card = assistantEntryCard(state, preparationId) ?: return false
  if (card.kind != kind || card.id != id || card.actions.none { it.first == action }) return false
  when (action) {
    "open" -> expand(card.token)
    "confirm" -> confirm(id)
    "cancel" -> if (kind == "character") cancel(id) else cancelPreparation(id)
    else -> return false
  }
  return true
}

internal fun assistantEntryBackAction(state: AssistantState): String = when {
  state.characterTryOn != null -> "character"
  state.voicePreparation -> "preparation"
  state.entry == EntryMode.Voice -> "voice"
  state.entry == EntryMode.Text -> "text"
  else -> "none"
}

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
