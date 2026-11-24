package org.agentos.agent.bridge

import org.agentos.agent.bridge.HandoffDecision.Skip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HandoffDecisionTest {
  private val running = HandoffDecision.Candidate("thread", "turn", null)

  private fun decide(candidate: HandoffDecision.Candidate? = running, changing: Boolean = false, interactive: Boolean = true,
                     picker: Boolean = false, lastResumed: String? = HandoffDecision.CONVERSATION) =
    HandoffDecision.decide(candidate, changing, interactive, picker, lastResumed)

  @Test fun homeBackAndAnotherAppHandOff() {
    assertNull(decide(lastResumed = HandoffDecision.HOME)) // Home gesture, or Back onto Home
    assertNull(decide(lastResumed = HandoffDecision.CONVERSATION)) // Recents into another app
    assertNull(decide(lastResumed = null))
  }

  @Test fun eachSkipRule() {
    assertEquals(Skip.NO_CANDIDATE, decide(candidate = null))
    assertEquals(Skip.CONFIGURATION_CHANGE, decide(changing = true))
    assertEquals(Skip.SCREEN_OFF, decide(interactive = false))
    assertEquals(Skip.PICKER_OPEN, decide(picker = true))
    assertEquals(Skip.OWN_ACTIVITY, decide(lastResumed = "org.agentos.agent.home.onboarding.ServiceSetupActivity"))
  }

  @Test fun javascriptEmptyStringsMeanNone() {
    assertNull(HandoffDecision.candidate(" ", "turn", "task"))
    assertEquals(HandoffDecision.Candidate("thread", null, "7"), HandoffDecision.candidate("thread", "", "7"))
    assertEquals(HandoffDecision.Candidate("thread", "turn", null), HandoffDecision.candidate(" thread ", "turn", " "))
  }
}
