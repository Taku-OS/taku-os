package org.agentos.agent.home.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.foundation.pager.PagerState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Velocity
import org.json.JSONObject

/**
 * Gate nested scroll deltas, not touch events: taps, long presses and child drag handlers still
 * receive their original stream. Android retains ownership of its system gestures. The stock
 * Pager keeps its touch-slop, nested child coordination, cancellation and snap implementation.
 */
@Composable
internal fun rememberHomeGestureBoundary(policy: HomeGesturePolicy, pager: PagerState, controller: HomeController): NestedScrollConnection {
  val connection = remember(policy, controller) {
    object : NestedScrollConnection {
      override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        // Programmatic icon edge paging and accessibility scrolls are not pointer gestures.
        if (source != NestedScrollSource.UserInput || !policy.active || controller.drag.item != null) return Offset.Zero
        return Offset(if (policy.blockX()) available.x else 0f, if (policy.blockY()) available.y else 0f)
      }

      override suspend fun onPreFling(available: Velocity): Velocity {
        if (controller.drag.item != null) return Velocity.Zero
        return Velocity(if (policy.blockX()) available.x else 0f, if (policy.blockY()) available.y else 0f)
      }
    }
  }
  DisposableEffect(policy, pager) {
    val reader = {
      val r = policy.region
      JSONObject().put("available", true).put("sequence", policy.sequence)
        .put("active", policy.active).put("owner", policy.owner.name.lowercase())
        .put("systemStart", policy.systemStart).put("terminal", policy.terminal)
        .put("startX", policy.startX).put("startY", policy.startY).put("dx", policy.dx).put("dy", policy.dy)
        .put("region", JSONObject().put("width", r.width).put("height", r.height)
          .put("left", r.left).put("top", r.top).put("right", r.right).put("bottom", r.bottom))
        .put("currentPage", pager.currentPage).put("settledPage", pager.settledPage)
        .put("pageOffset", pager.currentPageOffsetFraction).put("scrolling", pager.isScrollInProgress)
        .put("dragging", controller.drag.item != null)
    }
    HomeGestureSession.read = reader
    onDispose { if (HomeGestureSession.read === reader) HomeGestureSession.read = null }
  }
  return connection
}
