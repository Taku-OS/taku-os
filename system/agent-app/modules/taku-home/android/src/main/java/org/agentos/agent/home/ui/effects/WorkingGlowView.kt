package org.agentos.agent.home.ui.effects

import android.animation.ValueAnimator
import android.content.Context
import android.database.ContentObserver
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Choreographer
import android.view.RoundedCorner
import android.view.View

/** Same renderer in the full-display overlay and the in-window fallback. Never takes input. */
internal class WorkingGlowView(
  context: Context,
  private val clock: WorkingGlowClock = WorkingGlowClock(),
  private val windowAlpha: Float = 1f,
  private val onHidden: (WorkingGlowView) -> Unit = {},
) : View(context), Choreographer.FrameCallback {
  private val glow = ScreenEdgeGlow()
  private val paint = Paint()
  private var previousFrame = 0L
  private var scheduled = false
  private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
    override fun onChange(selfChange: Boolean) { invalidate(); schedule() }
  }

  fun setWorking(on: Boolean) {
    clock.working = on
    schedule()
  }

  private fun schedule() {
    if (scheduled || !isAttachedToWindow || windowVisibility != VISIBLE || !clock.visible) return
    scheduled = true
    Choreographer.getInstance().postFrameCallback(this)
  }

  private fun stop() {
    Choreographer.getInstance().removeFrameCallback(this)
    scheduled = false
    previousFrame = 0L
  }

  override fun doFrame(frameTimeNanos: Long) {
    scheduled = false
    val dt = if (previousFrame == 0L) 0f else (frameTimeNanos - previousFrame) / 1_000_000f
    previousFrame = frameTimeNanos
    clock.advance(dt)
    invalidate()
    if (!clock.visible) { previousFrame = 0L; onHidden(this); return }
    // Reduced motion retains the static designer light; no continuous redraw at full strength.
    if (ValueAnimator.areAnimatorsEnabled() || clock.progress < 1f || !clock.working) schedule()
    else previousFrame = 0L
  }

  override fun onAttachedToWindow() {
    super.onAttachedToWindow()
    context.contentResolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer)
    schedule()
  }

  override fun onDetachedFromWindow() {
    stop()
    context.contentResolver.unregisterContentObserver(observer)
    super.onDetachedFromWindow()
  }

  override fun onWindowVisibilityChanged(visibility: Int) {
    super.onWindowVisibilityChanged(visibility)
    if (visibility == VISIBLE) schedule() else stop()
  }

  override fun onDraw(canvas: Canvas) {
    if (clock.level <= .0005f || width == 0 || height == 0) return
    val density = resources.displayMetrics.density
    val radius = rootWindowInsets?.getRoundedCorner(RoundedCorner.POSITION_TOP_LEFT)?.radius?.toFloat() ?: (48f * density)
    glow.update(width.toFloat(), height.toFloat(), radius, density, clock.timeSeconds, clock.level,
      still = !ValueAnimator.areAnimatorsEnabled(), windowAlpha = windowAlpha)
    paint.shader = glow.shader
    canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
  }
}
