package org.agentos.agent.home

import org.agentos.agent.home.assistant.CharacterVoiceHandoff
import org.junit.Assert.*
import org.junit.Test

class CharacterVoiceHandoffTest {
  @Test fun onlyTheBoundFirstRecordingCanClaimTheCharacterReference() {
    val handoff = CharacterVoiceHandoff()
    assertTrue(handoff.begin("system_hold_1", 121_000, 1_000))
    assertTrue(handoff.bind("system_hold_1", 7, 1_001))
    assertFalse(handoff.bind("system_hold_1", 8, 1_002))
    assertEquals(CharacterVoiceHandoff.Decision.Ignore, handoff.classify("another_hold", 7, "帮我换成这个角色", 1_010))
    assertEquals(CharacterVoiceHandoff.Decision.Ignore, handoff.classify("system_hold_1", 8, "帮我换成这个角色", 1_010))
    assertTrue(handoff.awaiting())
    assertEquals(CharacterVoiceHandoff.Decision.Character, handoff.classify("system_hold_1", 7, "帮我换成这个角色", 1_010))
    assertFalse(handoff.awaiting())
    assertTrue(handoff.claimed("system_hold_1", 1_010))
    assertEquals(CharacterVoiceHandoff.Decision.Ignore, handoff.classify("system_hold_1", 7, "帮我换成这个角色", 1_011))
  }

  @Test fun ordinarySpeechReleasesTheReferenceForTheExistingLivePath() {
    val handoff = CharacterVoiceHandoff()
    handoff.begin("system_hold_1", 121_000, 1_000)
    handoff.bind("system_hold_1", 7, 1_001)
    assertEquals(CharacterVoiceHandoff.Decision.Live, handoff.classify("system_hold_1", 7, "What is this video about?", 1_010))
    assertFalse(handoff.eligible("system_hold_1", 1_010))
    assertFalse(handoff.awaiting())
  }

  @Test fun silenceExpiryAndCancellationCannotBecomeLiveOrCharacterRequests() {
    val handoff = CharacterVoiceHandoff()
    handoff.begin("system_hold_1", 2_000, 1_000)
    handoff.bind("system_hold_1", 7, 1_001)
    assertEquals(CharacterVoiceHandoff.Decision.Drop, handoff.classify("system_hold_1", 7, "", 1_010))
    handoff.begin("system_hold_2", 2_000, 1_000)
    handoff.bind("system_hold_2", 8, 1_001)
    assertEquals(CharacterVoiceHandoff.Decision.Drop, handoff.classify("system_hold_2", 8, "帮我换成这个角色", 2_001))
    handoff.begin("system_hold_3", 2_000, 1_000)
    handoff.bind("system_hold_3", 9, 1_001)
    handoff.clear()
    assertEquals(CharacterVoiceHandoff.Decision.Ignore, handoff.classify("system_hold_3", 9, "帮我换成这个角色", 1_010))
  }

  @Test fun aNewGestureAndLateOperationCannotRenewTheOriginalLease() {
    val handoff = CharacterVoiceHandoff()
    handoff.begin("system_hold_1", 2_000, 1_000)
    assertTrue(handoff.bind("system_hold_1", 7, 1_500))
    assertTrue(handoff.expired(2_001))
    assertFalse(handoff.bind("system_hold_1", 7, 2_001))
    handoff.begin("system_hold_2", 3_000, 2_001)
    assertFalse(handoff.eligible("system_hold_1", 2_001))
    assertEquals(CharacterVoiceHandoff.Decision.Ignore, handoff.classify("system_hold_1", 7, "帮我换成这个角色", 2_002))
    assertFalse(handoff.begin("bad", 3_000, 2_002))
    assertFalse(handoff.begin("system_hold_3", 2_000, 2_001))
  }

  @Test fun leavingAClaimedOverlayCancelsOnlyItsOwnInvocation() {
    val handoff = CharacterVoiceHandoff()
    handoff.begin("system_hold_1", 121_000, 1_000)
    handoff.bind("system_hold_1", 7, 1_001)
    handoff.classify("system_hold_1", 7, "帮我换成这个角色", 1_010)
    assertTrue(handoff.cancelOnSurfaceStop("system_hold_1", false, 1_011))
    assertFalse(handoff.cancelOnSurfaceStop("system_hold_1", true, 1_011))
    assertFalse(handoff.cancelOnSurfaceStop("another_hold", false, 1_011))
    handoff.clear() // Core handoff or an explicit close has already released this lease.
    assertFalse(handoff.cancelOnSurfaceStop("system_hold_1", false, 1_012))
    handoff.begin("system_hold_2", 121_000, 1_013)
    handoff.bind("system_hold_2", 8, 1_013)
    handoff.classify("system_hold_2", 8, "帮我换成这个角色", 1_014)
    assertFalse(handoff.cancelOnSurfaceStop("system_hold_1", false, 1_015))
    assertTrue(handoff.cancelOnSurfaceStop("system_hold_2", false, 1_015))
  }
}
