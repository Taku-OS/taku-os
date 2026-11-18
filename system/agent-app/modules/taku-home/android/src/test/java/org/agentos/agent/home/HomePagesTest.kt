package org.agentos.agent.home

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import org.agentos.agent.home.apps.HomePages
import org.agentos.agent.home.apps.Slot
import org.agentos.agent.home.feed.FeedException
import org.agentos.agent.home.ui.AppDragState
import org.agentos.agent.home.ui.DropTarget
import org.agentos.agent.home.ui.GridGeometry
import org.agentos.agent.home.ui.itemKey
import org.junit.Assert.*
import org.junit.Test

class HomePagesTest {
  private fun a(k: String) = Slot.App(k)
  private fun move(pages: List<List<Slot>>, key: String? = null, folder: String? = null, page: Int, index: Int? = null, into: Boolean = false) =
    HomePages.move(pages, key, folder, page, index, into, { "f1" }, { "Apps" })

  @Test fun reordersWithinAPage() {
    val pages = listOf(listOf(a("a"), a("b"), a("c")))
    assertEquals(listOf(listOf(a("b"), a("c"), a("a"))), move(pages, "a", page = 0, index = 3).pages)
    assertEquals(listOf(listOf(a("c"), a("a"), a("b"))), move(pages, "c", page = 0, index = 0).pages)
    val change = move(pages, "a", page = 0, index = 2)
    assertEquals(listOf(listOf(a("b"), a("a"), a("c"))), change.pages)
    assertEquals(1, change.index)
  }

  @Test fun draggingPastTheLastPageCreatesOneAndEmptyPagesDisappear() {
    val pages = listOf(listOf(a("a"), a("b")))
    val created = move(pages, "b", page = 1)
    assertEquals("page-created", created.effect)
    assertEquals(listOf(listOf(a("a")), listOf(a("b"))), created.pages)
    assertEquals(1, created.page)
    // Moving the only app back closes page 1 again.
    val back = move(created.pages, "b", page = 0, index = 0)
    assertEquals(listOf(listOf(a("b"), a("a"))), back.pages)
  }

  @Test fun pageZeroStaysEvenWhenEmpty() {
    val change = move(listOf(listOf(a("a"))), "a", page = 1)
    assertEquals(listOf(emptyList<Slot>(), listOf(a("a"))), change.pages)
  }

  @Test fun droppingOnAnAppMakesAFolderAndOnAFolderJoinsIt() {
    val pages = listOf(listOf(a("a"), a("b"), a("c")))
    val made = move(pages, "c", page = 0, index = 0, into = true)
    assertEquals("folder-created", made.effect)
    assertEquals(listOf(listOf(Slot.Folder("f1", "Apps", listOf("a", "c")), a("b"))), made.pages)
    val joined = move(made.pages, "b", page = 0, index = 0, into = true)
    assertEquals("added-to-folder", joined.effect)
    assertEquals(listOf(listOf(Slot.Folder("f1", "Apps", listOf("a", "c", "b")))), joined.pages)
    assertEquals("f1", joined.folderId)
    // Dragging the app before the folder onto it accounts for its own removal.
    val shifted = move(listOf(listOf(a("x"), a("y"))), "x", page = 0, index = 1, into = true)
    assertEquals(listOf(listOf(Slot.Folder("f1", "Apps", listOf("y", "x")))), shifted.pages)
  }

  @Test fun anAppLeavesAFolderAndAFolderOfOneBecomesTheApp() {
    val pages = listOf(listOf(Slot.Folder("f", "Apps", listOf("a", "b")), a("c")))
    val change = move(pages, "b", page = 1)
    assertEquals(listOf(listOf(a("a"), a("c")), listOf(a("b"))), change.pages)
    assertNull(change.folderId)
  }

  @Test fun foldersMoveAcrossPagesButCannotBeDroppedIntoItems() {
    val pages = listOf(listOf(Slot.Folder("f", "Apps", listOf("a", "b")), a("c")), listOf(a("d")))
    assertEquals(listOf(listOf(a("c")), listOf(a("d"), Slot.Folder("f", "Apps", listOf("a", "b")))), move(pages, folder = "f", page = 1).pages)
    assertCode("INVALID_ARGUMENT") { move(pages, folder = "f", page = 0, index = 1, into = true) }
  }

  @Test fun rejectsInvalidMoves() {
    val pages = listOf(listOf(a("a")))
    assertCode("INVALID_ARGUMENT") { move(pages, "a", page = 2) }
    assertCode("INVALID_ARGUMENT") { move(pages, "a", page = 0, index = 5) }
    assertCode("INVALID_ARGUMENT") { move(pages, "a", "f", page = 0) }
    assertCode("NOT_ON_HOME") { move(pages, "z", page = 0) }
    assertCode("EMPTY_CELL") { move(pages, "a", page = 1, index = 0, into = true) }
    val full = listOf(listOf(Slot.Folder("f", "Apps", (1..HomePages.MAX_FOLDER).map { "k$it" }), a("a")))
    assertCode("FOLDER_FULL") { move(full, "a", page = 0, index = 0, into = true) }
    assertEquals("unchanged", move(pages, "a", page = 0, index = 0, into = true).effect)
  }

  @Test fun reconcileAppendsNewAppsToTheLastPageAndDropsRemovedOnes() {
    val pages = listOf(listOf(a("a"), Slot.Folder("f", "Apps", listOf("b", "c"))), listOf(a("d")))
    assertEquals(listOf(listOf(a("a"), a("b")), listOf(a("d"), a("e"))), HomePages.reconcile(pages, listOf("a", "b", "d", "e")))
    // The only app on page 1 is uninstalled: the page goes.
    assertEquals(listOf(listOf(a("a"), Slot.Folder("f", "Apps", listOf("b", "c")))), HomePages.reconcile(pages, listOf("a", "b", "c")))
    assertEquals(listOf(listOf(a("x"))), HomePages.reconcile(emptyList(), listOf("x")))
  }

  @Test fun ungroupAndRename() {
    val pages = listOf(listOf(a("z"), Slot.Folder("f", "Apps", listOf("a", "b")), a("c")))
    assertEquals(listOf(listOf(a("z"), a("a"), a("b"), a("c"))), HomePages.ungroup(pages, "f").pages)
    assertEquals("Travel", (HomePages.rename(pages, "f", " Travel ")[0][1] as Slot.Folder).name)
    assertCode("INVALID_ARGUMENT") { HomePages.rename(pages, "f", " ") }
    assertCode("NOT_FOUND") { HomePages.ungroup(pages, "nope") }
  }

  @Test fun migrationMovesTheOldExtraPageAfterTheAppPage() {
    val grid = listOf(a("a"), Slot.Folder("builtin-taku", "Taku", listOf("t1", "t2", "t3")), a("b"))
    val extra = listOf(Slot.Folder("x", "Apps", listOf("b", "t1")))
    assertEquals(listOf(listOf(a("a"), Slot.Folder("builtin-taku", "Taku", listOf("t2", "t3"))), extra), HomePages.migrate(grid, extra))
    assertEquals(listOf(grid), HomePages.migrate(grid, emptyList()))
  }

  @Test fun fullPagesPushOverflowToTheNextPage() {
    val apps = (1..30).map { a("k$it") }
    val pages = HomePages.reconcile(emptyList(), apps.map { it.key })
    assertEquals(listOf(28, 2), pages.map { it.size })
    // Inserting into the full page 0 pushes its last item to the start of page 1.
    val moved = move(pages, "k30", page = 0, index = 0)
    assertEquals(listOf(a("k30")) + apps.take(27), moved.pages[0])
    assertEquals(listOf(a("k28")) + apps.subList(28, 29), moved.pages[1])
    assertEquals(0 to 0, moved.page to moved.index)
    // A cascade through full pages creates a new last page.
    val full = listOf(apps.take(28), (1..24).map { a("p$it") }, listOf(a("z")))
    val cascade = move(full, "z", page = 0, index = 3)
    assertEquals(listOf(28, 24, 1), cascade.pages.map { it.size })
    assertEquals(a("p24"), cascade.pages[2][0])
    // Ungrouping a folder on a full page moves the overflow on as well.
    val folderPage = listOf(apps.take(27) + Slot.Folder("f", "F", listOf("x", "y")))
    assertEquals(listOf(listOf(a("y"))), HomePages.ungroup(folderPage, "f").pages.drop(1))
    // Saved pages longer than the capacity are repaired.
    assertEquals(listOf(28, 12), HomePages.paginate(listOf(apps + (1..10).map { a("q$it") })).map { it.size })
  }

  @Test fun gapAndFolderTargetsFollowFixedCells() {
    // Four 100 px cells with 20 px gaps, rows 120 px high; plates 100 px.
    val grid = GridGeometry(Offset(0f, 200f), 100f, 120f, 20f, 20f, 100f)
    val base = (0 until 6).map { a("b$it") }
    val dragged = a("d")
    val shown = AppDragState.preview(0, base, dragged, DropTarget(0, 2))
    assertEquals(listOf("b0", "b1", "d", "b2", "b3", "b4", "b5"), shown.map { it.itemKey })
    fun at(x: Float, y: Float, current: DropTarget? = DropTarget(0, 2), limit: Int = 6) =
      AppDragState.candidate(0, Offset(x, y), grid, AppDragState.preview(0, base, dragged, current), base, dragged, current, limit)
    // Edge of cell 4 (b3 is shown there): the gap moves to that cell.
    assertEquals(DropTarget(0, 4), at(5f, 340f))
    // Middle of b3's plate: folder target, the gap stays.
    assertEquals(DropTarget(0, 2, "b3"), at(50f, 390f))
    // Over the gap itself nothing changes; above the grid (widgets) neither.
    assertEquals(DropTarget(0, 2), at(250f, 250f))
    assertEquals(DropTarget(0, 2), at(50f, 100f))
    // Below the last icon: the end, limited on a full page.
    assertEquals(DropTarget(0, 6), at(50f, 600f))
    assertEquals(DropTarget(0, 5), at(50f, 600f, limit = 5))
    // A folder never targets another item.
    val folder = Slot.Folder("f", "F", listOf("x", "y"))
    assertEquals(DropTarget(0, 1), AppDragState.candidate(0, Offset(170f, 250f), grid, base, base, folder, null, 6))
    // An empty page (the new one) opens its gap at 0.
    assertEquals(DropTarget(3, 0), AppDragState.candidate(3, Offset(50f, 250f), grid, listOf(dragged), emptyList(), dragged, DropTarget(3, 0), 0))
    assertEquals(Rect(120f, 340f, 220f, 440f), grid.plateRect(5))
  }

  private fun assertCode(code: String, block: () -> Unit) {
    try { block(); fail("expected $code") } catch (e: FeedException) { assertEquals(code, e.code) }
  }
}
