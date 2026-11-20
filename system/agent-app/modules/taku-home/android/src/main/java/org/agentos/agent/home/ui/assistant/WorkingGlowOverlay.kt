package org.agentos.agent.home.ui.assistant

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.os.Build
import android.util.Log
import android.view.RoundedCorner
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import org.agentos.agent.home.ui.effects.Shaders
import kotlin.math.cos
import kotlin.math.sin

/**
 * "Taku is working" (Figma 1-4 / 1-5) above everything on screen: one full-display system
 * overlay window that stacks over the input method, the status bar and every app, so the whole
 * edge glow shows even with the keyboard up. It never takes focus or touches. Any visible AI
 * surface (Home, the cross-app assistant overlay) may request it; it fades in while one of them
 * reports a response in progress and fades out as soon as none does.
 *
 * The window needs `INTERNAL_SYSTEM_WINDOW` (signature; Home ships with the platform development
 * signature). Where it cannot be added, [available] turns false and callers draw the glow in their
 * own window instead (under the keyboard, as before).
 */
internal class WorkingGlowOverlay private constructor(private val context: Context) {
  private val windows = context.getSystemService(WindowManager::class.java)
  private val requests = HashMap<Any, Boolean>()
  private var colors: List<Color> = emptyList()
  private var view: GlowView? = null

  /** False once the system refused the overlay window; callers then draw the glow themselves. */
  var available by mutableStateOf(Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
    private set

  /** [owner] wants the glow [on] (a response in progress on a visible surface), in [glow] colours. */
  fun request(owner: Any, on: Boolean, glow: List<Color>) {
    if (!available) return
    requests[owner] = on
    if (glow.size >= 4) colors = glow
    update()
  }

  /** [owner] left the screen (disposed): it no longer asks for the glow. */
  fun release(owner: Any) {
    if (requests.remove(owner) != null) update()
  }

  private fun update() {
    val wanted = requests.values.any { it }
    val current = view
    if (wanted) {
      val shown = current ?: attach() ?: return
      shown.colors = colors
      shown.fadeTo(1f)
    } else current?.fadeTo(0f)
  }

  private fun attach(): GlowView? {
    val glow = GlowView(context, onHidden = ::detach).also { it.colors = colors }
    val params = WindowManager.LayoutParams(
      WindowManager.LayoutParams.MATCH_PARENT,
      WindowManager.LayoutParams.MATCH_PARENT,
      @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY,
      WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
        WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
      PixelFormat.TRANSLUCENT,
    ).apply {
      title = "TakuWorkingGlow"
      // Under the system's touch-occlusion limit (0.8), so taps keep reaching the keyboard and
      // apps beneath; the glow's strength makes up for the window alpha.
      alpha = MAX_OBSCURING_ALPHA
      layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
      fitInsetsTypes = 0
    }
    return try {
      windows.addView(glow, params)
      view = glow
      glow
    } catch (e: RuntimeException) {
      // WindowManager.BadTokenException / SecurityException: no overlay on this build.
      Log.w(TAG, "working glow overlay unavailable; drawing it in the app window", e)
      available = false
      requests.clear()
      null
    }
  }

  private fun detach(glow: GlowView) {
    if (view !== glow) return
    view = null
    runCatching { windows.removeViewImmediate(glow) }
    // A request may have come back while the fade-out finished.
    if (requests.values.any { it }) update()
  }

  /** The four "working gradient" layers, drawn every frame while shown. */
  private class GlowView(context: Context, private val onHidden: (GlowView) -> Unit) : View(context) {
    var colors: List<Color> = emptyList()
    private val layers = List(4) { Shaders.EdgeGlow() }
    private val paints = List(4) { i -> Paint().apply { blendMode = if (WORKING_PLUS[i]) BlendMode.PLUS else BlendMode.SRC_OVER } }
    private val start = System.nanoTime()
    private var strength = 0f
    private var animator: ValueAnimator? = null
    private var target = 0f

    fun fadeTo(next: Float) {
      if (next == target && animator != null) return
      target = next
      animator?.cancel()
      animator = ValueAnimator.ofFloat(strength, next).apply {
        duration = if (next > strength) FADE_IN_MS else FADE_OUT_MS
        interpolator = DecelerateInterpolator()
        addUpdateListener { strength = it.animatedValue as Float; invalidate() }
        doOnEnd { if (target == 0f && strength <= 0.002f) onHidden(this@GlowView) }
        start()
      }
    }

    private fun ValueAnimator.doOnEnd(block: () -> Unit) = addListener(object : android.animation.AnimatorListenerAdapter() {
      override fun onAnimationEnd(animation: android.animation.Animator) = block()
    })

    override fun onDraw(canvas: Canvas) {
      if (colors.size < 4 || strength <= 0.002f) return
      val d = resources.displayMetrics.density
      val time = (System.nanoTime() - start) / 1e9f
      val angle = time * 0.45f
      val turnX = cos(angle); val turnY = sin(angle)
      val breathing = (strength * (0.92f + 0.08f * sin(time * 2.1f)) / MAX_OBSCURING_ALPHA).coerceAtMost(1.25f)
      val radius = (display?.getRoundedCorner(RoundedCorner.POSITION_TOP_LEFT)?.radius?.toFloat()) ?: (48f * d)
      for (i in 0 until 4) {
        // Rotate the Figma offset by the slow phase, as the in-window glow does.
        val ox = (WORKING_OFFSETS[i].first * turnX - WORKING_OFFSETS[i].second * turnY) * d
        val oy = (WORKING_OFFSETS[i].first * turnY + WORKING_OFFSETS[i].second * turnX) * d
        layers[i].update(width.toFloat(), height.toFloat(), radius, d, ox, oy, breathing, colors[WORKING_COLORS[i]])
        paints[i].shader = layers[i].shader
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paints[i])
      }
      postInvalidateOnAnimation()
    }
  }

  companion object {
    private const val TAG = "TakuWorkingGlow"
    private const val FADE_IN_MS = 700L
    /** Gone as soon as the response completes: a short fade, not a lingering one. */
    private const val FADE_OUT_MS = 250L
    /** InputManager's default maximum obscuring opacity for touches passing to other apps. */
    private const val MAX_OBSCURING_ALPHA = 0.8f
    /** Figma 1-4 layers in drawing order: colour index into workingGlow, offset (dp), plus-lighter. */
    private val WORKING_COLORS = intArrayOf(0, 1, 2, 3)
    private val WORKING_OFFSETS = listOf(0f to -4f, 4f to 0f, -4f to 0f, 0f to 4f)
    private val WORKING_PLUS = booleanArrayOf(true, false, false, true)

    @Volatile private var instance: WorkingGlowOverlay? = null
    fun get(context: Context): WorkingGlowOverlay = instance ?: synchronized(this) {
      instance ?: WorkingGlowOverlay(context.applicationContext).also { instance = it }
    }
  }
}
