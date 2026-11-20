package org.agentos.agent.home.ui.effects

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
