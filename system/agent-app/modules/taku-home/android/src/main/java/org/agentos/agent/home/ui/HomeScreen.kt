package org.agentos.agent.home.ui

import org.agentos.agent.home.design.*
import android.os.Build
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import org.agentos.agent.home.ui.assistant.AssistantLayer
import org.agentos.agent.home.panels.PanelNavigation
import org.agentos.agent.home.panels.PanelPage
import org.agentos.agent.home.panels.PanelsUpdateRequiredPage
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.agentos.agent.home.ui.effects.Shaders

/** The feed: the leftmost page. */
const val PAGE_FEED = 0
/** Home page 0 (widgets + apps): the page shown after unlock. Home page n is pager page PAGE_HOME + n. */
const val PAGE_HOME = 1

/**
 * Home: wallpaper + pager (feed | home pages…) recorded into one scene layer that the status-bar
 * edge, the bottom glass and the long-press scrim sample.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun HomeScreen(controller: HomeController, backdrop: Backdrop?, pager: PagerState, feedList: LazyListState, homeScroll: ScrollState, gestures: HomeGesturePolicy) {
  val density = LocalDensity.current
  val gestureBoundary = rememberHomeGestureBoundary(gestures, pager, controller)
  val state by controller.repo.state.collectAsStateWithLifecycle()
  // Ignoring visibility: the expanded Dynamic Island hides the status bar without moving the page.
  val statusInset = with(density) { WindowInsets.statusBarsIgnoringVisibility.getTop(this).toDp() }
  val navInset = with(density) { WindowInsets.navigationBars.getBottom(this).toDp() }
  // The frames put the first content at y = 65 under a 52 dp status bar.
  val topInset = max(statusInset, 52.dp) + 13.dp
  val bottomInset = 150.dp + navInset
  val scene = rememberGraphicsLayer()
  // Only feed-card menus blur the scene. Launcher menus use a light scrim with a clear icon.
  val menuOpen = controller.menu != null
  val scrim by animateFloatAsState(if (menuOpen) 1f else 0f, tween(220), label = "scrim")

  LaunchedEffect(pager) {
    snapshotFlow { pager.currentPage == PAGE_FEED && !pager.isScrollInProgress }.collect { controller.feedOnScreen = it }
  }
  LaunchedEffect(Unit) { controller.repo.ensureLoaded() }
  LaunchedEffect(pager) {
    snapshotFlow { pager.currentPage }.collect {
      val page = controller.homePageAt(it)
      if (page != null && controller.drag.item != null) controller.drag.enter(page) else controller.drag.page = page
    }
  }
  // While dragging an icon: resting at a side edge turns the page (never onto the feed, over the
  // panel pages; past the last home page onto the empty one), and near the top or bottom a long page scrolls.
  val dragging = controller.drag.item != null
  var wasDragging by remember { mutableStateOf(false) }
  LaunchedEffect(dragging, controller.displayPages.size) {
    if (!dragging && wasDragging) {
      // Ending a drag cancels its edge-paging animation, possibly between page boundaries.
      // Snap the visible home page even when it remains in range; a cancelled drop may also
      // remove the temporary last app page. Neither case should leave an offset or a panel.
      val lastHome = controller.pagerPage(controller.displayPages.lastIndex)
      pager.scrollToPage(pager.currentPage.coerceIn(PAGE_HOME, lastHome))
    }
    wasDragging = dragging
  }
  LaunchedEffect(dragging) {
    if (!dragging) return@LaunchedEffect
    val edge = with(density) { 28.dp.toPx() }
    val scrollZone = with(density) { 72.dp.toPx() }
    val scrollStep = with(density) { 18.dp.toPx() }
    var heldSince = 0L
    while (controller.drag.item != null) {
      val (x, y) = controller.drag.pointer
      val width = controller.screenWidth
      val current = controller.homePageAt(pager.currentPage)
      val target = when {
        current == null -> null
        x < edge && current > 0 -> controller.pagerPage(current - 1)
        x > width - edge -> controller.pagerPage(current + 1).takeIf { controller.homePageAt(it) != null }
        else -> null
      }
      if (target == null) heldSince = 0L
      else if (heldSince == 0L) heldSince = System.currentTimeMillis()
      else if (System.currentTimeMillis() - heldSince > 450 && !pager.isScrollInProgress) {
        pager.animateScrollToPage(target)
        // The page observer above updates on a later frame; aim at the new page right away.
        controller.homePageAt(target)?.let { controller.drag.enter(it) }
        heldSince = 0L
      }
      // A resting finger still moves the gap or picks a folder target once it has dwelt.
      controller.drag.move(controller.drag.pointer)
      val scroller = controller.drag.page?.let { controller.drag.scrollers[it] }
      val top = with(density) { topInset.toPx() }
      val bottom = controller.screenHeight - with(density) { bottomInset.toPx() }
      val delta = when {
        y < top + scrollZone -> -scrollStep
        y > bottom - scrollZone -> scrollStep
        else -> 0f
      }
      if (scroller != null && delta != 0f && target == null && scroller.maxValue > 0) {
        scroller.scrollBy(delta)
        controller.drag.move(controller.drag.pointer)
      }
      kotlinx.coroutines.delay(50)
    }
  }

  run {
    Box(Modifier.fillMaxSize().onSizeChanged { controller.screenHeight = it.height.toFloat(); controller.screenWidth = it.width.toFloat() }) {
      Box(
        Modifier
          .fillMaxSize()
          .graphicsLayer {
            if (scrim > 0f && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
              renderEffect = Shaders.gaussian(4.dp.toPx() * scrim).asComposeRenderEffect()
            } else renderEffect = null
          },
      ) {
        Box(
          Modifier
            .fillMaxSize()
            .drawWithContent {
              scene.record { this@drawWithContent.drawContent() }
              drawLayer(scene)
            },
        ) {
          val canvas = TakuTheme.colors.canvas
          // One screen-fixed wallpaper behind every page; only pager content moves horizontally.
          Box(Modifier.fillMaxSize().drawBehind {
            if (backdrop != null) drawImage(backdrop.sharp, dstSize = IntSize(size.width.toInt(), size.height.toInt())) else drawRect(canvas)
          })
          // While dragging, every page stays composed: the lifted icon's gesture lives on its source
          // page, which would otherwise leave composition two pages away and cancel the drag.
          HorizontalPager(
            state = pager,
            beyondViewportPageCount = if (dragging) pager.pageCount else 1,
            userScrollEnabled = !dragging,
            key = { controller.pageKey(it) },
            modifier = Modifier.fillMaxSize().nestedScroll(gestureBoundary),
          ) { page ->
            // Each page is clipped to its own bounds: feed cards (React surfaces, glows) must not
            // leave a sliver on the neighbouring page once the pager settles (#30 N4).
            Box(Modifier.fillMaxSize().clipToBounds()) {
              val panelId = controller.panelAt(page)
              when {
                page == PAGE_FEED -> FeedPage(state, controller, backdrop, feedList, topInset, bottomInset)
                page == PAGE_HOME -> HomePage(controller, 0, state, backdrop, homeScroll, topInset - 1.dp + if (controller.editingHome) 48.dp else 0.dp, bottomInset)
                panelId != null -> PanelPage(
                  panelId = panelId,
                  title = controller.panelTitle(panelId),
                  pool = controller.pool,
                  mounted = kotlin.math.abs(page - pager.currentPage) <= 1,
                  top = topInset,
                  bottom = bottomInset,
                )
                page == controller.updateRequiredPage -> PanelsUpdateRequiredPage(topInset, bottomInset)
                else -> HomePage(controller, controller.homePageAt(page) ?: 0, state, backdrop, rememberScrollState(), topInset - 1.dp + if (controller.editingHome) 48.dp else 0.dp, bottomInset)
              }
            }
          }
          // The desktop's navigation state is a projection of what the pager shows right now.
          val navigation = PanelNavigation.Snapshot(
            available = controller.availablePanelIds,
            visible = controller.mountedPanelIds(pager.currentPage),
            selectedPanelId = controller.selectedPanelId,
          )
          LaunchedEffect(navigation) { PanelNavigation.publish(navigation) }
          DustOverlay(controller)
        }
        TopEdgeBlur(scene, statusInset + 28.dp)
        if (controller.editingHome) {
          PageDots(pager.pageCount, pager.currentPage, Modifier.align(Alignment.BottomCenter).padding(bottom = navInset + TakuSpacing.sm))
        } else AssistantEntry(scene, controller, pager.pageCount, pager.currentPage, navInset, gestures)
      }
      HomeWorkspacePreviews(controller, onOpenWorkspace = controller::openWorkspace)
      FolderSheet(controller, backdrop)
      if (controller.editingHome) HomeEditingToolbar(controller, scene, statusInset)
      LauncherHomeMenu(controller, scene)
      DragOverlay(controller, backdrop)
      LauncherIconMenu(controller, scene)
      WidgetDialogs(controller, backdrop)
      LauncherHiddenApps(controller, backdrop)
      LauncherRemoval(controller, backdrop)
      controller.menu?.let { target ->
        CardMenu(target, controller, scrim, controller.screenHeight, with(density) { topInset.toPx() })
      }
      AssistantLayer(scene, controller)
    }
  }
}

/**
 * Progressive blur under the status bar: σ 6 dp at the top edge easing to sharp
 * 28 dp below the status bar, so feed content scrolling under the system icons frosts.
 * (Product request; the Figma frames leave this edge unblurred.)
 */
@Composable
private fun TopEdgeBlur(scene: GraphicsLayer, height: Dp) {
  if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
  val strip = rememberGraphicsLayer()
  val key = remember { arrayOfNulls<Any>(1) }
  Canvas(Modifier.fillMaxWidth().height(height)) {
    if (key[0] != size) {
      strip.renderEffect = Shaders.progressiveBlur(size.width, size.height, 0f, 6.dp.toPx(), size.height, 0f).asComposeRenderEffect()
      key[0] = size
    }
    strip.clip = true
    strip.record(IntSize(size.width.toInt(), size.height.toInt())) { drawLayer(scene) }
    drawLayer(strip)
  }
}

@Composable
private fun DustOverlay(controller: HomeController) {
  val runs = controller.dusts
  if (runs.isEmpty()) return
  Canvas(Modifier.fillMaxSize()) {
    drawIntoCanvas { canvas -> for (run in runs) run.dissolve.draw(canvas.nativeCanvas, run.progress.value) }
  }
}
