package org.agentos.agent.home.ui.effects

/**
 * Screen-edge glow trigger policy: never shown during ordinary Agent Chat or AI output
 * ([working] alone); shown only while Live mode ([voiceContinuous], e.g. side-key long-press
 * invocation / Live Talk) is active.
 */
internal fun shouldShowEdgeGlow(working: Boolean, voiceContinuous: Boolean): Boolean = voiceContinuous

/**
 * Softened visual parameters for the Live-mode screen-edge glow, tuned for a calm breathing
 * presence along the bezel without glare, dizziness, or encroachment on edge content.
 */
internal object LiveEdgeGlowSpec {
  const val INTENSITY = 0.42f
  const val SIGMA_DP = 9.0f
  const val FLOOR = 0.52f
  const val WIDTH_MIN_DP = 20f
  const val WIDTH_MAX_DP = 150f
  const val REACH_MIN = 0.45f
  const val REACH_MAX = 1.05f
  const val DRIFT_DP = 60f
  const val OFFSET_DP = 2.5f
  const val WARP_DP = 1.4f
  val PACKETS = arrayOf(
    doubleArrayOf(.05, 70.0, .47, 0.0, .65),
    doubleArrayOf(.21, -52.0, .61, 1.7, .58),
    doubleArrayOf(.37, 38.0, .39, 3.1, .72),
    doubleArrayOf(.52, 91.0, .83, .6, .52),
    doubleArrayOf(.66, -78.0, .52, 2.4, .62),
    doubleArrayOf(.81, 55.0, .71, 4.2, .65),
    doubleArrayOf(.93, -35.0, .33, 5.0, .55),
  )
}

/** Designer's reversible linear envelope with smoothstep output and a continuous flow clock. */
internal class WorkingGlowClock {
  var working = false
  var progress = 0f
    private set
  var timeSeconds = 0f
    private set
  val level: Float get() = progress * progress * (3f - 2f * progress)
  val visible: Boolean get() = working || progress > 0f

  fun advance(deltaMillis: Float) {
    val dt = deltaMillis.coerceIn(0f, 64f)
    if (!visible) return
    timeSeconds += dt / 1000f
    progress = if (working) (progress + dt / 200f).coerceAtMost(1f)
      else (progress - dt / 400f).coerceAtLeast(0f)
  }
}
