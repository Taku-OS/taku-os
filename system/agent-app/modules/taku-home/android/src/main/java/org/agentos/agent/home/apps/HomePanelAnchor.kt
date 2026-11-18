package org.agentos.agent.home.apps

/**
 * Where the desktop should point after the Panel directory changes under the current page.
 * Selection is an ID, never a page index: an insertion or a reorder moves the page, a removal
 * picks the surviving neighbour (or the main desktop when none is left).
 */
object HomePanelAnchor {
  sealed interface Target {
    /** The pager was not on a Panel page, so the directory change does not move it. */
    data object Stay : Target

    /** No Panel of the old order survives: fall back to the main desktop. */
    data object Home : Target

    data class Panel(val panelId: String) : Target
  }

  fun afterDirectoryChange(selected: String?, previous: List<String>, next: List<String>): Target {
    if (selected == null) return Target.Stay
    if (selected in next) return Target.Panel(selected)
    val removed = previous.indexOf(selected)
    // Selected, but never in the order we saw: the first Panel is the only meaningful anchor.
    if (removed < 0) return next.firstOrNull()?.let { Target.Panel(it) } ?: Target.Home
    // Walk the old order outwards from the removed Panel, following neighbour first, so a
    // surviving Panel wins even when a whole run of neighbours went away with it.
    return survivingNeighbour(previous, next, removed)?.let { Target.Panel(it) } ?: Target.Home
  }

  private fun survivingNeighbour(previous: List<String>, next: List<String>, removed: Int): String? {
    for (index in removed + 1 until previous.size) if (previous[index] in next) return previous[index]
    for (index in removed - 1 downTo 0) if (previous[index] in next) return previous[index]
    return null
  }
}
