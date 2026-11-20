/* Copyright 2026 Taku OS. SPDX-License-Identifier: Apache-2.0 */
package com.android.systemui.taku.island

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.TextUtils
import android.util.PathParser
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.agentos.island.DotMatrixLoader
import org.json.JSONObject

/** Figma 297:415. All views, text, animation and input are owned and drawn by SystemUI.
 * Payloads contain data and PendingIntent IDs only; no remote surface or Compose renderer. */
internal class IslandAssistantCard(context: Context, private val command: (String, String?, String?) -> Unit,
    private val collapse: (String) -> Unit) : LinearLayout(context) {
    private val density = resources.displayMetrics.density
    private val scaled = resources.displayMetrics.scaledDensity
    private val regular = font("hanken_grotesk_regular")
    private val medium = font("hanken_grotesk_medium")
    private val status = StatusGlyph(context)
    private val expand = ExpandGlyph(context)
    private val text = label(18f, 0xccffffff.toInt(), regular).apply { setLineHeight((27.9f * scaled).toInt()) }
    private val content = LinearLayout(context).apply { orientation = VERTICAL; addView(text) }
    private val scroll = ScrollView(context).apply { isVerticalScrollBarEnabled = false; addView(content); isFillViewport = false }
    private val headerHeight = dp(32)
    private val bodyMargin = dp(4)
    private val chipGap = dp(8)
    private val chipBleed = dp(5)
    private val chipTouchSize = kotlin.math.ceil(48 * density).toInt()
    private val chips = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private val horizontalChips = HorizontalScrollView(context).apply {
        isHorizontalScrollBarEnabled = false; overScrollMode = OVER_SCROLL_NEVER
        addView(chips); setPadding(0, chipBleed, 0, chipBleed); clipToPadding = false
    }
    private val rows = SuggestionRows(context, chipGap)
    private val wrappedChips = ScrollView(context).apply {
        isVerticalScrollBarEnabled = false
        overScrollMode = OVER_SCROLL_NEVER
        setPadding(0, 0, dp(16), 0)
        addView(rows, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        visibility = GONE
    }
    private val chipArea = FrameLayout(context).apply {
        addView(horizontalChips, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(wrappedChips, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        visibility = GONE
    }
    // Keep the shader bleed outside the gap and bottom inset of the horizontal strip.
    private val chipAreaParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
        topMargin = dp(11)
        bottomMargin = -chipBleed
    }
    private var item: IslandActivity? = null
    private var bodyFingerprint: String? = null
    private var suggestions: List<Pair<String, CharSequence>> = emptyList()
    private val swipeSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var swipeEligible = false
    private var swiped = false

    init {
        orientation = VERTICAL
        setPadding(dp(16), dp(16), 0, dp(16))
        val header = FrameLayout(context).apply {
            addView(status, FrameLayout.LayoutParams(dp(32), dp(32), Gravity.LEFT))
            addView(expand, FrameLayout.LayoutParams(dp(32), dp(32), Gravity.RIGHT))
        }
        expand.contentDescription = "Open conversation"
        expand.setOnClickListener {
            item?.let { current ->
                val id = current.payload.getJSONObject("expanded").optString("primaryAction")
                if (current.actions.containsKey(id)) invoke(current, id) else command("open", current.key, null)
            }
        }
        addView(header, LayoutParams(-1, headerHeight).apply { rightMargin = dp(16) })
        addView(scroll, LayoutParams(-1, 0, 1f).apply { topMargin = bodyMargin; rightMargin = dp(16) })
        addView(chipArea, chipAreaParams)
        horizontalChips.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (horizontalChips.width > 0 && horizontalChips.height > 0)
                horizontalChips.setRenderEffect(IslandCardEffects.chipEdge(horizontalChips.width.toFloat(), horizontalChips.height.toFloat(), density))
        }
        scroll.viewTreeObserver.addOnScrollChangedListener { updateBottomEdge() }
        scroll.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateBottomEdge() }
    }

    /** Observe before children consume motion: ScrollView disallows parent interception after
     * touch slop, so a header-only listener cannot close a swipe starting on reply text/chips.
     * A long reply keeps scrolling until its end; horizontal option gestures and taps stay intact. */
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.rawX; downY = event.rawY; swiped = false
                // Child bounds and event.x/y share this card's coordinates (raw coordinates do not).
                val reading = touches(scroll, event) && scroll.canScrollVertically(1)
                val scrollingChips = wrappedChips.isShown && touches(chipArea, event) &&
                    wrappedChips.canScrollVertically(1)
                swipeEligible = !reading && !scrollingChips
            }
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL -> swipeEligible = false
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> {
                if (swiped) return true
                val dx = kotlin.math.abs(event.rawX - downX)
                val up = downY - event.rawY
                if (dx > swipeSlop && dx > kotlin.math.abs(up)) swipeEligible = false
                if (swipeEligible && event.pointerCount == 1 && up > maxOf(dp(24), swipeSlop) && up > dx * 1.3f) {
                    swipeEligible = false; swiped = true
                    val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
                    super.dispatchTouchEvent(cancel); cancel.recycle()
                    post { collapse("swipe_up") }
                    return true
                }
                if (event.actionMasked == MotionEvent.ACTION_UP) swipeEligible = false
            }
        }
        return super.dispatchTouchEvent(event)
    }

    private fun touches(view: View, event: MotionEvent): Boolean =
        event.x >= view.left && event.x < view.right && event.y >= view.top && event.y < view.bottom

    override fun onTouchEvent(event: MotionEvent): Boolean = true

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_DISMISS)
        info.isDismissable = true
    }

    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
        if (action == AccessibilityNodeInfo.ACTION_DISMISS) { collapse("accessibility_dismiss"); return true }
        return super.performAccessibilityAction(action, arguments)
    }

    fun updateAndMeasure(next: IslandActivity, width: Int): Int {
        item = next
        val expanded = next.payload.getJSONObject("expanded")
        val data = expanded.optJSONObject("assistant")
        status.working = data?.optString("phase") == "working"
        // The publisher supplies the actual response/error/listening text. SystemUI invents no reply.
        val body = next.body.ifBlank { next.title }
        val steps = expanded.optJSONArray("steps")
        val fingerprint = body + steps?.toString()
        if (fingerprint != bodyFingerprint) {
            text.text = body
            while (content.childCount > 1) content.removeViewAt(1)
            if (steps != null) for (i in 0 until steps.length()) {
                val step = steps.getJSONObject(i)
                content.addView(label(14f, 0xa3ffffff.toInt(), regular).apply {
                    text = (if (step.optBoolean("running")) "• " else "✓ ") + step.getString("text")
                }, LayoutParams(-1, -2).apply { topMargin = dp(if (i == 0) 16 else 8) })
            }
            bodyFingerprint = fingerprint
            scroll.post { updateBottomEdge() }
        }
        val ids = data?.optJSONArray("suggestions")
        val options = if (ids == null) emptyList() else (0 until ids.length()).mapNotNull { index ->
            val id = ids.getString(index)
            next.actions[id]?.let { id to it.title }
        }
        if (options != suggestions) updateSuggestions(options)
        return desiredHeight(body, steps?.length() ?: 0, width)
    }

    private fun updateSuggestions(options: List<Pair<String, CharSequence>>) {
        suggestions = options
        val wraps = options.size > 2
        chipAreaParams.topMargin = dp(if (wraps) 10 else 11)
        chipAreaParams.bottomMargin = if (wraps) 0 else -chipBleed
        chips.removeAllViews()
        rows.removeAllViews()
        wrappedChips.scrollTo(0, 0)
        options.forEachIndexed { index, (id, title) ->
            val chip = label(16f, 0xebffffff.toInt(), medium).apply {
                text = title
                isSingleLine = true
                gravity = Gravity.CENTER
                setPadding(dp(12), dp(8), dp(12), dp(8))
                minHeight = if (wraps) chipTouchSize else (24 * scaled).toInt() + dp(16)
                background = GradientDrawable().apply { setColor(0x14ffffff); cornerRadius = dp(999).toFloat() }
                isFocusable = true
                setOnClickListener {
                    if (suggestions.any { it.first == id }) item?.let { current -> invoke(current, id) }
                }
            }
            if (wraps) {
                chip.minWidth = chipTouchSize
                chip.ellipsize = TextUtils.TruncateAt.END
                rows.addView(chip)
            } else {
                chips.addView(chip, LayoutParams(-2, -2).apply { if (index > 0) leftMargin = chipGap })
            }
        }
        horizontalChips.visibility = if (wraps) GONE else VISIBLE
        wrappedChips.visibility = if (wraps) VISIBLE else GONE
        chipArea.visibility = if (options.isEmpty()) GONE else VISIBLE
    }

    private fun desiredHeight(message: String, stepCount: Int, width: Int): Int {
        val available = (width - dp(32)).coerceAtLeast(dp(48))
        val layout = android.text.StaticLayout.Builder.obtain(message, 0, message.length, text.paint, available)
            .setIncludePad(false).setLineSpacing(text.lineSpacingExtra, text.lineSpacingMultiplier).build()
        var height = chromeHeight() + maxOf(layout.height, text.lineHeight)
        if (stepCount > 0) height += dp(16) + stepCount * ((22 * scaled).toInt() + dp(8))
        if (suggestions.isNotEmpty()) height += measureChipArea(width) + chipMargins()
        return height.coerceAtLeast(dp(96))
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        chipAreaParams.height = LayoutParams.WRAP_CONTENT
        if (wrappedChips.visibility == VISIBLE && MeasureSpec.getMode(heightMeasureSpec) != MeasureSpec.UNSPECIFIED) {
            val available = (MeasureSpec.getSize(heightMeasureSpec) - chromeHeight() - chipMargins()).coerceAtLeast(0)
            // Keep at least one line of reply and one full touch target when the window has room for both.
            val line = text.paint.fontMetricsInt
            val bodySpace = (available - chipTouchSize).coerceIn(0, line.descent - line.ascent)
            chipAreaParams.height = minOf(measureChipArea(MeasureSpec.getSize(widthMeasureSpec)), available - bodySpace)
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    private fun chromeHeight() = paddingTop + paddingBottom + headerHeight + bodyMargin
    private fun chipMargins() = chipAreaParams.topMargin + chipAreaParams.bottomMargin
    private fun measureChipArea(width: Int): Int {
        val available = (width - paddingLeft - paddingRight).coerceAtLeast(0)
        chipArea.measure(MeasureSpec.makeMeasureSpec(available, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        return chipArea.measuredHeight
    }

    fun state(): JSONObject = JSONObject().put("renderer", "systemui-native")
        .put("text", text.text.toString()).put("suggestionCount", suggestions.size)

    private fun updateBottomEdge() {
        scroll.setRenderEffect(if (scroll.height > 0 && scroll.canScrollVertically(1))
            IslandCardEffects.bottomEdge(scroll.width.toFloat(), scroll.height.toFloat(), density) else null)
    }
    private fun invoke(current: IslandActivity, id: String) = command("action", current.key, JSONObject().put("id", id).toString())
    private fun label(size: Float, color: Int, face: Typeface) = TextView(context).apply {
        textSize = size; setTextColor(color); typeface = face; includeFontPadding = false; letterSpacing = -.01f
    }
    private fun font(name: String): Typeface = runCatching {
        val app = context.createPackageContext("org.agentos.agent", 0)
        app.resources.getFont(app.resources.getIdentifier(name, "font", app.packageName))
    }.getOrDefault(Typeface.create("sans-serif", Typeface.NORMAL))
    private fun dp(value: Int) = (value * density).toInt()

    private class SuggestionRows(context: Context, private val gap: Int) : ViewGroup(context) {
        private val bounds = mutableListOf<Rect>()

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val width = MeasureSpec.getSize(widthMeasureSpec)
            val childWidth = MeasureSpec.makeMeasureSpec(width, MeasureSpec.AT_MOST)
            val childHeight = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
            var x = 0
            var y = 0
            var rowHeight = 0
            bounds.clear()
            for (index in 0 until childCount) {
                val child = getChildAt(index)
                child.measure(childWidth, childHeight)
                if (x > 0 && x + child.measuredWidth > width) {
                    x = 0
                    y += rowHeight + gap
                    rowHeight = 0
                }
                bounds.add(Rect(x, y, x + child.measuredWidth, y + child.measuredHeight))
                x += child.measuredWidth + gap
                rowHeight = maxOf(rowHeight, child.measuredHeight)
            }
            setMeasuredDimension(width, y + rowHeight)
        }

        override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
            for ((index, rect) in bounds.withIndex()) {
                val x = if (layoutDirection == LAYOUT_DIRECTION_RTL) width - rect.right else rect.left
                getChildAt(index).layout(x, rect.top, x + rect.width(), rect.bottom)
            }
        }
    }

    private class StatusGlyph(context: Context) : View(context) {
        private val d = resources.displayMetrics.density
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        private val pose = DotMatrixLoader.Pose()
        private var time = 0f
        private var clock: ValueAnimator? = null
        var working = false
            set(value) { if (field != value) { field = value; contentDescription = if (value) "Working" else "Taku"; invalidate() } }
        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            if (ValueAnimator.areAnimatorsEnabled()) clock = ValueAnimator.ofFloat(0f, 4000f).apply {
                duration = 4000; interpolator = LinearInterpolator(); repeatCount = ValueAnimator.INFINITE
                addUpdateListener { time = it.animatedValue as Float; invalidate() }; start()
            }
        }
        override fun onDetachedFromWindow() { clock?.cancel(); clock = null; super.onDetachedFromWindow() }
        override fun onDraw(canvas: Canvas) {
            val x = width / 2f; val y = height / 2f
            if (working) {
                if (ValueAnimator.areAnimatorsEnabled()) DotMatrixLoader.thinking(time.toDouble(), pose) else DotMatrixLoader.rest(pose)
                for (i in 0..8) {
                    paint.color = Color.argb((pose.opacity[i] * 255).toInt(), 255, 255, 255)
                    canvas.drawCircle(x + (i % 3 - 1) * 6 * d, y + (i / 3 - 1) * 6 * d, 2 * d * pose.scale[i].toFloat(), paint)
                }
            } else {
                val blink = if (time < 3700 || !ValueAnimator.areAnimatorsEnabled()) 1f else if (time < 3780) 1f - .88f * (time - 3700) / 80 else if (time < 3890) .12f + .88f * (time - 3780) / 110 else 1f
                paint.color = Color.WHITE
                for (left in listOf(-8, 2)) canvas.drawRoundRect(x + left * d, y - 6 * d * blink, x + (left + 6) * d, y + 6 * d * blink, 3 * d, 3 * d, paint)
            }
        }
    }

    private class ExpandGlyph(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 1.66667f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        }
        // Exact 20x20 paths from design/assistant-card/expand.svg (Figma 297:423).
        private val path = PathParser.createPathFromPathData("M1.77626 8.33333L1.71196 4.4221C1.69616 3.46072 1.68826 2.98003 1.87147 2.61455C2.03255 2.2932 2.2932 2.03255 2.61455 1.87146C2.98003 1.68826 3.46072 1.69616 4.4221 1.71196L8.33333 1.77626M18.2237 11.6667L18.288 15.5779C18.3038 16.5393 18.3117 17.02 18.1285 17.3854C17.9675 17.7068 17.7068 17.9675 17.3854 18.1285C17.02 18.3117 16.5393 18.3038 15.5779 18.288L11.6667 18.2237")
        override fun onDraw(canvas: Canvas) {
            val d = resources.displayMetrics.density
            canvas.save(); canvas.translate((width - 20 * d) / 2, (height - 20 * d) / 2); canvas.scale(d, d)
            canvas.drawPath(path, paint); canvas.restore()
        }
    }
}
