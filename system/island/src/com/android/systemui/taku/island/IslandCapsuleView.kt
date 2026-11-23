/* Copyright 2026 Taku OS. SPDX-License-Identifier: Apache-2.0 */
package com.android.systemui.taku.island

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.Animatable2
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.LinearInterpolator
import org.agentos.island.DotMatrixLoader
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.max

/**
 * Compact island, Figma 1379:889 for every activity: a 32 dp disc whose centre is 36 dp left of
 * the camera, the 24 dp camera disc, and a 32 dp text pill starting 20 dp right of it, joined by
 * the authored metaball necks. The disc shows the activity's glyph (loader, picture, progress
 * ring or app icon); the pill shows its text, timer or number in Hanken 12 sp. The view's left
 * edge sits [CAMERA_DP] left of the camera centre. Vertical drags are left to the shade.
 */
internal class IslandCapsuleView(context: Context, private val tap: () -> Unit,
    private val longPress: () -> Unit, private val next: () -> Unit, private val hide: () -> Unit) : View(context) {
    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = IslandStyle.regular(context)
        textSize = 12f * resources.displayMetrics.scaledDensity
        letterSpacing = -.01f
    }
    private val icons = mutableMapOf<String, Drawable>()
    private val secondaryIcons = mutableMapOf<String, Drawable>()
    private var second: IslandActivity? = null
    private var secondaryIdentity: JSONObject? = null
    private var secondaryGeneration = 0
    var stale = false
    /** Exact MetaballShape1 path (Figma 1379:904), 16.0743 × 21.3333. */
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
    /** The pill text for the current layout width; empty when only the disc fits. */
    private var shownText = ""
    /** Group B is an icon or animation and the pill has room for it. */
    private var pillGlyphShown = false
    /** Running app-supplied animated icons and when their looping began (60 s budget). */
    private val iconLoops = mutableMapOf<Drawable, Long>()
    var secondary = false
        private set
    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(event: MotionEvent) = true
        override fun onSingleTapUp(event: MotionEvent): Boolean { performClick(); return true }
        override fun onLongPress(event: MotionEvent) { performLongClick() }
        override fun onFling(first: MotionEvent?, last: MotionEvent, vx: Float, vy: Float): Boolean {
            if (first != null && abs(last.x - first.x) > dp(24) && abs(vx) > abs(vy)) {
                // Swipe left: next activity. Swipe right: hide this one (owner decision).
                if (vx < 0) next() else hide()
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
        setOnLongClickListener { longPress(); true }
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.addAction(AccessibilityNodeInfo.AccessibilityAction(AccessibilityNodeInfo.ACTION_LONG_CLICK, "Expand"))
        info.addAction(AccessibilityNodeInfo.AccessibilityAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, "Next activity"))
        info.addAction(AccessibilityNodeInfo.AccessibilityAction(AccessibilityNodeInfo.ACTION_DISMISS, "Hide"))
    }

    override fun performAccessibilityAction(action: Int, arguments: android.os.Bundle?): Boolean = when (action) {
        AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> { next(); true }
        AccessibilityNodeInfo.ACTION_DISMISS -> { hide(); true }
        else -> super.performAccessibilityAction(action, arguments)
    }

    override fun verifyDrawable(who: Drawable): Boolean = who in icons.values || super.verifyDrawable(who)

    /** Picture keys the publisher asked to animate (`icon.animate`). */
    private fun animatedKeys(activity: IslandActivity): Set<String> = listOf("a", "b")
        .mapNotNull { activity.compact?.optJSONObject(it) }
        .filter { it.optString("type") == "icon" && it.optBoolean("animate") }
        .map { it.getString("pic") }.toSet()

    /** Runs animated drawables while shown and motion is enabled; each loops for at most 60 s. */
    private fun startIconAnimations() {
        val activity = item ?: return
        if (!ValueAnimator.areAnimatorsEnabled() || visibility != VISIBLE || !isAttachedToWindow) return
        for (key in animatedKeys(activity)) {
            val drawable = icons[key] ?: continue
            val animated = drawable as? Animatable2 ?: continue
            if (drawable in iconLoops) continue
            drawable.callback = this
            iconLoops[drawable] = SystemClock.uptimeMillis()
            animated.registerAnimationCallback(object : Animatable2.AnimationCallback() {
                override fun onAnimationEnd(finished: Drawable) {
                    val began = iconLoops[finished] ?: return
                    if (SystemClock.uptimeMillis() - began < ICON_LOOP_MS && visibility == VISIBLE && isAttachedToWindow) {
                        (finished as Animatable2).start()
                    } else iconLoops.remove(finished)
                }
            })
            animated.start()
        }
    }

    private fun stopIconAnimations() {
        for (drawable in iconLoops.keys) (drawable as? Animatable2)?.apply { clearAnimationCallbacks(); stop() }
        iconLoops.clear()
    }

    /**
     * What the disc draws for group A: Taku's AI animation, a progress ring or an icon. Without
     * group A it shows the notification's own icon (tinted white), else a plain dot.
     */
    private fun discSlot(activity: IslandActivity): JSONObject? = glyph(activity.compact?.optJSONObject("a"))
        ?: if (activity.pics.containsKey(IslandActivity.APP_PIC))
            JSONObject().put("type", "pic").put("pic", IslandActivity.APP_PIC).put("tint", true) else null

    /** Group A slot in the disc's drawing vocabulary. */
    private fun glyph(slot: JSONObject?): JSONObject? = when (slot?.optString("type")) {
        "animation" -> when (slot.optString("name")) {
            "ai-working" -> JSONObject().put("animated", "working")
            "ai-listening" -> JSONObject().put("animated", "listening")
            else -> null
        }
        "progress" -> JSONObject().put("type", "progress").apply { if (slot.has("value")) put("value", slot.get("value")) }
        "icon" -> JSONObject().put("type", "pic").put("pic", slot.getString("pic")).put("tint", slot.optBoolean("tint"))
            .apply { if (slot.has("progress")) put("ring", slot.getDouble("progress")) }
        else -> null
    }

    /** Group B drawn as a glyph inside the pill (an icon or Taku's AI animation). */
    private fun pillGlyph(activity: IslandActivity): JSONObject? =
        activity.compact?.optJSONObject("b")?.takeIf { it.optString("type") in setOf("icon", "animation") }?.let(::glyph)

    /** What the pill says: group B's text, number, clock or percentage. */
    private fun pillSlot(activity: IslandActivity): JSONObject? =
        activity.compact?.optJSONObject("b")?.takeIf { it.optString("type") in setOf("text", "timer", "countdown", "number", "progress") }

    private fun pillText(slot: JSONObject?): String = when (slot?.optString("type")) {
        "text" -> slot.optString("text")
        "number" -> slot.optString("value") + slot.optString("unit")
        "progress" -> "${(slot.optDouble("value", 0.0) * 100).toInt()}%"
        "timer", "countdown" -> {
            val millis = if (slot.optString("type") == "timer") System.currentTimeMillis() - slot.optLong("startEpochMs")
                else slot.optLong("endEpochMs") - System.currentTimeMillis()
            val seconds = max(0, millis / 1000)
            if (seconds >= 3600) "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60)
            else "%d:%02d".format(seconds / 60, seconds % 60)
        }
        else -> ""
    }

    /**
     * Width for [activity] within [maximum] (measured from the view's left edge): the disc and
     * camera always; the pill when at least a short label fits; one more 24 dp disc for a
     * [secondaryWanted] activity when room remains. Returns 0 when not even the disc fits.
     */
    fun layoutWidth(activity: IslandActivity, maximum: Int, secondaryWanted: Boolean): Int {
        val base = px(CAMERA_DP + 12)
        if (maximum < base) return 0
        val slot = pillSlot(activity)
        val text = pillText(slot)
        var width = base
        shownText = ""
        pillGlyphShown = false
        val room = maximum - px(PILL_START_DP) - px(16) - if (secondaryWanted) px(28) else 0
        if (text.isEmpty() && pillGlyph(activity) != null && room >= px(16)) {
            pillGlyphShown = true
            width = px(PILL_START_DP) + px(16) + px(16)
        }
        if (text.isNotEmpty()) {
            // Timers reserve their widest digits so the pill does not jitter every second.
            val natural = max(textPaint.measureText(text), if (slot?.optString("type") in setOf("timer", "countdown")) textPaint.measureText(text.replace(Regex("[0-9]"), "0")) else 0f)
            val fitted = minOf(natural, room.toFloat())
            if (fitted >= minOf(natural, dp(24))) {
                shownText = text.take(textPaint.breakText(text, true, fitted + 1, null))
                width = px(PILL_START_DP) + px(16) + kotlin.math.ceil(fitted).toInt()
            }
        }
        secondary = secondaryWanted && width + px(28) <= maximum
        return width + if (secondary) px(28) else 0
    }

    fun bind(activity: IslandActivity, other: IslandActivity?) {
        item = activity
        second = other.takeIf { secondary }
        if (secondaryIdentity !== second?.payload) {
            secondaryIdentity = second?.payload
            val current = ++secondaryGeneration
            secondaryIcons.clear()
            second?.pics?.forEach { (key, icon) ->
                icon.loadDrawableAsync(context, { drawable ->
                    if (current == secondaryGeneration && drawable != null) { secondaryIcons[key] = drawable; invalidate() }
                }, Handler(Looper.getMainLooper()))
            }
        }
        contentDescription = "${if (stale) "May be outdated. " else ""}${activity.title}. ${activity.body}. " +
            "Double tap to ${if (activity.tap == "open" && activity.openIntent != null) "open the app" else "expand"}; " +
            "long press to expand; swipe left for the next activity; swipe right to hide."
        if (identity !== activity.payload) {
            identity = activity.payload
            val current = ++generation
            stopIconAnimations()
            icons.clear()
            activity.pics.forEach { (key, icon) ->
                icon.loadDrawableAsync(context, { drawable ->
                    if (current == generation && drawable != null) {
                        icons[key] = drawable
                        startIconAnimations()
                        invalidate()
                    }
                }, Handler(Looper.getMainLooper()))
            }
        }
        startIconAnimations()
        val motion = listOfNotNull(discSlot(activity), pillGlyph(activity)).firstOrNull { it.has("animated") }
        val moving = motion != null
        if (moving && animation == null && ValueAnimator.areAnimatorsEnabled() && visibility == VISIBLE) animation = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = if (motion?.optString("animated") == "listening") 1400 else DotMatrixLoader.CYCLE_MS
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
        stopIconAnimations()
        generation++
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility != VISIBLE) { animation?.cancel(); animation = null; stopIconAnimations() }
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
        val cy = height / 2f
        val camera = dp(CAMERA_DP)
        val pillEnd = width - if (secondary) dp(28) else 0f
        val hasPill = shownText.isNotEmpty() || pillGlyphShown
        paint.color = Color.BLACK
        paint.style = Paint.Style.FILL
        canvas.drawCircle(dp(16), cy, dp(16), paint)
        canvas.drawCircle(camera, cy, dp(12), paint)
        if (hasPill) canvas.drawRoundRect(dp(PILL_START_DP), cy - dp(16), pillEnd.toFloat(), cy + dp(16), dp(16), dp(16), paint)
        for (direction in if (hasPill) listOf(1, -1) else listOf(1)) {
            canvas.save()
            canvas.translate(camera - direction * dp(24), cy - dp(21.3333f) / 2 + dp(.167f))
            canvas.scale(direction * density, density)
            canvas.drawPath(neck, paint)
            canvas.restore()
        }
        drawDisc(canvas, discSlot(activity), dp(16), cy)
        if (pillGlyphShown) {
            drawDisc(canvas, pillGlyph(activity), dp(PILL_START_DP) + dp(16), cy)
        } else if (hasPill) {
            val slot = pillSlot(activity)
            val text = if (slot?.optString("type") in setOf("timer", "countdown")) pillText(slot) else shownText
            textPaint.color = if (stale || slot?.optString("emphasis") == "muted") IslandStyle.TEXT_MUTED
                else if (slot?.optString("emphasis") == "accent") IslandStyle.ACCENT else IslandStyle.TEXT_PRIMARY
            val start = dp(PILL_START_DP) + dp(8)
            val end = textPaint.breakText(text, true, pillEnd - dp(8) - start, null)
            canvas.drawText(text.take(end), start, cy - (textPaint.ascent() + textPaint.descent()) / 2, textPaint)
            // Live clocks: redraw on the next whole second while shown.
            if (slot?.optString("type") in setOf("timer", "countdown")) postInvalidateDelayed(1000 - System.currentTimeMillis() % 1000)
        }
        if (secondary) drawSecondary(canvas, width - dp(12), cy)
    }

    /** 16 dp glyph area centred on ([cx], [cy]): Figma's 3×3 loader, a picture or a progress ring. */
    private fun drawDisc(canvas: Canvas, slot: JSONObject?, cx: Float, cy: Float) {
        val motion = slot?.optString("animated").orEmpty()
        val icon = slot?.optString("pic")?.let(icons::get)
        when {
            motion == "listening" -> {
                paint.color = IslandStyle.TEXT_PRIMARY
                val wave = (kotlin.math.sin(phase * 2 * Math.PI) + 1) / 2
                canvas.drawCircle(cx, cy, dp(3) + dp(2) * wave.toFloat(), paint)
            }
            slot?.optString("type") == "progress" -> {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = dp(2)
                paint.strokeCap = Paint.Cap.ROUND
                paint.color = IslandStyle.TRACK
                canvas.drawCircle(cx, cy, dp(7), paint)
                paint.color = IslandStyle.TEXT_PRIMARY
                canvas.drawArc(cx - dp(7), cy - dp(7), cx + dp(7), cy + dp(7), -90f,
                    (slot.optDouble("value", 0.0).coerceIn(0.0, 1.0) * 360).toFloat(), false, paint)
                paint.style = Paint.Style.FILL
            }
            icon != null && motion.isEmpty() -> {
                val ring = slot?.optDouble("ring", Double.NaN) ?: Double.NaN
                // With a progress ring the icon shrinks so the 2 dp ring fits inside the 32 dp disc.
                val half = if (ring.isNaN()) dp(8) else dp(6)
                icon.colorFilter = if (slot?.optBoolean("tint") == true) PorterDuffColorFilter(IslandStyle.TEXT_PRIMARY, PorterDuff.Mode.SRC_IN) else null
                icon.setBounds((cx - half).toInt(), (cy - half).toInt(), (cx + half).toInt(), (cy + half).toInt())
                icon.draw(canvas)
                if (!ring.isNaN()) {
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = dp(2)
                    paint.strokeCap = Paint.Cap.ROUND
                    paint.color = IslandStyle.TRACK
                    canvas.drawCircle(cx, cy, dp(10), paint)
                    paint.color = IslandStyle.TEXT_PRIMARY
                    canvas.drawArc(cx - dp(10), cy - dp(10), cx + dp(10), cy + dp(10), -90f,
                        (ring.coerceIn(0.0, 1.0) * 360).toFloat(), false, paint)
                    paint.style = Paint.Style.FILL
                }
            }
            motion.isEmpty() -> {
                // No glyph: the nine-dot loader is reserved for AI loading, so draw a plain dot.
                paint.color = IslandStyle.TEXT_PRIMARY
                canvas.drawCircle(cx, cy, dp(3), paint)
            }
            else -> {
                // Figma's 16 dp dot loader: animated while working, its resting pose otherwise.
                val pose = if (ValueAnimator.areAnimatorsEnabled()) DotMatrixLoader.thinking(phase * DotMatrixLoader.CYCLE_MS.toDouble(), loaderPose)
                    else DotMatrixLoader.rest(loaderPose)
                for (row in 0..2) for (col in 0..2) {
                    val index = row * 3 + col
                    paint.color = Color.argb((pose.opacity[index] * 255).toInt(), 255, 255, 255)
                    canvas.drawCircle(cx + dp(-6 + col * 6), cy + dp(-6 + row * 6), dp(2) * pose.scale[index].toFloat(), paint)
                }
            }
        }
    }

    /** Another live activity: a 24 dp disc with its small-island glyph. */
    private fun drawSecondary(canvas: Canvas, cx: Float, cy: Float) {
        paint.color = Color.BLACK
        canvas.drawCircle(cx, cy, dp(12), paint)
        // System-managed: the next activity's own group A glyph, else its notification icon.
        val slot = glyph(second?.compact?.optJSONObject("a"))?.takeIf { it.optString("type") in setOf("pic", "progress") }
        val icon = slot?.optString("pic")?.takeIf { it.isNotEmpty() }?.let(secondaryIcons::get)
            ?: secondaryIcons[IslandActivity.APP_PIC]
        if (slot?.optString("type") == "progress") {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(2)
            paint.color = IslandStyle.TEXT_PRIMARY
            canvas.drawArc(cx - dp(7), cy - dp(7), cx + dp(7), cy + dp(7), -90f,
                (slot.optDouble("value", .25) * 360).toFloat(), false, paint)
            paint.style = Paint.Style.FILL
        } else if (icon != null) {
            icon.colorFilter = if (slot == null || slot.optBoolean("tint")) PorterDuffColorFilter(IslandStyle.TEXT_PRIMARY, PorterDuff.Mode.SRC_IN) else null
            icon.setBounds((cx - dp(7)).toInt(), (cy - dp(7)).toInt(), (cx + dp(7)).toInt(), (cy + dp(7)).toInt())
            icon.draw(canvas)
        } else {
            paint.color = IslandStyle.TEXT_PRIMARY
            canvas.drawCircle(cx, cy, dp(2), paint)
        }
    }

    fun state(): JSONObject = JSONObject().put("text", shownText).put("secondary", secondary)
        .put("pillGlyph", if (pillGlyphShown) item?.let { pillGlyph(it) }?.let { it.optString("animated").ifEmpty { it.optString("type") } } ?: JSONObject.NULL else JSONObject.NULL)
        .put("animatingIcons", iconLoops.size)
        .put("disc", item?.let { discSlot(it)?.let { slot -> slot.optString("animated").ifEmpty { slot.optString("type") } } ?: "dot" } ?: JSONObject.NULL)

    private fun dp(value: Float) = value * density
    private fun dp(value: Int) = value * density
    /** Whole pixels for layout sizes. */
    private fun px(value: Int) = (value * density).toInt()

    companion object {
        /** An app's animated icon loops for at most this long per binding. */
        const val ICON_LOOP_MS = 60_000L
        /** Camera centre and pill start, measured from the capsule's left edge (dp). */
        const val CAMERA_DP = 52
        const val PILL_START_DP = 72
    }
}
