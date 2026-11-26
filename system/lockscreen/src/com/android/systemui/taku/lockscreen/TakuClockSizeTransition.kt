package com.android.systemui.taku.lockscreen

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.Rect
import android.transition.Transition
import android.transition.TransitionValues
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import com.android.systemui.plugins.keyguard.ui.clocks.ClockViewIds

/** Replaces the stock two-face crossfade for Taku only. Authentication stays owned by Keyguard. */
class TakuClockSizeTransition : Transition() {
    init { addTarget(ClockViewIds.LOCKSCREEN_CLOCK_VIEW_LARGE) }

    override fun getTransitionProperties() = arrayOf(VISIBLE)

    private fun capture(values: TransitionValues, start: Boolean) {
        val face = values.view as? TakuClockFaceView ?: return
        values.values[VISIBLE] = face.visibility == View.VISIBLE
        values.values[BOUNDS] = Rect(face.left, face.top, face.right, face.bottom)
        if (start) values.values[PROGRESS] = face.morphOverlay?.morphProgress
    }

    override fun captureStartValues(values: TransitionValues) = capture(values, true)
    override fun captureEndValues(values: TransitionValues) = capture(values, false)

    override fun createAnimator(root: ViewGroup, start: TransitionValues?, end: TransitionValues?): Animator? {
        if (start == null || end == null || start.values[VISIBLE] == end.values[VISIBLE]) return null
        val large = end.view as? TakuClockFaceView ?: return null
        val small = root.findViewById<TakuClockFaceView>(ClockViewIds.LOCKSCREEN_CLOCK_VIEW_SMALL) ?: return null
        val toTall = end.values[VISIBLE] == true
        val from = start.values[PROGRESS] as? Float ?: if (toTall) 1f else 0f
        val target = if (toTall) 0f else 1f
        val bounds = (if (toTall) end else start).values[BOUNDS] as Rect
        val overlay = TakuClockFaceView(root.context, isLarge = true).apply {
            copyDisplayFrom(if (toTall) large else small)
            morphProgress = from
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            measure(View.MeasureSpec.makeMeasureSpec(root.width, View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            val x = bounds.centerX() - measuredWidth / 2
            layout(x, bounds.top, x + measuredWidth, bounds.top + measuredHeight)
        }
        // ValueAnimator obeys animator_duration_scale, including zero. No animation loop, JS or WebView.
        return ValueAnimator.ofFloat(0f, 1f).apply {
            duration = if (toTall) TakuClockMotion.TO_TALL_MS else TakuClockMotion.TO_COMPACT_MS
            interpolator = LinearInterpolator()
            val largeAlpha = large.alpha
            val smallAlpha = small.alpha
            val detach = object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {}
                override fun onViewDetachedFromWindow(v: View) { cancel() }
            }
            addUpdateListener {
                overlay.morphProgress = from + (target-from)*TakuClockMotion.spring(it.animatedFraction)
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationStart(animation: Animator) {
                    large.morphOverlay = overlay
                    large.alpha = 0f
                    small.alpha = 0f
                    root.overlay.add(overlay)
                    root.addOnAttachStateChangeListener(detach)
                }
                override fun onAnimationEnd(animation: Animator) {
                    root.overlay.remove(overlay)
                    root.removeOnAttachStateChangeListener(detach)
                    if (large.morphOverlay === overlay) {
                        large.morphOverlay = null
                        large.alpha = largeAlpha
                        small.alpha = smallAlpha
                    }
                }
            })
        }
    }

    companion object {
        private const val VISIBLE = "taku:clock:visible"
        private const val BOUNDS = "taku:clock:bounds"
        private const val PROGRESS = "taku:clock:progress"
    }
}
