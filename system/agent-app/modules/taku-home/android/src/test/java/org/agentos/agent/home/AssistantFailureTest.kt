package org.agentos.agent.home

import org.agentos.agent.home.assistant.AssistantState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** Issue #25 F3: a failed request comes back for edit / retry without overwriting a newer draft. */
class AssistantFailureTest {
  @Test fun microphonePermissionIsDistinctFromDisabledVoicePolicy() {
    assertEquals("VOICE_PERMISSION_REQUIRED", org.agentos.agent.home.assistant.AssistantSession.voiceError("PERMISSION_DENIED"))
    assertEquals("VOICE_DISABLED", org.agentos.agent.home.assistant.AssistantSession.voiceError("POLICY_DISABLED"))
    assertEquals("VOICE_BUSY", org.agentos.agent.home.assistant.AssistantSession.voiceError("RESOURCE_BUSY"))
  }
  @Test fun asyncTaskFailureRestoresTheRequestAsDraft() {
    val s = AssistantState(pending = "Find a flight to Kyoto", task = "running").failedWith("TASK_FAILED", "Find a flight to Kyoto")
    assertEquals("TASK_FAILED", s.error)
    assertEquals("Find a flight to Kyoto", s.draft)
    assertEquals("Find a flight to Kyoto", s.failedText)
    assertNull(s.pending)
    assertFalse(s.sending)
    assertEquals("Find a flight to Kyoto", s.retryText)
  }

  @Test fun aNewerDraftIsNeverOverwritten() {
    val s = AssistantState(pending = "first", draft = "second thought").failedWith("TASK_FAILED", "first")
    assertEquals("second thought", s.draft)
    assertEquals("first", s.failedText)
    assertEquals("second thought", s.retryText)
  }

  @Test fun clearedDraftFallsBackToTheFailedRequest() {
    val s = AssistantState().failedWith("TURN_FAILED", "  hello  ").copy(draft = "")
    assertEquals("hello", s.retryText)
  }

  @Test fun unknownTextKeepsEarlierFailure() {
    val s = AssistantState(failedText = "earlier").failedWith("TASK_FAILED", null)
    assertEquals("earlier", s.failedText)
    assertEquals("", s.draft)
  }

  // Issue #25 F3 follow-up: offline past the grace period ends "working" with the text restored.
  @Test fun givingUpOfflineEndsWorkingAndRestoresTheRequest() {
    val running = AssistantState(threadId = "t", taskId = "k", task = "running", pending = "Find a flight", online = false)
    check(running.working)
    val gaveUp = running.failedWith("NETWORK_UNAVAILABLE", running.pending).copy(abandoned = true)
    assertFalse(gaveUp.working)
    assertEquals("NETWORK_UNAVAILABLE", gaveUp.error)
    assertEquals("Find a flight", gaveUp.draft)
    assertEquals("Find a flight", gaveUp.retryText)
    // The broker still reports the task running (it never fails offline): still not working.
    assertFalse(gaveUp.copy(task = "running").working)
  }

  @Test fun anAbandonedActiveTurnIsNotWorkingAndKeepsANewerDraft() {
    val turn = org.agentos.agent.home.assistant.TurnView(turnId = "u1", active = true, failed = false, userText = "Book it", headline = null, reply = null, steps = emptyList(), suggestions = emptyList())
    val s = AssistantState(turn = turn, draft = "something newer").failedWith("NETWORK_UNAVAILABLE", turn.userText).copy(abandoned = true)
    assertFalse(s.working)
    assertEquals("something newer", s.draft)
    assertEquals("Book it", s.failedText)
  }

  @Test fun aNewSendClearsAbandoned() {
    val s = AssistantState(abandoned = true, error = "NETWORK_UNAVAILABLE").copy(sending = true, pending = "again", error = null, abandoned = false)
    check(s.working)
  }
}
