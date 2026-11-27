package com.android.systemui.taku.lockscreen

import android.content.Context
import android.graphics.Canvas
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.widget.LinearLayout
import android.widget.ScrollView
import kotlin.math.abs

/**
 * The lock screen's notification list (Figma 979:641): one flat column of items, 16 dp apart,
 * scrolling under the bottom bar. The part under the bar is drawn through the same blur and edge
 * refraction as [TakuLockscreenBackgroundDrawable]'s bar, so items pass beneath it as in the design.
 *
 * Collapsed and expanded, like an iPhone ([TakuLockscreenExpansion], progress 0…1):
 * - collapsed: the first item rests [STACK_BOTTOM_DP] above the bottom, over the shortcuts; the
 *   others are hidden behind it, slightly smaller and lower, as a stack;
 * - while the user drags up the stack fans out, each item moving to its place in the column;
 * - expanded: the plain column under the compact clock, scrollable.
 * The column is always laid out expanded; collapsing is translation, scale and alpha only.
 *
 * Touch, when there is anything to show: the list takes vertical drags starting anywhere in its area
 * except the [UNLOCK_ZONE_DP] above the navigation bar. Up opens, down closes. A drag down on the
 * collapsed list goes back to the window root, which pulls the shade. The expanded list scrolls; a
 * drag down at its top closes it, and a drag up at its end does nothing (no accidental unlock).
 * Swipes from the gesture bar go to the shade's own touch handler (`LauncherProxyService` →
 * `handleExternalTouch`), never through here, so they stay the system unlock. An empty list takes
 * no touches: a swipe up anywhere is the stock unlock.
 */
class TakuNotificationListView(context: Context, private val expansion: TakuLockscreenExpansion) : ScrollView(context) {
    private val density = resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    /**
     * While the stack is not fully open the first item is drawn last, on top of the items behind it,
     * and so it also gets touches first (touch dispatch follows the drawing order).
     */
    private val column = object : LinearLayout(context) {
        init { isChildrenDrawingOrderEnabled = true }
        override fun getChildDrawingOrder(childCount: Int, drawingPosition: Int): Int =
            if (progress < 1f) childCount - 1 - drawingPosition else drawingPosition
    }.apply {
        orientation = LinearLayout.VERTICAL
        val side = dp(16f)
        setPadding(side, 0, side, dp(TakuLockscreenBackgroundDrawable.BAR_HEIGHT_DP))
        clipChildren = false
    }
    private val views = LinkedHashMap<String, TakuNotificationCardView>()

    /**
     * A view pinned above the cards (the Dynamic Island's card). It stays the column's first child;
     * while it is GONE the first card starts the column as before.
     */
    var header: View? = null
        set(value) {
            if (field === value) return
            field?.let(column::removeView)
            field = value
            if (value != null) column.addView(value, 0, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }

    /** Recorded children, drawn once clipped above the bar and once through the bar effect. */
    private val content = RenderNode("TakuNotificationList")
    private val bar = RenderNode("TakuNotificationListBar")
    private var barEffectSize = 0L

    var callbacks: TakuNotificationCardView.Callbacks? = null
        set(value) {
            field = value
            views.values.forEach { it.callbacks = value }
        }

    init {
        isVerticalScrollBarEnabled = false
        overScrollMode = OVER_SCROLL_NEVER
        isFillViewport = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        addView(column, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    /** Shows [cards] in order, reusing each card's view by notification key. */
    fun submit(cards: List<TakuNotificationCard>) {
        val keys = cards.mapTo(HashSet()) { it.key }
        views.keys.filter { it !in keys }.forEach { key -> views.remove(key)?.let(column::removeView) }
        val pinned = header
        val offset = if (pinned != null) 1 else 0
        cards.forEachIndexed { index, card ->
            val position = index + offset
            val view = views.getOrPut(card.key) {
                TakuNotificationCardView(context).also { it.callbacks = callbacks }
            }
            view.bind(card)
            if (column.getChildAt(position) !== view) {
                (view.parent as? LinearLayout)?.removeView(view)
                column.addView(view, position, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
                ))
            }
            (view.layoutParams as LinearLayout.LayoutParams).let { params ->
                val margin = if (index > 0 || pinned?.visibility == View.VISIBLE) dp(16f) else 0
                if (params.topMargin != margin) {
                    params.topMargin = margin
                    view.layoutParams = params
                }
            }
        }
    }

    /** A dismissal the notification refused: put its card back. */
    fun restore(key: String) {
        views[key]?.resetSwipe()
    }

    val cardCount: Int get() = views.size

    // region Drawing

    override fun dispatchDraw(canvas: Canvas) {
        val w = width
        val h = height
        if (!canvas.isHardwareAccelerated || w == 0 || h == 0) return super.dispatchDraw(canvas)
        val barHeight = dp(TakuLockscreenBackgroundDrawable.BAR_HEIGHT_DP).coerceAtMost(h)
        // The canvas is already translated by -scrollY: these are content coordinates.
        val barTop = scrollY + h - barHeight
        val contentHeight = maxOf(column.bottom, scrollY + h)

        content.setPosition(0, 0, w, contentHeight)
        val recording = content.beginRecording(w, contentHeight)
        super.dispatchDraw(recording)
        content.endRecording()

        canvas.save()
        canvas.clipRect(0, scrollY, w, barTop)
        canvas.drawRenderNode(content)
        canvas.restore()

        val size = (w.toLong() shl 32) or barHeight.toLong()
        if (size != barEffectSize) {
            barEffectSize = size
            bar.setRenderEffect(barEffect(w, barHeight))
        }
        bar.setPosition(0, 0, w, barHeight)
        val barRecording = bar.beginRecording(w, barHeight)
        barRecording.translate(0f, -barTop.toFloat())
        barRecording.drawRenderNode(content)
        bar.endRecording()
        canvas.save()
        canvas.translate(0f, barTop.toFloat())
        canvas.drawRenderNode(bar)
        canvas.restore()
    }

    private fun barEffect(w: Int, barHeight: Int): RenderEffect =
        TakuLockscreenBackgroundDrawable.barEffect(w, barHeight, dp(TakuLockscreenBackgroundDrawable.BAR_BLUR_DP).toFloat())

    // endregion

    // region Collapse and expand

    private var progress = 0f

    /** Items that take part: the island's card when shown, then the notification cards. */
    private fun items(): List<View> = (0 until column.childCount).map(column::getChildAt).filter { it.visibility != View.GONE }

    /** Where the first item's top rests when collapsed, in column coordinates. */
    private fun stackTop(first: View): Float =
        (height - dp(STACK_BOTTOM_DP) - first.height - column.top + scrollY).toFloat()

    /** How far the first item travels between collapsed and expanded: the drag that opens the list. */
    private fun travel(): Float {
        val first = items().firstOrNull() ?: return dp(MIN_TRAVEL_DP).toFloat()
        return maxOf(stackTop(first) - first.top, dp(MIN_TRAVEL_DP).toFloat())
    }

    /** From [TakuLockscreenExpansion.progress]. */
    fun setProgress(value: Float) {
        val reorder = (progress < 1f) != (value < 1f)
        progress = value
        if (reorder) column.invalidate()
        if (value < 1f && scrollY != 0) scrollTo(0, 0)
        applyProgress()
    }

    private fun applyProgress() {
        val shown = items()
        val first = shown.firstOrNull() ?: return
        val eased = progress
        val restTop = stackTop(first)
        val restBottom = restTop + first.height
        shown.forEachIndexed { index, item ->
            // Collapsed: bottom-aligned behind the first item, each a little lower and smaller.
            val depth = minOf(index, STACK_DEPTH)
            val collapsedScale = 1f - STACK_SCALE_STEP * depth
            val collapsedTop = restBottom + dp(STACK_OFFSET_DP) * depth - item.height
            val collapsedY = collapsedTop - item.top
            item.pivotY = item.height.toFloat()
            item.translationY = collapsedY * (1f - eased)
            val scale = collapsedScale + (1f - collapsedScale) * eased
            item.scaleX = scale
            item.scaleY = scale
            // Only the first item shows when collapsed; the rest appear as the stack fans out. A fully
            // hidden item is INVISIBLE so it takes no touches behind the first one.
            item.alpha = if (index == 0) 1f else (eased / FAN_IN).coerceIn(0f, 1f)
            val visibility = if (item.alpha > 0f) View.VISIBLE else View.INVISIBLE
            if (item.visibility != visibility) item.visibility = visibility
        }
        column.invalidate()
        invalidate()
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        applyProgress()
    }

    // endregion

    // region Touch

    private enum class Mode { NONE, UNDECIDED, DRAG, SCROLL, CHILD }

    private var mode = Mode.NONE
    private var downY = 0f
    private var downX = 0f
    private var dragStart = 0f
    private var velocity: VelocityTracker? = null

    /** Height above the bottom left to the stock gestures: the navigation bar plus [UNLOCK_ZONE_DP]. */
    private fun unlockZone(): Int =
        (rootWindowInsets?.getInsets(WindowInsets.Type.navigationBars())?.bottom ?: 0) + dp(UNLOCK_ZONE_DP)

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            mode = Mode.NONE
            if (items().isEmpty() || event.y >= height - unlockZone()) return false
            mode = Mode.UNDECIDED
            downX = event.x
            downY = event.y
            velocity?.recycle()
            velocity = VelocityTracker.obtain()
            // Keep the window root from taking the drag before the list knows its direction.
            parent?.requestDisallowInterceptTouchEvent(true)
        }
        if (mode == Mode.NONE) return false
        velocity?.addMovement(event)
        if (mode == Mode.UNDECIDED && event.actionMasked == MotionEvent.ACTION_MOVE) decide(event)
        val handled = when (mode) {
            Mode.DRAG -> { drag(event); true }
            Mode.NONE -> false
            // Taps and sideways swipes on cards; the expanded list's own scrolling.
            else -> super.dispatchTouchEvent(event)
        }
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            if (mode == Mode.DRAG) release(event.actionMasked == MotionEvent.ACTION_CANCEL)
            mode = Mode.NONE
            velocity?.recycle()
            velocity = null
        }
        return handled
    }

    private fun decide(event: MotionEvent) {
        val dx = event.x - downX
        val dy = event.y - downY
        if (abs(dx) > touchSlop && abs(dx) > abs(dy)) {
            mode = Mode.CHILD
            return
        }
        if (abs(dy) <= touchSlop || abs(dy) <= abs(dx)) return
        val open = progress >= 1f
        mode = when {
            // Collapsed, finger down: the shade's pull-down, as before.
            progress <= 0f && dy > 0 -> Mode.NONE
            // Open and scrolled: the list scrolls; at its top, a drag down closes it.
            open && (dy < 0 || canScrollVertically(-1)) -> Mode.SCROLL
            else -> Mode.DRAG
        }
        when (mode) {
            Mode.NONE -> {
                cancelChildren(event)
                parent?.requestDisallowInterceptTouchEvent(false)
            }
            Mode.DRAG -> {
                cancelChildren(event)
                dragStart = progress
                downY = event.y
                expansion.beginDrag()
            }
            else -> {}
        }
    }

    private fun drag(event: MotionEvent) {
        if (event.actionMasked != MotionEvent.ACTION_MOVE) return
        expansion.drag(dragStart + (downY - event.y) / travel())
    }

    /** Fling or past halfway opens, else it closes; a cancelled gesture goes back to where it began. */
    private fun release(canceled: Boolean) {
        val tracker = velocity
        tracker?.computeCurrentVelocity(1000)
        val speed = tracker?.yVelocity ?: 0f
        val fling = dp(FLING_DP_PER_S)
        val open = when {
            canceled -> dragStart >= 1f
            speed < -fling -> true
            speed > fling -> false
            else -> progress >= OPEN_THRESHOLD
        }
        expansion.settle(open)
    }

    private fun cancelChildren(event: MotionEvent) {
        val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
        super.dispatchTouchEvent(cancel)
        cancel.recycle()
    }

    // endregion

    private fun dp(value: Float) = (value * density).toInt()

    companion object {
        /** The collapsed first item's bottom, above the screen's bottom: just over the shortcuts. */
        const val STACK_BOTTOM_DP = 112f
        /** Each item behind it sits this much lower and 5 % smaller, at most two deep. */
        const val STACK_OFFSET_DP = 8f
        const val STACK_SCALE_STEP = 0.05f
        const val STACK_DEPTH = 2
        /** The hidden items are fully visible once the list is this far open. */
        const val FAN_IN = 0.35f
        /** Above the navigation bar, a drag starting this low is left to the stock unlock. */
        const val UNLOCK_ZONE_DP = 40f
        const val MIN_TRAVEL_DP = 160f
        const val OPEN_THRESHOLD = 0.4f
        const val FLING_DP_PER_S = 600f
    }
}
