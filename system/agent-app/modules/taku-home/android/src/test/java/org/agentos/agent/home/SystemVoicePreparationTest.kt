package org.agentos.agent.home

import org.agentos.agent.home.assistant.SystemVoicePreparation
import org.agentos.agent.home.assistant.VoiceGesture
import org.junit.Assert.*
import org.junit.Test

class SystemVoicePreparationTest {
  private val id = "system-hold-0001"
  private val next = "system-hold-0002"
  private val gesture = VoiceGesture()
  private val preparation = SystemVoicePreparation(gesture)

  private fun reserve() {
    assertTrue(gesture.prepare(id, "system", 100, 100))
    assertTrue(preparation.reserve(id, 100, true))
  }

  private fun present() {
    assertTrue(preparation.allowPresentation(id, 110, true))
    assertTrue(preparation.present(id, 110, true))
  }

  @Test fun preparingIsVisibleWithoutActivatingTheMicrophone() {
    reserve()
    assertFalse(preparation.visible())
    assertFalse(preparation.present(id, 105, true)) // An Intent cannot authorize an early show.
    present()
    assertTrue(preparation.visible())
    assertEquals(VoiceGesture.Phase.Pending, gesture.phase)
    assertFalse(preparation.activate(id, 120, true)) // An ordinary Intent cannot bypass capture.
    assertEquals(VoiceGesture.Phase.Pending, gesture.phase)
  }

  @Test fun originalCaptureCompletionAuthorizesExactlyOneActivation() {
    reserve(); present()
    assertTrue(preparation.complete(id, 120, true))
    assertTrue(preparation.visible())
    assertEquals(VoiceGesture.Phase.Pending, gesture.phase)
    assertTrue(preparation.activate(id, 130, true))
    assertEquals(VoiceGesture.Phase.Holding, gesture.phase)
    assertFalse(preparation.visible())
    assertFalse(preparation.complete(id, 140, true))
    assertFalse(preparation.activate(id, 140, true))
    assertFalse(preparation.present(id, 140, true))
  }

  @Test fun terminalGestureRejectsLateGatewayAndActivityWithoutReopening() {
    for (cancelled in listOf(false, true)) {
      val g = VoiceGesture().also { it.prepare(id, "system", 100, 100) }
      val p = SystemVoicePreparation(g)
      assertTrue(p.reserve(id, 100, true))
      assertTrue(p.allowPresentation(id, 110, true))
      assertTrue(p.present(id, 110, true))
      g.end(cancelled); p.clear()
      assertFalse(p.complete(id, 120, true))
      assertFalse(p.present(id, 120, true))
      assertFalse(p.activate(id, 120, true))
      assertFalse(p.visible())
    }
  }

  @Test fun lateFirstInvocationCannotCompleteOrActivateTheNextOne() {
    reserve(); present()
    gesture.end(true); preparation.clear()
    assertTrue(gesture.prepare(next, "system", 200, 200))
    assertTrue(preparation.reserve(next, 200, true))
    assertTrue(preparation.allowPresentation(next, 210, true))
    assertTrue(preparation.present(next, 210, true))
    assertFalse(preparation.owns(id))
    assertFalse(preparation.complete(id, 220, true))
    assertFalse(preparation.activate(id, 220, true))
    assertTrue(preparation.visible())
    assertTrue(preparation.complete(next, 220, true))
    assertTrue(preparation.activate(next, 230, true))
  }

  @Test fun expiredHeartbeatOrLockedDeviceCannotCompleteOrActivate() {
    reserve(); present()
    assertFalse(preparation.complete(id, 3101, true))
    assertFalse(preparation.present(id, 3101, true))
    assertFalse(preparation.complete(id, 120, false))
    assertTrue(preparation.complete(id, 120, true))
    assertFalse(preparation.activate(id, 130, false))
    assertFalse(preparation.activate(id, 3101, true))
    assertEquals(VoiceGesture.Phase.Pending, gesture.phase)
  }

  @Test fun configurationRecreationDoesNotResetTheCaptureGate() {
    reserve(); present()
    assertTrue(preparation.present(id, 120, true))
    assertFalse(preparation.activate(id, 120, true))
    assertTrue(preparation.complete(id, 130, true))
    assertTrue(preparation.present(id, 140, true))
    assertTrue(preparation.reserve(id, 140, true)) // Cannot reset Ready during recreation.
    assertTrue(preparation.activate(id, 150, true))
  }

  @Test fun anIntentCannotCreatePreparationForDesktopOrUnreservedSystemHolds() {
    assertTrue(gesture.prepare(id, "desktop", 100, 100))
    assertFalse(preparation.reserve(id, 100, true))
    assertFalse(preparation.allowPresentation(id, 100, true))
    assertFalse(preparation.present(id, 100, true))
    assertFalse(preparation.complete(id, 100, true))
    assertFalse(preparation.activate(id, 100, true))
  }
}
