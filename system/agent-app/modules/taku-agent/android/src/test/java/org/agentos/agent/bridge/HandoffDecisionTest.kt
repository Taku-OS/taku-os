package org.agentos.agent.bridge

import org.agentos.agent.bridge.HandoffDecision.Skip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HandoffDecisionTest {
  private val running = HandoffDecision.Candidate("thread", "turn", null)

  private fun leave(changing: Boolean = false, interactive: Boolean = true, picker: Boolean = false,
                    lastResumed: String? = HandoffDecision.CONVERSATION) =
    HandoffDecision.leave(changing, interactive, picker, lastResumed)

  @Test fun homeBackAndAnotherAppAreALeave() {
    assertNull(leave(lastResumed = HandoffDecision.HOME)) // Home gesture, or Back onto Home
    assertNull(leave(lastResumed = HandoffDecision.CONVERSATION)) // Recents into another app
    assertNull(leave(lastResumed = null))
  }

  @Test fun eachSkipRule() {
    assertEquals(Skip.CONFIGURATION_CHANGE, leave(changing = true))
    assertEquals(Skip.SCREEN_OFF, leave(interactive = false))
    assertEquals(Skip.PICKER_OPEN, leave(picker = true))
    assertEquals(Skip.OWN_ACTIVITY, leave(lastResumed = "org.agentos.agent.home.onboarding.ServiceSetupActivity"))
  }

  @Test fun onlyTheChatsOwnSendInFlightKeepsALeavePending() {
    // Retest of f6a0913f: Home pressed while the message was still being sent.
    assertEquals(1_000L + HandoffDecision.PENDING_LEAVE_MS, HandoffDecision.pendingUntil(null, null, sending = true, now = 1_000L))
    // Review #3: nothing running when the user left, so nothing started later pops a card.
    assertEquals(0L, HandoffDecision.pendingUntil(null, null, sending = false, now = 1_000L))
    assertEquals("already handed off at the leave", 0L, HandoffDecision.pendingUntil(null, running, sending = true, now = 1_000L))
    assertEquals(0L, HandoffDecision.pendingUntil(Skip.SCREEN_OFF, null, sending = true, now = 1_000L))
  }

  @Test fun aPendingLeaveHandsOffOnceWhileHiddenAndInTime() {
    val until = 61_000L
    assertTrue(HandoffDecision.handOffLater(running, until, now = 30_000L, visible = false, handedOffThread = null))
    assertFalse("only once per conversation", HandoffDecision.handOffLater(running, until, 30_000L, false, "thread"))
    assertTrue(HandoffDecision.handOffLater(running, until, 30_000L, false, "other"))
    assertFalse("the chat is back", HandoffDecision.handOffLater(running, until, 30_000L, true, null))
    assertFalse("too late", HandoffDecision.handOffLater(running, until, 61_001L, false, null))
    assertFalse("no pending leave", HandoffDecision.handOffLater(running, 0L, 30_000L, false, null))
    assertFalse(HandoffDecision.handOffLater(null, until, 30_000L, false, null))
  }

  @Test fun anUnavailableHomeIsRetriedButABusyOneIsFinal() {
    // Review #4.
    assertTrue(HandoffDecision.retry("HOME_UNAVAILABLE", 1))
    assertTrue(HandoffDecision.retry("TIMEOUT", 2))
    assertFalse(HandoffDecision.retry("HOME_UNAVAILABLE", HandoffDecision.MAX_ATTEMPTS))
    assertFalse(HandoffDecision.retry("HOME_BUSY", 1))
    assertFalse(HandoffDecision.retry("INVALID_ARGUMENT", 1))
  }

  @Test fun javascriptEmptyStringsMeanNone() {
    assertNull(HandoffDecision.candidate(" ", "turn", "task"))
    assertEquals(HandoffDecision.Candidate("thread", null, "7"), HandoffDecision.candidate("thread", "", "7"))
    assertEquals(HandoffDecision.Candidate("thread", null, null, "[\"t1\"]"), HandoffDecision.candidate("thread", "", "", "[\"t1\"]"))
    assertEquals(HandoffDecision.Candidate("thread", "turn", null), HandoffDecision.candidate(" thread ", "turn", " "))
  }
}
