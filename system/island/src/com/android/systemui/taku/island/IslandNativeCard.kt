/* Copyright 2026 Taku OS. SPDX-License-Identifier: Apache-2.0 */
package com.android.systemui.taku.island

import android.content.Context
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.LinearLayout
import android.widget.ScrollView
import org.json.JSONObject

/**
 * Shared input for SystemUI-drawn island cards: an upward swipe collapses, a tap on the card
 * (outside its own controls) runs the primary action, and readable text keeps scrolling first.
 */
internal abstract class IslandNativeCard(context: Context, protected val command: (String, String?, String?) -> Unit,
    protected val collapse: (String) -> Unit) : LinearLayout(context) {
    protected val density = resources.displayMetrics.density
    protected val scaled = resources.displayMetrics.scaledDensity
    protected var item: IslandActivity? = null
    private val swipeSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val tapTimeout = ViewConfiguration.getLongPressTimeout().toLong()
    private var downX = 0f
    private var downY = 0f
    private var downAt = 0L
    private var swipeEligible = false
    private var swiped = false
    private var tapEligible = false

    /** The scrolling text area; a swipe starting there scrolls until its end before collapsing. */
    protected abstract val reading: ScrollView
    /** Controls that keep their own taps (chips, the corner glyph). */
    protected abstract fun ownTaps(): List<View>
    /** Accessibility label for the card's primary action. */
    protected abstract val primaryLabel: String

    /** The publisher's primary action when it has one, otherwise its content intent ("open"). */
    protected open fun openPrimary() {
        item?.let { current ->
            val id = current.payload.getJSONObject("expanded").optString("primaryAction")
            if (current.actions.containsKey(id)) invoke(current, id) else command("open", current.key, null)
        }
    }

    protected fun invoke(current: IslandActivity, id: String) =
        command("action", current.key, JSONObject().put("id", id).toString())

    protected fun dp(value: Int) = (value * density).toInt()

    private fun inside(view: View, x: Float, y: Float): Boolean {
        val at = IntArray(2); val card = IntArray(2)
        view.getLocationInWindow(at); getLocationInWindow(card)
        val left = at[0] - card[0]; val top = at[1] - card[1]
        return view.isShown && x >= left && x < left + view.width && y >= top && y < top + view.height
    }

    /** Observe before children consume motion: ScrollView disallows parent interception after
     * touch slop, so a header-only listener cannot close a swipe starting on text or chips. */
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.rawX; downY = event.rawY; swiped = false
                // Child bounds and event.x/y share this card's coordinates (raw coordinates do not).
                val inText = event.x >= reading.left && event.x < reading.right &&
                    event.y >= reading.top && event.y < reading.bottom
                swipeEligible = !(inText && reading.canScrollVertically(1))
                downAt = event.eventTime
                tapEligible = ownTaps().none { inside(it, event.x, event.y) }
            }
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL -> { swipeEligible = false; tapEligible = false }
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> {
                if (swiped) return true
                val dx = kotlin.math.abs(event.rawX - downX)
                val up = downY - event.rawY
                if (dx > swipeSlop && dx > kotlin.math.abs(up)) swipeEligible = false
                // A scroll, a swipe or a long press is not a tap.
                if (dx > swipeSlop || kotlin.math.abs(up) > swipeSlop || event.eventTime - downAt > tapTimeout) tapEligible = false
                if (swipeEligible && event.pointerCount == 1 && up > maxOf(dp(24), swipeSlop) && up > dx * 1.3f) {
                    swipeEligible = false; swiped = true
                    val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
                    super.dispatchTouchEvent(cancel); cancel.recycle()
                    post { collapse("swipe_up") }
                    return true
                }
                if (event.actionMasked == MotionEvent.ACTION_UP) {
                    swipeEligible = false
                    if (tapEligible && event.pointerCount == 1) {
                        tapEligible = false
                        val handled = super.dispatchTouchEvent(event)
                        post { openPrimary() }
                        return handled
                    }
                }
            }
        }
        return super.dispatchTouchEvent(event)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean = true

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_DISMISS)
        info.isDismissable = true
        info.addAction(AccessibilityNodeInfo.AccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, primaryLabel))
        info.isClickable = true
    }

    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
        if (action == AccessibilityNodeInfo.ACTION_DISMISS) { collapse("accessibility_dismiss"); return true }
        if (action == AccessibilityNodeInfo.ACTION_CLICK) { openPrimary(); return true }
        return super.performAccessibilityAction(action, arguments)
    }
}
