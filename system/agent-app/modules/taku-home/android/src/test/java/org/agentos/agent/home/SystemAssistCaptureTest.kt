package org.agentos.agent.home

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.agentos.agent.home.assistant.SystemAssistCapture
import org.agentos.agent.home.assistant.VoiceDeliveryMachine
import org.agentos.agent.home.assistant.VoiceDestination
import org.agentos.agent.home.assistant.VoiceEffect
import org.agentos.agent.home.assistant.VoiceOutcome
import org.agentos.agent.home.assistant.VoicePhase
import org.agentos.agent.home.assistant.VoiceState
import org.agentos.agent.home.assistant.VoiceTarget
import org.junit.Assert.*
import org.junit.Test

class SystemAssistCaptureTest {
  @Test fun anEarlySurfaceHideKeepsOnlyTheActiveOriginalCapture() = runBlocking {
    val capture = SystemAssistCapture.begin("early-overlay-gesture")
    assertTrue(capture.retainOnHide(presentedEarly = true, inFlight = true))
    assertFalse(capture.retainOnHide(presentedEarly = false, inFlight = true))
    assertFalse(capture.retainOnHide(presentedEarly = true, inFlight = false))
    // onHide hands UI ownership to the overlay; the original capture can still finish.
    capture.received()
    capture.awaitReceived()
    val cancelled = SystemAssistCapture.begin("cancelled-overlay-gesture")
    cancelled.reject()
    assertFalse(cancelled.retainOnHide(presentedEarly = true, inFlight = true))
  }

  @Test fun aReleasedRecordingWaitsForCaptureAndSubmitsOnlyOnce() = runBlocking {
    val capture = SystemAssistCapture.begin("released-gesture")
    val delivery = VoiceDeliveryMachine(200)
    delivery.begin(7, VoiceDestination.AwaitGesture)
    val final = VoiceState(operationId = 7, target = VoiceTarget.Hold, phase = VoicePhase.Closed,
      outcome = VoiceOutcome.Success("original words"))
    assertEquals(VoiceEffect.None, delivery.settle(final, ""))
    delivery.aim(VoiceDestination.Submit)
    assertEquals(VoiceEffect.Send("original words"), delivery.settle(final, ""))
    var submissions = 0
    val send = async { capture.awaitReceived(); submissions++ }
    delay(15)
    assertEquals(0, submissions)
    assertEquals(VoiceEffect.None, delivery.settle(final, ""))
    capture.received()
    capture.received()
    send.await()
    assertEquals(1, submissions)
  }

  @Test fun aConversationBoundaryRevokesBothPendingScreenAndRecording() = runBlocking {
    val capture = SystemAssistCapture.begin("old-conversation-gesture")
    val delivery = VoiceDeliveryMachine(200)
    delivery.begin(8, VoiceDestination.Submit)
    capture.reject()
    delivery.newGeneration()
    capture.received()
    val final = VoiceState(operationId = 8, target = VoiceTarget.Hold, phase = VoicePhase.Closed,
      outcome = VoiceOutcome.Success("old words"))
    assertEquals(VoiceEffect.None, delivery.settle(final, ""))
    val failure = runCatching { capture.awaitReceived() }.exceptionOrNull() as SystemAssistCapture.Failure
    assertEquals("CAPTURE_EXPIRED", failure.code)
  }

  @Test fun voiceSubmissionWaitsUntilItsScreenWasReceived() = runBlocking {
    val capture = SystemAssistCapture.begin("first-gesture")
    var submitted = false
    val send = async { capture.awaitReceived(); submitted = true }
    delay(15)
    assertFalse(submitted)
    capture.received()
    send.await()
    assertTrue(submitted)
  }

  @Test fun keepTalkingConnectionAndVoiceSubmissionShareTheOriginalCaptureGate() = runBlocking {
    val capture = SystemAssistCapture.begin("handoff-gesture")
    val handoff = SystemAssistCapture.forGesture("handoff-gesture")!!
    var submitted = false
    var connected = false
    val send = async { capture.awaitReceived(); submitted = true }
    val connect = async { handoff.awaitReceived(); connected = true }
    delay(15)
    assertSame(capture, handoff)
    assertFalse(capture.ready)
    assertFalse(submitted)
    assertFalse(connected)
    capture.received()
    send.await(); connect.await()
    assertTrue(capture.ready)
    assertTrue(submitted)
    assertTrue(connected)
  }

  @Test fun cancellationPreventsLateCaptureFromReleasingSubmission() = runBlocking {
    val capture = SystemAssistCapture.begin("cancelled-gesture")
    capture.reject()
    capture.received()
    val result = runCatching { capture.awaitReceived() }.exceptionOrNull()
    assertEquals("CAPTURE_EXPIRED", (result as SystemAssistCapture.Failure).code)
    assertTrue(capture.rejected)
  }

  @Test fun cancellationWhileSubmissionIsWaitingNeverReachesSubmit() = runBlocking {
    val capture = SystemAssistCapture.begin("waiting-gesture")
    var submitted = false
    val send = async { runCatching { capture.awaitReceived(); submitted = true }.exceptionOrNull() }
    delay(15)
    capture.reject()
    capture.received()
    assertEquals("CAPTURE_EXPIRED", (send.await() as SystemAssistCapture.Failure).code)
    assertFalse(submitted)
  }

  @Test fun aNewGestureInvalidatesOnlyTheOldPendingCapture() = runBlocking {
    val old = SystemAssistCapture.begin("old-gesture")
    val next = SystemAssistCapture.begin("new-gesture")
    old.received()
    assertEquals("CAPTURE_EXPIRED", (runCatching { old.awaitReceived() }.exceptionOrNull() as SystemAssistCapture.Failure).code)
    assertNull(SystemAssistCapture.forGesture("old-gesture"))
    assertSame(next, SystemAssistCapture.forGesture("new-gesture"))
    next.received()
    next.awaitReceived()
  }

  @Test fun receiptRemainsUsableAfterNormalSurfaceHideOrVoiceFinish() = runBlocking {
    val capture = SystemAssistCapture.begin("finished-gesture")
    capture.received()
    capture.reject() // The completed capture is not revoked by cleanup.
    capture.awaitReceived()
    capture.awaitReceived() // Waiting is idempotent; sending still belongs to VoiceDeliveryMachine.
    assertFalse(capture.rejected)
  }

  @Test fun missingCaptureTimesOutAndRejectsALateReceipt() = runBlocking {
    val capture = SystemAssistCapture.begin("timeout-gesture")
    val result = runCatching { capture.awaitReceived(10) }.exceptionOrNull()
    assertEquals("SCREENSHOT_UNAVAILABLE", (result as SystemAssistCapture.Failure).code)
    capture.received()
    assertTrue(capture.rejected)
    assertEquals("SCREENSHOT_UNAVAILABLE", (runCatching { capture.awaitReceived() }.exceptionOrNull() as SystemAssistCapture.Failure).code)
  }
}
