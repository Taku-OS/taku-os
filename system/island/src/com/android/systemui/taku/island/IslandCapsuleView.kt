/* Copyright 2026 Taku OS. SPDX-License-Identifier: Apache-2.0 */
package com.android.systemui.taku.island

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.LinearInterpolator
import org.agentos.island.DotMatrixLoader
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.max

/** Native cutout slots. Vertical drags are left to the parent status-bar shade dispatcher. */
internal class IslandCapsuleView(context: Context, private val tap: () -> Unit,
    private val cycle: (Int) -> Unit, private val choose: () -> Unit) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val density = resources.displayMetrics.density
    private val buildingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = runCatching {
            val source = context.createPackageContext("org.agentos.agent", 0)
            val id = source.resources.getIdentifier("hanken_grotesk_regular", "font", source.packageName)
            source.resources.getFont(id)
        }.getOrDefault(Typeface.create("sans-serif", Typeface.NORMAL))
        textSize = 12f * resources.displayMetrics.scaledDensity
        color = 0xebffffff.toInt()
    }
    fun buildingWidth(): Int = kotlin.math.ceil(dp(88) + buildingTextWidth()).toInt()
    private fun buildingTextWidth(): Float = buildingPaint.measureText("Building...") - dp(.12f) * 10
    private val icons = mutableMapOf<String, Drawable>()
    private val secondaryIcons = mutableMapOf<String, Drawable>()
    private var second: IslandActivity? = null
    private var secondaryIdentity: JSONObject? = null
    private var secondaryGeneration = 0
    var stale = false
    private val neck = Path().apply {
        moveTo(16.0743f, 1.72239f)
        cubicTo(10.9102f, 6.34134f, 6.1586f, 6.88552f, 0f, 0f)
        lineTo(0f, 21.3333f)
        cubicTo(6.1586f, 14.4478f, 10.9102f, 14.992f, 16.0743f, 19.6109f)
        close()
    }
    private var item: IslandActivity? = null
    private var identity: JSONObject? = null
    private var generation = 0
    private var animation: ValueAnimator? = null
    private var phase = 0f
    private val loaderPose = DotMatrixLoader.Pose()
    private var downY = 0f
    private var downX = 0f
    private var vertical = false
    var cutoutWidth = 0f
    var widthMode = 0 // full slots, leading only, single glyph
    var secondary = false
    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(event: MotionEvent) = true
        override fun onSingleTapUp(event: MotionEvent): Boolean { performClick(); return true }
        override fun onLongPress(event: MotionEvent) { performLongClick() }
        override fun onFling(first: MotionEvent?, last: MotionEvent, vx: Float, vy: Float): Boolean {
            if (first != null && abs(last.x - first.x) > dp(24) && abs(vx) > abs(vy)) {
                cycle(if (vx < 0) 1 else -1)
                return true
            }
            return false
        }
    })

    init {
        isClickable = true
        isFocusable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        setOnClickListener { tap() }
        setOnLongClickListener { choose(); true }
    }

    fun bind(activity: IslandActivity, other: IslandActivity?) {
        item = activity
        second = other
        secondary = other != null
        if (secondaryIdentity !== other?.payload) {
            secondaryIdentity = other?.payload
            val current = ++secondaryGeneration
            secondaryIcons.clear()
            other?.pics?.forEach { (key, icon) ->
                icon.loadDrawableAsync(context, { drawable ->
                    if (current == secondaryGeneration && drawable != null) { secondaryIcons[key] = drawable; invalidate() }
                }, Handler(Looper.getMainLooper()))
            }
        }
        contentDescription = "${if (stale) "May be outdated. " else ""}${activity.title}. ${activity.body}. Double tap to expand; long press to choose activity."
        if (identity !== activity.payload) {
            identity = activity.payload
            val current = ++generation
            icons.clear()
            activity.pics.forEach { (key, icon) ->
                icon.loadDrawableAsync(context, { drawable ->
                    if (current == generation && drawable != null) { icons[key] = drawable; invalidate() }
                }, Handler(Looper.getMainLooper()))
            }
        }
        val text = activity.payload.toString()
        val moving = activity.building || text.contains("\"animated\":\"working\"") || text.contains("\"animated\":\"listening\"")
        if (moving && animation == null && ValueAnimator.areAnimatorsEnabled() && visibility == VISIBLE) animation = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = if (activity.building) DotMatrixLoader.CYCLE_MS else 1400
            interpolator = LinearInterpolator()
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { phase = it.animatedValue as Float; invalidate() }
            start()
        }
        if (!moving) { animation?.cancel(); animation = null }
        invalidate()
    }

    override fun onDetachedFromWindow() {
        animation?.cancel()
        animation = null
        generation++
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility != VISIBLE) { animation?.cancel(); animation = null }
        else item?.let { bind(it, second) }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) { downX = event.x; downY = event.y; vertical = false }
        if (event.actionMasked == MotionEvent.ACTION_MOVE && abs(event.y - downY) > abs(event.x - downX) && abs(event.y - downY) > ViewConfiguration.get(context).scaledTouchSlop) {
            vertical = true
            val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
            gestures.onTouchEvent(cancel)
            cancel.recycle()
            parent.requestDisallowInterceptTouchEvent(false)
        }
        if (vertical) return false
        gestures.onTouchEvent(event)
        return true
    }

    override fun onDraw(canvas: Canvas) {
        val activity = item ?: return
        if (activity.building) { drawBuilding(canvas); return }
        val h = height.toFloat()
        val islandWidth = width - if (secondary) dp(28) else 0f
        paint.color = Color.BLACK
        val center = islandWidth / 2
        if (cutoutWidth > 0 && widthMode == 0) {
            // Native port of the existing 1-6 neck path; physical cutout mask remains authoritative.
            val cameraRadius = minOf(h / 2, cutoutWidth / 2, dp(14))
            val gap = cameraRadius + dp(8)
            canvas.drawCircle(center, h / 2, cameraRadius, paint)
            canvas.drawRoundRect(0f, 0f, center - gap, h, h / 2, h / 2, paint)
            canvas.drawRoundRect(center + gap, 0f, islandWidth, h, h / 2, h / 2, paint)
            for (direction in -1..1 step 2) {
                canvas.save()
                canvas.translate(center + direction * gap, (h - dp(21)) / 2)
                canvas.scale(-direction * (gap - cameraRadius + dp(4)) / 16.0743f, dp(21) / 21.3333f)
                canvas.drawPath(neck, paint)
                canvas.restore()
            }
        } else canvas.drawRoundRect(0f, 0f, islandWidth, h, h / 2, h / 2, paint)
        val safe = max(cutoutWidth, dp(22)) / 2 + dp(4)
        val big = activity.payload.getJSONObject("bigIsland")
        val leading = big.optJSONObject("leading") ?: big.optJSONObject("trailing")
        if (widthMode == 2) drawGlyph(canvas, leading, dp(6), h)
        else {
            drawSlot(canvas, if (widthMode == 0) big.optJSONObject("leading") else leading,
                RectF(dp(8), 0f, center - safe, h))
            if (widthMode == 0) drawSlot(canvas, big.optJSONObject("trailing"), RectF(center + safe, 0f, islandWidth - dp(8), h))
        }
        if (secondary) {
            paint.color = Color.BLACK
            val cx = width - dp(12)
            canvas.drawCircle(cx, h / 2, dp(12), paint)
            val slot = second?.payload?.optJSONObject("smallIsland")
                ?: second?.payload?.optJSONObject("bigIsland")?.optJSONObject("leading")?.takeIf { it.optString("type") == "pic" }
            val icon = slot?.optString("pic")?.let(secondaryIcons::get) ?: secondaryIcons.values.firstOrNull()
            if (slot?.optString("type") == "progress") {
                paint.color = Color.WHITE
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = dp(2)
                canvas.drawArc(cx - dp(7), h / 2 - dp(7), cx + dp(7), h / 2 + dp(7), -90f,
                    (slot.optDouble("value", .25) * 360).toFloat(), false, paint)
                paint.style = Paint.Style.FILL
            } else if (icon != null) {
                icon.setBounds((cx - dp(7)).toInt(), (h / 2 - dp(7)).toInt(), (cx + dp(7)).toInt(), (h / 2 + dp(7)).toInt())
                icon.draw(canvas)
            } else {
                paint.color = Color.WHITE
                canvas.drawCircle(cx, h / 2, dp(2), paint)
            }
        }
    }

    /** Exact 1-6 geometry: 32 dp loading disc, 24 dp camera, 32 dp text pill.
     * The physical exclusion rectangle is NOT the camera's visual shape or radius. */
    private fun drawBuilding(canvas: Canvas) {
        val cy = height / 2f
        val camera = dp(52)
        paint.color = Color.BLACK
        canvas.drawCircle(dp(16), cy, dp(16), paint)
        canvas.drawCircle(camera, cy, dp(12), paint)
        canvas.drawRoundRect(dp(72), cy - dp(16), width.toFloat(), cy + dp(16), dp(16), dp(16), paint)
        for (direction in listOf(1, -1)) {
            canvas.save()
            canvas.translate(camera - direction * dp(24), cy - dp(21.3333f) / 2 + dp(.167f))
            canvas.scale(direction * density, density)
            canvas.drawPath(neck, paint)
            canvas.restore()
        }
        val pose = if (ValueAnimator.areAnimatorsEnabled()) DotMatrixLoader.thinking(phase * 1000.0, loaderPose)
            else DotMatrixLoader.rest(loaderPose)
        for (row in 0..2) for (col in 0..2) {
            val index = row * 3 + col
            paint.color = Color.argb((pose.opacity[index] * 255).toInt(), 255, 255, 255)
            canvas.drawCircle(dp(10 + col * 6), cy + dp(-6 + row * 6), dp(2) * pose.scale[index].toFloat(), paint)
        }
        var x = dp(80)
        val baseline = cy - (buildingPaint.ascent() + buildingPaint.descent()) / 2
        for (letter in "Building...") {
            val glyph = letter.toString()
            canvas.drawText(glyph, x, baseline, buildingPaint)
            x += buildingPaint.measureText(glyph) - dp(.12f)
        }
    }

    private fun drawGlyph(canvas: Canvas, slot: JSONObject?, x: Float, h: Float) {
        val icon = slot?.optString("pic")?.let(icons::get)
        val motion = slot?.optString("animated")
        if (motion == "working") {
            paint.color = Color.WHITE
            for (index in 0..2) {
                val wave = (kotlin.math.sin((phase * 2 * Math.PI) - index * .8) + 1) / 2
                canvas.drawCircle(x + dp(3 + index * 5), h / 2, dp(1) + dp(1) * wave.toFloat(), paint)
            }
        } else if (motion == "listening") {
            paint.color = Color.WHITE
            val wave = (kotlin.math.sin(phase * 2 * Math.PI) + 1) / 2
            canvas.drawCircle(x + dp(8), h / 2, dp(4) + dp(3) * wave.toFloat(), paint)
        } else if (icon != null) {
            val side = minOf(dp(18), h - dp(4)).toInt()
            icon.setBounds(x.toInt(), ((h - side) / 2).toInt(), x.toInt() + side, ((h + side) / 2).toInt())
            icon.draw(canvas)
        } else {
            paint.color = Color.WHITE
            canvas.drawCircle(x + dp(7), h / 2, dp(3 + (phase * 2).toInt()), paint)
        }
    }

    private fun drawSlot(canvas: Canvas, slot: JSONObject?, rect: RectF) {
        if (slot == null || rect.width() < dp(12)) return
        canvas.save()
        canvas.clipRect(rect)
        val type = slot.optString("type")
        if (type != "pic" && slot.has("pic") && rect.width() >= dp(34)) {
            drawGlyph(canvas, slot, rect.left, rect.height())
            rect.left += dp(22)
            canvas.clipRect(rect)
        }
        if (type == "pic") drawGlyph(canvas, slot, rect.left, rect.height())
        else if (type == "progress") {
            paint.color = Color.DKGRAY
            canvas.drawRoundRect(rect.left, height / 2f - dp(2), rect.right, height / 2f + dp(2), dp(2), dp(2), paint)
            paint.color = Color.WHITE
            val progress = slot.optDouble("value", .35).toFloat()
            canvas.drawRoundRect(rect.left, height / 2f - dp(2), rect.left + rect.width() * progress,
                height / 2f + dp(2), dp(2), dp(2), paint)
        } else {
            val text = when (type) {
                "text" -> slot.optString("text")
                "number" -> slot.optString("value") + slot.optString("unit")
                "timer", "countdown" -> {
                    val millis = if (type == "timer") System.currentTimeMillis() - slot.optLong("startEpochMs")
                        else slot.optLong("endEpochMs") - System.currentTimeMillis()
                    val seconds = max(0, millis / 1000)
                    "%d:%02d".format(seconds / 60, seconds % 60)
                }
                else -> "●"
            }
            paint.color = if (stale || slot.optString("emphasis") == "muted") Color.LTGRAY
                else if (slot.optString("emphasis") == "accent") 0xffff6a28.toInt() else Color.WHITE
            paint.textSize = minOf(dp(12) * resources.configuration.fontScale, rect.height() * .55f)
            val end = paint.breakText(text, true, rect.width(), null)
            val baseline = height / 2f - (paint.ascent() + paint.descent()) / 2
            canvas.drawText(text.take(end), rect.left, baseline, paint)
        }
        canvas.restore()
    }

    private fun dp(value: Float) = value * density
    private fun dp(value: Int) = value * density
}
