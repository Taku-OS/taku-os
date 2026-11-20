package org.agentos.agent.home

import org.agentos.agent.home.ui.effects.WorkingGlowClock
import org.junit.Assert.*
import org.junit.Test

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
}
