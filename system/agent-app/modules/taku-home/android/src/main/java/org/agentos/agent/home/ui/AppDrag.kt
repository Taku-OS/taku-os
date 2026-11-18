package org.agentos.agent.home.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import kotlinx.coroutines.withTimeoutOrNull
import org.agentos.agent.home.apps.Slot

/** What is being dragged: an app (from a page or an open folder) or a folder. */
data class DragItem(val slot: Slot, val label: String, val icon: ImageBitmap?)

/** A long-press without movement opens the icon menu at [anchor] (root coordinates). */
data class IconMenuTarget(val slot: Slot, val label: String, val anchor: Rect, val inFolder: Boolean, val iconBounds: Rect)

/** Stable identity of a page item: an app's key or a folder's id. */
val Slot.itemKey: String get() = when (this) { is Slot.App -> key; is Slot.Folder -> id; is Slot.Widget -> "widget:$appWidgetId" }

/**
 * Where a drop lands on displayed page [page] (the page count = the new page): the gap at
 * [gap], counted in the page without the dragged item, or with [intoKey] onto that item (makes
 * or joins a folder; the gap stays put meanwhile so nothing slides under the finger).
 */
data class DropTarget(val page: Int, val gap: Int, val intoKey: String? = null)

/**
 * Cell geometry of one page's icon grid in root coordinates. Cells are fixed: icons slide
 * between them, so hit-testing never follows an animation.
 */
data class GridGeometry(val origin: Offset, val cellWidth: Float, val rowHeight: Float, val gapX: Float, val gapY: Float, val plate: Float, val columns: Int = 4, val cells: List<Rect>? = null) {
  fun cellRect(index: Int): Rect {
    cells?.getOrNull(index)?.let { return it.translate(origin) }
    val col = index % columns
    val row = index / columns
    val left = origin.x + col * (cellWidth + gapX)
    val top = origin.y + row * (rowHeight + gapY)
    return Rect(left, top, left + cellWidth, top + rowHeight)
  }

  /** The icon plate (square, centred at the top of the cell) of cell [index]. */
  fun plateRect(index: Int): Rect {
    val cell = cellRect(index)
    val left = cell.left + (cellWidth - plate) / 2
    return Rect(left, cell.top, left + plate, cell.top + plate)
  }

  /** Cell under [at], or null above the grid or beside it. */
  fun indexAt(at: Offset): Int? {
    if (cells != null) {
      val local = at - origin
      cells.indexOfFirst { it.contains(local) }.takeIf { it >= 0 }?.let { return it }
      if (local.y < 0) return null
      return cells.indexOfFirst { it.top > local.y || (it.bottom > local.y && it.left > local.x) }.let { if (it < 0) cells.size else it }
    }
    val local = at - origin
    if (local.y < 0f || local.x < -gapX || local.x > columns * (cellWidth + gapX)) return null
    val col = (local.x / (cellWidth + gapX)).toInt().coerceIn(0, columns - 1)
    val row = (local.y / (rowHeight + gapY)).toInt()
    return row * columns + col
  }
}

/**
 * Drag-and-drop across the home pages. Pages report their [GridGeometry] and the controller
 * supplies each page's items; [HomeScreen] tells which page is showing, turns pages when the
 * finger rests at a screen edge, scrolls a long page, and re-checks the target while the finger
 * rests (a gap moves after [GAP_DWELL_MS], a folder target after [INTO_DWELL_MS]).
 */
@Stable
class AppDragState {
  var item by mutableStateOf<DragItem?>(null)
    private set
  var pointer by mutableStateOf(Offset.Zero)
    private set
  /** Offset between the finger and the icon's top-left when the drag started. */
  var grab = Offset.Zero
    private set
  var iconSize = 0f
    private set
  var target by mutableStateOf<DropTarget?>(null)
    private set
  var menu by mutableStateOf<IconMenuTarget?>(null)
  /** Icon grid of each laid-out displayed page. */
  val grids = HashMap<Int, GridGeometry>()
  /** Scroll state of each laid-out page, for scrolling a long page while dragging. */
  val scrollers = HashMap<Int, ScrollState>()
  /** The displayed home page under the finger, or null on the feed. */
  var page: Int? = null
  /** Items of a displayed page without the dragged item, and the largest gap allowed there. */
  var base: (page: Int) -> List<Slot> = { emptyList() }
  var gapLimit: (page: Int, base: List<Slot>) -> Int = { _, b -> b.size }
  /** What a displayed page shows right now (gap open, overflow past the capacity gone). */
  var shown: (page: Int) -> List<Slot> = { emptyList() }
  private var pending: DropTarget? = null
  private var pendingSince = 0L

  fun start(item: DragItem, pointer: Offset, iconBounds: Rect, origin: DropTarget?) {
    this.item = item
    this.pointer = pointer
    grab = pointer - iconBounds.topLeft
    iconSize = iconBounds.width
    target = origin
    pending = null
  }

  /** The finger moved (or rests: [HomeScreen] calls this periodically). */
  fun move(to: Offset, now: Long = System.currentTimeMillis()) {
    pointer = to
    val current = item ?: return
    val page = page ?: return
    val base = base(page)
    val candidate = candidate(page, to, grids[page], shown(page), base, current.slot, target, gapLimit(page, base)) ?: return
    if (candidate == target) { pending = null; return }
    if (candidate != pending) { pending = candidate; pendingSince = now; return }
    val dwell = when {
      candidate.intoKey != null -> INTO_DWELL_MS
      // Over another app's icon the finger is usually on its way to that icon's centre to make a
      // folder: moving the gap there at once would slide the icon away (issue #32 H8).
      current.slot is Slot.App && overOtherIcon(page, to, current.slot) -> GAP_OVER_ICON_DWELL_MS
      else -> GAP_DWELL_MS
    }
    if (now - pendingSince >= dwell) { target = candidate; pending = null }
  }

  private fun overOtherIcon(page: Int, at: Offset, dragged: Slot): Boolean {
    val grid = grids[page] ?: return false
    val display = shown(page)
    val index = grid.indexAt(at)?.takeIf { it < display.size } ?: return false
    return display[index].itemKey != dragged.itemKey && grid.plateRect(index).contains(at)
  }

  /** Aims at the first spot of another page right away (after a page turn). */
  fun enter(page: Int) {
    this.page = page
    if (item == null) return
    if (target?.page != page) target = DropTarget(page, gapLimit(page, base(page)))
    pending = null
  }

  /** Ends the drag; returns the item and where it lands, or null when dropped nowhere. */
  fun end(): Pair<DragItem, DropTarget?>? {
    val current = item ?: return null
    // Reordering does not require a hover: release commits the last gap even if
    // its preview dwell has not elapsed. Joining a folder still requires dwell.
    val landed = pending?.takeIf { it.intoKey == null } ?: target
    item = null
    target = null
    pending = null
    return current to landed
  }

  companion object {
    const val GAP_DWELL_MS = 90L
    /** A gap over another icon's plate waits longer than a folder does there. */
    const val GAP_OVER_ICON_DWELL_MS = 300L
    const val INTO_DWELL_MS = 200L

    /** What a page shows while [dragged] is lifted: its items with the gap opened at the target. */
    fun preview(page: Int, base: List<Slot>, dragged: Slot, target: DropTarget?): List<Slot> =
      if (target?.page == page) base.toMutableList().apply { add(target.gap.coerceIn(0, base.size), dragged) } else base

    /**
     * Over a cell's icon an app joins it (folder); elsewhere on a cell the gap moves to that cell,
     * so the icons there slide aside; below the last icon the gap goes to the end. [display] is
     * the page as currently shown (the dragged item standing for the gap).
     */
    fun candidate(page: Int, at: Offset, grid: GridGeometry?, display: List<Slot>, base: List<Slot>, dragged: Slot, current: DropTarget?, limit: Int): DropTarget? {
      val keepGap = current?.takeIf { it.page == page }?.gap ?: limit
      if (grid == null) return null
      val index = grid.indexAt(at) ?: return DropTarget(page, keepGap)
      if (index >= display.size) return DropTarget(page, limit)
      val entry = display[index]
      if (entry.itemKey == dragged.itemKey) return DropTarget(page, keepGap)
      val plate = grid.plateRect(index)
      // The inner 68 % of the plate makes a folder (it was 56 %, too small to hit while icons move).
      val inset = plate.width * 0.16f
      val core = Rect(plate.left + inset, plate.top + inset, plate.right - inset, plate.bottom - inset)
      if (dragged is Slot.App && entry !is Slot.Widget && core.contains(at)) return DropTarget(page, keepGap, entry.itemKey)
      return DropTarget(page, index.coerceAtMost(limit))
    }
  }
}

/**
 * Long press on this element that no child takes: [onLongPress] with the local position. It
 * consumes nothing, so the pager and the icons keep their gestures; a move past the touch slop
 * or a child consuming the pointer cancels it.
 */
fun Modifier.passiveLongPress(key: Any, onLongPress: (Offset) -> Unit): Modifier = pointerInput(key) {
  awaitEachGesture {
    val down = awaitFirstDown(requireUnconsumed = false)
    val ended = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
      while (true) {
        val e = awaitPointerEvent()
        val c = e.changes.firstOrNull { it.id == down.id } ?: return@withTimeoutOrNull true
        if (!c.pressed || c.isConsumed || (c.position - down.position).getDistance() > viewConfiguration.touchSlop) return@withTimeoutOrNull true
      }
      @Suppress("UNREACHABLE_CODE") true
    }
    if (ended == null) onLongPress(down.position)
  }
}

/**
 * Long press, then either release in place (→ [onMenu]) or drag (→ [onStart]/[onMove]/[onEnd]).
 * Observes the Initial pass so the surrounding grid can still scroll before the long press.
 */
fun Modifier.dragOrMenu(
  key: Any,
  immediateDrag: Boolean = false,
  dragBeforeLongPress: Boolean = false,
  onStart: (Offset) -> Unit,
  onMove: (Offset) -> Unit,
  onEnd: (cancelled: Boolean) -> Unit,
  onMenu: () -> Unit,
  onTap: () -> Unit,
  rootOffset: () -> Offset,
): Modifier = pointerInput(key, immediateDrag, dragBeforeLongPress) {
  awaitEachGesture {
    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
    var released = false
    var earlyDrag = false
    var last = down.position
    val early = withTimeoutOrNull(if (immediateDrag) 0L else viewConfiguration.longPressTimeoutMillis) {
      while (true) {
        val e = awaitPointerEvent(PointerEventPass.Initial)
        val c = e.changes.firstOrNull { it.id == down.id } ?: return@withTimeoutOrNull true
        if (!c.pressed) { released = !c.isConsumed; return@withTimeoutOrNull true }
        if (c.isConsumed) return@withTimeoutOrNull true
        if ((c.position - down.position).getDistance() > viewConfiguration.touchSlop) {
          if (dragBeforeLongPress) {
            earlyDrag = true
            last = c.position
            c.consume()
          }
          return@withTimeoutOrNull true
        }
      }
      @Suppress("UNREACHABLE_CODE") true
    }
    if (early != null && !earlyDrag) {
      if (released) onTap()
      return@awaitEachGesture
    }
    // Long press reached: this gesture now belongs to the icon.
    if (!immediateDrag && !earlyDrag) onMenu()
    var dragging = earlyDrag
    var finished = false
    try {
      if (earlyDrag) {
        onStart(rootOffset() + down.position)
        onMove(rootOffset() + last)
      }
      while (true) {
        val e = awaitPointerEvent(PointerEventPass.Initial)
        val c = e.changes.firstOrNull { it.id == down.id }
        if (c == null || !c.pressed) {
          // Compose delivers Android CANCEL as an already-consumed synthetic UP.
          // Capture that flag before consuming our own release; a cancel must never commit.
          val cancelled = c == null || c.isConsumed
          if (dragging || !immediateDrag) e.changes.forEach { it.consume() }
          finished = true
          if (dragging) {
            if (!cancelled) c?.let { onMove(rootOffset() + it.position) }
            onEnd(cancelled)
          } else if (immediateDrag && !cancelled) onTap()
          return@awaitEachGesture
        }
        if (c.positionChange() != Offset.Zero) last = c.position
        if (!dragging && (last - down.position).getDistance() > viewConfiguration.touchSlop) {
          dragging = true
          onStart(rootOffset() + down.position)
        }
        if (dragging) onMove(rootOffset() + last)
        if (dragging || !immediateDrag) c.consume()
      }
    } finally {
      // The icon left composition or the gesture was cancelled mid-drag.
      if (dragging && !finished) onEnd(true)
    }
  }
}
