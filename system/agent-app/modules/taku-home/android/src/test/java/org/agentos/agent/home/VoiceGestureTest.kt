package org.agentos.agent.home

import org.agentos.agent.home.assistant.AssistantException
import org.agentos.agent.home.assistant.VoiceGesture
import org.junit.Assert.*
import org.junit.Test

class VoiceGestureTest {
  private val id = "gesture-0001"
  private fun prepared(): VoiceGesture = VoiceGesture().also { assertTrue(it.prepare(id, "system", 100, 100)) }

  @Test fun releaseDuringColdStartCannotActivateLater() {
    val g = prepared()
    assertTrue(g.event(id, 1, 110, 110))
    assertEquals(VoiceGesture.Phase.Pending, g.end(false))
    assertFalse(g.activate(id))
    assertFalse(g.prepare(id, "system", 100, 150))
  }

  @Test fun releasedHoldStaysListeningWithoutExpiringOrAcceptingLatePointerEvents() {
    val g = prepared(); g.activate(id)
    assertTrue(g.latch())
    assertEquals(VoiceGesture.Phase.Listening, g.phase)
    assertFalse(g.held)
    assertFalse(g.expired(200_000))
    assertFalse(g.event(id, 1, 150, 150))
    assertTrue(g.keepTalking())
    assertEquals(VoiceGesture.Phase.Continuous, g.phase)
  }

  @Test fun latchedPageStillCancelsAndRejectsAnotherGesture() {
    val g = prepared(); g.activate(id); g.latch()
    try { g.prepare("gesture-0002", "desktop", 120, 120); fail() }
    catch (e: AssistantException) { assertEquals("VOICE_BUSY", e.code) }
    assertEquals(VoiceGesture.Phase.Listening, g.end(true))
    assertFalse(g.keepTalking())
  }

  @Test fun cancellationIsTerminalAndIgnoresLateFinalPointer() {
    val g = prepared(); assertTrue(g.activate(id))
    g.end(true)
    assertFalse(g.event(id, 5, 150, 150))
    assertFalse(g.activate(id))
    assertEquals(VoiceGesture.Phase.Cancelled, g.phase)
  }

  @Test fun duplicateAndOutOfOrderEventsCannotChangeZone() {
    val g = prepared(); g.activate(id)
    assertTrue(g.event(id, 2, 120, 120)); g.move(VoiceGesture.Zone.Cancel)
    assertFalse(g.event(id, 2, 120, 120))
    assertFalse(g.event(id, 1, 110, 130))
    assertFalse(g.event("other-id", 3, 140, 140))
    assertEquals(VoiceGesture.Zone.Cancel, g.zone)
  }

  @Test fun keepTalkingTransfersOwnershipAndOldStreamCannotSendOrCancel() {
    val g = prepared(); g.activate(id); g.move(VoiceGesture.Zone.KeepTalking)
    assertTrue(g.keepTalking())
    assertEquals(id, g.id)
    assertEquals(VoiceGesture.Zone.None, g.zone)
    assertFalse(g.held)
    assertFalse(g.event(id, 1, 150, 150))
    assertFalse(g.expired(20_000))
    assertFalse(g.keepTalking())
    assertEquals(VoiceGesture.Phase.Continuous, g.end(false))
    assertEquals(VoiceGesture.Phase.Ended, g.phase)
  }

  @Test fun aNewGestureCannotStealActiveRecording() {
    val g = prepared(); g.activate(id)
    try { g.prepare("gesture-0002", "desktop", 120, 120); fail() }
    catch (e: AssistantException) { assertEquals("VOICE_BUSY", e.code) }
    assertEquals(id, g.id)
  }

  @Test fun leaseIsRefreshedOnlyByAcceptedEvents() {
    val g = prepared(); g.activate(id)
    assertTrue(g.event(id, 1, 1000, 1000))
    assertFalse(g.expired(4000)); assertTrue(g.expired(4001))
    assertFalse(g.event(id, 1, 4001, 4001)); assertTrue(g.expired(4001))
  }

  @Test fun persistedStartRejectsReplayAfterProcessRestart() {
    var start = -1L
    val g = VoiceGesture({ start = it }); g.prepare(id, "system", 100, 100)
    val restarted = VoiceGesture(lastStart = start)
    assertFalse(restarted.prepare(id, "system", 100, 200))
    assertFalse(restarted.prepare("older-id", "system", 99, 200))
    assertFalse(restarted.activate(id))
    assertTrue(restarted.prepare("newer-id", "system", 201, 201))
  }

  @Test fun staleAndFutureEventsDoNotAdvanceSequence() {
    val g = prepared()
    for (time in listOf(99L, 6002L, 100L)) {
      try { g.event(id, 1, time, 6001); fail() }
      catch (e: AssistantException) { assertEquals("STALE_GESTURE", e.code) }
    }
    assertEquals(0L, g.sequence)
  }

  @Test fun pendingGestureCannotBecomeContinuous() {
    val g = prepared(); assertFalse(g.keepTalking())
    assertEquals(VoiceGesture.Phase.Pending, g.phase)
  }

  @Test fun nextValidGestureStartsWithCleanState() {
    val g = prepared(); g.activate(id); g.move(VoiceGesture.Zone.Cancel); g.end(true)
    assertTrue(g.prepare("gesture-0002", "agent", 200, 200))
    assertEquals(VoiceGesture.Zone.None, g.zone); assertEquals(0L, g.sequence)
    assertTrue(g.activate("gesture-0002"))
  }
}
