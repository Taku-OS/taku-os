/* Copyright 2026 Taku OS. SPDX-License-Identifier: Apache-2.0 */
package com.android.systemui.taku.island

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.PathParser
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityNodeInfo
import android.os.Bundle
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
    private val chips = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private val chipScroll = HorizontalScrollView(context).apply {
        isHorizontalScrollBarEnabled = false; overScrollMode = OVER_SCROLL_NEVER
        addView(chips); setPadding(0, dp(5), 0, dp(5)); clipToPadding = false
    }
    private var item: IslandActivity? = null
    private var bodyFingerprint: String? = null
    private var actionFingerprint: String? = null
    private var optionIds: List<String> = emptyList()
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
        addView(header, LayoutParams(-1, dp(32)).apply { rightMargin = dp(16) })
        addView(scroll, LayoutParams(-1, 0, 1f).apply { topMargin = dp(4); rightMargin = dp(16) })
        // Five dp shader bleed above/below the 40 dp chips, without changing the 16 dp gap.
        addView(chipScroll, LayoutParams(-1, -2).apply { topMargin = dp(11); bottomMargin = -dp(5) })
        chipScroll.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (chipScroll.width > 0 && chipScroll.height > 0)
                chipScroll.setRenderEffect(IslandCardEffects.chipEdge(chipScroll.width.toFloat(), chipScroll.height.toFloat(), density))
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
                val reading = event.x >= scroll.left && event.x < scroll.right &&
                    event.y >= scroll.top && event.y < scroll.bottom
                swipeEligible = !(reading && scroll.canScrollVertically(1))
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

    fun update(next: IslandActivity) {
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
        optionIds = if (ids == null) emptyList() else (0 until ids.length()).map { ids.getString(it) }.filter { next.actions.containsKey(it) }
        val actionKey = next.key + optionIds.joinToString { it + next.actions[it]?.title }
        if (actionKey != actionFingerprint) {
            chips.removeAllViews()
            optionIds.forEachIndexed { index, id ->
                chips.addView(label(16f, 0xebffffff.toInt(), medium).apply {
                    text = next.actions.getValue(id).title
                    isSingleLine = true
                    gravity = Gravity.CENTER
                    setPadding(dp(12), dp(8), dp(12), dp(8))
                    minHeight = (24 * scaled).toInt() + dp(16)
                    background = GradientDrawable().apply { setColor(0x14ffffff); cornerRadius = dp(999).toFloat() }
                    isClickable = true; isFocusable = true
                    setOnClickListener { item?.let { current -> if (id in optionIds) invoke(current, id) } }
                }, LayoutParams(-2, -2).apply { if (index > 0) leftMargin = dp(8) })
            }
            actionFingerprint = actionKey
        }
        chipScroll.visibility = if (optionIds.isEmpty()) GONE else VISIBLE
    }

    fun desiredHeight(next: IslandActivity, width: Int): Int {
        val expanded = next.payload.getJSONObject("expanded")
        val available = (width - dp(32)).coerceAtLeast(dp(48))
        val message = next.body.ifBlank { next.title }
        val layout = android.text.StaticLayout.Builder.obtain(message, 0, message.length, text.paint, available)
            .setIncludePad(false).setLineSpacing(text.lineSpacingExtra, text.lineSpacingMultiplier).build()
        var height = dp(68) + maxOf(layout.height, (27.9f * scaled).toInt())
        val steps = expanded.optJSONArray("steps")
        if (steps != null && steps.length() > 0) height += dp(16) + steps.length() * ((22 * scaled).toInt() + dp(8))
        if ((expanded.optJSONObject("assistant")?.optJSONArray("suggestions")?.length() ?: 0) > 0) height += dp(32) + (24 * scaled).toInt()
        return height.coerceAtLeast(dp(96))
    }

    fun state(): JSONObject = JSONObject().put("renderer", "systemui-native")
        .put("text", text.text.toString()).put("suggestionCount", optionIds.size)

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
