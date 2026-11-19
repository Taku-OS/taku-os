/* Copyright 2026 Taku OS. SPDX-License-Identifier: Apache-2.0 */
package com.android.systemui.taku.island

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import android.view.animation.LinearInterpolator
import org.agentos.island.DotMatrixLoader

/** Shared native Taku eyes / working glyph; preserves the existing assistant animation. */
internal class IslandStatusGlyph(context: Context) : View(context) {
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
