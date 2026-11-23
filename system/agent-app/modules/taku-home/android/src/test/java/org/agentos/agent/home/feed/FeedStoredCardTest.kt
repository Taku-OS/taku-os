package org.agentos.agent.home.feed

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class FeedStoredCardTest {
  private fun card() = JSONObject("""
    {"schema":"taku.feed-card/1","id":"synthetic.stored-panel","source":{"id":"agent","app":"Synthetic storage test"},
     "layout":{"span":"full","surface":"card"},"summary":"Retained synthetic card",
     "pin":{"label":"Saved","icon":{"kind":"pixel","glyph":"chat"},"trailing":{"icon":"play","actionId":"open"}},
     "ui":{"catalog":"taku-feed-1","spec":{"root":"r","elements":{"r":{"type":"Text","props":{"text":"Retained"},"children":[]}}},"state":{"keep":"value"}},
     "actions":{"open":{"label":"Open","intent":{"type":"panel","panelId":"synthetic.old-panel","payload":{"keep":"opaque"}}},
                "ask":{"label":"Ask","intent":{"type":"ask-taku","prompt":"About this card"}}}}
  """.trimIndent())

  @Test fun onlyStoredCardsRetainRemovedPanelActions() {
    val input = card()
    val before = input.toString()
    val stored = FeedCard.parseStored(input)
    assertEquals(before, stored.json.toString())
    assertEquals(before, input.toString())
    assertEquals(Intent.Unavailable, stored.actions.getValue("open").intent)
    assertFalse(stored.actions.getValue("open").available)
    assertTrue(stored.actions.getValue("ask").available)
    assertEquals("opaque", stored.json.getJSONObject("actions").getJSONObject("open").getJSONObject("intent").getJSONObject("payload").getString("keep"))
  }

  @Test fun presentationAndAgentAvailabilityDoNotRewriteStoredJson() {
    val stored = FeedCard.parseStored(card())
    val before = stored.json.toString()
    val availability = stored.actionAvailability()
    assertFalse(availability.getJSONObject("open").getBoolean("available"))
    assertEquals(Intent.Unavailable.CODE, availability.getJSONObject("open").getString("code"))
    assertEquals(Intent.Unavailable.MESSAGE, availability.getJSONObject("open").getString("message"))
    assertTrue(availability.getJSONObject("ask").getBoolean("available"))
    assertEquals(availability.toString(), stored.presentationJson().getJSONObject("actionAvailability").toString())
    assertEquals("panel", stored.presentationJson().getJSONObject("actions").getJSONObject("open").getJSONObject("intent").getString("type"))
    assertEquals(before, stored.json.toString())
  }

  @Test fun newPublicationAndGenerationCannotOptIntoStoredCompatibility() {
    for (type in listOf("panel", "unavailable", "unknown-action")) {
      val input = card().apply { getJSONObject("actions").getJSONObject("open").getJSONObject("intent").put("type", type) }
      for (parse in listOf<(JSONObject) -> FeedCard>({ FeedCard.parse(it) }, { FeedCard.parseGenerated(it) })) {
        try { parse(input); fail("New input accepted $type") }
        catch (error: FeedException) { assertEquals("INVALID_CARD", error.code) }
      }
    }
    val unknown = card().apply { getJSONObject("actions").getJSONObject("open").getJSONObject("intent").put("type", "unknown-action") }
    try { FeedCard.parseStored(unknown); fail("Storage ignored an unknown action") }
    catch (error: FeedException) { assertEquals("INVALID_CARD", error.code) }
  }

  @Test fun unavailableActionFailsExplicitlyWhileValidActionRemainsUsable() {
    val stored = FeedCard.parseStored(card())
    try { stored.actions.getValue("open").requireAvailable(); fail("Unavailable action was accepted") }
    catch (error: FeedException) {
      assertEquals("ACTION_UNAVAILABLE", error.code)
      assertEquals("This action is no longer available.", error.message)
    }
    stored.actions.getValue("ask").requireAvailable()
  }
}
