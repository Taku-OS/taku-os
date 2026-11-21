/* Copyright 2026 Taku OS. SPDX-License-Identifier: Apache-2.0 */
package com.android.systemui.taku.island

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject

/** Figma 1155:1965 chrome, with actual notification text/actions rather than All set demo data. */
internal class IslandProgressCard(context: Context, private val command: (String, String?, String?) -> Unit,
    private val collapse: (String) -> Unit) : LinearLayout(context) {
    private val density = resources.displayMetrics.density
    private val scaled = resources.displayMetrics.scaledDensity
    private val regular = font("hanken_grotesk_regular")
    private val medium = font("hanken_grotesk_medium")
    private val message = label(18f, 0xccffffff.toInt(), regular).apply {
        val line = (27.9f * scaled).toInt()
        setLineHeight(line)
        // Android adds line spacing only between baselines; CSS includes half-leading
        // above the first line and below the last. Preserve the Figma 27.9 sp line box.
        val metrics = paint.fontMetricsInt
        val leading = (line - (metrics.descent - metrics.ascent)).coerceAtLeast(0)
        setPadding(0, leading / 2, 0, leading - leading / 2)
    }
    private val scroll = ScrollView(context).apply {
        isVerticalScrollBarEnabled = false; isFillViewport = false
        addView(message, LayoutParams(-1, -2))
    }
    private val actions = LinearLayout(context).apply { orientation = HORIZONTAL }
    private var current: IslandActivity? = null
    private var fingerprint: String? = null
    private var downX = 0f
    private var downY = 0f
    private var eligible = false
    private var swiped = false
    private val slop = ViewConfiguration.get(context).scaledTouchSlop

    init {
        orientation = VERTICAL
        setPadding(dp(16), dp(16), dp(16), dp(16))
        addView(IslandStatusGlyph(context).apply { contentDescription = "Taku" }, LayoutParams(dp(32), dp(32)))
        addView(scroll, LayoutParams(-1, 0, 1f).apply { topMargin = dp(4) })
        addView(actions, LayoutParams(-1, -2).apply { topMargin = dp(16) })
    }

    fun update(next: IslandActivity) {
        current = next
        message.text = next.body.ifBlank { next.title }
        val expanded = next.payload.getJSONObject("expanded")
        val declared = expanded.optJSONArray("actions")
        val ids = if (declared == null) emptyList() else (0 until declared.length()).map { declared.getString(it) }
            .filter { next.actions.containsKey(it) || next.nativeActions.containsKey(it) }
        val primary = expanded.optString("primaryAction")
        val key = next.key + primary + ids.joinToString { it + (next.actions[it]?.title ?: next.nativeActions[it]?.title) }
        if (key == fingerprint) return
        fingerprint = key
        actions.removeAllViews()
        // Closing only folds the card. It never cancels the browser sign-in or the notification.
        addButton("Close", false) { collapse("button") }
        ids.forEach { id ->
            val title = next.actions[id]?.title ?: next.nativeActions.getValue(id).title
            addButton(title, id == primary) {
                current?.let { item -> command("action", item.key, JSONObject().put("id", id).toString()) }
            }
        }
        if (ids.isEmpty() && next.openIntent != null) addButton("Open", true) {
            current?.let { command("open", it.key, null) }
        }
    }

    /** UNSPECIFIED measurement lets the weighted ScrollView measure its natural content.
     * At the real window's capped EXACT height it shrinks and scrolls, keeping actions visible. */
    fun desiredHeight(next: IslandActivity, width: Int): Int {
        update(next)
        measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        return measuredHeight
    }

    private fun addButton(title: CharSequence, primary: Boolean, clicked: () -> Unit) {
        val button = object : TextView(context) {
            override fun getAccessibilityClassName(): CharSequence = "android.widget.Button"
        }.apply {
            text = title; typeface = medium; textSize = 16f; letterSpacing = -.01f; includeFontPadding = false
            setTextColor(if (primary) 0xff1b1b1b.toInt() else 0xebffffff.toInt())
            setLineHeight((24 * scaled).toInt()); gravity = Gravity.CENTER
            setPadding(dp(12), dp(8), dp(12), dp(8))
            minHeight = (24 * scaled).toInt() + dp(16)
            val shape = GradientDrawable().apply { setColor(if (primary) Color.WHITE else 0x14ffffff); cornerRadius = dp(999).toFloat() }
            background = RippleDrawable(ColorStateList.valueOf(if (primary) 0x22000000 else 0x33ffffff), shape, null)
            isClickable = true; isFocusable = true
            setOnClickListener { clicked() }
        }
        actions.addView(button, LayoutParams(0, -2, 1f).apply { if (actions.childCount > 0) leftMargin = dp(8) })
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.rawX; downY = event.rawY; swiped = false
                val reading = event.y >= scroll.top && event.y < scroll.bottom
                eligible = !(reading && scroll.canScrollVertically(1))
            }
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL -> eligible = false
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> {
                if (swiped) return true
                val dx = kotlin.math.abs(event.rawX - downX)
                val up = downY - event.rawY
                if (dx > slop && dx > kotlin.math.abs(up)) eligible = false
                if (eligible && event.pointerCount == 1 && up > maxOf(dp(24), slop) && up > dx * 1.3f) {
                    eligible = false; swiped = true
                    val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
                    super.dispatchTouchEvent(cancel); cancel.recycle()
                    post { collapse("swipe_up") }; return true
                }
                if (event.actionMasked == MotionEvent.ACTION_UP) eligible = false
            }
        }
        return super.dispatchTouchEvent(event)
    }
    override fun onTouchEvent(event: MotionEvent): Boolean = true
    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_DISMISS); info.isDismissable = true
    }
    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
        if (action == AccessibilityNodeInfo.ACTION_DISMISS) { collapse("accessibility_dismiss"); return true }
        return super.performAccessibilityAction(action, arguments)
    }
    fun state(): JSONObject = JSONObject().put("renderer", "systemui-native").put("text", message.text.toString())
        .put("buttonCount", actions.childCount)
    private fun label(size: Float, color: Int, face: Typeface) = TextView(context).apply {
        textSize = size; setTextColor(color); typeface = face; includeFontPadding = false; letterSpacing = -.01f
    }
    private fun font(name: String): Typeface = runCatching {
        val app = context.createPackageContext("org.agentos.agent", 0)
        app.resources.getFont(app.resources.getIdentifier(name, "font", app.packageName))
    }.getOrDefault(Typeface.create("sans-serif", Typeface.NORMAL))
    private fun dp(value: Int) = (value * density).toInt()
}
