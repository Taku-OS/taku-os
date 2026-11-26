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
    assertEquals("no listening cue before the microphone records", "", assistantCardHeadline(hold, preparing))
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
  // BUG-0028: a new hold must never bring back the previous finished reply.
  @Test fun aHoldSupersedesOnlyAFinishedTurn() {
    val done = TurnView("old", "old question", false, false, null, "old reply", emptyList(), emptyList())
    assertTrue(AssistantState(turn = done, task = "completed").holdSupersedesTurn)
    assertTrue("a failed turn's text is not the new hold's answer either",
      AssistantState(turn = done.copy(failed = true), error = "TURN_FAILED").holdSupersedesTurn)
    assertFalse(AssistantState(turn = done.copy(active = true)).holdSupersedesTurn)
    assertFalse(AssistantState(turn = done, task = "running").holdSupersedesTurn)
    assertFalse(AssistantState(turn = done, task = "needs_user").holdSupersedesTurn)
    assertFalse(AssistantState(turn = done, sending = true).holdSupersedesTurn)
    assertFalse(AssistantState().holdSupersedesTurn)
  }
  @Test fun aHoldThatEndsWithoutSendingShowsNoOlderReply() {
    // The previous operation's closed state is still current while the new hold starts, and is
    // the state left by a cancelled or failed hold; the superseded turn is gone from the session.
    val done = TurnView("old", "old question", false, false, null, "old reply", emptyList(), emptyList())
    val before = AssistantState(entry = EntryMode.Voice, turn = done, task = "completed")
    assertTrue(before.holdSupersedesTurn)
    val hold = before.copy(turn = null) // what starting the hold leaves (AssistantSession.supersedeSettledTurn)
    for (outcome in listOf(VoiceOutcome.Cancelled("released"), VoiceOutcome.Success("previous question"))) {
      val closed = VoiceState(operationId = 1, target = VoiceTarget.Hold, phase = VoicePhase.Closed, outcome = outcome)
      assertEquals("old reply", assistantCardHeadline(before, closed)) // the reported symptom
      assertFalse("old reply shown for $outcome", assistantCardHeadline(hold, closed).contains("old reply"))
    }
  }
  @Test fun emptyReplyDoesNotHideAnActualHeadline() {
    val turn = TurnView("turn", "question", false, false, "Actual status", "", emptyList(), emptyList())
    assertEquals("Actual status", assistantCardHeadline(AssistantState(turn = turn), VoiceState()))
  }
}
