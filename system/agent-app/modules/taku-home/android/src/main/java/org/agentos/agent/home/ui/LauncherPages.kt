package org.agentos.agent.home.ui

import org.agentos.agent.home.design.*
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import org.agentos.agent.home.R
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import org.agentos.agent.home.apps.AppCatalog
import org.agentos.agent.home.apps.HomeLayout
import org.agentos.agent.home.apps.HomeMode
import org.agentos.agent.home.apps.HomeGrid
import org.agentos.agent.home.apps.Slot
import org.agentos.agent.home.feed.FeedState
import kotlin.math.roundToInt

/** Column metrics of the 4-1 grid: 16 dp margins and gaps, four 83 dp cells on a 412 dp screen. */
private fun cellFor(width: Dp): Dp = (width - TakuSpacing.gutter * 2 - 16.dp * 3) / 4

/** Icon cell height: plate + 4 dp + 16 dp label ([IconCell]). */
private val LABEL_BLOCK = 20.dp

/**
 * One displayed home page. Page 0 (shown after unlock) starts with the two widget rows. In
 * [HomeMode.PAGES] (default) a page holds three icon rows under the widgets, six elsewhere, and
 * never scrolls: overflow lives on the next page. In [HomeMode.SCROLL] page 0 lists every app
 * and scrolls under the status-bar and bottom blur. While an icon is dragged the icons slide
 * aside for the gap; [page] equal to the page count is the empty page shown past the last one.
 * Long-press empty space for the layout menu (pages / one scrolling page).
 */
@Composable
fun HomePage(controller: HomeController, page: Int, feed: FeedState, backdrop: Backdrop?, scroll: ScrollState, topInset: Dp, bottomInset: Dp) {
  val context = controller.context
  val catalog = remember { AppCatalog.get(context) }
  val apps by catalog.apps.collectAsStateWithLifecycle()
  val byKey = remember(apps) { apps.associateBy { it.key } }
  val scrolling = controller.homeLayout.mode == HomeMode.SCROLL
  val items = controller.pageItems(page)
  val ghost = controller.ghost(page, items)
  androidx.compose.runtime.DisposableEffect(page, scroll) {
    controller.drag.scrollers[page] = scroll
    onDispose { if (controller.drag.scrollers[page] === scroll) controller.drag.scrollers.remove(page) }
  }
  var origin by remember { mutableStateOf(Offset.Zero) }
  BoxWithConstraints(
    Modifier
      .fillMaxSize()
      .onGloballyPositioned { origin = it.positionInRoot() }
      .passiveLongPress(page) { local ->
        // Icons have their own long press (menu / drag); only empty space opens the layout menu.
        val at = origin + local
        // Widgets are not empty space either: long-pressing one does not open the layout menu.
        val index = controller.drag.grids[page]?.indexAt(at)
        if (index == null || index >= controller.pageItems(page).size) controller.openHomeMenu(at)
      },
  ) {
    val cell = cellFor(maxWidth)
    val availableHeight = maxHeight
    Column(
      verticalArrangement = Arrangement.spacedBy(TakuSpacing.lg),
      modifier = Modifier
        .fillMaxSize()
        .then(if (scrolling) Modifier.verticalScroll(scroll, enabled = controller.drag.item == null) else Modifier)
        .padding(start = TakuSpacing.gutter, end = TakuSpacing.gutter, top = topInset, bottom = if (scrolling) bottomInset else 0.dp),
    ) {
      IconGrid(controller, page, items, ghost, byKey, backdrop, cell, if (scrolling) null else availableHeight - topInset - bottomInset.coerceAtMost(100.dp))
    }
    if (items.isEmpty() && page > 0) {
      BasicText(
        "Drop here to add a page",
        style = TakuTheme.type.bodyMd.copy(color = TakuTheme.colors.textPlaceholder, textAlign = TextAlign.Center),
        modifier = Modifier.align(Alignment.Center).padding(horizontal = 48.dp),
      )
    }
  }
}

/**
 * Four fixed columns of [cell] with 16 dp gaps. Each item keeps its composition across moves
 * (keyed) and slides to its new cell. [ghost] is the lifted icon after it left this page: it
 * stays composed at zero size so its drag gesture lives on.
 */
@Composable
private fun IconGrid(controller: HomeController, page: Int, items: List<Slot>, ghost: Slot?, apps: Map<String, org.agentos.agent.home.apps.LaunchableApp>, backdrop: Backdrop?, cell: Dp, maxGridHeight: Dp?) {
  val density = LocalDensity.current
  val gap = TakuSpacing.lg
  val dragged = controller.drag.item?.slot
  val placements = HomeGrid.place(items)
  val rows = placements.maxOfOrNull { it.y + it.h } ?: 0
  val naturalHeights = (0 until rows).map { row ->
    if (placements.indices.any { items[it] !is Slot.Widget && placements[it].y == row }) cell + LABEL_BLOCK else cell
  }
  val naturalTotal = naturalHeights.fold(0.dp) { a, b -> a + b }
  val scale = if (maxGridHeight == null || rows == 0) 1f else ((maxGridHeight - gap * (rows - 1)).value / naturalTotal.value).coerceIn(0.5f, 1f)
  val heights = naturalHeights.map { it * scale }
  val tops = heights.runningFold(0.dp) { a, b -> a + b + gap }
  fun height(c: HomeGrid.Cell): Dp = (c.y until c.y + c.h).fold(0.dp) { a, row -> a + heights[row] } + gap * (c.h - 1)
  Layout(
    content = {
      // One keyed loop for the items and the ghost, so an item turning into the ghost keeps its
      // composition (and the running gesture).
      (items + listOfNotNull(ghost)).forEachIndexed { index, slot ->
        key(slot.itemKey) {
          val shown = index < items.size
          Box(Modifier.animatePlacement()) {
            if (slot is Slot.Widget) {
              val w = cell * slot.spanX + gap * (slot.spanX - 1)
              val h = placements.getOrNull(index)?.let(::height) ?: cell * slot.spanY + gap * (slot.spanY - 1)
              // The source (including its ghost) owns the live host view until drop commits.
              // A destination preview must not replace AppWidgetHost's single listener for
              // this ID; otherwise returning/cancelling the drag strands the original view.
              val preview = dragged?.itemKey == slot.itemKey && controller.displayPages.getOrElse(page) { emptyList() }.none { it.itemKey == slot.itemKey }
              if (preview) Box(Modifier.size(w, h))
              else HostedWidget(controller, slot, page, index.takeIf { shown }, backdrop, Modifier.size(w, h).graphicsLayer { alpha = if (!shown || dragged?.itemKey == slot.itemKey) 0f else 1f })
            } else GridIcon(controller, slot, apps, backdrop, cell.coerceAtMost((placements.getOrNull(index)?.let(::height) ?: cell + LABEL_BLOCK) - LABEL_BLOCK), page, index.takeIf { shown },
              placeholder = !shown || (dragged != null && slot.itemKey == dragged.itemKey))
          }
        }
      }
    },
    modifier = Modifier.fillMaxWidth().onGloballyPositioned { coords ->
      with(density) {
        controller.drag.grids[page] = GridGeometry(coords.positionInRoot(), cell.toPx(), (cell + LABEL_BLOCK).toPx(), gap.toPx(), gap.toPx(), cell.toPx(), cells = placements.map { c ->
          val x = c.x * (cell + gap).toPx(); val y = tops[c.y].toPx()
          Rect(x, y, x + (cell * c.w + gap * (c.w - 1)).toPx(), y + height(c).toPx())
        })
      }
    },
  ) { measurables, constraints ->
    val cellPx = cell.roundToPx()
    val gapPx = gap.roundToPx()
    val placeables = measurables.mapIndexed { i, m ->
      if (i < items.size) placements[i].let { c -> m.measure(Constraints.fixed(c.w * cellPx + (c.w - 1) * gapPx, height(c).roundToPx())) } else m.measure(Constraints.fixed(0, 0))
    }
    val height = if (rows == 0) 0 else (tops.last() - gap).roundToPx()
    layout(constraints.maxWidth, height) {
      placeables.forEachIndexed { i, p ->
        if (i < items.size) p.place(placements[i].x * (cellPx + gapPx), tops[placements[i].y].roundToPx()) else p.place(0, 0)
      }
    }
  }
}

@Composable
private fun GridIcon(controller: HomeController, slot: Slot, apps: Map<String, org.agentos.agent.home.apps.LaunchableApp>, backdrop: Backdrop?, cell: Dp, page: Int, index: Int?, placeholder: Boolean) {
  Box(Modifier.graphicsLayer { alpha = if (placeholder) 0f else 1f }) {
    when (slot) {
      is Slot.Widget -> Unit
      is Slot.App -> {
        val app = apps[slot.key]
        DraggableIcon(controller, slot, app?.label ?: "", page = page, index = index, plate = cell) { AppIcon(app, slot.key, backdrop, cell) }
      }
      is Slot.Folder -> DraggableIcon(controller, slot, slot.name, page = page, index = index, plate = cell) { FolderIcon(slot, backdrop, cell) }
    }
  }
}

/** Slides an element from its previous place in its parent to the new one (spring). */
private fun Modifier.animatePlacement(): Modifier = composed {
  val scope = rememberCoroutineScope()
  val offset = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
  var last by remember { mutableStateOf<Offset?>(null) }
  this
    .onPlaced { coords ->
      val now = coords.positionInParent()
      val previous = last
      last = now
      if (previous != null && previous != now) {
        val start = offset.value + (previous - now)
        scope.launch {
          offset.snapTo(start)
          offset.animateTo(Offset.Zero, spring(dampingRatio = 0.86f, stiffness = Spring.StiffnessMediumLow))
        }
      }
    }
    .graphicsLayer { translationX = offset.value.x; translationY = offset.value.y }
}

/**
 * An icon cell that launches on tap, opens its menu on long-press and drags after long-press +
 * move. [page]/[index] say where it is shown (null in an open folder); the gesture is keyed by
 * the item alone so it survives the item sliding to another cell while dragged.
 */
@Composable
fun DraggableIcon(controller: HomeController, slot: Slot, label: String, page: Int?, index: Int?, plate: Dp, icon: @Composable () -> Unit) {
  val drag = controller.drag
  val haptics = LocalHapticFeedback.current
  var bounds by remember { mutableStateOf(Rect.Zero) }
  var iconBounds by remember { mutableStateOf(Rect.Zero) }
  var origin by remember { mutableStateOf(Offset.Zero) }
  val where by rememberUpdatedState(if (page != null && index != null) DropTarget(page, index) else null)
  val rotation = if (controller.editingHome) {
    val transition = rememberInfiniteTransition(label = "home-edit")
    val angle by transition.animateFloat(-1.3f, 1.3f, infiniteRepeatable(tween(145 + (slot.itemKey.hashCode() and 31)), RepeatMode.Reverse), label = "icon-wiggle")
    angle
  } else 0f
  IconCell(
    label,
    plate,
    Modifier
      .graphicsLayer { rotationZ = rotation }
      .onGloballyPositioned { origin = it.positionInRoot(); bounds = it.boundsInRoot() }
      .semantics { contentDescription = label }
      .dragOrMenu(
        key = slot,
        immediateDrag = controller.editingHome,
        // Folder contents do not scroll or page: a direct drag can lift an app
        // out, while a stationary long press still opens its context menu.
        dragBeforeLongPress = page == null,
        onStart = { p ->
          drag.menu = null
          haptics.performHapticFeedback(HapticFeedbackType.LongPress)
          drag.start(DragItem(slot, label, null), p, Rect(bounds.left, bounds.top, bounds.left + bounds.width, bounds.top + bounds.width), where)
        },
        onMove = { drag.move(it) },
        onEnd = { cancelled -> controller.dropDragged(cancelled) },
        onMenu = {
          haptics.performHapticFeedback(HapticFeedbackType.LongPress)
          drag.menu = IconMenuTarget(slot, label, bounds, inFolder = page == null, iconBounds = iconBounds)
        },
        onTap = { if (!controller.editingHome || slot is Slot.Folder) controller.openSlot(slot, bounds) },
        rootOffset = { origin },
      ),
  ) {
    Box(Modifier.onGloballyPositioned { iconBounds = it.boundsInRoot() }) {
      icon()
      if (controller.editingHome && slot is Slot.App) {
        TakuIconButton(TakuIcons.Minus, stringResource(R.string.home_remove_label, label),
          { controller.requestRemove(slot.key) }, modifier = Modifier.offset((-4).dp, (-4).dp), style = IconButtonStyle.Badge)
      }
    }
  }
}
