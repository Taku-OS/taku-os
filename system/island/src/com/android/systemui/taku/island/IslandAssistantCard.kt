/* Copyright 2026 Taku OS. SPDX-License-Identifier: Apache-2.0 */
package com.android.systemui.taku.island

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.Gravity
import android.view.View
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
internal class IslandAssistantCard(context: Context, command: (String, String?, String?) -> Unit,
    collapse: (String) -> Unit) : IslandNativeCard(context, command, collapse) {
    private val regular = IslandStyle.regular(context)
    private val status = StatusGlyph(context)
    private val expand = IslandStyle.ExpandGlyph(context)
    private val text = IslandStyle.label(context, 18f, IslandStyle.TEXT_BODY).apply { setLineHeight((27.9f * scaled).toInt()) }
    private val content = LinearLayout(context).apply { orientation = VERTICAL; addView(text) }
    private val scroll = ScrollView(context).apply { isVerticalScrollBarEnabled = false; addView(content); isFillViewport = false }
    private val chips = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private val chipScroll = HorizontalScrollView(context).apply {
        isHorizontalScrollBarEnabled = false; overScrollMode = OVER_SCROLL_NEVER
        addView(chips); setPadding(0, dp(5), 0, dp(5)); clipToPadding = false
    }
    private var bodyFingerprint: String? = null
    private var actionFingerprint: String? = null
    private var optionIds: List<String> = emptyList()
    override val reading: ScrollView get() = scroll
    override val primaryLabel = "Open conversation"
    override fun ownTaps(): List<View> = listOf(chipScroll, expand)

    init {
        orientation = VERTICAL
        setPadding(dp(16), dp(16), 0, dp(16))
        val header = FrameLayout(context).apply {
            addView(status, FrameLayout.LayoutParams(dp(32), dp(32), Gravity.LEFT))
            addView(expand, FrameLayout.LayoutParams(dp(32), dp(32), Gravity.RIGHT))
        }
        expand.contentDescription = "Open conversation"
        expand.setOnClickListener { openPrimary() }
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
                content.addView(IslandStyle.label(context, 14f, IslandStyle.TEXT_SECONDARY, regular).apply {
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
                chips.addView(IslandStyle.chip(context, next.actions.getValue(id).title, false) {
                    item?.let { current -> if (id in optionIds) invoke(current, id) }
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
}
