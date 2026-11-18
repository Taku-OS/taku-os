package org.agentos.agent.home.ui

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.agentos.agent.home.apps.Slot
import org.agentos.agent.home.design.*

/** Native touch arbitration preserves RemoteViews buttons/scrolling until a long press wins. */
private class WidgetTouchFrame(context: Context) : FrameLayout(context) {
  var edit = false
  var start: (Offset, Rect) -> Unit = { _, _ -> }
  var move: (Offset) -> Unit = {}
  var end: (Boolean) -> Unit = {}
  var menu: (Rect) -> Unit = {}
  private val handler = Handler(Looper.getMainLooper())
  private var down = Offset.Zero
  private var held = false
  private var dragging = false
  private val hold = Runnable { held = true; parent?.requestDisallowInterceptTouchEvent(true) }
  private fun bounds(): Rect { val p = IntArray(2); getLocationOnScreen(p); return Rect(p[0].toFloat(), p[1].toFloat(), (p[0] + width).toFloat(), (p[1] + height).toFloat()) }
  override fun dispatchTouchEvent(e: MotionEvent): Boolean {
    val at = Offset(e.rawX, e.rawY)
    when (e.actionMasked) {
      MotionEvent.ACTION_DOWN -> { down = at; held = edit; dragging = false; if (edit) parent?.requestDisallowInterceptTouchEvent(true) else handler.postDelayed(hold, ViewConfiguration.getLongPressTimeout().toLong()) }
      MotionEvent.ACTION_MOVE -> {
        if ((at - down).getDistance() > ViewConfiguration.get(context).scaledTouchSlop) {
          if (!held) handler.removeCallbacks(hold)
          else {
            if (!dragging) {
              dragging = true
              val cancel = MotionEvent.obtain(e); cancel.action = MotionEvent.ACTION_CANCEL; super.dispatchTouchEvent(cancel); cancel.recycle()
              start(down, bounds())
            }
            move(at); return true
          }
        }
      }
      MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
        handler.removeCallbacks(hold)
        parent?.requestDisallowInterceptTouchEvent(false)
        if (dragging) { move(at); end(e.actionMasked == MotionEvent.ACTION_CANCEL); dragging = false; return true }
        if (held && e.actionMasked == MotionEvent.ACTION_UP) { menu(bounds()); held = false; return true }
      }
    }
    return if (edit) true else super.dispatchTouchEvent(e)
  }
  override fun onDetachedFromWindow() { handler.removeCallbacks(hold); if (dragging) { dragging = false; end(true) }; super.onDetachedFromWindow() }
}

@Composable
fun HostedWidget(controller: HomeController, slot: Slot.Widget, page: Int, index: Int?, backdrop: Backdrop?, modifier: Modifier) {
  val context = LocalContext.current
  val info = controller.widgetHost.info(slot.appWidgetId)
  if (info == null) {
    Box(modifier.background(TakuTheme.colors.surfaceCard, RoundedCornerShape(TakuRadius.card)).clickable { controller.removeWidget(slot.appWidgetId) }.padding(16.dp)) {
      BasicText("Widget unavailable\nTap to remove", style = TakuTheme.type.bodyMd)
    }
    return
  }
  val label = info.loadLabel(context.packageManager)
  val current by rememberUpdatedState(slot)
  val currentIndex by rememberUpdatedState(index)
  // The original glass surface remains a host treatment; content/data are still RemoteViews.
  val bundled = info.provider.packageName in setOf("org.taku.widgets", "org.taku.weather")
  GlassSurface(if (bundled) backdrop else null, modifier,
    shape = if (bundled) RoundedCornerShape(TakuRadius.card) else RectangleShape, fill = Color.Transparent) {
    AndroidView(modifier = Modifier.fillMaxSize(), factory = {
      WidgetTouchFrame(it).apply {
        val view = controller.widgetHost.host.createView(it.applicationContext, slot.appWidgetId, info)
        controller.widgetHost.rememberView(slot.appWidgetId, view)
        view.setPadding(0, 0, 0, 0)
        view.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
          if (view.width > 0 && view.height > 0) {
            val d = context.resources.displayMetrics.density
            controller.widgetHost.updateMeasuredSize(slot.appWidgetId, (view.width / d).toInt(), (view.height / d).toInt())
          }
        }
        addView(view, FrameLayout.LayoutParams(-1, -1))
        start = { point, bounds ->
          controller.drag.menu = null
          val preview = runCatching {
            android.graphics.Bitmap.createBitmap(width.coerceAtLeast(1), height.coerceAtLeast(1), android.graphics.Bitmap.Config.ARGB_8888).also { draw(android.graphics.Canvas(it)) }.asImageBitmap()
          }.getOrNull()
          controller.drag.start(DragItem(current, label, preview), point, bounds, currentIndex?.let { i -> DropTarget(page, i) })
        }
        move = { point -> controller.drag.move(point) }
        end = { cancelled -> controller.dropDragged(cancelled) }
        menu = { rect -> controller.drag.menu = IconMenuTarget(current, label, rect, false, rect) }
      }
    }, update = { frame ->
      frame.edit = controller.editingHome
    })
  }
}

@Composable
fun WidgetDragPreview(controller: HomeController, slot: Slot.Widget, width: Dp) {
  val label = controller.widgetHost.info(slot.appWidgetId)?.loadLabel(controller.context.packageManager) ?: "Widget"
  val image = controller.drag.item?.icon
  if (image != null) {
    Image(image, label, Modifier.size(width, width * image.height.toFloat() / image.width))
    return
  }
  Box(Modifier.size(width, width * slot.spanY / slot.spanX).background(TakuTheme.colors.surfaceCard, RoundedCornerShape(TakuRadius.card)).padding(16.dp)) {
    BasicText(label, style = TakuTheme.type.bodyLg)
  }
}

@Composable
fun WidgetDialogs(controller: HomeController, backdrop: Backdrop?) {
  if (controller.showingWidgetPicker) {
    val close = { controller.showingWidgetPicker = false }
    BackHandler(onBack = close)
    TakuDialog(close, rememberAppear(true), backdrop = backdrop) {
      BasicText("Add widget", style = TakuTheme.type.headingXl)
      val providers = remember { controller.widgetHost.providers() }
      val pending = remember { controller.widgetHost.instancesJson().getJSONArray("pending") }
      if (pending.length() > 0) {
        BasicText("Finish adding", style = TakuTheme.type.bodyLg)
        for (i in 0 until pending.length()) {
          val request = pending.getJSONObject(i)
          val id = request.getInt("appWidgetId")
          val label = runCatching { controller.widgetHost.provider(request.getString("provider")).loadLabel(controller.context.packageManager) }.getOrDefault("Widget unavailable")
          BasicText(label, style = TakuTheme.type.bodySm)
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TakuButton("Resume", { controller.resumeWidget(id) }, Modifier.weight(1f), style = ButtonStyle.Frosted)
            TakuButton("Cancel", { controller.cancelWidget(id) }, Modifier.weight(1f), style = ButtonStyle.Frosted)
          }
        }
      }
      Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
        providers.forEach { p ->
          val size = controller.widgetHost.defaultSpan(p)
          Column(Modifier.fillMaxWidth().clickable { controller.addWidget(p.provider.flattenToString()) }.padding(vertical = 12.dp)) {
            BasicText(p.loadLabel(controller.context.packageManager), style = TakuTheme.type.bodyLg)
            BasicText("${controller.context.packageManager.getApplicationInfo(p.provider.packageName, 0).loadLabel(controller.context.packageManager)} · ${size.first} × ${size.second}", style = TakuTheme.type.bodySm)
          }
        }
      }
      TakuButton("Cancel", close, Modifier.fillMaxWidth())
    }
  }
  controller.resizingWidget?.let { slot ->
    val close = { controller.resizingWidget = null }
    BackHandler(onBack = close)
    TakuDialog(close, rememberAppear(slot.appWidgetId), backdrop = backdrop) {
      BasicText("Resize widget", style = TakuTheme.type.headingXl)
      BasicText("Choose width × height", style = TakuTheme.type.bodyMd)
      val info = controller.widgetHost.info(slot.appWidgetId)
      val sizes = if (info == null) emptyList() else (1..6).flatMap { y -> (1..4).mapNotNull { x ->
        if (runCatching { controller.widgetHost.validateSize(info, x, y, slot) }.isSuccess) x to y else null
      } }
      Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
        sizes.chunked(3).forEach { row ->
          Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            row.forEach { (x, y) -> TakuButton("$x × $y", { controller.resizeWidget(slot.appWidgetId, x, y) }, Modifier.weight(1f), style = ButtonStyle.Frosted) }
          }
        }
      }
      if (sizes.isEmpty()) BasicText("This widget cannot be resized.", style = TakuTheme.type.bodyMd)
      TakuButton("Cancel", close, Modifier.fillMaxWidth())
    }
  }
}
