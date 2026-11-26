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
    val fresh = state.copy(requestGeneration = state.requestGeneration + 1, turn = null, optionsItemId = null)
    assertNull(fresh.options); assertNull(fresh.optionsItemId); assertFalse(fresh.optionsVisible)
    try { fresh.optionsFor("reply-options"); fail("Old options remained actionable") }
    catch (expected: AssistantException) { assertEquals("REQUEST_EXPIRED", expected.code) }
  }
}
