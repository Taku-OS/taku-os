package org.agentos.agent.home.feed

internal data class FeedPresentation(val cards: List<FeedCard>, val pins: List<FeedCard>, val leaving: Set<String>)

/** A removal animation may retain a dismissed card, but cannot hide or duplicate a restored one. */
internal fun projectFeedCards(all: List<FeedCard>, preferences: FeedPreferences, leaving: Set<String>): FeedPresentation {
  val byId = all.associateBy { it.id }
  val pins = preferences.pins.mapNotNull { byId[it] }
  val pinned = preferences.pins.toHashSet()
  val activeLeaves = leaving.filterTo(LinkedHashSet()) { it in preferences.removed && it in byId && it !in pinned }
  val cards = all.filter { it.id !in pinned && (it.id !in preferences.removed || it.id in activeLeaves) }
    .sortedByDescending { it.rank }
  return FeedPresentation(cards, pins, activeLeaves)
}
