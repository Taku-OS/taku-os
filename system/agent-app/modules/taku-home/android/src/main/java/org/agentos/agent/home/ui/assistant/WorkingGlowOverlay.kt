package org.agentos.agent.home.ui.assistant

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.util.Log
import org.agentos.agent.home.ui.effects.WorkingGlowClock
import org.agentos.agent.home.ui.effects.WorkingGlowView
import android.view.WindowManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Softened screen-edge glow above everything on screen: one full-display system overlay window
 * that stacks over the input method, the status bar and every app. It never takes focus or
 * touches. Any visible AI surface (Home, the cross-app assistant overlay) may request it; it
 * fades in only while Live mode is active (never during ordinary Agent Chat / AI output) and
 * fades out as soon as Live mode ends.
 *
 * The window needs `INTERNAL_SYSTEM_WINDOW` (signature; Home ships with the platform development
 * signature). Where it cannot be added, [available] turns false and callers draw the glow in their
 * own window instead (under the keyboard, as before).
 */
internal class WorkingGlowOverlay private constructor(private val context: Context) {
  private val windows = context.getSystemService(WindowManager::class.java)
  private val requests = HashMap<Any, Boolean>()
  private val clock = WorkingGlowClock()
  private var view: WorkingGlowView? = null

  /** False once the system refused the overlay window; callers then draw the glow themselves. */
  var available by mutableStateOf(Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
    private set

  /** [owner] wants the glow [on] (a response in progress on a visible surface). */
  fun request(owner: Any, on: Boolean) {
    if (!available) return
    requests[owner] = on
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
      shown.setWorking(true)
    } else current?.setWorking(false)
  }

  private fun attach(): WorkingGlowView? {
    val glow = WorkingGlowView(context, clock, MAX_OBSCURING_ALPHA, ::detach)
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
      // apps beneath; the shader compensates its premultiplied output for this alpha.
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

  private fun detach(glow: WorkingGlowView) {
    if (view !== glow) return
    view = null
    runCatching { windows.removeViewImmediate(glow) }
    // A request may have come back while the fade-out finished.
    if (requests.values.any { it }) update()
  }

  companion object {
    private const val TAG = "TakuWorkingGlow"
    /** InputManager's default maximum obscuring opacity for touches passing to other apps. */
    private const val MAX_OBSCURING_ALPHA = 0.8f
    @Volatile private var instance: WorkingGlowOverlay? = null
    fun get(context: Context): WorkingGlowOverlay = instance ?: synchronized(this) {
      instance ?: WorkingGlowOverlay(context.applicationContext).also { instance = it }
    }
  }
}
