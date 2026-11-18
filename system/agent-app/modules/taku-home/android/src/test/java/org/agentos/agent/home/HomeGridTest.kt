package org.agentos.agent.home

import org.agentos.agent.home.apps.*
import org.agentos.agent.home.feed.FeedException
import org.junit.Assert.*
import org.junit.Test

class HomeGridTest {
  private fun widget(id: Int, x: Int = 2, y: Int = 2) = Slot.Widget(id, "test/Widget", x, y)
  private val defaults = listOf(widget(1), widget(2, 2, 1), widget(3, 1, 1), widget(4, 1, 1), widget(5), widget(6))
  @Test fun sixDefaultWidgetsRetainTheirArrangementAndLeaveTwelveAppCells() {
    val apps = (1..13).map { Slot.App("app$it") }
    val pages = HomePages.paginate(listOf(defaults + apps))
    assertEquals(listOf(18, 1), pages.map { it.size })
    assertEquals(listOf(0 to 0, 2 to 0, 2 to 1, 3 to 1, 0 to 2, 2 to 2), HomeGrid.place(defaults).map { it.x to it.y })
    assertEquals(0 to 4, HomeGrid.place(pages[0])[6].let { it.x to it.y })
  }
  @Test fun appReconciliationPreservesWidgetsAndFolderContents() {
    val pages = HomePages.reconcile(listOf(defaults + Slot.Folder("f", "Taku", listOf("a", "b"))), listOf("a", "b", "c"))
    assertEquals(defaults, pages[0].filterIsInstance<Slot.Widget>())
    assertEquals(setOf("a", "b", "c"), HomePages.keys(pages))
  }
  @Test fun widgetMovesAcrossPagesAndNeverBecomesAFolderMember() {
    val initial = listOf(defaults + Slot.App("a"))
    val changed = HomePages.move(initial, null, null, 1, null, false, { "f" }, { "Apps" }, 5)
    assertEquals(1, HomePages.locateWidget(changed.pages, 5)!!.page)
    assertEquals(6, changed.pages.flatten().filterIsInstance<Slot.Widget>().size)
    try { HomePages.move(initial, "a", null, 0, 0, true, { "f" }, { "Apps" }); fail("widgets cannot contain apps") }
    catch (e: FeedException) { assertEquals("INVALID_ARGUMENT", e.code) }
  }
  @Test fun resizedWidgetsReflowWithoutOverlappingOrLosingAnything() {
    val items = listOf(widget(1, 4, 6)) + defaults.drop(1) + (1..20).map { Slot.App("a$it") }
    val pages = HomePages.paginate(listOf(items))
    assertEquals(items.toSet(), pages.flatten().toSet())
    pages.forEachIndexed { page, entries ->
      val occupied = HashSet<Pair<Int, Int>>()
      HomeGrid.place(entries).forEach { c ->
        assertTrue(c.y + c.h <= HomePages.capacity(page) / 4)
        for (y in c.y until c.y + c.h) for (x in c.x until c.x + c.w) assertTrue(occupied.add(x to y))
      }
    }
  }
  @Test fun rejectsDimensionsThatCannotFitAnyPage() {
    for (size in listOf(0 to 2, 5 to 2, 2 to 0, 2 to 7)) {
      try { HomeGrid.place(listOf(widget(1, size.first, size.second))); fail("invalid size") }
      catch (e: FeedException) { assertEquals("INVALID_ARGUMENT", e.code) }
    }
  }
}
