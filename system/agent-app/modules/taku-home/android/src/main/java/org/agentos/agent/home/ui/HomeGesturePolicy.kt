package org.agentos.agent.home.ui

import kotlin.math.abs
import kotlin.math.hypot

/** Per-pointer-stream decision, made before Compose dispatch; never reclassified on edge crossing. */
internal class HomeGesturePolicy {
  enum class Owner { Pending, System, Horizontal, Vertical }
  data class Region(val width: Int, val height: Int, val left: Int, val top: Int, val right: Int, val bottom: Int) {
    fun containsEdge(x: Float, y: Float) = x < left || y < top || x >= width - right || y >= height - bottom
  }

  var region = Region(0, 0, 0, 0, 0, 0)
    private set
  var owner = Owner.Pending
    private set
  var systemStart = false
    private set
  var active = false
    private set
  var startX = 0f
    private set
  var startY = 0f
    private set
  var dx = 0f
    private set
  var dy = 0f
    private set
  var terminal = "none"
    private set
  var sequence = 0L
    private set
  private var slop = 0f

  fun down(x: Float, y: Float, region: Region, touchSlop: Float) {
    this.region = region
    startX = x; startY = y; dx = 0f; dy = 0f; slop = touchSlop
    systemStart = region.containsEdge(x, y)
    owner = if (systemStart) Owner.System else Owner.Pending
    active = true; terminal = "none"; sequence++
  }

  fun move(x: Float, y: Float) {
    if (!active) return
    dx = x - startX; dy = y - startY
    if (owner == Owner.Pending && hypot(dx, dy) > slop) {
      owner = if (abs(dx) > abs(dy)) Owner.Horizontal else Owner.Vertical
    }
  }

  fun end(cancelled: Boolean) {
    if (!active) return
    active = false
    terminal = if (cancelled) "cancel" else "up"
    // Keep the decision for the nested fling dispatched after UP/CANCEL. A new DOWN resets it.
  }

  fun blockX() = owner == Owner.System || owner == Owner.Vertical
  fun blockY() = owner == Owner.System || owner == Owner.Horizontal
}
