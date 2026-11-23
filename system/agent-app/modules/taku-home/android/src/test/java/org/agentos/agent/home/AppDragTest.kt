package org.agentos.agent.home

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import org.agentos.agent.home.apps.Slot
import org.agentos.agent.home.ui.AppDragState
import org.agentos.agent.home.ui.DragItem
import org.agentos.agent.home.ui.DropTarget
import org.agentos.agent.home.ui.GridGeometry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Issue #32 H8: dragging an app onto another app's centre makes a folder instead of reordering. */
class AppDragTest {
  private val grid = GridGeometry(origin = Offset.Zero, cellWidth = 83f, rowHeight = 103f, gapX = 16f, gapY = 16f, plate = 83f)
  private val apps = listOf("a", "b", "c", "d").map { Slot.App(it) }

  @Test fun compactIconsKeepTheirVisibleBoundsWhenPickedUpAndTargeted() {
    val compact = GridGeometry(Offset(16f, 100f), 83f, 80f, 16f, 16f, 60f)
    val first = compact.plateRect(0)
    assertEquals(Rect(27.5f, 100f, 87.5f, 160f), first)
    val d = AppDragState().apply {
      grids[0] = compact
      base = { apps.drop(1) }
      shown = { apps }
      page = 0
      start(DragItem(apps[0], "a", null), first.center, first, DropTarget(0, 0))
    }
    assertEquals(60f, d.iconSize, 0f)
    assertEquals(Offset(30f, 30f), d.grab)
    val target = compact.plateRect(1).center
    d.move(target, now = 1_000)
    d.move(target, now = 1_400)
    assertEquals("b", d.target?.intoKey)
  }

  @Test fun compressedRowsUseTheRenderedIconSizeForTheirDropTargets() {
    val compact = GridGeometry(Offset.Zero, 83f, 80f, 16f, 16f, 60f,
      cells = listOf(Rect(0f, 0f, 83f, 64f)), plateSizes = listOf(44f))
    assertEquals(Rect(19.5f, 0f, 63.5f, 44f), compact.plateRect(0))
  }

  private fun drag(): AppDragState = AppDragState().apply {
    grids[0] = grid
    base = { apps.drop(1) }
    shown = { apps }
    page = 0
    start(DragItem(apps[0], "a", null), Offset(41.5f, 41.5f), Rect(0f, 0f, 83f, 83f), DropTarget(0, 0))
  }

  @Test fun approachingAnIconCentreMakesAFolderWithoutSlidingIt() {
    val d = drag()
    val b = grid.plateRect(1)
    // Over b's plate edge on the way in: the gap must not move there yet.
    d.move(Offset(b.left + 4f, b.center.y), now = 1_000)
    d.move(Offset(b.left + 4f, b.center.y), now = 1_150)
    assertEquals(DropTarget(0, 0), d.target)
    // At b's centre, resting: b becomes the folder target.
    d.move(b.center, now = 1_160)
    d.move(b.center, now = 1_400)
    assertEquals("b", d.target?.intoKey)
  }

  @Test fun restingBesideAnIconStillMovesTheGap() {
    val d = drag()
    val b = grid.plateRect(1)
    d.move(Offset(b.left + 4f, b.center.y), now = 1_000)
    d.move(Offset(b.left + 4f, b.center.y), now = 1_400)
    assertEquals(1, d.target?.gap)
    assertNull(d.target?.intoKey)
  }

  @Test fun releasingAnAppFromAFolderCommitsTheFinalGapWithoutWaiting() {
    val folder = Slot.Folder("folder", "Apps", listOf("a", "b"))
    val page = listOf(folder, Slot.App("c"))
    val d = AppDragState().apply {
      grids[0] = grid
      base = { page }
      shown = { page }
      this.page = 0
      start(DragItem(Slot.App("a"), "a", null), Offset(20f, 20f), Rect(0f, 0f, 64f, 64f), null)
    }
    d.move(grid.plateRect(2).center, now = 1_000)
    assertEquals(DropTarget(0, 2), d.end()?.second)
    assertNull(d.item)
  }

  @Test fun releasingAtANewGapDoesNotUseThePreviousPreviewPosition() {
    val d = drag()
    val at = grid.cellRect(2).bottomLeft + Offset(4f, -2f)
    d.move(at, now = 1_000)
    assertEquals(DropTarget(0, 2), d.end()?.second)
  }

  @Test fun passingOverAnAppDoesNotJoinItWithoutHovering() {
    val d = drag()
    d.move(grid.plateRect(1).center, now = 1_000)
    assertEquals(DropTarget(0, 0), d.end()?.second)
  }
}
