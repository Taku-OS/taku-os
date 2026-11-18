package org.agentos.agent.home.feed

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.UUID

class FeedEventsTest {
  private val resource = "content://org.agentos.agent.home-feed/events"
  private val epoch = UUID.fromString("7ce87ec1-59d8-4fe8-906b-8ff6ad2d12a6")

  private fun rejects(code: String, block: () -> Unit) {
    try {
      block()
      fail("expected $code")
    } catch (e: FeedException) {
      assertEquals(code, e.code)
    }
  }

  @Test fun parsesFiniteReplayAndExplicitFollowWithoutChangingLiteralIds() {
    val finite = FeedEventRequest.parse(resource)
    assertEquals(FeedStart.Beginning, finite.start)
    assertFalse(finite.follow)
    assertEquals(FeedCardFilter.All, finite.filter.cards)

    val follow = FeedEventRequest.parse("$resource?after=now&follow=1&cardId=acme%3Aone&cardId=acme.two&origin=gui&op=action")
    assertEquals(FeedStart.Now, follow.start)
    assertTrue(follow.follow)
    assertEquals(FeedCardFilter.Ids(listOf("acme:one", "acme.two")), follow.filter.cards)
    assertEquals(FeedOrigin.GUI, follow.filter.origin)
    assertEquals(FeedOperation.ACTION, follow.filter.operation)

    val prefix = FeedEventRequest.parse("$resource?cardIdPrefix=Acme_one.")
    assertEquals(FeedCardFilter.Prefix("Acme_one."), prefix.filter.cards)
  }

  @Test fun rejectsAmbiguousOrUnsupportedFiltersBeforeOpeningAStream() {
    val invalid = listOf(
      "$resource?follow=true", "$resource?follow=", "$resource?origin=GUI", "$resource?op=action:open",
      "$resource?unknown=value", "$resource?after=0&after=now", "$resource?after=0&%61fter=now",
      "$resource?origin=gui&origin=agent", "$resource?op=action&op=pin", "$resource?follow=0&follow=1",
      "$resource?cardIdPrefix=acme.&cardIdPrefix=other.", "$resource?cardId=a&cardIdPrefix=a",
      "$resource?cardId=", "$resource?cardId=bad+id", "$resource?cardIdPrefix=%25", "$resource?cardId=%FF",
      "$resource?cardId=a&", "$resource?follow", "$resource#ignored", "$resource/",
      "content://wrong-authority/events", "https://org.agentos.agent.home-feed/events",
      "content://org.agentos.agent.home-feed/%65vents", "$resource?" + List(129) { "cardId=a" }.joinToString("&"),
      "$resource?cardId=" + "a".repeat(16 * 1024),
    )
    for (uri in invalid) rejects("INVALID_ARGUMENT") { FeedEventRequest.parse(uri) }
  }

  @Test fun cursorsPreserveIntegerPrecisionAndRejectNoncanonicalSequences() {
    val raw = "$epoch:9007199254740993"
    val request = FeedEventRequest.parse("$resource?after=$raw")
    assertEquals(FeedStart.After(FeedCursor(epoch, 9_007_199_254_740_993L)), request.start)
    assertEquals(raw, FeedCursor.parse(raw).toString())
    for (invalid in listOf("42", "$epoch:-1", "$epoch:01", "$epoch:1.0", "$epoch:9223372036854775808", "1-1-1-1-1:1")) {
      rejects("INVALID_CURSOR") { FeedCursor.parse(invalid) }
    }
  }

  @Test fun acceptedSelectionIsAnImmutableSnapshotOfJsonPathsAndTypes() {
    val input = JSONObject().put("/ui/size", "大杯").put("/ui/page", 2).put("/ui/enabled", false)
      .put("/ui/empty", JSONObject.NULL).put("/ui/options", JSONArray().put("a").put(3))
      .put("/ui/nested", JSONObject().put("key", "original"))
    val selected = FeedSelectionValues.capture(input)
    input.getJSONArray("/ui/options").put("later")
    input.getJSONObject("/ui/nested").put("key", "later")
    selected.json().put("/ui/page", 99)
    val encoded = FeedEvent.accepted(FeedOrigin.GUI, "acme.order", CardAction("confirm", "Confirm", Intent.None), selected, false)
      .encode(FeedCursor(epoch, 9_007_199_254_740_993L))
    val event = JSONObject(String(encoded, Charsets.UTF_8))
    assertEquals("$epoch:9007199254740993", event.getString("cursor"))
    assertEquals("declared", event.getString("actionSource"))
    assertEquals("accepted", event.getString("phase"))
    assertEquals("none", event.getString("intentType"))
    val values = event.getJSONObject("values")
    assertEquals("大杯", values.getString("/ui/size"))
    assertEquals(2, values.getInt("/ui/page"))
    assertEquals(false, values.get("/ui/enabled"))
    assertTrue(values.isNull("/ui/empty"))
    assertEquals(2, values.getJSONArray("/ui/options").length())
    assertEquals(3, values.getJSONArray("/ui/options").getInt(1))
    assertEquals("original", values.getJSONObject("/ui/nested").getString("key"))
  }

  @Test fun limitsNewSelectionsByUtf8BytesAndRejectsOversizedCompleteEvents() {
    val nearLimit = FeedSelectionValues.capture(JSONObject().put("/ui/text", "x".repeat(65_000)))
    assertEquals(65_000, nearLimit.json().getString("/ui/text").length)
    rejects("EVENT_TOO_LARGE") { FeedSelectionValues.capture(JSONObject().put("/ui/text", "界".repeat(22_000))) }
    rejects("EVENT_TOO_LARGE") {
      FeedEvent.accepted(FeedOrigin.AGENT, "card", CardAction("a".repeat(128 * 1024), "Action", Intent.None), FeedSelectionValues.EMPTY, false)
        .encode(FeedCursor(epoch, 1))
    }
  }

  @Test fun legacyActionsKeepOnlyKnownFieldsAndRetainTheirExactEntry() {
    val old = JSONObject().put("op", "action:confirm").put("cardId", "old.card")
      .put("detail", JSONObject().put("effect", "none")).put("unknown", JSONArray().put(1).put(JSONObject.NULL))
    val event = JSONObject(String(FeedEvent.legacy(old).encode(FeedCursor(epoch, 1)), Charsets.UTF_8))
    assertEquals("action", event.getString("op"))
    assertEquals("confirm", event.getString("actionId"))
    assertEquals("legacy-recorded", event.getString("phase"))
    assertEquals(old.toString(), event.getJSONObject("legacy").toString())
    for (absent in listOf("at", "origin", "values", "intentType", "actionSource")) assertFalse(absent, event.has(absent))
  }

  @Test fun legacySelectionsAreNotTruncatedToTheNewEventLimit() {
    val original = "界".repeat(70_000)
    val old = JSONObject().put("at", 12).put("origin", "gui").put("op", "action:old")
      .put("detail", JSONObject().put("values", JSONObject().put("/ui/text", original)))
    val bytes = FeedEvent.legacy(old).encode(FeedCursor(epoch, 2))
    assertTrue(bytes.size > 128 * 1024)
    val event = JSONObject(String(bytes, Charsets.UTF_8))
    assertEquals(original, event.getJSONObject("values").getString("/ui/text"))
    assertEquals(old.toString(), event.getJSONObject("legacy").toString())
  }
}
