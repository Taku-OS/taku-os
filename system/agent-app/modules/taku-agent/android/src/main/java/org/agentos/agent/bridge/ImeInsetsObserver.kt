package org.agentos.agent.bridge

import android.app.Activity
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowInsets
import android.view.WindowInsetsAnimation
import androidx.annotation.RequiresApi

/** Complete geometry from one UI-thread observation; no keyboardDidShow screen coordinates. */
@RequiresApi(Build.VERSION_CODES.R)
internal class ImeInsetsObserver(
  private val activity: Activity,
  private val content: ViewGroup,
  private val emit: (Map<String, Any>) -> Unit
) : View(activity), ViewTreeObserver.OnGlobalLayoutListener {
  private var previous: List<Int>? = null
  private val location = IntArray(2)

  init {
    importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    isFocusable = false
    isClickable = false
    setWindowInsetsAnimationCallback(object : WindowInsetsAnimation.Callback(DISPATCH_MODE_CONTINUE_ON_SUBTREE) {
      override fun onProgress(insets: WindowInsets, runningAnimations: MutableList<WindowInsetsAnimation>): WindowInsets {
        // React applies discrete layouts, not UI-thread animation frames. Publishing the
        // animation's old position here would overwrite the target received by onApply.
        return insets
      }
      override fun onEnd(animation: WindowInsetsAnimation) { refresh("end") }
    })
  }

  override fun onAttachedToWindow() {
    super.onAttachedToWindow()
    content.viewTreeObserver.addOnGlobalLayoutListener(this)
    requestApplyInsets()
    refresh("attach")
  }

  override fun onDetachedFromWindow() {
    if (content.viewTreeObserver.isAlive) content.viewTreeObserver.removeOnGlobalLayoutListener(this)
    super.onDetachedFromWindow()
  }

  override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
    // This callback is the new state; rootWindowInsets can still contain the previous state.
    publish(insets, "apply")
    // Insets dispatch precedes layout. Reconcile the current root and its final size afterward.
    post { publish(rootWindowInsets, "apply-post") }
    return insets
  }

  override fun onGlobalLayout() { publish(rootWindowInsets, "global") }

  fun refresh(source: String = "refresh") {
    previous = null
    publish(rootWindowInsets, source)
  }

  private fun publish(insets: WindowInsets?, source: String) {
    if (insets == null || !isAttachedToWindow || content.height <= 0) return
    content.getLocationInWindow(location)
    val metrics = activity.windowManager.currentWindowMetrics
    val windowHeight = metrics.bounds.height()
    val imeBottom = insets.getInsets(WindowInsets.Type.ime()).bottom
    val visible = insets.isVisible(WindowInsets.Type.ime()) || imeBottom > 0
    val values = listOf(windowHeight, location[1], content.height, imeBottom, if (visible) 1 else 0)
    if (Log.isLoggable("TakuIme", Log.DEBUG)) {
      fun ime(value: WindowInsets?): String = if (value == null) "null" else
        "${value.getInsets(WindowInsets.Type.ime()).bottom}/${value.isVisible(WindowInsets.Type.ime())}"
      Log.d("TakuIme", "t=${SystemClock.elapsedRealtime()} source=$source changed=${values != previous}" +
        " ime=${ime(insets)} rootIme=${ime(rootWindowInsets)} metricsIme=${ime(metrics.windowInsets)}" +
        " window=$windowHeight top=${location[1]} height=${content.height} density=${resources.displayMetrics.density}")
    }
    if (values == previous) return
    previous = values
    val density = resources.displayMetrics.density.toDouble()
    emit(mapOf(
      "windowHeight" to windowHeight / density,
      "rootTop" to location[1] / density,
      "rootHeight" to content.height / density,
      "imeBottom" to imeBottom / density,
      "visible" to visible
    ))
  }
}
