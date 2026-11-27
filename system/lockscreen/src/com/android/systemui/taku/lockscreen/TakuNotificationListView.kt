package com.android.systemui.taku.lockscreen

import android.content.Context
import android.graphics.Canvas
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.LinearLayout
import android.widget.ScrollView
import kotlin.math.abs

/**
 * The authenticated lock screen's notification list (Figma 979:641): one flat column of cards, 16 dp
 * apart, scrolling under the bottom bar. The part under the bar is drawn through the same blur and
 * edge refraction as [TakuLockscreenBackgroundDrawable]'s bar, so cards pass beneath it as in the design.
 *
 * Touch: the window root turns vertical drags on the lock screen into the shade (down) or unlock
 * (up). The list keeps a gesture only when it starts on a card and the list can scroll that way;
 * everything else (empty space, the bar, a drag past either end) stays with the stock gestures.
 */
class TakuNotificationListView(context: Context) : ScrollView(context) {
    private val density = resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val column = LinearLayout(context).apply {
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

    // region Touch

    private var claimed = false
    private var decided = false
    private var downY = 0f
    private var downX = 0f

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val barTop = height - dp(TakuLockscreenBackgroundDrawable.BAR_HEIGHT_DP)
                claimed = event.y < barTop && cardAt(event.x, event.y) != null
                if (!claimed) return false
                decided = false
                downX = event.x
                downY = event.y
                // Keep the window root from taking the drag before the list knows its direction.
                if (canScrollVertically(1) || canScrollVertically(-1)) parent?.requestDisallowInterceptTouchEvent(true)
            }
        }
        if (!claimed) return false
        val handled = super.dispatchTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_MOVE && !decided) {
            val dx = event.x - downX
            val dy = event.y - downY
            if (abs(dy) > touchSlop && abs(dy) > abs(dx)) {
                decided = true
                // Finger up scrolls content down (direction 1). Past either end, hand the gesture
                // back so the stock shade (down) or unlock (up) can take it on the next move.
                if (!canScrollVertically(if (dy < 0) 1 else -1)) parent?.requestDisallowInterceptTouchEvent(false)
            } else if (abs(dx) > touchSlop) {
                decided = true
            }
        }
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) claimed = false
        return handled
    }

    private fun cardAt(x: Float, y: Float): View? {
        val cy = y + scrollY - column.top
        val cx = x - column.left
        for (index in 0 until column.childCount) {
            val child = column.getChildAt(index)
            if (cx >= child.left && cx < child.right && cy >= child.top && cy < child.bottom) return child
        }
        return null
    }

    // endregion

    private fun dp(value: Float) = (value * density).toInt()
}
