package org.agentos.agent.home

import org.agentos.agent.home.feed.CardSpan
import org.agentos.agent.home.feed.AcceptedFeedAction
import org.agentos.agent.home.feed.FeedCard
import org.agentos.agent.home.feed.FeedCursor
import org.agentos.agent.home.feed.FeedEvent
import org.agentos.agent.home.feed.FeedException
import org.agentos.agent.home.feed.FeedOrigin
import org.agentos.agent.home.feed.FeedSelectionValues
import org.agentos.agent.home.feed.Intent
import org.agentos.agent.home.feed.PinIcon
import org.agentos.agent.home.feed.SampleFeedSource
import org.agentos.agent.home.design.PixelGlyphs
import org.agentos.agent.home.ui.splitDisplayValue
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.HexFormat
import java.util.UUID

class FeedContractTest {
  private val sampleFile = File("src/main/assets/feed/sample-feed.json")
  private val assets = File("src/main/assets")

  private fun minimal(id: String = "agent-note") = JSONObject("""
    {"schema":"taku.feed-card/1","id":"$id","source":{"id":"agent","app":"Notes"},
     "summary":"A note.","pin":{"label":"Note","icon":{"kind":"pixel","glyph":"chat"}},
     "ui":{"catalog":"taku-feed-1","spec":{"root":"r","elements":{"r":{"type":"Text","props":{"text":"Hi"},"children":[]}}},"state":{}},
     "actions":{"open":{"label":"Open","intent":{"type":"ask-taku","prompt":"Open the note"}}}}
  """.trimIndent())

  private fun rejects(code: String, mutate: JSONObject.() -> Unit) {
    val card = minimal().apply(mutate)
    try { FeedCard.parse(card); fail("expected $code") } catch (e: FeedException) { assertEquals(e.message, code, e.code) }
  }

  @Test fun parsesMinimalCard() {
    val card = FeedCard.parse(minimal())
    assertEquals(CardSpan.FULL, card.span)
    assertEquals("ask-taku", card.json.getJSONObject("actions").getJSONObject("open").getJSONObject("intent").getString("type"))
  }

  @Test fun rejectsInvalidCards() {
    rejects("INVALID_CARD") { put("id", "bad id!") }
    rejects("INVALID_CARD") { put("schema", "other") }
    rejects("INVALID_CARD") { remove("summary") }
    rejects("INVALID_CARD") { getJSONObject("source").put("icon", "file:///etc/passwd") }
    rejects("INVALID_CARD") { getJSONObject("source").put("icon", "asset:feed/../../x.png") }
    rejects("INVALID_CARD") { getJSONObject("pin").put("label", "x".repeat(25)) }
    rejects("INVALID_CARD") { getJSONObject("pin").put("trailing", JSONObject().put("actionId", "missing")) }
    rejects("INVALID_CARD") { getJSONObject("ui").getJSONObject("spec").put("root", "nope") }
    rejects("INVALID_CARD") { getJSONObject("actions").getJSONObject("open").getJSONObject("intent").put("type", "open-url").put("url", "http://x") }
  }

  private fun whatsappShare(text: Any = "Reservation confirmed\n3 guests", packageName: Any = "com.whatsapp") =
    JSONObject().put("type", "share-text").put("package", packageName).put("text", text)

  private fun withShareIntent(intent: JSONObject) = minimal().apply {
    getJSONObject("actions").getJSONObject("open").put("intent", intent)
  }

  @Test fun feedShareActionPreservesWhatsAppTextAndRecordsItsIntentType() {
    val input = withShareIntent(whatsappShare())
    for (card in listOf(FeedCard.parse(input, forcedSource = "agent"), FeedCard.parseGenerated(input))) {
      val expected = input.getJSONObject("actions").getJSONObject("open").getJSONObject("intent")
      val saved = card.json.getJSONObject("actions").getJSONObject("open").getJSONObject("intent")
      assertEquals(expected.toString(), saved.toString())
      val action = card.actions.getValue("open")
      assertEquals(Intent.ShareText("com.whatsapp", expected.getString("text")), action.intent)
      val event = FeedEvent.accepted(FeedOrigin.GUI, card.id, action, FeedSelectionValues.EMPTY, askAbout = false)
      assertEquals("share-text", event.indexString("intentType"))
      val epoch = UUID.randomUUID()
      val accepted = AcceptedFeedAction(card, action, FeedSelectionValues.EMPTY, FeedCursor(epoch, 1))
      val dispatched = JSONObject(String(FeedEvent.dispatched(FeedOrigin.GUI, accepted).encode(FeedCursor(epoch, 2)), Charsets.UTF_8))
      assertEquals("dispatched", dispatched.getString("phase"))
      assertEquals("share-text", dispatched.getString("intentType"))
      assertEquals(accepted.cursor.toString(), dispatched.getString("acceptedCursor"))
      val proof = dispatched.getJSONObject("intent")
      assertEquals("com.whatsapp", proof.getString("package"))
      assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
        .digest(expected.getString("text").toByteArray(Charsets.UTF_8))), proof.getString("textSha256"))
      assertTrue(!proof.has("text"))
    }
  }

  @Test fun feedShareActionRejectsOtherAppsAndInvalidText() {
    for (intent in listOf(
      whatsappShare(packageName = "com.whatsapp.w4b"),
      whatsappShare(packageName = 12),
      whatsappShare(text = 12),
      whatsappShare(text = " "),
      whatsappShare(text = "a".repeat(Intent.ShareText.MAX_TEXT + 1)),
      whatsappShare().apply { remove("text") },
    )) {
      val input = withShareIntent(intent)
      for (parse in listOf<(JSONObject) -> FeedCard>({ FeedCard.parse(it) }, { FeedCard.parseGenerated(it) })) {
        try { parse(input); fail("accepted an invalid share-text action") }
        catch (e: FeedException) { assertEquals("INVALID_CARD", e.code) }
      }
    }
  }

  @Test fun generatedCardsRejectCoercedEnvelopeFields() {
    val malformed: List<JSONObject.() -> Unit> = listOf(
      { put("summary", 42) },
      { put("rank", "1") },
      { put("createdAt", 0.5) },
      { put("pinned", "false") },
      { getJSONObject("source").put("app", true) },
      { getJSONObject("pin").put("label", 12) },
      { getJSONObject("ui").put("state", "not an object") },
      { getJSONObject("actions").getJSONObject("open").getJSONObject("intent").put("prompt", 7) },
    )
    for (mutate in malformed) {
      try { FeedCard.parseGenerated(minimal().apply(mutate)); fail("accepted malformed generated card") }
      catch (e: FeedException) { assertEquals("INVALID_CARD", e.code) }
    }
  }

  @Test fun generatedFileEnvelopeKeepsItsContentAndAcceptsExistingOptionalFields() {
    val input = minimal().apply {
      put("rank", 9).put("pinned", true)
      getJSONObject("ui").remove("state")
    }
    val parsed = FeedCard.parseGenerated(input)
    assertEquals(input.getJSONObject("ui").toString(), parsed.json.getJSONObject("ui").toString())
    assertEquals(input.getJSONObject("actions").toString(), parsed.json.getJSONObject("actions").toString())
    assertEquals("A note.", parsed.summary)
  }

  @Test fun valueOnlyPinIsAllowed() {
    val card = FeedCard.parse(minimal().apply { put("pin", JSONObject("""{"value":"23°","icon":{"kind":"pixel","glyph":"sun"}}""")) })
    assertEquals("23°", card.pin.value)
  }

  @Test fun forcedSourceOverridesAgentClaims() {
    val card = FeedCard.parse(minimal().apply { getJSONObject("source").put("id", "system") }, forcedSource = "agent")
    assertEquals("agent", card.sourceId)
  }

  @Test fun everySampleCardParsesAndItsAssetsExist() {
    val cards = JSONObject(sampleFile.readText()).getJSONArray("cards")
    assertTrue(cards.length() >= 11)
    for (i in 0 until cards.length()) {
      val card = FeedCard.parse(cards.getJSONObject(i), forcedSource = "sample")
      val refs = Regex("asset:feed/[A-Za-z0-9._/-]+").findAll(card.json.toString()).map { it.value }.toSet()
      for (ref in refs) assertTrue("${card.id}: $ref", File(assets, ref.removePrefix("asset:")).isFile)
      (card.pin.icon as? PinIcon.Image)?.let { assertTrue(it.src, !it.src.endsWith(".svg")) }
    }
  }

  @Test fun samplePagesStartWithTheDesignThenCycle() {
    val source = SampleFeedSource({ sampleFile.readText() }, pageSize = 4, maxCycles = 2)
    val seen = ArrayList<FeedCard>()
    var cursor: String? = null
    do { val page = source.page(cursor); seen += page.cards; cursor = page.next } while (cursor != null)
    val ids = seen.map { it.id }
    assertEquals(ids.size, ids.toSet().size)
    val design = JSONObject(sampleFile.readText()).getJSONArray("cards")
    assertEquals(design.getJSONObject(0).getString("id"), ids.first())
    assertTrue(ids.any { it.endsWith(":1") } && ids.any { it.endsWith(":2") })
    assertTrue(seen.filter { it.id.contains(':') }.none { it.pinnedByDefault })
    val ranks = seen.map { it.rank }
    assertTrue(ranks.drop(design.length()).zipWithNext().all { (a, b) -> a >= b - 1.0 })
  }

  @Test fun displayValueSplitsDigitsFromUnits() {
    assertEquals(listOf("8" to true, "h" to false, "21" to true, "m" to false), splitDisplayValue("8h21m"))
    assertEquals(listOf("23°" to true), splitDisplayValue("23°"))
    assertEquals(listOf("31" to true, "%" to false), splitDisplayValue("31%"))
    assertEquals(listOf("$5.5" to true), splitDisplayValue("$5.5"))
  }

  @Test fun generatedGlyphsAreDeterministicAndMirrored() {
    val a = PixelGlyphs.dots("starbucks-reorder")
    assertEquals(a, PixelGlyphs.dots("starbucks-reorder"))
    assertTrue(a.isNotEmpty())
    for (d in a) assertTrue(a.any { kotlin.math.abs(it.x - (31.2f - d.x)) < 0.01f && it.y == d.y })
  }
}
