package org.agentos.agent.home

import org.agentos.agent.home.ui.effects.LiveEdgeGlowSpec
import org.agentos.agent.home.ui.effects.WorkingGlowClock
import org.agentos.agent.home.ui.effects.shouldShowEdgeGlow
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class WorkingGlowClockTest {
  private fun advance(clock: WorkingGlowClock, millis: Int) { repeat(millis / 20) { clock.advance(20f) } }

  @Test fun workStartsAndFinishesWithDesignerTiming() {
    val c = WorkingGlowClock()
    c.working = true
    advance(c, 100); assertEquals(.5f, c.level, .0001f)
    advance(c, 100); assertEquals(1f, c.level, .0001f)
    c.working = false
    advance(c, 200); assertEquals(.5f, c.level, .0001f)
    advance(c, 200); assertEquals(0f, c.level, .0001f); assertFalse(c.visible)
  }

  @Test fun reversingDoesNotJumpOrRestartFlow() {
    val c = WorkingGlowClock(); c.working = true; advance(c, 100)
    val level = c.level; val time = c.timeSeconds
    c.working = false; assertEquals(level, c.level, 0f)
    advance(c, 40); val fading = c.level
    c.working = true; assertEquals(fading, c.level, 0f)
    advance(c, 120); assertEquals(1f, c.level, .0001f); assertTrue(c.timeSeconds > time)
    c.working = false; advance(c, 400)
    val paused = c.timeSeconds; advance(c, 1000); assertEquals(paused, c.timeSeconds, 0f)
    c.working = true; advance(c, 20); assertTrue(c.timeSeconds > paused)
  }

  @Test fun aDelayedFrameCannotSkipTheWholeFade() {
    val c = WorkingGlowClock(); c.working = true
    c.advance(5000f); assertEquals(.32f, c.progress, .0001f)
    c.advance(-10f); assertEquals(.32f, c.progress, .0001f)
  }

  @Test fun edgeGlowIsDisabledForAgentChatOutputAndEnabledOnlyInLiveMode() {
    assertFalse("idle state never shows edge glow", shouldShowEdgeGlow(working = false, voiceContinuous = false))
    assertFalse("ordinary Agent Chat / AI output never shows edge glow", shouldShowEdgeGlow(working = true, voiceContinuous = false))
    assertTrue("Live mode invocation shows edge glow", shouldShowEdgeGlow(working = false, voiceContinuous = true))
    assertTrue("Live mode stays active while AI responds", shouldShowEdgeGlow(working = true, voiceContinuous = true))
  }

  @Test fun liveEdgeGlowParametersAreSoftenedAndRestrained() {
    assertTrue("intensity stays soft and non-glaring", LiveEdgeGlowSpec.INTENSITY in 0.2f..0.5f)
    assertTrue("base blur sigma stays thin along the bezel", LiveEdgeGlowSpec.SIGMA_DP in 4f..10f)
    val peakSigma = LiveEdgeGlowSpec.SIGMA_DP * LiveEdgeGlowSpec.REACH_MAX
    assertTrue("peak inward blur stays under 10 dp to avoid covering edge content", peakSigma <= 10f)
    assertTrue("ambient floor stays high enough to avoid strobing", LiveEdgeGlowSpec.FLOOR >= 0.45f)
    assertTrue("chromatic offset stays cohesive", LiveEdgeGlowSpec.OFFSET_DP <= 3.0f)
    assertTrue("contour warp stays subtle", LiveEdgeGlowSpec.WARP_DP <= 1.6f)
    for (packet in LiveEdgeGlowSpec.PACKETS) {
      assertTrue("packet speed stays calm", abs(packet[1]) <= 100.0)
      assertTrue("packet pulse amplitude stays gentle", packet[4] <= 0.75)
    }
  }
}
