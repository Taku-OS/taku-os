package org.agentos.agent.home

import org.agentos.agent.home.ui.HomeGesturePolicy
import org.agentos.agent.home.ui.HomeGesturePolicy.Owner
import org.junit.Assert.*
import org.junit.Test

class HomeGesturePolicyTest {
  private val region = HomeGesturePolicy.Region(1080, 2424, 78, 164, 78, 84)
  private fun policy(x: Float, y: Float, r: HomeGesturePolicy.Region = region) = HomeGesturePolicy().apply { down(x, y, r, 24f) }

  @Test fun edgesRemainSystemEvenWhenMovingIntoContent() {
    for ((x, y) in listOf(77f to 1000f, 1002f to 1000f, 500f to 163f, 500f to 2340f)) {
      val p = policy(x, y)
      p.move(500f, 1200f)
      assertEquals(Owner.System, p.owner)
      assertTrue(p.blockX()); assertTrue(p.blockY())
      p.end(false)
      assertEquals("up", p.terminal)
      assertTrue(p.blockX()) // release velocity cannot turn an edge swipe into a page fling
    }
  }

  @Test fun contentCanCrossEdgesAndDirectionDoesNotFlip() {
    val p = policy(78f, 164f)
    p.move(120f, 170f)
    assertEquals(Owner.Horizontal, p.owner)
    p.move(1050f, 2400f)
    assertEquals(Owner.Horizontal, p.owner)
    assertFalse(p.systemStart); assertFalse(p.blockX()); assertTrue(p.blockY())
  }

  @Test fun verticalDiagonalLocksOutPagerEvenAfterReversing() {
    val p = policy(500f, 1000f)
    p.move(515f, 1030f)
    assertEquals(Owner.Vertical, p.owner)
    p.move(900f, 1030f)
    assertEquals(Owner.Vertical, p.owner)
    assertTrue(p.blockX()); assertFalse(p.blockY())
  }

  @Test fun tapAndJitterDoNotTakeOwnership() {
    val p = policy(500f, 1000f)
    p.move(512f, 1009f)
    assertEquals(Owner.Pending, p.owner)
    assertFalse(p.blockX()); assertFalse(p.blockY())
    p.end(false)
    assertFalse(p.active)
  }

  @Test fun cancelKeepsOldDecisionForFlingButNextDownResets() {
    val p = policy(30f, 1000f)
    p.end(true)
    p.move(700f, 1000f)
    assertEquals(0f, p.dx, 0.001f)
    assertEquals("cancel", p.terminal)
    p.down(500f, 1000f, region, 24f)
    assertEquals(2L, p.sequence)
    assertFalse(p.systemStart)
    assertEquals(Owner.Pending, p.owner)
    p.move(550f, 1000f)
    assertEquals(Owner.Horizontal, p.owner)
  }

  @Test fun threeButtonAndChangedSensitivityUseActualInsets() {
    val buttons = region.copy(left = 0, right = 0, bottom = 0)
    val p = policy(1f, 1000f, buttons)
    p.move(500f, 1000f)
    assertEquals(Owner.Horizontal, p.owner)
    p.down(90f, 1000f, region.copy(left = 100), 24f)
    assertEquals(Owner.System, p.owner)
    p.down(90f, 1000f, region.copy(left = 50), 24f)
    assertEquals(Owner.Pending, p.owner)
  }
}
