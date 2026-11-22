package org.agentos.agent.home

import org.agentos.agent.home.assistant.*
import org.agentos.agent.home.island.assistantCardHeadline
import org.agentos.agent.home.island.assistantCardListening
import org.agentos.agent.home.island.assistantCardTitle
import org.agentos.agent.home.island.assistantCardAnimation
import org.agentos.agent.home.island.assistantCardPhase
import org.agentos.agent.home.island.assistantEntryCard
import org.agentos.agent.home.island.assistantEntryBackAction
import org.agentos.agent.home.island.assistantEntryOwnsPresentation
import org.agentos.agent.home.island.assistantEntryUsesFallback
import org.agentos.agent.home.island.dispatchAssistantEntryAction
import org.junit.Assert.*
import org.junit.Test

class AssistantCardPresentationTest {
  @Test fun holdSaysListeningOnlyOnceTheMicrophoneRecords() {
    val hold = AssistantState(entry = EntryMode.Voice)
    val preparing = VoiceState(operationId = 1, target = VoiceTarget.Hold, phase = VoicePhase.Preparing)
    assertTrue("Preparing still owns the active operation", preparing.listening)
    assertNoListening(hold, preparing)
    assertEquals("Getting ready…", assistantCardHeadline(hold, preparing))
    assertEquals("Getting ready…", assistantCardTitle(hold, preparing))
    val capturing = preparing.copy(phase = VoicePhase.Capturing)
    assertTrue(capturing.ready)
    assertTrue(assistantCardListening(hold, capturing))
    assertEquals("Taku is listening...", assistantCardHeadline(hold, capturing))
    assertEquals("Taku is listening...", assistantCardTitle(hold, capturing))
    assertEquals("listening", assistantCardAnimation(hold, capturing))
    assertEquals("listening", assistantCardPhase(hold, capturing))
  }
  @Test fun idleAndPreparingNeverExposeAnOlderReplyOrInviteSpeech() {
    val old = TurnView("old", "old headline", false, false, "old question", "old reply", emptyList(), emptyList())
    val state = AssistantState(entry = EntryMode.Voice, turn = old)
    for (voice in listOf(VoiceState(), VoiceState(operationId = 1, target = VoiceTarget.Hold, phase = VoicePhase.Preparing))) {
      assertNoListening(state, voice)
      assertEquals("Getting ready…", assistantCardHeadline(state, voice))
      assertEquals("Getting ready…", assistantCardTitle(state, voice))
    }
  }
  @Test fun finalizingIsProcessingEvenThoughTheOperationIsStillActive() {
    val state = AssistantState(entry = EntryMode.Voice)
    val voice = VoiceState(operationId = 1, target = VoiceTarget.Hold, phase = VoicePhase.Finalizing)
    assertTrue(voice.listening)
    assertNoListening(state, voice)
    assertEquals("Taku is finishing that, one moment.", assistantCardHeadline(state, voice))
    assertEquals("Taku", assistantCardTitle(state, voice))
  }
  @Test fun dictationPreparationAlsoHasNoListeningTitleOrAnimation() {
    val state = AssistantState(entry = EntryMode.Text)
    val voice = VoiceState(operationId = 1, target = VoiceTarget.Dictation, phase = VoicePhase.Preparing)
    assertNoListening(state, voice)
    assertEquals("none", assistantCardAnimation(state, voice))
  }
  @Test fun completedTurnUsesTheActualReplyInsteadOfAHeadlineOrGreeting() {
    val turn = TurnView("turn", "summary", false, false, "question", "The actual agent response.", emptyList(), emptyList())
    assertEquals("The actual agent response.", assistantCardHeadline(AssistantState(turn = turn), VoiceState()))
  }
  @Test fun emptyFinalDoesNotClaimCaptureOrReuseAnOlderReply() {
    val old = TurnView("old", "old headline", false, false, "old question", "old reply", emptyList(), emptyList())
    val empty = VoiceState(operationId = 1, target = VoiceTarget.Hold, phase = VoicePhase.Closed,
      outcome = VoiceOutcome.Success("  "))
    val state = AssistantState(entry = EntryMode.Voice, turn = old)
    assertNoListening(state, empty)
    assertEquals("", assistantCardHeadline(state, empty))
    assertEquals("Taku", assistantCardTitle(state, empty))
    assertFalse(empty.copy(outcome = VoiceOutcome.Failed("NETWORK_FAILED")).emptySuccess)
  }
  @Test fun retainedHoldShowsProcessingThenTheReplyRatherThanListening() {
    val finished = VoiceState(operationId = 1, target = VoiceTarget.Hold,
      phase = VoicePhase.Closed, outcome = VoiceOutcome.Success("question"))
    val working = AssistantState(entry = EntryMode.Voice, sending = true, pending = "question")
    assertFalse(assistantCardListening(working, finished))
    assertEquals("“question”", assistantCardHeadline(working, finished))
    assertEquals("working", assistantCardPhase(working, finished))
    assertEquals("working", assistantCardAnimation(working, finished))
    assertEquals("Taku is working", assistantCardTitle(working, finished))
    val turn = TurnView("turn", "summary", false, false, "question", "Actual reply", emptyList(), emptyList())
    val replied = AssistantState(entry = EntryMode.Voice, turn = turn)
    assertFalse("real suggestions must not be hidden on the retained page", assistantCardListening(replied, finished))
    assertEquals("Actual reply", assistantCardHeadline(replied, finished))
    assertEquals("message", assistantCardPhase(replied, finished))
    assertEquals("none", assistantCardAnimation(replied, finished))
    assertEquals("Taku", assistantCardTitle(replied, finished))
  }
  @Test fun pendingWorkIsNotReplacedByAStartupMessage() {
    val state = AssistantState(entry = EntryMode.Voice, sending = true, pending = "Check this")
    for (voice in listOf(VoiceState(), VoiceState(operationId = 1, target = VoiceTarget.Hold, phase = VoicePhase.Preparing))) {
      assertEquals("“Check this”", assistantCardHeadline(state, voice))
      assertEquals("Taku is working", assistantCardTitle(state, voice))
      assertEquals("working", assistantCardPhase(state, voice))
      assertEquals("working", assistantCardAnimation(state, voice))
    }
  }
  @Test fun errorAndConfirmationTakePriorityOverPreparation() {
    val voice = VoiceState(operationId = 1, target = VoiceTarget.Hold, phase = VoicePhase.Preparing)
    val error = AssistantState(entry = EntryMode.Voice, error = "VOICE_PERMISSION_REQUIRED")
    assertEquals("Allow microphone access in Taku Audio to use voice input.", assistantCardHeadline(error, voice))
    assertEquals("Taku couldn’t finish", assistantCardTitle(error, voice))
    assertEquals("message", assistantCardPhase(error, voice))
    val confirmation = AssistantState(entry = EntryMode.Voice, task = "needs_user")
    assertEquals("Taku needs your confirmation to continue.", assistantCardHeadline(confirmation, voice))
    assertEquals("Taku needs your confirmation", assistantCardTitle(confirmation, voice))
  }
  @Test fun emptyReplyDoesNotHideAnActualHeadline() {
    val turn = TurnView("turn", "question", false, false, "Actual status", "", emptyList(), emptyList())
    assertEquals("Actual status", assistantCardHeadline(AssistantState(turn = turn), VoiceState()))
  }

  @Test fun nativeConfirmationPublishesTheExistingAssistantTemplateAndTwoExplicitButtons() {
    val state = character(CharacterTryOnPhase.Confirming)
    val card = assistantEntryCard(state, null)!!
    val payload = card.payload()
    assertEquals("assistant", payload.getString("category"))
    val expanded = payload.getJSONObject("expanded")
    assertEquals("assistant", expanded.getString("template"))
    assertFalse("no remote Home/Compose surface", expanded.has("content"))
    assertEquals("Try this character?", expanded.getString("body"))
    assertEquals(listOf("cancel" to "Not now", "confirm" to "Try on"), card.buttons)
    assertEquals("open", expanded.getString("primaryAction"))
    assertNotEquals("confirm", expanded.getString("primaryAction"))
    val native = expanded.getJSONObject("assistant")
    assertEquals("message", native.getString("phase"))
    assertEquals(listOf("cancel", "confirm"), (0 until native.getJSONArray("suggestions").length()).map { native.getJSONArray("suggestions").getString(it) })
    assertEquals("none", payload.getJSONObject("bigIsland").getJSONObject("leading").getString("animated"))
    assertTrue(payload.toString().toByteArray(Charsets.UTF_8).size < 8192)
  }

  @Test fun preparationHasOnlyNativeFeedbackAndRetainsTheTransparentOwnerAndBack() {
    val state = AssistantState(voicePreparation = true)
    val card = assistantEntryCard(state, "system_hold_1234")!!
    assertEquals("Getting ready…", card.body)
    assertEquals(listOf("cancel" to "Cancel"), card.buttons)
    assertEquals("preparation", assistantEntryBackAction(state))
    assertTrue("the owner must not finish just because Compose draws no modal", state.overlayVisible)
    assertFalse(assistantEntryUsesFallback(state, true))
    assertTrue(assistantEntryUsesFallback(state, false))
    assertEquals("message", card.payload().getJSONObject("expanded").getJSONObject("assistant").getString("phase"))
    assertNull(assistantEntryCard(state, null))
  }

  @Test fun startingHasNoWaitingCardAndCannotExposeAnOlderReply() {
    val state = character(CharacterTryOnPhase.Starting).copy(
      turn = TurnView("old", "old question", false, false, "old reply", "old reply", emptyList(), emptyList()))
    assertNull(assistantEntryCard(state, null))
    assertTrue("Starting still suppresses the ordinary notification branch", assistantEntryOwnsPresentation(state))
    assertTrue("source owner stays alive until handoff or cancel", state.overlayVisible)
    assertFalse(assistantEntryUsesFallback(state, true))
    assertFalse(assistantEntryUsesFallback(state, false))
    assertEquals("character", assistantEntryBackAction(state))
  }

  @Test fun failedAndCheckingCardsCannotStartAPreview() {
    for (phase in listOf(CharacterTryOnPhase.Checking, CharacterTryOnPhase.Failed)) {
      val state = character(phase)
      val card = assistantEntryCard(state, null)!!
      assertEquals(listOf("cancel"), card.buttons.map { it.first })
      assertEquals(if (phase == CharacterTryOnPhase.Failed) "Close" else "Not now", card.buttons.single().second)
      assertEquals(if (phase == CharacterTryOnPhase.Failed) "Synthetic failure" else "Getting ready…", card.body)
      assertFalse(dispatch(state, "character", "request_1234", "confirm").first)
      assertTrue(assistantEntryUsesFallback(state, false))
      assertFalse(assistantEntryUsesFallback(state, true))
    }
  }

  @Test fun nativeActionsUseTheCurrentRequestAndNeverTreatBodyOrExpandAsTryOn() {
    val state = character(CharacterTryOnPhase.Confirming)
    assertEquals(true to listOf("confirm:request_1234"), dispatch(state, "character", "request_1234", "confirm"))
    assertEquals(true to listOf("cancel:request_1234"), dispatch(state, "character", "request_1234", "cancel"))
    assertEquals(true to listOf("expand:entry:character:request_1234"), dispatch(state, "character", "request_1234", "open"))
    for ((kind, id, action) in listOf(Triple("character", "old_request", "confirm"), Triple("preparation", "request_1234", "confirm"), Triple("character", "request_1234", "unknown"))) {
      assertEquals(false to emptyList<String>(), dispatch(state, kind, id, action))
    }
    assertEquals(false to emptyList<String>(), dispatch(AssistantState(), "character", "request_1234", "confirm"))
    assertEquals(false to emptyList<String>(), dispatch(character(CharacterTryOnPhase.Starting), "character", "request_1234", "confirm"))
    assertEquals(false to emptyList<String>(), dispatch(character(CharacterTryOnPhase.Failed), "character", "request_1234", "confirm"))
  }

  @Test fun preparingActionsRejectOldGesturesAndCannotActivateAMicrophone() {
    val state = AssistantState(voicePreparation = true)
    assertEquals(true to listOf("prepare-cancel:system_hold_1234"), dispatch(state, "preparation", "system_hold_1234", "cancel"))
    assertEquals(true to listOf("expand:entry:preparation:system_hold_1234"), dispatch(state, "preparation", "system_hold_1234", "open"))
    assertEquals(false to emptyList<String>(), dispatch(state, "preparation", "old_gesture", "cancel"))
    assertEquals(false to emptyList<String>(), dispatch(state, "preparation", "system_hold_1234", "confirm"))
    assertEquals(false to emptyList<String>(), dispatch(AssistantState(), "preparation", "system_hold_1234", "cancel"))
  }

  @Test fun pendingIntentIdentitySeparatesRequestsAndOperations() {
    val card = assistantEntryCard(character(CharacterTryOnPhase.Confirming), null)!!
    val next = assistantEntryCard(character(CharacterTryOnPhase.Confirming).copy(characterTryOn = CharacterTryOnState("new_request_1234", CharacterTryOnPhase.Confirming)), null)!!
    assertNotEquals(card.actionIdentity("confirm"), card.actionIdentity("cancel"))
    assertNotEquals(card.actionIdentity("confirm"), next.actionIdentity("confirm"))
    assertNotEquals(card.token, next.token)
  }

  private fun character(phase: CharacterTryOnPhase) = AssistantState(
    characterTryOn = CharacterTryOnState("request_1234", phase, "Synthetic failure"))

  private fun dispatch(state: AssistantState, kind: String, id: String, action: String): Pair<Boolean, List<String>> {
    val called = mutableListOf<String>()
    val accepted = dispatchAssistantEntryAction(state, "system_hold_1234", kind, id, action,
      { called += "confirm:$it" }, { called += "cancel:$it" },
      { called += "prepare-cancel:$it" }, { called += "expand:$it" })
    return accepted to called
  }

  private fun assertNoListening(state: AssistantState, voice: VoiceState) {
    assertFalse(voice.ready)
    assertFalse(assistantCardListening(state, voice))
    assertFalse(assistantCardHeadline(state, voice).contains("listening", ignoreCase = true))
    assertFalse(assistantCardTitle(state, voice).contains("listening", ignoreCase = true))
    assertNotEquals("listening", assistantCardAnimation(state, voice))
    assertEquals("message", assistantCardPhase(state, voice))
  }
}
