package org.agentos.agent.home.island

import android.content.Context
import android.graphics.PointF
import android.os.Build
import android.view.WindowInsets
import expo.modules.kotlin.AppContext
import expo.modules.kotlin.viewevent.EventDispatcher
import expo.modules.kotlin.views.ExpoView

class IslandAnchorView(context: Context, appContext: AppContext) : ExpoView(context, appContext) {
  private val onAnchorChanged by EventDispatcher<Map<String, Float>>()
  private val viewPosition = IntArray(2)
  private val rootPosition = IntArray(2)
  private var lastAnchor: PointF? = null

  override fun onAttachedToWindow() {
    super.onAttachedToWindow()
    requestApplyInsets()
  }

  override fun onDetachedFromWindow() {
    lastAnchor = null
    super.onDetachedFromWindow()
  }

  override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
    reportAnchor(insets)
    return super.onApplyWindowInsets(insets)
  }

  override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
    super.onLayout(changed, left, top, right, bottom)
    reportAnchor()
  }

  private fun reportAnchor(insets: WindowInsets? = rootWindowInsets) {
    if (insets == null || width == 0) return

    val density = resources.displayMetrics.density
    val cutout = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      (display?.cutout ?: insets.displayCutout)?.boundingRectTop?.takeUnless { it.isEmpty }
    } else null
    getLocationOnScreen(viewPosition)
    rootView.getLocationOnScreen(rootPosition)
    val x = cutout?.exactCenterX() ?: (rootPosition[0] + rootView.width / 2f)
    val y = cutout?.exactCenterY() ?: (rootPosition[1] + 33.5f * density)
    val anchor = PointF((x - viewPosition[0]) / density, (y - viewPosition[1]) / density)
    if (anchor == lastAnchor) return

    lastAnchor = anchor
    onAnchorChanged(mapOf("x" to anchor.x, "y" to anchor.y))
  }
}
