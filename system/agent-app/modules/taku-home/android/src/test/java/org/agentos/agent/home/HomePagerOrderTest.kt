package org.agentos.agent.home

import org.agentos.agent.home.apps.HomePagerOrder
import org.junit.Assert.*
import org.junit.Test

class HomePagerOrderTest {
  @Test fun appsStayTogetherBeforePanels() {
    val order = HomePagerOrder(3, 4)
    assertNull(order.homeAt(0))
    for (home in 0..2) assertEquals(home, order.homeAt(order.homePage(home)))
    assertEquals(2, order.homePage(1))
    assertEquals(4, order.panelPage(0))
    assertNull(order.homeAt(4))
    assertEquals(8, order.pageCount)
  }

  @Test fun temporaryDropPageNeverBecomesAPanelTarget() {
    val order = HomePagerOrder(2, 3, extraDropPage = true)
    assertEquals(2, order.homeAt(3))
    assertNull(order.panelAt(3))
    assertNull(order.homeAt(4))
    for (panel in 0..2) assertEquals(panel, order.panelAt(order.panelPage(panel)))
    assertEquals(3, HomePagerOrder(2, 3).panelPage(0))
  }

  @Test fun scrollingModeHasOneHomePageBeforePanels() {
    val order = HomePagerOrder(1, 3)
    assertEquals(0, order.homeAt(1))
    assertEquals(0, order.panelAt(2))
    assertNull(order.homeAt(2))
  }
}
