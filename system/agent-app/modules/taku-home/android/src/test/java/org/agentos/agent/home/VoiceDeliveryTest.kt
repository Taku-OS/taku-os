package org.agentos.agent.home

import org.agentos.agent.home.assistant.VoiceDeliveryMachine
import org.agentos.agent.home.assistant.VoiceDestination
import org.agentos.agent.home.assistant.VoiceEffect
import org.agentos.agent.home.assistant.VoiceOutcome
import org.agentos.agent.home.assistant.VoicePhase
import org.agentos.agent.home.assistant.VoiceState
import org.agentos.agent.home.assistant.VoiceTarget
import org.agentos.agent.home.assistant.settleFinishDeadline
import org.agentos.agent.home.assistant.voiceOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** One recording, one delivered result: the AI entry's gesture and Engine callbacks are races. */
class VoiceDeliveryTest {
  private fun active(operation: Long, phase: VoicePhase = VoicePhase.Capturing, preview: String = "", target: VoiceTarget = VoiceTarget.Dictation) =
    VoiceState(operationId = operation, target = target, phase = phase, preview = preview)

  private fun closed(operation: Long, outcome: VoiceOutcome, preview: String = "", target: VoiceTarget = VoiceTarget.Dictation) =
    VoiceState(operationId = operation, target = target, phase = VoicePhase.Closed, preview = preview, outcome = outcome)

  @Test fun tailAfterTheTextGestureJoinsTheDraftOnceAndKeepsLaterEdits() {
    val delivery = VoiceDeliveryMachine(200)
    delivery.begin(7, VoiceDestination.Draft)
    assertEquals(VoiceEffect.None, delivery.settle(active(7, preview = "flight to"), "book a"))
    // The engine finishes after the gesture; the draft was edited while the tail was recognised.
    val effect = delivery.settle(closed(7, VoiceOutcome.Success("flight to Tokyo"), "flight to Tokyo"), "book a seat for")
    assertEquals(VoiceEffect.Draft("book a seat for flight to Tokyo"), effect)
    assertEquals("a repeated terminal delivers nothing", VoiceEffect.None, delivery.settle(closed(7, VoiceOutcome.Success("flight to Tokyo")), "book a seat for"))
  }

  @Test fun successBeforeReleaseWaitsForTheGestureThenSendsOnce() {
    val delivery = VoiceDeliveryMachine(200)
    delivery.begin(8, VoiceDestination.AwaitGesture)
    val done = closed(8, VoiceOutcome.Success("book it"), target = VoiceTarget.Hold)
    assertEquals("a completed recording is not a gesture", VoiceEffect.None, delivery.settle(done, ""))
    delivery.aim(VoiceDestination.Submit)
    assertEquals(VoiceEffect.Send("book it"), delivery.settle(done, ""))
    assertEquals(VoiceEffect.None, delivery.settle(done, ""))
  }

  @Test fun textReleaseCanStillChooseTheDraftForACompletedRecording() {
    val delivery = VoiceDeliveryMachine(200)
    delivery.begin(9, VoiceDestination.AwaitGesture)
    val done = closed(9, VoiceOutcome.Success("check the flight"), target = VoiceTarget.Hold)
    assertEquals(VoiceEffect.None, delivery.settle(done, ""))
    delivery.aim(VoiceDestination.Draft)
    assertEquals(VoiceEffect.Draft("draft check the flight"), delivery.settle(done, "draft"))
  }

  @Test fun aRepeatedFinishAfterTextCannotTurnTheResultIntoASend() {
    val delivery = VoiceDeliveryMachine(200)
    delivery.begin(10, VoiceDestination.AwaitGesture)
    delivery.aim(VoiceDestination.Draft)
    assertEquals(VoiceEffect.None, delivery.settle(active(10, VoicePhase.Finalizing, target = VoiceTarget.Hold), "edited"))
    delivery.aim(VoiceDestination.Submit)
    val done = closed(10, VoiceOutcome.Success("complete tail"), target = VoiceTarget.Hold)
    assertEquals(VoiceEffect.Draft("edited complete tail"), delivery.settle(done, "edited"))
    assertEquals(VoiceEffect.None, delivery.settle(done, "edited complete tail"))
  }

  @Test fun cancellationRevokesTheResultEvenIfSuccessArrivesLater() {
    val delivery = VoiceDeliveryMachine(200)
    delivery.begin(4, VoiceDestination.Draft)
    delivery.revoke()
    assertEquals(VoiceEffect.None, delivery.settle(closed(4, VoiceOutcome.Success("too late")), ""))
  }

  @Test fun aNewConversationCannotReceiveTheOldRecording() {
    val delivery = VoiceDeliveryMachine(200)
    delivery.begin(4, VoiceDestination.Draft)
    delivery.newGeneration()
    delivery.begin(5, VoiceDestination.Draft)
    assertEquals(VoiceEffect.None, delivery.settle(closed(4, VoiceOutcome.Success("old words")), ""))
    assertEquals(VoiceEffect.Draft("old words new words"), delivery.settle(closed(5, VoiceOutcome.Success("new words")), "old words"))
  }

  @Test fun aFailedRecordingNeverDeliversItsPartialText() {
    val delivery = VoiceDeliveryMachine(200)
    delivery.begin(6, VoiceDestination.Submit)
    assertEquals(
      VoiceEffect.Failed("NETWORK_FAILED"),
      delivery.settle(closed(6, VoiceOutcome.Failed("NETWORK_FAILED"), preview = "half a sentence"), ""),
    )
    assertEquals("the failure was already consumed", VoiceEffect.None, delivery.settle(closed(6, VoiceOutcome.Success("half a sentence")), ""))
  }

  @Test fun aFailureReachesTheIslandWhileTheFingerIsStillDown() {
    val delivery = VoiceDeliveryMachine(200)
    delivery.begin(6, VoiceDestination.AwaitGesture)
    assertEquals(VoiceEffect.Failed("POLICY_REVOKED"), delivery.settle(closed(6, VoiceOutcome.Failed("POLICY_REVOKED"), target = VoiceTarget.Hold), ""))
  }

  @Test fun aCancelledHoldClosesAndACancelledDictationKeepsTheDraft() {
    val hold = VoiceDeliveryMachine(200)
    hold.begin(3, VoiceDestination.AwaitGesture)
    assertEquals(VoiceEffect.CloseEntry, hold.settle(closed(3, VoiceOutcome.Cancelled("canceled"), target = VoiceTarget.Hold), ""))
    val dictation = VoiceDeliveryMachine(200)
    dictation.begin(3, VoiceDestination.Draft)
    assertEquals(VoiceEffect.None, dictation.settle(closed(3, VoiceOutcome.Cancelled("canceled")), "kept"))
  }

  @Test fun aFullDraftKeepsTheOriginalAndTheWholeResult() {
    val delivery = VoiceDeliveryMachine(10)
    delivery.begin(7, VoiceDestination.Draft)
    val effect = delivery.settle(closed(7, VoiceOutcome.Success("a long dictation")), "0123456789")
    assertTrue(effect is VoiceEffect.Retained)
    val retained = (effect as VoiceEffect.Retained).result
    assertEquals("a long dictation", retained.text)
    assertEquals("DRAFT_FULL", retained.reason)
    assertEquals("the retained result is not appended later", VoiceEffect.None, delivery.settle(closed(7, VoiceOutcome.Success("a long dictation")), "0123456789"))
  }

  @Test fun aBlankResultLeavesTheDraftAloneAndSendsNothing() {
    val dictation = VoiceDeliveryMachine(200)
    dictation.begin(7, VoiceDestination.Draft)
    assertEquals(VoiceEffect.None, dictation.settle(closed(7, VoiceOutcome.Success("   ")), "unchanged"))
    val hold = VoiceDeliveryMachine(200)
    hold.begin(7, VoiceDestination.AwaitGesture)
    val done = closed(7, VoiceOutcome.Success(""), target = VoiceTarget.Hold)
    hold.aim(VoiceDestination.Submit)
    assertEquals(VoiceEffect.CloseEntry, hold.settle(done, ""))
  }

  @Test fun aTerminalThatLandedBeforeOwnershipStillSettlesOnceWhenRegistered() {
    val delivery = VoiceDeliveryMachine(200)
    val failed = closed(1, VoiceOutcome.Failed("ENGINE_UNAVAILABLE"), target = VoiceTarget.Hold)
    assertEquals("nothing is owed before the recording owns delivery", VoiceEffect.None, delivery.settle(failed, ""))
    delivery.begin(1, VoiceDestination.AwaitGesture)
    assertEquals(VoiceEffect.Failed("ENGINE_UNAVAILABLE"), delivery.settle(failed, ""))
    assertEquals("the failure is reported once", VoiceEffect.None, delivery.settle(failed, ""))
  }

  @Test fun goingIdleRevokesAPendingDelivery() {
    val delivery = VoiceDeliveryMachine(200)
    delivery.begin(7, VoiceDestination.Draft)
    assertEquals(VoiceEffect.None, delivery.settle(VoiceState(), ""))
    assertEquals(VoiceEffect.None, delivery.settle(closed(7, VoiceOutcome.Success("late")), ""))
  }

  @Test fun onlyCaptureIsReady() {
    val preparing = active(1, VoicePhase.Preparing, target = VoiceTarget.Hold)
    assertFalse(preparing.ready)
    assertTrue(preparing.listening)
    val capturing = active(1, VoicePhase.Capturing, target = VoiceTarget.Hold)
    assertTrue(capturing.ready)
    val finalizing = active(1, VoicePhase.Finalizing, preview = "half", target = VoiceTarget.Hold)
    assertFalse(finalizing.ready)
    assertTrue(finalizing.listening)
    assertEquals("half", finalizing.transcript)
    val success = closed(1, VoiceOutcome.Success("the whole tail"), preview = "the tail")
    assertTrue(success.final)
    assertFalse(success.listening)
    assertEquals("the whole tail", success.transcript)
    val failure = closed(1, VoiceOutcome.Failed("PROCESSING_TIMEOUT"), preview = "the tail")
    assertEquals("PROCESSING_TIMEOUT", failure.error)
    assertEquals("the partial preview stays visible", "the tail", failure.transcript)
  }

  @Test fun engineTerminalFactsDecideSuccess() {
    assertEquals(VoiceOutcome.Success("the tail"), voiceOutcome("completed", "complete", true, "the tail"))
    assertEquals(VoiceOutcome.Failed("PROCESSING_TIMEOUT"), voiceOutcome("PROCESSING_TIMEOUT", "incomplete", true, "partial"))
    assertEquals(VoiceOutcome.Cancelled("canceled"), voiceOutcome("canceled", "incomplete", true, "partial"))
    assertEquals("an unreleased session is not a success", VoiceOutcome.Failed("RESTORE_FAILED"), voiceOutcome("completed", "complete", false, "the tail"))
  }

  @Test fun theFirstDrainDeadlineWinsAndLateEventsCannotExtendIt() {
    assertEquals(6_000L, settleFinishDeadline(null, null, 1_000L, 5_000L))
    assertEquals(4_800L, settleFinishDeadline(null, 4_800L, 1_000L, 5_000L))
    assertEquals("a later, longer deadline is ignored", 4_800L, settleFinishDeadline(4_800L, 9_000L, 2_000L, 5_000L))
    assertEquals("an earlier Engine deadline shortens the wait", 4_500L, settleFinishDeadline(4_800L, 4_500L, 2_000L, 5_000L))
  }
}
