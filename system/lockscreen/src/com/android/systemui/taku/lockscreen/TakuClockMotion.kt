package com.android.systemui.taku.lockscreen

import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/** Port of LockScreenHero's layoutSpring(0.78, 9), independent of frame rate. */
internal object TakuClockMotion {
    const val TO_COMPACT_MS = 720L
    const val TO_TALL_MS = 1050L
    fun spring(fraction: Float): Float {
        if (fraction <= 0f) return 0f
        if (fraction >= 1f) return 1f
        val zeta = 0.78
        val w = 9.0
        val wd = w*sqrt(1-zeta*zeta)
        fun raw(x: Double) = 1-exp(-zeta*w*x)*(cos(wd*x)+zeta*w/wd*sin(wd*x))
        return (raw(fraction.toDouble())/raw(1.0)).toFloat()
    }
}
