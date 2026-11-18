package org.agentos.agent.home.apps

import org.agentos.agent.home.feed.FeedException

/** Deterministic first-fit packing shared by rendering, drag targets, persistence and Agent reads. */
object HomeGrid {
  data class Cell(val x: Int, val y: Int, val w: Int, val h: Int)
  fun validate(slot: Slot.Widget) {
    if (slot.spanX !in 1..4 || slot.spanY !in 1..6) throw FeedException("INVALID_ARGUMENT", "spanX must be 1..4; spanY must be 1..6")
  }
  fun place(items: List<Slot>): List<Cell> {
    val occupied = HashSet<Int>()
    return items.map { item ->
      val w = (item as? Slot.Widget)?.spanX ?: 1
      val h = (item as? Slot.Widget)?.spanY ?: 1
      if (item is Slot.Widget) validate(item)
      var cell = 0
      while (true) {
        val x = cell % 4; val y = cell / 4
        if (x + w <= 4 && (y until y + h).all { r -> (x until x + w).all { c -> r * 4 + c !in occupied } }) {
          for (r in y until y + h) for (c in x until x + w) occupied += r * 4 + c
          break
        }
        cell++
      }
      Cell(cell % 4, cell / 4, w, h)
    }
  }
}
