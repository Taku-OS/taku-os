package org.agentos.agent.home

import org.agentos.agent.home.assistant.CharacterTextInvocation
import org.agentos.agent.home.assistant.CharacterTryOnPhase
import org.junit.Assert.*
import org.junit.Test
import org.taku.cosplay.api.CosplayContract

class CharacterTextInvocationTest {
  @Test fun onlyTheReservedCaptureCanBecomeAnActiveTextEntry() {
    val lease = CharacterTextInvocation()
    assertFalse(lease.activate("made_up_entry", 1_000))
    assertTrue(lease.reserve("system_text_1", 1_000, 1_000))
    assertFalse(lease.activate("system_text_1", 1_010))
    assertFalse(lease.ready("another_entry", 1_010))
    assertTrue(lease.pending("system_text_1"))
    assertTrue(lease.ready("system_text_1", 1_010))
    assertFalse(lease.pending("system_text_1"))
    assertTrue(lease.activate("system_text_1", 1_020))
    assertTrue(lease.activate("system_text_1", 1_030)) // Activity recreation, not a new capture.
    assertTrue(lease.active(1_030))
  }

  @Test fun cancellationAndNewInvocationsRejectLateCallbacksAndActivities() {
    val lease = CharacterTextInvocation()
    lease.reserve("system_text_1", 1_000, 1_000)
    lease.clear()
    assertFalse(lease.ready("system_text_1", 1_001))
    assertFalse(lease.activate("system_text_1", 1_001))
    lease.reserve("system_text_2", 1_002, 1_002)
    assertFalse(lease.ready("system_text_1", 1_003))
    assertFalse(lease.matches("system_text_1", 1_003))
    assertTrue(lease.ready("system_text_2", 1_004))
    assertTrue(lease.activate("system_text_2", 1_004))
  }

  @Test fun aSubmittedEntryRemainsOwnedForConfirmationButCannotSendOrOpenAgain() {
    val lease = CharacterTextInvocation()
    assertTrue(lease.transfer("system_hold_1", 1_000, 1_050))
    assertTrue(lease.submit(1_060))
    assertFalse(lease.active(1_060))
    assertFalse(lease.submit(1_061))
    assertFalse(lease.activate("system_hold_1", 1_061))
    assertTrue(lease.matches("system_hold_1", 1_061))
    lease.clear()
    assertFalse(lease.matches("system_hold_1", 1_061))
  }

  @Test fun editingAndRecreatingTextCannotRenewTheOriginalDeadline() {
    val lease = CharacterTextInvocation()
    val end = 1_000 + CosplayContract.MAX_INVOCATION_AGE_MS
    assertTrue(lease.transfer("system_hold_1", 1_000, 2_000))
    assertTrue(lease.activate("system_hold_1", end))
    assertTrue(lease.active(end))
    assertFalse(lease.matches("system_hold_1", end + 1))
    assertFalse(lease.activate("system_hold_1", end + 1))
    assertFalse(lease.submit(end + 1))
    assertFalse(lease.reserve("system_text_1", 2_000, 1_999))
    assertFalse(lease.reserve("bad", 2_000, 2_000))
  }

  @Test fun unavailableCheckingNeverClosesAndReopensTheTextInput() {
    val lease = CharacterTextInvocation()
    lease.transfer("system_text_1", 1_000, 1_000)
    assertFalse(lease.closesTextInput(CharacterTryOnPhase.Checking))
    lease.submit(1_001)
    assertFalse(lease.closesTextInput(CharacterTryOnPhase.Checking))
    // An unavailable status clears the character state and returns the text to the generic sender.
    assertFalse(lease.closesTextInput(null))
    lease.clear()
    assertFalse(lease.closesTextInput(null))
  }

  @Test fun onlyAnOwnedConfirmationOrFailureReplacesTextInput() {
    val lease = CharacterTextInvocation()
    lease.transfer("system_text_1", 1_000, 1_000)
    assertFalse(lease.closesTextInput(CharacterTryOnPhase.Confirming))
    lease.submit(1_001)
    assertFalse(lease.closesTextInput(CharacterTryOnPhase.Checking))
    assertTrue(lease.closesTextInput(CharacterTryOnPhase.Confirming))
    assertTrue(lease.closesTextInput(CharacterTryOnPhase.Starting))
    assertTrue(lease.closesTextInput(CharacterTryOnPhase.Failed))
  }
}
