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
 * Compact island, Figma 1:4742 (52:815) for every activity: a 32 dp disc, the camera, and a 32 dp
 * text pill, each 8 dp from the camera's edge and joined by metaball necks that leave every circle
 * tangentially ([IslandCapsuleGeometry.neck]). The camera is the device's real cutout: its radius
 * comes from the visible display cutout path, not its padded exclusion rectangle. The design's
 * 24 dp circle is used without a physical hole. The disc shows the activity's glyph; the
 * pill shows its text, timer or number in Hanken 12 sp. Another live activity waits as a 32 dp
 * circle on the far left, where the bar has room; the main capsule never moves for it. On a status
 * bar shorter than 32 dp everything scales down rather than clipping. Vertical drags are left to
 * the shade. A system status ([IslandLockStatus]) is the disc alone, holding a padlock.
 */
internal class IslandCapsuleView(context: Context, private val tap: () -> Unit,
    private val longPress: () -> Unit, private val next: () -> Unit, private val hide: () -> Unit) : View(context) {
    private val density = resources.displayMetrics.density
    /** Pixels per design dp: the density, scaled down when the status bar is shorter than 32 dp. */
    var unit = density
        private set
    /** The camera cutout's radius in pixels (12 dp without a physical centre cutout). */
    var cameraRadius = 12 * density
        private set
    /** Vertical offset of the camera centre from the view's middle when the bar clamps it. */
    var centerShift = 0f
        set(value) { if (field != value) { field = value; invalidate() } }
    /** The waiting activity's circle sits left of the main capsule. */
    private var lead = 0f
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = IslandStyle.regular(context)
        textSize = 12f * resources.displayMetrics.scaledDensity
        letterSpacing = -.01f
    }
    private val neck = Path()
    private val icons = mutableMapOf<String, Drawable>()
    private val secondaryIcons = mutableMapOf<String, Drawable>()
    private var second: IslandActivity? = null
    private var secondaryIdentity: JSONObject? = null
    private var secondaryGeneration = 0
    var stale = false
    private var item: IslandActivity? = null
    private var identity: JSONObject? = null
    private var generation = 0
    private var animation: ValueAnimator? = null
    private var phase = 0f
    private var listeningPhase = 0f
    private val loaderPose = DotMatrixLoader.Pose()
    private var downY = 0f
    private var downX = 0f
    private var vertical = false
    /** The pill text for the current layout width, ellipsized when cut; empty when only the disc fits. */
    private var shownText = ""
    /** Group B is an icon or animation and the pill has room for it. */
    private var pillGlyphShown = false
    /** Running app-supplied animated icons and when their looping began (60 s budget). */
    private val iconLoops = mutableMapOf<Drawable, Long>()
    /** Drawables whose 60 s budget is used up for the current payload. */
    private val iconSpent = mutableSetOf<Drawable>()
    var secondary = false
        private set
    /** False where the capsule only displays (the lock screen): every touch passes through. */
    var interactive = true
        set(value) { field = value; isClickable = value; isLongClickable = value }
    /** Padlock shackle: 0 closed, 1 open; null until a status is bound, so the first one does not animate. */
    private var lockOpen: Float? = null
    private var lockTarget: Float? = null
    private var lockAnimation: ValueAnimator? = null
    private val shackle = Path()
    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(event: MotionEvent) = true
        override fun onSingleTapUp(event: MotionEvent): Boolean {
            // The waiting circle brings its activity forward; the rest of the capsule is the tap.
            if (secondary && event.x < lead) next() else performClick()
            return true
        }
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
        // A status only announces itself; an activity's gestures do not apply to it.
        if (item?.source == IslandActivity.SOURCE_STATUS) return
        info.addAction(AccessibilityNodeInfo.AccessibilityAction(AccessibilityNodeInfo.ACTION_LONG_CLICK, "Expand"))
        info.addAction(AccessibilityNodeInfo.AccessibilityAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, "Next activity"))
        info.addAction(AccessibilityNodeInfo.AccessibilityAction(AccessibilityNodeInfo.ACTION_DISMISS, "Hide"))
    }

    override fun performAccessibilityAction(action: Int, arguments: android.os.Bundle?): Boolean = when (action) {
        AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> { next(); true }
        AccessibilityNodeInfo.ACTION_DISMISS -> { hide(); true }
        else -> super.performAccessibilityAction(action, arguments)
    }

    override fun verifyDrawable(who: Drawable): Boolean =
        who in icons.values || who in secondaryIcons.values || super.verifyDrawable(who)

    /**
     * Fits the capsule to the bar: [barHeight] below 32 dp scales every dimension, and
     * [cutoutRadius] (pixels, 0 without a centred physical cutout) sizes the camera circle.
     */
    fun configure(barHeight: Int, cutoutRadius: Float) {
        unit = density * minOf(1f, barHeight / (32 * density)).coerceAtLeast(.5f)
        cameraRadius = if (cutoutRadius > 0) cutoutRadius else 12 * unit
        textPaint.textSize = 12f * resources.displayMetrics.scaledDensity * unit / density
    }

    /** Main disc centre, camera centre and pill start, from the view's left edge (pixels). */
    private fun discX() = lead + 16 * unit
    fun cameraOffset(): Int = (40 * unit + cameraRadius).toInt()
    private fun cameraX() = lead + 40 * unit + cameraRadius
    private fun pillStart() = cameraX() + cameraRadius + 8 * unit
    /** Width the waiting circle and its gap add on the left. */
    fun leadWidth(): Int = (36 * unit).toInt()

    /** Picture keys the publisher asked to animate (`icon.animate`). */
    private fun animatedKeys(activity: IslandActivity): Set<String> = listOf("a", "b")
        .mapNotNull { activity.compact?.optJSONObject(it) }
        .filter { it.optString("type") == "icon" && it.optBoolean("animate") }
        .map { it.getString("pic") }.toSet()

    /**
     * Runs animated drawables while shown and motion is enabled. Each loops for at most 60 s per
     * payload: once its budget is spent it rests until the publisher posts an update.
     */
    private fun startIconAnimations() {
        val activity = item ?: return
        if (!ValueAnimator.areAnimatorsEnabled() || visibility != VISIBLE || !isAttachedToWindow) return
        val visibleIcons = animatedKeys(activity).mapNotNull(icons::get) +
            listOfNotNull(second?.let { other ->
                val slot = other.compact?.optJSONObject("a")
                if (slot?.optBoolean("animate") == true) secondaryIcons[slot.optString("pic")] else null
            })
        for (drawable in visibleIcons) {
            val animated = drawable as? Animatable2 ?: continue
            if (drawable in iconLoops || drawable in iconSpent) continue
            drawable.callback = this
            iconLoops[drawable] = SystemClock.uptimeMillis()
            animated.registerAnimationCallback(object : Animatable2.AnimationCallback() {
                override fun onAnimationEnd(finished: Drawable) {
                    val began = iconLoops[finished] ?: return
                    if (SystemClock.uptimeMillis() - began < ICON_LOOP_MS && visibility == VISIBLE && isAttachedToWindow) {
                        (finished as Animatable2).start()
                    } else {
                        if (SystemClock.uptimeMillis() - began >= ICON_LOOP_MS) iconSpent.add(finished)
                        iconLoops.remove(finished)
                        (finished as Animatable2).clearAnimationCallbacks()
                    }
                }
            })
            animated.start()
        }
    }

    private fun stopIconAnimations() {
        for (drawable in iconLoops.keys) (drawable as? Animatable2)?.apply { clearAnimationCallbacks(); stop() }
        iconLoops.clear()
    }

    private fun clearIcons(loaded: MutableMap<String, Drawable>) {
        for (drawable in loaded.values) {
            (drawable as? Animatable2)?.apply { clearAnimationCallbacks(); stop() }
            drawable.callback = null
            iconLoops.remove(drawable)
            iconSpent.remove(drawable)
        }
        loaded.clear()
    }

    /**
     * What the disc draws for group A: Taku's AI animation, a progress ring or an icon. Without
     * group A it shows the notification's own icon (tinted white), else a plain dot.
     */
    private fun discSlot(activity: IslandActivity): JSONObject? =
        if (activity.source == IslandActivity.SOURCE_STATUS) JSONObject().put("type", "lock")
            .put("open", activity.payload.optString("status") == IslandActivity.STATUS_UNLOCKED)
        else glyph(activity.compact?.optJSONObject("a"))
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

    /** The publisher's validated accent colour, else Taku's accent. */
    private fun accent(activity: IslandActivity): Int = activity.accentColor ?: IslandStyle.ACCENT

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
     * Width for [activity] within [maximum] (pixels from the view's left edge, which includes the
     * waiting circle when [withSecondary]): the disc and camera always, and the pill with as much
     * of group B as fits. Text that does not fit ends with an ellipsis; only when not even one
     * character and the ellipsis fit is the pill left out. Returns 0 when not even the disc fits.
     */
    fun layoutWidth(activity: IslandActivity, maximum: Int, withSecondary: Boolean): Int {
        secondary = withSecondary
        lead = if (withSecondary) leadWidth().toFloat() else 0f
        val base = kotlin.math.ceil(cameraX() + cameraRadius).toInt()
        shownText = ""
        pillGlyphShown = false
        if (maximum < base) return 0
        val slot = pillSlot(activity)
        val text = pillText(slot)
        var width = base
        val room = maximum - pillStart() - 16 * unit
        if (text.isEmpty() && pillGlyph(activity) != null && room >= 16 * unit) {
            pillGlyphShown = true
            width = kotlin.math.ceil(pillStart() + 32 * unit).toInt()
        }
        if (text.isNotEmpty() && room >= 16 * unit) {
            // Timers reserve their widest digits so the pill does not jitter every second.
            val natural = max(textPaint.measureText(text), if (slot?.optString("type") in setOf("timer", "countdown")) textPaint.measureText(text.replace(Regex("[0-9]"), "0")) else 0f)
            val fitted = ellipsized(text, room)
            if (fitted.isNotEmpty()) {
                shownText = fitted
                val labelWidth = if (fitted == text) minOf(natural, room) else textPaint.measureText(fitted)
                // The neck joins a 16 dp-radius end cap, including one-character Live Updates.
                width = kotlin.math.ceil(pillStart() + max(32 * unit, 16 * unit + labelWidth)).toInt()
            }
        }
        return width
    }

    /** [text] within [room] pixels: whole, or cut with a trailing ellipsis; empty when not even one character fits. */
    private fun ellipsized(text: String, room: Float): String {
        if (textPaint.measureText(text) <= room) return text
        val cut = android.text.TextUtils.ellipsize(text, textPaint, room, android.text.TextUtils.TruncateAt.END).toString()
        return if (cut.isEmpty() || cut == ELLIPSIS) "" else cut
    }

    fun bind(activity: IslandActivity, other: IslandActivity?) {
        item = activity
        second = other.takeIf { secondary }
        if (secondaryIdentity !== second?.payload) {
            secondaryIdentity = second?.payload
            val current = ++secondaryGeneration
            clearIcons(secondaryIcons)
            second?.pics?.forEach { (key, icon) ->
                IslandStyle.loadIcon(context, icon) { drawable ->
                    if (current == secondaryGeneration && drawable != null) {
                        secondaryIcons[key] = drawable
                        startIconAnimations()
                        invalidate()
                    }
                }
            }
        }
        bindLock(activity)
        contentDescription = if (activity.source == IslandActivity.SOURCE_STATUS) activity.title else
            "${if (stale) "May be outdated. " else ""}${activity.title}. ${activity.body}. " +
            "Double tap to ${if (activity.tap == "open" && activity.openIntent != null) "open the app" else "expand"}; " +
            "long press to expand; swipe left for the next activity; swipe right to hide." +
            if (second != null) " ${second?.title} is also live; tap the circle on the left to show it." else ""
        if (identity !== activity.payload) {
            identity = activity.payload
            val current = ++generation
            clearIcons(icons)
            activity.pics.forEach { (key, icon) ->
                IslandStyle.loadIcon(context, icon) { drawable ->
                    if (current == generation && drawable != null) {
                        icons[key] = drawable
                        startIconAnimations()
                        invalidate()
                    }
                }
            }
        }
        startIconAnimations()
        val moving = listOfNotNull(discSlot(activity), pillGlyph(activity), second?.let(::discSlot))
            .any { it.has("animated") }
        if (moving && animation == null && ValueAnimator.areAnimatorsEnabled() && isShown && isAttachedToWindow) animation = ValueAnimator.ofFloat(0f, 1f).apply {
            // One repaint clock for both discs. Keep each authored cycle when selection changes:
            // working = 1000 ms; listening = 1400 ms; their common cycle is 7000 ms.
            duration = 7000
            interpolator = LinearInterpolator()
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener {
                val elapsed = (it.animatedValue as Float) * 7000
                phase = (elapsed % DotMatrixLoader.CYCLE_MS) / DotMatrixLoader.CYCLE_MS
                listeningPhase = (elapsed % 1400) / 1400
                invalidate()
            }
            start()
        }
        if (!moving || !ValueAnimator.areAnimatorsEnabled() || !isShown || !isAttachedToWindow) {
            animation?.cancel(); animation = null
        }
        if (!ValueAnimator.areAnimatorsEnabled() || visibility != VISIBLE) stopIconAnimations()
        invalidate()
    }

    /** The padlock follows the bound status: a change while shown animates the shackle, anything else jumps. */
    private fun bindLock(activity: IslandActivity) {
        if (activity.source != IslandActivity.SOURCE_STATUS) {
            lockAnimation?.cancel()
            lockOpen = null
            lockTarget = null
            return
        }
        val target = if (activity.payload.optString("status") == IslandActivity.STATUS_UNLOCKED) 1f else 0f
        if (lockTarget == target) return
        val from = lockOpen
        lockTarget = target
        lockAnimation?.cancel()
        if (from == null || !ValueAnimator.areAnimatorsEnabled() || !isShown) {
            lockOpen = target
            return
        }
        lockAnimation = ValueAnimator.ofFloat(from, target).apply {
            duration = LOCK_MS
            interpolator = android.view.animation.DecelerateInterpolator()
            addUpdateListener { lockOpen = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        item?.let { bind(it, second) }
    }

    override fun onDetachedFromWindow() {
        animation?.cancel()
        animation = null
        lockAnimation?.cancel()
        lockOpen = lockTarget
        stopIconAnimations()
        generation++
        secondaryGeneration++
        identity = null
        secondaryIdentity = null
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility != VISIBLE) { animation?.cancel(); animation = null; stopIconAnimations() }
        else item?.let { bind(it, second) }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!interactive) return false
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
        val cy = height / 2f + centerShift
        val disc = discX()
        val camera = cameraX()
        val pill = pillStart()
        val hasPill = shownText.isNotEmpty() || pillGlyphShown
        paint.color = Color.BLACK
        paint.style = Paint.Style.FILL
        val r = 16 * unit
        canvas.drawCircle(disc, cy, r, paint)
        canvas.drawCircle(camera, cy, cameraRadius, paint)
        drawNeck(canvas, disc, r, camera, cameraRadius, cy)
        if (hasPill) {
            canvas.drawRoundRect(pill, cy - r, width.toFloat(), cy + r, r, r, paint)
            // The pill's rounded end is a 16 dp circle centred 16 dp inside its start.
            drawNeck(canvas, pill + r, r, camera, cameraRadius, cy)
        }
        drawDisc(canvas, discSlot(activity), disc, cy, icons)
        if (pillGlyphShown) {
            drawDisc(canvas, pillGlyph(activity), pill + r, cy, icons)
        } else if (hasPill) {
            val slot = pillSlot(activity)
            val timed = slot?.optString("type") in setOf("timer", "countdown")
            textPaint.color = if (stale || slot?.optString("emphasis") == "muted") IslandStyle.TEXT_MUTED
                else if (slot?.optString("emphasis") == "accent") accent(activity) else IslandStyle.TEXT_PRIMARY
            val start = pill + 8 * unit
            // A live clock is re-measured every tick; it ellipsizes like any text if it outgrows its room.
            val text = if (timed) ellipsized(pillText(slot), width - 8 * unit - start) else shownText
            val textStart = if (width - pill <= 32 * unit + 1)
                max(start, pill + (width - pill - textPaint.measureText(text)) / 2) else start
            canvas.drawText(text, textStart, cy - (textPaint.ascent() + textPaint.descent()) / 2, textPaint)
            // Live clocks: redraw on the next whole second while shown.
            if (timed) postInvalidateDelayed(1000 - System.currentTimeMillis() % 1000)
        }
        if (secondary) drawSecondary(canvas, 16 * unit, cy)
    }

    /**
     * The metaball neck between a circle at [circleX] (radius [r]) and the camera, on either side;
     * mirrored when the circle lies right of the camera.
     */
    private fun drawNeck(canvas: Canvas, circleX: Float, r: Float, camera: Float, cameraR: Float, cy: Float) {
        val n = IslandCapsuleGeometry.neck(r, cameraR, kotlin.math.abs(camera - circleX), 4 * unit)
        neck.rewind()
        neck.moveTo(n[0], n[1])
        neck.cubicTo(n[2], n[3], n[4], n[5], n[6], n[7])
        neck.lineTo(n[6], -n[7])
        neck.cubicTo(n[4], -n[5], n[2], -n[3], n[0], -n[1])
        neck.close()
        canvas.save()
        canvas.translate(circleX, cy)
        if (camera < circleX) canvas.scale(-1f, 1f)
        canvas.drawPath(neck, paint)
        canvas.restore()
    }

    /** 16 dp glyph area centred on ([cx], [cy]): Figma's 3×3 loader, a picture or a progress ring. */
    private fun drawDisc(canvas: Canvas, slot: JSONObject?, cx: Float, cy: Float, loaded: Map<String, Drawable>) {
        val motion = slot?.optString("animated").orEmpty()
        val icon = slot?.optString("pic")?.let(loaded::get)
        when {
            slot?.optString("type") == "lock" -> drawLock(canvas, cx, cy, lockOpen ?: if (slot.optBoolean("open")) 1f else 0f)
            motion == "listening" -> {
                paint.color = IslandStyle.TEXT_PRIMARY
                val wave = (kotlin.math.sin(listeningPhase * 2 * Math.PI) + 1) / 2
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

    /**
     * White padlock in the 16 dp glyph area: an 11 × 8 dp body under a 1.7 dp shackle. Opening lifts
     * the shackle 3 dp, so its right leg clears the body while the left one stays in it.
     */
    private fun drawLock(canvas: Canvas, cx: Float, cy: Float, open: Float) {
        // The lifted shackle makes the glyph taller; shift it down to stay centred in the disc.
        val bodyTop = cy - dp(.7f) + dp(1.5f) * open
        val arcY = bodyTop - dp(2.6f) - dp(3f) * open
        val leg = dp(3.1f)
        paint.color = Color.WHITE
        paint.style = Paint.Style.FILL
        canvas.drawRoundRect(cx - dp(5.5f), bodyTop, cx + dp(5.5f), bodyTop + dp(8f), dp(2.2f), dp(2.2f), paint)
        shackle.rewind()
        shackle.moveTo(cx - leg, bodyTop + dp(1f))
        shackle.lineTo(cx - leg, arcY)
        shackle.arcTo(cx - leg, arcY - leg, cx + leg, arcY + leg, 180f, 180f, false)
        shackle.lineTo(cx + leg, bodyTop + dp(1f) - dp(4.2f) * open)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(1.7f)
        paint.strokeCap = Paint.Cap.ROUND
        canvas.drawPath(shackle, paint)
        paint.style = Paint.Style.FILL
    }

    /**
     * The waiting activity: a 32 dp circle, as tall as the main disc, with the same 16 dp glyph
     * (its own group A, else its notification icon).
     */
    private fun drawSecondary(canvas: Canvas, cx: Float, cy: Float) {
        val other = second ?: return
        paint.color = Color.BLACK
        canvas.drawCircle(cx, cy, 16 * unit, paint)
        drawDisc(canvas, discSlot(other), cx, cy, secondaryIcons)
    }

    fun state(): JSONObject = JSONObject().put("text", shownText).put("secondary", secondary)
        .put("pillGlyph", if (pillGlyphShown) item?.let { pillGlyph(it) }?.let { it.optString("animated").ifEmpty { it.optString("type") } } ?: JSONObject.NULL else JSONObject.NULL)
        .put("animatingIcons", iconLoops.size)
        .put("secondaryPosition", if (secondary) "left" else JSONObject.NULL)
        .put("cameraRadiusDp", cameraRadius / density).put("scale", unit / density)
        .put("geometry", geometryState())
        .put("disc", item?.let { discSlot(it)?.let { slot -> slot.optString("animated").ifEmpty { slot.optString("type") } } ?: "dot" } ?: JSONObject.NULL)

    /** Drawn coordinates in screen pixels, shared by every source and summary entry path. */
    private fun geometryState(): JSONObject {
        val origin = IntArray(2)
        getLocationOnScreen(origin)
        return JSONObject().put("cameraCenterX", origin[0] + cameraX())
            .put("cameraCenterY", origin[1] + height / 2f + centerShift)
            .put("discDiameterDp", 32 * unit / density)
            .put("pillHeightDp", if (shownText.isNotEmpty() || pillGlyphShown) 32 * unit / density else 0f)
            .put("pillWidthDp", if (shownText.isNotEmpty() || pillGlyphShown) (width - pillStart()) / density else 0f)
            .put("secondaryGapDp", if (secondary) (lead - 32 * unit) / density else JSONObject.NULL)
    }

    private fun dp(value: Float) = value * unit
    private fun dp(value: Int) = value * unit

    companion object {
        /** An app's animated icon loops for at most this long per binding. */
        const val ICON_LOOP_MS = 60_000L
        /** The padlock's shackle opens or closes over this time. */
        const val LOCK_MS = 300L
        private const val ELLIPSIS = "\u2026"
        /** Camera centre and pill start from the main capsule's left edge for the 24 dp design camera (dp). */
        const val CAMERA_DP = 52
        const val PILL_START_DP = 72
    }
}
