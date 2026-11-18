package org.agentos.agent.home.feed

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedPresentationTest {
  private fun card(id: String) = FeedCard.parse(JSONObject("""
    {"schema":"taku.feed-card/1","id":"$id","source":{"id":"agent","app":"Notes"},
     "summary":"A note","pin":{"label":"Note","icon":{"kind":"pixel","glyph":"chat"}},
     "ui":{"catalog":"taku-feed-1","spec":{"root":"r","elements":{"r":{"type":"Text","props":{"text":"Hi"}}}}}}
  """))

  @Test fun moreThanOneThousandDismissalsSurvivePersistenceWithoutResurrectingCards() {
    val cards = (0..1000).map { card("note-$it") }
    val initial = FeedPreferences(pins = listOf("note-0"), defaultsApplied = true)
    val removed = cards.fold(initial) { state, card -> state.removing(card.id) }
    val restored = FeedPreferences.parse(JSONObject(removed.json().toString()))
    assertEquals(1001, restored.removed.size)
    assertTrue(restored.pins.isEmpty())
    assertTrue(projectFeedCards(cards, restored, emptySet()).cards.isEmpty())
    assertEquals(listOf("note-0"), initial.pins)
  }

  @Test fun restoringOrRepublishingDuringRemovalProducesOneVisibleCard() {
    val card = card("note")
    val leaving = setOf(card.id)
    val removed = FeedPreferences().removing(card.id)
    val animated = projectFeedCards(listOf(card), removed, leaving)
    assertEquals(listOf(card), animated.cards)
    assertEquals(leaving, animated.leaving)
    // A delayed animation callback can outlive the authoritative restore/publish mutation.
    val restored = projectFeedCards(listOf(card), removed.copy(removed = emptySet()), leaving)
    assertEquals(listOf(card), restored.cards)
    assertTrue(restored.leaving.isEmpty())
  }

  @Test fun republishedPinnedAndRetractedCardsCannotRemainInTheRemovalAnimation() {
    val card = card("note")
    val prefs = FeedPreferences(pins = listOf(card.id))
    val pinned = projectFeedCards(listOf(card), prefs, setOf(card.id))
    assertEquals(listOf(card), pinned.pins)
    assertTrue(pinned.cards.isEmpty())
    assertTrue(pinned.leaving.isEmpty())
    val retracted = projectFeedCards(emptyList(), prefs.removing(card.id), setOf(card.id))
    assertTrue(retracted.cards.isEmpty())
    assertTrue(retracted.leaving.isEmpty())
  }
}
