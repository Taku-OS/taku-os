package org.agentos.agent.home.assistant

import org.junit.Assert.*
import org.junit.Test

class CandidateRequestOwnershipTest {
  @Test fun newInvocationDoesNotCarryOldOptionsOrTheirSelection() {
    val options = ReplyOptions("reply-options", "options", "turn", 1L, "a".repeat(64), "Restaurants", "",
      listOf(ReplyOption("option-2", "Observed restaurant", "", "")))
    val turn = TurnView(turnId = "turn", userText = null, active = false, failed = false, headline = null,
      reply = null, steps = emptyList(), suggestions = emptyList(), options = options)
    val state = AssistantState(threadId = "thread", turn = turn,
      island = IslandMode.Expanded, optionsItemId = "option-2")
    assertTrue(state.optionsVisible)
    assertEquals("reply-options", state.optionsFor("reply-options").replyId)
    assertFalse("The Island survives a missing overlay acknowledgement", candidateOwnsResultPresentation(state, null))
    val visible = state.candidatePresentationIdentity
    assertNotNull(visible)
    assertTrue(candidateOwnsResultPresentation(state, visible))
    assertFalse(candidateOwnsResultPresentation(state.copy(requestGeneration = 1), visible))
    assertFalse(candidateOwnsResultPresentation(state.copy(island = IslandMode.Hidden), visible))
    assertFalse(candidateOwnsResultPresentation(state.copy(sending = true), visible))
    val fresh = state.copy(requestGeneration = state.requestGeneration + 1, turn = null, optionsItemId = null)
    assertNull(fresh.options); assertNull(fresh.optionsItemId); assertFalse(fresh.optionsVisible)
    try { fresh.optionsFor("reply-options"); fail("Old options remained actionable") }
    catch (expected: AssistantException) { assertEquals("REQUEST_EXPIRED", expected.code) }
  }

  @Test fun aLateRetryOnlyClearsItsOwnBusyState() {
    val old = AssistantState(sending = true, pollRetryOperation = "old")
    val changed = old.copy(requestGeneration = 1, threadId = "different")
    assertFalse(changed.finishPollRetry("old").sending)
    assertNull(changed.finishPollRetry("old").pollRetryOperation)
    val newer = changed.copy(pollRetryOperation = "newer")
    assertEquals(newer, newer.finishPollRetry("old"))
    val ordinarySend = changed.copy(pollRetryOperation = null)
    assertEquals(ordinarySend, ordinarySend.finishPollRetry("old"))
  }
}
