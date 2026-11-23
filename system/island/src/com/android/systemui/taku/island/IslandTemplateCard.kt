/* Copyright 2026 Taku OS. SPDX-License-Identifier: Apache-2.0 */
package com.android.systemui.taku.island

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import org.json.JSONObject

/**
 * Figma 1:4031 for every publisher other than the Taku assistant: the same 92 % black card,
 * Hanken type, corner glyph and option chips, filled from the notification's island payload
 * (`expanded.title/body/pic/steps/progress/actions/primaryAction`). Nothing here is a stock
 * widget, so an island never shows unstyled Android buttons.
 */
internal class IslandTemplateCard(context: Context, command: (String, String?, String?) -> Unit,
    collapse: (String) -> Unit) : IslandNativeCard(context, command, collapse) {
    private val leading = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
    private val expand = IslandStyle.ExpandGlyph(context)
    private val title = IslandStyle.label(context, 18f, IslandStyle.TEXT_BODY).apply { setLineHeight((27.9f * scaled).toInt()) }
    private val body = IslandStyle.label(context, 16f, IslandStyle.TEXT_SECONDARY).apply { setLineHeight((24 * scaled).toInt()) }
    private val content = LinearLayout(context).apply {
        orientation = VERTICAL
        addView(title)
        addView(body, LayoutParams(-1, -2).apply { topMargin = dp(4) })
    }
    private val scroll = ScrollView(context).apply { isVerticalScrollBarEnabled = false; addView(content); isFillViewport = false }
    /** Text area; an optional rich-content surface is layered over it by the card window. */
    val bodyHost = FrameLayout(context).apply { addView(scroll, FrameLayout.LayoutParams(-1, -2)) }
    private val progress = ProgressLine(context)
    private val chips = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private val chipScroll = HorizontalScrollView(context).apply {
        isHorizontalScrollBarEnabled = false; overScrollMode = OVER_SCROLL_NEVER
        addView(chips); setPadding(0, dp(5), 0, dp(5)); clipToPadding = false
    }
    private var iconGeneration = 0
    private var iconIdentity: Any? = null
    override val reading: ScrollView get() = scroll
    override val primaryLabel = "Open"
    override fun ownTaps(): List<View> = listOf(chipScroll, expand)

    init {
        orientation = VERTICAL
        setPadding(dp(16), dp(16), 0, dp(16))
        val header = FrameLayout(context).apply {
            addView(leading, FrameLayout.LayoutParams(dp(20), dp(20), Gravity.LEFT or Gravity.CENTER_VERTICAL).apply { leftMargin = dp(6) })
            addView(expand, FrameLayout.LayoutParams(dp(32), dp(32), Gravity.RIGHT))
        }
        expand.setOnClickListener { openPrimary() }
        addView(header, LayoutParams(-1, dp(32)).apply { rightMargin = dp(16) })
        addView(bodyHost, LayoutParams(-1, 0, 1f).apply { topMargin = dp(4); rightMargin = dp(16) })
        addView(progress, LayoutParams(-1, dp(4)).apply { topMargin = dp(16); rightMargin = dp(16) })
        addView(chipScroll, LayoutParams(-1, -2).apply { topMargin = dp(11); bottomMargin = -dp(5) })
        chipScroll.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (chipScroll.width > 0 && chipScroll.height > 0)
                chipScroll.setRenderEffect(IslandCardEffects.chipEdge(chipScroll.width.toFloat(), chipScroll.height.toFloat(), density))
        }
        scroll.viewTreeObserver.addOnScrollChangedListener { updateBottomEdge() }
        scroll.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateBottomEdge() }
    }

    /** Opening a card with no content intent and no primary action does nothing, and says so. */
    override fun openPrimary() {
        val current = item ?: return
        val primary = current.payload.getJSONObject("expanded").optString("primaryAction")
        if (current.actions.containsKey(primary) || current.openIntent != null) super.openPrimary()
    }

    fun update(next: IslandActivity) {
        item = next
        val expanded = next.payload.getJSONObject("expanded")
        title.text = next.title
        body.text = next.body
        body.visibility = if (next.body.isBlank()) GONE else VISIBLE
        while (content.childCount > 2) content.removeViewAt(2)
        expanded.optJSONArray("steps")?.let { steps ->
            for (i in 0 until steps.length()) {
                val step = steps.getJSONObject(i)
                content.addView(IslandStyle.label(context, 14f, IslandStyle.TEXT_SECONDARY).apply {
                    text = (if (step.optBoolean("running")) "• " else "✓ ") + step.getString("text")
                }, LayoutParams(-1, -2).apply { topMargin = dp(if (i == 0) 16 else 8) })
            }
        }
        val track = expanded.optJSONObject("progress")
        progress.visibility = if (track == null) GONE else VISIBLE
        progress.value = track?.takeIf { it.has("value") }?.optDouble("value")?.toFloat()
        progress.contentDescription = progress.value?.let { "${(it * 100).toInt()}%" } ?: "In progress"
        // The expanded picture when the publisher set one, else the posting app's small icon.
        val picKey = expanded.optString("pic").takeIf { next.pics.containsKey(it) }
        showIcon(picKey?.let(next.pics::get) ?: next.pics["app"], tint = picKey == null)
        val primary = expanded.optString("primaryAction")
        chips.removeAllViews()
        var index = 0
        next.actions.forEach { (id, action) ->
            chips.addView(IslandStyle.chip(context, action.title, id == primary) { item?.let { invoke(it, id) } },
                LayoutParams(-2, -2).apply { if (index++ > 0) leftMargin = dp(8) })
        }
        next.nativeActions.forEach { (id, action) ->
            chips.addView(IslandStyle.chip(context, action.title, false) { item?.let { invoke(it, id) } },
                LayoutParams(-2, -2).apply { if (index++ > 0) leftMargin = dp(8) })
        }
        chipScroll.visibility = if (chips.childCount == 0) GONE else VISIBLE
        expand.visibility = if (next.openIntent != null || next.actions.containsKey(primary)) VISIBLE else INVISIBLE
        expand.contentDescription = "Open ${next.title}"
        scroll.post { updateBottomEdge() }
    }

    /** Hides the native text while a ready rich surface covers [bodyHost]. */
    fun showTemplateText(visible: Boolean) { scroll.visibility = if (visible) VISIBLE else INVISIBLE }

    /** Height for [width] with this content, from a real measure pass (fonts are already loaded). */
    fun heightFor(width: Int): Int {
        measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        return measuredHeight.coerceAtLeast(dp(96))
    }

    fun state(): JSONObject = JSONObject().put("renderer", "systemui-native").put("title", title.text.toString())
        .put("body", body.text.toString()).put("actionCount", chips.childCount)
        .put("progress", if (progress.visibility == VISIBLE) progress.value ?: -1f else JSONObject.NULL)

    private fun showIcon(icon: Icon?, tint: Boolean) {
        if (icon === iconIdentity) return
        iconIdentity = icon
        val generation = ++iconGeneration
        leading.setImageDrawable(null)
        leading.visibility = if (icon == null) INVISIBLE else VISIBLE
        icon?.loadDrawableAsync(context, { drawable: Drawable? ->
            if (generation != iconGeneration || drawable == null) return@loadDrawableAsync
            // Small icons are alpha masks: draw them white like the rest of the island's glyphs.
            if (tint) drawable.mutate().setTint(IslandStyle.TEXT_PRIMARY)
            leading.setImageDrawable(drawable)
        }, Handler(Looper.getMainLooper()))
    }

    private fun updateBottomEdge() {
        scroll.setRenderEffect(if (scroll.height > 0 && scroll.canScrollVertically(1))
            IslandCardEffects.bottomEdge(scroll.width.toFloat(), scroll.height.toFloat(), density) else null)
    }

    /** 4 dp rounded line: white 16 % track, white 92 % fill; a sweeping segment when indeterminate. */
    private class ProgressLine(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var sweep: ValueAnimator? = null
        private var phase = 0f
        var value: Float? = null
            set(next) { field = next?.coerceIn(0f, 1f); syncSweep(); invalidate() }

        private fun syncSweep() {
            val wanted = value == null && isAttachedToWindow && visibility == VISIBLE && ValueAnimator.areAnimatorsEnabled()
            if (wanted && sweep == null) sweep = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 1400; interpolator = LinearInterpolator(); repeatCount = ValueAnimator.INFINITE
                addUpdateListener { phase = it.animatedValue as Float; invalidate() }; start()
            } else if (!wanted) { sweep?.cancel(); sweep = null }
        }
        override fun onAttachedToWindow() { super.onAttachedToWindow(); syncSweep() }
        override fun onDetachedFromWindow() { sweep?.cancel(); sweep = null; super.onDetachedFromWindow() }
        override fun onVisibilityChanged(changedView: View, visibility: Int) { super.onVisibilityChanged(changedView, visibility); syncSweep() }
        override fun onDraw(canvas: Canvas) {
            val h = height.toFloat(); val w = width.toFloat(); val r = h / 2
            paint.color = IslandStyle.TRACK
            canvas.drawRoundRect(0f, 0f, w, h, r, r, paint)
            paint.color = IslandStyle.TEXT_PRIMARY
            val current = value
            if (current != null) canvas.drawRoundRect(0f, 0f, maxOf(h, w * current), h, r, r, paint)
            else {
                val segment = w * .3f
                val start = (w + segment) * phase - segment
                canvas.drawRoundRect(maxOf(0f, start), 0f, minOf(w, start + segment), h, r, r, paint)
            }
        }
    }
}
