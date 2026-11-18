package org.agentos.agent.home

import org.agentos.agent.home.apps.HomePanelAnchor
import org.junit.Assert.assertEquals
import org.junit.Test

class HomePanelAnchorTest {
  @Test fun aPageThatIsNotAPanelKeepsItsPage() {
    assertEquals(HomePanelAnchor.Target.Stay, HomePanelAnchor.afterDirectoryChange(null, listOf("a"), listOf("b", "a")))
  }

  @Test fun insertionOrReorderKeepsTheSameSelectedId() {
    val before = listOf("a", "b", "c")
    assertEquals(HomePanelAnchor.Target.Panel("b"), HomePanelAnchor.afterDirectoryChange("b", before, listOf("x", "a", "b", "c")))
    assertEquals(HomePanelAnchor.Target.Panel("b"), HomePanelAnchor.afterDirectoryChange("b", before, listOf("c", "a", "b")))
  }

  @Test fun removalSelectsTheNeighbourThatTookItsPlace() {
    val before = listOf("a", "b", "c")
    assertEquals(HomePanelAnchor.Target.Panel("c"), HomePanelAnchor.afterDirectoryChange("b", before, listOf("a", "c")))
  }

  @Test fun removalOfTheLastPanelFallsBackToTheOneBeforeIt() {
    val before = listOf("a", "b")
    assertEquals(HomePanelAnchor.Target.Panel("a"), HomePanelAnchor.afterDirectoryChange("b", before, listOf("a")))
  }

  @Test fun aRunOfRemovedNeighboursStillLandsOnTheSurvivingPanel() {
    val before = listOf("a", "b", "c", "d")
    assertEquals(HomePanelAnchor.Target.Panel("a"), HomePanelAnchor.afterDirectoryChange("d", before, listOf("a")))
  }

  @Test fun aDistantFollowingPanelOutranksPrecedingOnes() {
    assertEquals(HomePanelAnchor.Target.Panel("d"),
      HomePanelAnchor.afterDirectoryChange("a", listOf("a", "b", "c", "d"), listOf("d", "x")))
  }

  @Test fun anUnselectedNewPanelNeverPullsThePagerOffTheOldOrder() {
    assertEquals(HomePanelAnchor.Target.Home,
      HomePanelAnchor.afterDirectoryChange("b", listOf("a", "b"), listOf("x", "y")))
  }

  @Test fun theLastPanelLeavesTheMainDesktop() {
    assertEquals(HomePanelAnchor.Target.Home, HomePanelAnchor.afterDirectoryChange("only", listOf("only"), emptyList()))
  }

  @Test fun anUnknownSelectedIdTakesTheFirstPanelOrHome() {
    assertEquals(HomePanelAnchor.Target.Panel("a"), HomePanelAnchor.afterDirectoryChange("gone", emptyList(), listOf("a")))
    assertEquals(HomePanelAnchor.Target.Home, HomePanelAnchor.afterDirectoryChange("gone", emptyList(), emptyList()))
  }
}
