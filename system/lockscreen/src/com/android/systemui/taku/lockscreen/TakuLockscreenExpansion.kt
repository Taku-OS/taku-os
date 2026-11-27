package com.android.systemui.taku.lockscreen

import android.animation.ValueAnimator
import android.view.animation.PathInterpolator
import com.android.systemui.dagger.SysUISingleton
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * How far the lock screen's notifications are pulled open, the iPhone way: collapsed (0) when the lock
 * screen appears, with the tall clock and the newest item resting above the shortcuts; expanded (1)
 * after the user drags the notification area up, with the compact clock and the full list.
 *
 * This is independent of authentication, which only decides what content the list may hold. The one
 * model shared by the list's drag ([TakuNotificationListView]), the clock size
 * ([TakuLockscreenPolicy.clockSize]) and `cmd statusbar lockscreen expand|collapse`. Main thread.
 */
@SysUISingleton
class TakuLockscreenExpansion @Inject constructor() {
    private val mutableProgress = MutableStateFlow(0f)
    private val mutableExpanded = MutableStateFlow(false)
    private var animator: ValueAnimator? = null

    /** 0 collapsed … 1 expanded, following the finger during a drag and the settle animation after it. */
    val progress: StateFlow<Float> = mutableProgress.asStateFlow()

    /**
     * The compact clock is wanted: the user is dragging or the list is open (or settling open). The
     * clock changes size as soon as a drag starts, so its morph runs with the finger.
     */
    val expanded: StateFlow<Boolean> = mutableExpanded.asStateFlow()

    /** A drag on the notification area has started. */
    fun beginDrag() {
        animator?.cancel()
        animator = null
        setExpanded(true)
    }

    /** The finger moved; [value] is clamped to 0…1. */
    fun drag(value: Float) {
        mutableProgress.value = value.coerceIn(0f, 1f)
    }

    /** The finger lifted: open or close from where it is. */
    fun settle(open: Boolean) = animateTo(if (open) 1f else 0f)

    /** Opens the list (Agent `expand`, or a settle). */
    fun expand(animate: Boolean = true) = if (animate) animateTo(1f) else jumpTo(1f)

    /** Closes the list (Agent `collapse`, the screen turning off, the keyguard going away). */
    fun collapse(animate: Boolean = true) = if (animate) animateTo(0f) else jumpTo(0f)

    private fun jumpTo(target: Float) {
        animator?.cancel()
        animator = null
        mutableProgress.value = target
        setExpanded(target > 0f)
    }

    private fun animateTo(target: Float) {
        animator?.cancel()
        val from = mutableProgress.value
        if (from == target || !ValueAnimator.areAnimatorsEnabled()) {
            jumpTo(target)
            return
        }
        // Opening switches the clock at once; closing keeps the compact clock until the cards are back.
        if (target > 0f) setExpanded(true)
        animator = ValueAnimator.ofFloat(from, target).apply {
            duration = (SETTLE_MS * kotlin.math.abs(target - from)).toLong().coerceAtLeast(MIN_SETTLE_MS)
            interpolator = EASE
            addUpdateListener { mutableProgress.value = it.animatedValue as Float }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                private var canceled = false
                override fun onAnimationCancel(animation: android.animation.Animator) { canceled = true }
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (canceled) return
                    animator = null
                    setExpanded(target > 0f)
                }
            })
            start()
        }
    }

    private fun setExpanded(value: Boolean) {
        TakuLockscreenPolicy.notificationsExpanded = value
        mutableExpanded.value = value
    }

    companion object {
        /** A full open or close; shorter for a partial one. */
        const val SETTLE_MS = 380L
        const val MIN_SETTLE_MS = 160L
        private val EASE = PathInterpolator(0.2f, 0f, 0f, 1f)
    }
}
