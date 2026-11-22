package org.agentos.agent.home

import org.agentos.agent.home.assistant.*
import org.agentos.agent.home.island.assistantCardHeadline
import org.agentos.agent.home.island.assistantCardListening
import org.agentos.agent.home.island.assistantCardTitle
import org.agentos.agent.home.island.assistantCardAnimation
import org.agentos.agent.home.island.assistantCardPhase
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

  private fun assertNoListening(state: AssistantState, voice: VoiceState) {
    assertFalse(voice.ready)
    assertFalse(assistantCardListening(state, voice))
    assertFalse(assistantCardHeadline(state, voice).contains("listening", ignoreCase = true))
    assertFalse(assistantCardTitle(state, voice).contains("listening", ignoreCase = true))
    assertNotEquals("listening", assistantCardAnimation(state, voice))
    assertEquals("message", assistantCardPhase(state, voice))
  }
}
