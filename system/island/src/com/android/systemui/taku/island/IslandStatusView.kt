/* Copyright 2026 Taku OS. SPDX-License-Identifier: Apache-2.0 */
package com.android.systemui.taku.island

import android.content.Context
import android.content.res.Configuration
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import org.json.JSONObject

/**
 * The island's capsule where the status bar is not: a strip across the top of its parent (the lock
 * screen) that draws the same camera-anchored capsule, with the same geometry, as [IslandPresenter]
 * does in the status bar. It shows a status (the padlock), an activity, or both: then the status
 * takes the disc and the activity the pill. Portrait only, like the keyguard.
 *
 * A capsule that only shows a status takes no touches. With an activity it takes the capsule's
 * gestures and reports them through [gestures]; vertical drags stay with the lock screen.
 */
class IslandStatusView(context: Context) : ViewGroup(context) {
    /** The capsule's gestures on the shown activity (its notification key). */
    interface Gestures {
        fun tap(key: String)
        fun longPress(key: String)
        fun next()
        fun hide(key: String)
    }

    private val density = resources.displayMetrics.density
    var gestures: Gestures? = null
    private val capsule = IslandCapsuleView(context,
        tap = { activity?.let { gestures?.tap(it.key) } },
        longPress = { activity?.let { gestures?.longPress(it.key) } },
        next = { gestures?.next() },
        hide = { activity?.let { gestures?.hide(it.key) } },
    ).apply {
        interactive = false
        visibility = View.GONE
    }
    private var status: IslandActivity? = null
    private var activity: IslandActivity? = null
    private var redacted = false

    /**
     * Right edge the capsule may reach, in screen pixels (the lock screen's status icons), or null
     * for the view's own width.
     */
    var endLimitOnScreen: Int? = null
        set(value) { if (field != value) { field = value; requestLayout() } }

    init {
        addView(capsule)
    }

    /**
     * What to draw: [status] alone, [activity] alone, or [status] in the disc with [activity] in
     * the pill; nothing when both are null, after a fade. [redacted] limits the activity to its glyph.
     */
    fun show(status: IslandActivity?, activity: IslandActivity?, redacted: Boolean) {
        if (this.status === status && this.activity === activity && this.redacted == redacted) return
        this.status = status
        this.activity = activity
        this.redacted = redacted
        capsule.animate().cancel()
        if (status != null || activity != null || capsule.visibility != View.VISIBLE) place()
        else capsule.animate().alpha(0f).setDuration(FADE_MS).withEndAction(::place).start()
    }

    /** Left edge of the drawn capsule on screen, or null while nothing is drawn. */
    fun capsuleLeftOnScreen(): Int? {
        if (capsule.visibility != View.VISIBLE || !isShown) return null
        val origin = IntArray(2)
        capsule.getLocationOnScreen(origin)
        return origin[0]
    }

    fun state(): JSONObject = JSONObject().put("status", status?.payload?.optString("status") ?: JSONObject.NULL)
        .put("key", activity?.key ?: JSONObject.NULL)
        .put("visible", capsule.visibility == View.VISIBLE && isShown && alpha > 0f)
        .put("capsule", if (capsule.visibility == View.VISIBLE) capsule.state() else JSONObject.NULL)

    /** As tall as the status bar (which contains the camera), and never shorter than the capsule. */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val bar = rootWindowInsets?.getInsetsIgnoringVisibility(
            WindowInsets.Type.statusBars() or WindowInsets.Type.displayCutout())?.top ?: 0
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), maxOf(bar, (32 * density).toInt()))
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) = place()

    // The bar height and the camera come from the window's insets: measure again whenever they can change.
    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        requestLayout()
        return super.onApplyWindowInsets(insets)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        requestLayout()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        requestLayout()
    }

    private fun place() {
        val live = activity
        val shown = live ?: status
        if (shown == null || width == 0 || height == 0 ||
                resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            capsule.visibility = View.GONE
            return
        }
        val camera = IslandCameraGeometry.locate(this, 64 * density)
        capsule.configure(height, camera.radius)
        capsule.statusOverlay = status.takeIf { live != null }
        capsule.redacted = redacted && live != null
        capsule.interactive = live != null
        val start = camera.x - capsule.cameraOffset()
        val origin = IntArray(2)
        getLocationOnScreen(origin)
        val end = endLimitOnScreen?.let { it - origin[0] }?.coerceAtMost(width) ?: width
        val capsuleWidth = capsule.layoutWidth(shown, end - start, false)
        if (start < 0 || capsuleWidth == 0) {
            capsule.visibility = View.GONE
            return
        }
        capsule.stale = false
        capsule.bind(shown, null)
        capsule.alpha = 1f
        capsule.visibility = View.VISIBLE
        // Same box as the status-bar capsule: tall enough for the 32 dp disc and the camera circle,
        // centred on the camera so the neck meets the real hole.
        val capsuleHeight = minOf(height, maxOf((32 * capsule.unit).toInt(), kotlin.math.ceil(2 * camera.radius + 2).toInt()))
        val capsuleTop = Math.round(camera.y - capsuleHeight / 2f).coerceIn(0, (height - capsuleHeight).coerceAtLeast(0))
        capsule.centerShift = camera.y - capsuleTop - capsuleHeight / 2f
        capsule.measure(MeasureSpec.makeMeasureSpec(capsuleWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(capsuleHeight, MeasureSpec.EXACTLY))
        capsule.layout(start, capsuleTop, start + capsuleWidth, capsuleTop + capsuleHeight)
    }

    private companion object {
        const val FADE_MS = 200L
    }
}
