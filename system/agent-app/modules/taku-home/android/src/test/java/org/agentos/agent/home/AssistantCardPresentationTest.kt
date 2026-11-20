package org.agentos.agent.home

import org.agentos.agent.home.assistant.*
import org.agentos.agent.home.island.assistantCardHeadline
import org.agentos.agent.home.island.assistantCardListening
import org.junit.Assert.*
import org.junit.Test

class AssistantCardPresentationTest {
  @Test fun holdSaysListeningOnlyOnceTheMicrophoneRecords() {
    val hold = AssistantState(entry = EntryMode.Voice)
    val preparing = VoiceState(operationId = 1, target = VoiceTarget.Hold, phase = VoicePhase.Preparing)
    assertEquals("One moment…", assistantCardHeadline(hold, preparing))
    assertEquals("Taku is listening...", assistantCardHeadline(hold, preparing.copy(phase = VoicePhase.Capturing)))
  }
  @Test fun holdEntryShowsOnlyListeningTextEvenWithAnOlderReply() {
    val old = TurnView("old", "old headline", false, false, "old question", "old reply", emptyList(), emptyList())
    assertEquals("Taku is listening...", assistantCardHeadline(AssistantState(entry = EntryMode.Voice, turn = old), VoiceState()))
  }
  @Test fun completedTurnUsesTheActualReplyInsteadOfAHeadlineOrGreeting() {
    val turn = TurnView("turn", "summary", false, false, "question", "The actual agent response.", emptyList(), emptyList())
    assertEquals("The actual agent response.", assistantCardHeadline(AssistantState(turn = turn), VoiceState()))
  }
  @Test fun emptyFinalKeepsListeningAndNeverReusesAnOlderReply() {
    val old = TurnView("old", "old headline", false, false, "old question", "old reply", emptyList(), emptyList())
    val empty = VoiceState(operationId = 1, target = VoiceTarget.Hold, phase = VoicePhase.Closed,
      outcome = VoiceOutcome.Success("  "))
    val state = AssistantState(entry = EntryMode.Voice, turn = old)
    assertTrue(assistantCardListening(state, empty))
    assertEquals("Taku is listening...", assistantCardHeadline(state, empty))
    assertFalse(empty.copy(outcome = VoiceOutcome.Failed("NETWORK_FAILED")).emptySuccess)
  }
  @Test fun retainedHoldShowsProcessingThenTheReplyRatherThanListening() {
    val finished = VoiceState(operationId = 1, target = VoiceTarget.Hold,
      phase = VoicePhase.Closed, outcome = VoiceOutcome.Success("question"))
    val working = AssistantState(entry = EntryMode.Voice, sending = true, pending = "question")
    assertFalse(assistantCardListening(working, finished))
    assertEquals("“question”", assistantCardHeadline(working, finished))
    val turn = TurnView("turn", "summary", false, false, "question", "Actual reply", emptyList(), emptyList())
    val replied = AssistantState(entry = EntryMode.Voice, turn = turn)
    assertFalse("real suggestions must not be hidden on the retained page", assistantCardListening(replied, finished))
    assertEquals("Actual reply", assistantCardHeadline(replied, finished))
  }
  @Test fun emptyReplyDoesNotHideAnActualHeadline() {
    val turn = TurnView("turn", "question", false, false, "Actual status", "", emptyList(), emptyList())
    assertEquals("Actual status", assistantCardHeadline(AssistantState(turn = turn), VoiceState()))
  }
}
