package org.agentos.agent.home.assistant

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ReplyOptionsTest {
  private val thread = "11111111-1111-4111-8111-111111111111"
  private var seq = 0L
  private fun reply(phase: String = "completed", revision: Int = 2, count: Int = 3, withYelpEvidence: Boolean = true): JSONObject = JSONObject("""{
    "schema":"taku.main-reply/1","id":"reply-options","key":"reply-options","revision":$revision,
    "readableText":"Real candidates","lifecycle":{"phase":"$phase"},
    "provenance":{"kind":"primary-assistant-reply","threadId":"$thread","turnId":"turn","contractHash":"${"a".repeat(64)}","anchorHash":"${"b".repeat(64)}","anchorOrdinal":0,"sourceItemIds":["answer"]},
    "card":{"request":{"version":2,"key":"reply-options","mode":"card","title":"Lunch options","summary":"November 18, noon, 3 people",
      "blocks":[{"id":"verified-options","kind":"collection","items":[]}],"actions":[]}}
  }""").also { r ->
    val rows = r.getJSONObject("card").getJSONObject("request").getJSONArray("blocks").getJSONObject(0).getJSONArray("items")
    repeat(count) { index ->
      val id = "option-$index"
      rows.put(JSONObject().put("id", id).put("label", "Observed branch $index")
        .put("detail", "Observed district").put("url", "https://www.yelp.com/biz/observed-branch-$index")
        .put("value", JSONObject().put("type", "text").put("value", if (index == 0) JSONObject.NULL else "4.6 · $$")))
      if (withYelpEvidence) companion(r, id, JSONArray().put(fact("cuisine", "Yelp", "Restaurant category")))
    }
  }
  private fun event(method: String, params: JSONObject, number: Long = ++seq): JSONObject = JSONObject()
    .put("schema", "taku.session-events/1").put("brokerEpoch", "epoch").put("threadId", thread).put("seq", number)
    .put("method", method).put("params", params)
  private fun start(): SessionStreamState = SessionStreamState(thread).also {
    it.apply(event("session/snapshot", JSONObject().put("thread", JSONObject().put("id", thread).put("turns", JSONArray())), 0))
    it.apply(event("turn/started", JSONObject().put("turnId", "turn").put("turn", JSONObject().put("id", "turn").put("status", "inProgress"))))
  }
  private fun publish(st: SessionStreamState, r: JSONObject): Boolean = st.apply(event("session/reply", JSONObject()
    .put("turnId", "turn").put("reply", r).put("items", JSONArray().put(JSONObject().put("id", "answer")
      .put("type", "agentMessage").put("replyId", r.getString("id")).put("text", r.getString("readableText"))))))
  private fun complete(st: SessionStreamState, status: String = "completed") = st.apply(event("turn/completed", JSONObject()
    .put("turnId", "turn").put("turn", JSONObject().put("id", "turn").put("status", status))))

  @Test fun firstSuccessfulTurnAutomaticallyProjectsActualOptionsWithoutAnotherMessage() {
    val st = start()
    assertTrue(publish(st, reply("streaming", 1)))
    assertNull(ThreadProjection.latest(st, emptySet())!!.options)
    assertTrue(publish(st, reply()))
    assertNull("a sealed answer cannot outrun task completion", ThreadProjection.latest(st, emptySet())!!.options)
    complete(st)
    val options = ThreadProjection.latest(st, emptySet())!!.options!!
    assertEquals(3, options.items.size)
    assertEquals("Observed branch 2", options.items[2].label)
    assertEquals("", options.items[0].value)
    assertEquals("4.6 · $$", options.items[1].value)
    assertFalse(st.needsResync)
  }

  @Test fun reconnectRestoresTheSameSavedOptionsWithNoFixedPhraseCityOrCount() {
    val r = reply(count = 2)
    val turn = JSONObject().put("id", "turn").put("status", "completed").put("items", JSONArray())
      .put("mainReplies", JSONObject().put(r.getString("id"), r))
    val st = SessionStreamState(thread)
    st.apply(event("session/snapshot", JSONObject().put("thread", JSONObject().put("id", thread).put("turns", JSONArray().put(turn))), 0))
    assertEquals(2, ThreadProjection.latest(st, emptySet())!!.options!!.items.size)
    assertNull(ThreadProjection.latest(st, setOf("turn")))
  }

  @Test fun lateRawTextDoesNotReplaceTheCanonicalReply() {
    val st = start(); publish(st, reply())
    st.apply(event("item/agentMessage/delta", JSONObject().put("turnId", "turn").put("itemId", "answer").put("delta", "@taku.reply/1")))
    st.apply(event("item/completed", JSONObject().put("turnId", "turn").put("item", JSONObject().put("id", "answer").put("type", "agentMessage").put("text", "raw protocol"))))
    complete(st)
    assertEquals("Real candidates", ThreadProjection.latest(st, emptySet())!!.reply)
    assertNotNull(ThreadProjection.latest(st, emptySet())!!.options)
  }

  @Test fun earlierRevisionCannotReplaceNewCandidates() {
    val st = start(); publish(st, reply(count = 3)); publish(st, reply(revision = 1, count = 1)); complete(st)
    assertEquals(3, ThreadProjection.latest(st, emptySet())!!.options!!.items.size)
  }

  @Test fun mismatchedThreadRequiresResyncAndNeverShowsAnotherGroupsCandidates() {
    val st = start(); val r = reply(); r.getJSONObject("provenance").put("threadId", "other-group")
    assertFalse(publish(st, r)); assertTrue(st.needsResync)
    assertNull(ThreadProjection.latest(st, emptySet())!!.options)
  }

  @Test fun cancelledAndFailedWorkCannotOfferSuccessfulCandidates() {
    for (status in listOf("cancelled", "interrupted", "failed")) {
      seq = 0; val st = start(); publish(st, reply()); complete(st, status)
      assertNull(ThreadProjection.latest(st, emptySet())!!.options)
    }
  }

  @Test fun duplicateRowsCannotBecomeSelectableOptions() {
    val r = reply(); val rows = r.getJSONObject("card").getJSONObject("request").getJSONArray("blocks").getJSONObject(0).getJSONArray("items")
    rows.getJSONObject(1).put("id", rows.getJSONObject(0).getString("id"))
    assertNull(ReplyOptions.from(r, thread, "turn"))
  }

  private fun fact(id: String, source: String, value: Any, type: String = "text") = JSONObject()
    .put("id", id).put("label", source).put("value", JSONObject().put("type", type).put("value", value))
  private fun companion(r: JSONObject, owner: String, rows: JSONArray): JSONObject {
    val blocks = r.getJSONObject("card").getJSONObject("request").getJSONArray("blocks")
    val existing = (1 until blocks.length()).mapNotNull(blocks::optJSONObject)
      .firstOrNull { it.optJSONObject("config")?.optString("group") == owner }
    if (existing != null) {
      val existingRows = existing.getJSONArray("items")
      val incomingRoles = (0 until rows.length()).map { rows.getJSONObject(it).getString("id") }.toSet()
      for (i in 0 until existingRows.length()) {
        val row = existingRows.getJSONObject(i)
        if (row.getString("id") !in incomingRoles) rows.put(row)
      }
      return existing.put("items", rows)
    }
    val b = JSONObject().put("id", "$owner-facts").put("kind", "collection")
      .put("config", JSONObject().put("group", owner).put("layout", "list")).put("items", rows)
    blocks.put(b)
    return b
  }

  @Test fun explicitFactsProjectIntoSameBranchTagsDescriptionAndPriceWithoutParsingProse() {
    val r = reply()
    companion(r, "option-0", JSONArray().put(fact("rating", "Provider A", 4.4, "number"))
      .put(fact("cuisine", "Yelp", "European"))
      .put(fact("price", "Provider A", "$30 and under").put("detail", "Price band; tax/tip unverified"))
      .put(fact("description", "Provider A", "Observed description"))
      .put(fact("address", "Provider A", "Observed branch street")))
    val options = ReplyOptions.from(r, thread, "turn")!!
    assertEquals(3, options.items.size)
    val first = options.items[0]
    assertEquals("4.4", first.fact("rating")!!.text)
    assertEquals("Provider A", first.fact("rating")!!.source)
    assertEquals("$30 and under", first.fact("price")!!.text)
    assertEquals("Observed description", first.description)
    assertEquals("Observed branch street", first.fact("address")!!.text)
    assertEquals("Restaurant category", options.items[1].fact("cuisine")!!.text)
    assertEquals(5, first.json().getJSONArray("facts").length())
  }

  @Test fun differingProviderFactsKeepTheirOwnSourceAndNeverBecomeAnExactPerPersonPrice() {
    val r = reply()
    companion(r, "option-0", JSONArray().put(fact("rating", "Yelp", 4.2, "number"))
      .put(fact("price", "Other source", "$30及以下")))
    val first = ReplyOptions.from(r, thread, "turn")!!.items[0]
    assertEquals("Yelp", first.fact("rating")!!.source)
    assertEquals("Other source", first.fact("price")!!.source)
    assertEquals("$30及以下", first.fact("price")!!.text)
    assertNull(first.fact("seats"))
  }

  @Test fun missingNullOrAmbiguousFactsStayAbsentAndLegacyTextIsNotConvertedIntoTags() {
    val r = reply()
    companion(r, "option-0", JSONArray().put(fact("rating", "A", 4.2, "number"))
      .put(fact("rating", "B", 4.4, "number")).put(fact("price", "A", JSONObject.NULL)))
    val options = ReplyOptions.from(r, thread, "turn")!!
    assertNull(options.items[0].fact("rating"))
    assertNull(options.items[0].fact("price"))
    assertEquals("Restaurant category", options.items[1].fact("cuisine")!!.text)
    assertEquals("4.6 · $$", options.items[1].value)
  }

  @Test fun twoCandidateCollectionsCannotAttachFactsBySimilarLabelsOrChooseOneSilently() {
    val r = reply()
    companion(r, "unrelated-branch", JSONArray().put(fact("rating", "A", 4.4, "number")))
    assertNull(ReplyOptions.from(r, thread, "turn"))
  }

  @Test fun anUnrelatedGroupedCollectionCannotBeHiddenAsRestaurantMetadata() {
    val r = reply(withYelpEvidence = false)
    companion(r, "option-0", JSONArray().put(fact("another-option", "Another real option", "Observed detail")))
    assertNull(ReplyOptions.from(r, thread, "turn"))
  }

  @Test fun additionalFactsKeepTheKnownFieldsAndDoNotHideTheCandidateCollection() {
    val r = reply()
    companion(r, "option-0", JSONArray().put(fact("cuisine", "Yelp", "Observed cuisine"))
      .put(fact("phone", "A", "An additional fact retained in the full canonical reply")))
    val first = ReplyOptions.from(r, thread, "turn")!!.items[0]
    assertEquals("Observed cuisine", first.fact("cuisine")!!.text)
    assertEquals(1, first.facts.size)
  }

  @Test fun yelpCategoryLabelsRemainSeparateFactsWithoutSplittingOrReplacingProviderData() {
    val r = reply()
    companion(r, "option-0", JSONArray().put(fact("category-tea-rooms", "Yelp", "茶室"))
      .put(fact("category-desserts", "Yelp", "甜品")))
    val first = ReplyOptions.from(r, thread, "turn")!!.items[0]
    assertEquals(listOf("茶室", "甜品"), first.facts.filter { it.role.startsWith("category-") }.map { it.text })
    assertTrue(first.facts.all { it.source == "Yelp" })
    assertNull(first.fact("price"))
    assertEquals(3, first.json().getJSONArray("facts").length())
  }

  @Test fun explicitlyTaggedMoneyKeepsItsCurrencyAndScale() {
    val r = reply()
    val price = fact("price", "A", "4200", "money")
    price.getJSONObject("value").put("currency", "USD").put("scale", 2)
    companion(r, "option-0", JSONArray().put(price))
    assertEquals("$42", ReplyOptions.from(r, thread, "turn")!!.items[0].fact("price")!!.text)
  }

  @Test fun mealConditionsAndUnverifiedFactsStayVisibleWhenTheModelUsesTextBlocks() {
    val r = reply(); val request = r.getJSONObject("card").getJSONObject("request")
    val candidates = request.getJSONArray("blocks").getJSONObject(0)
    request.put("summary", "").put("blocks", JSONArray()
      .put(JSONObject().put("id", "conditions").put("kind", "text").put("text", "11月18日纽约当地12:00，3人，Times Square"))
      .put(candidates)
      .put(JSONObject().put("id", "unknown").put("kind", "text").put("text", "当天营业和可订时段未核实"))
      .apply {
        val original = r.getJSONObject("card").getJSONObject("request").getJSONArray("blocks")
        for (i in 1 until original.length()) put(original.getJSONObject(i))
      })
    val options = ReplyOptions.from(r, thread, "turn")!!
    assertTrue(options.summary.contains("11月18日纽约当地12:00，3人，Times Square"))
    assertTrue(options.summary.contains("当天营业和可订时段未核实"))
    assertEquals(3, options.items.size)
  }

  private fun withoutRestaurantUrls(r: JSONObject): JSONObject = r.also {
    val rows = it.getJSONObject("card").getJSONObject("request").getJSONArray("blocks").getJSONObject(0).getJSONArray("items")
    for (i in 0 until rows.length()) rows.getJSONObject(i).remove("url")
  }
  @Test fun ordinaryNotebookAndFigmaCollectionsStayInTheirOriginalRenderer() {
    for (title in listOf("Notebook results", "Figma components", "Cedar 餐厅推荐")) {
      val r = withoutRestaurantUrls(reply(withYelpEvidence = false))
      r.getJSONObject("card").getJSONObject("request").put("title", title)
      assertNull("A title cannot infer restaurant domain", ReplyOptions.from(r, thread, "turn"))
    }
  }
  @Test fun shoppingRatingAndPriceAreNotRestaurantSemantics() {
    val r = withoutRestaurantUrls(reply(count = 2, withYelpEvidence = false))
    for (i in 0..1) companion(r, "option-$i", JSONArray().put(fact("rating", "Amazon", 4.5, "number")).put(fact("price", "Amazon", "$20")))
    assertNull(ReplyOptions.from(r, thread, "turn"))
  }
  @Test fun everyCandidateNeedsItsOwnTypedRestaurantEvidence() {
    val r = withoutRestaurantUrls(reply(count = 2, withYelpEvidence = false))
    companion(r, "option-0", JSONArray().put(fact("rating", "Yelp", 4.5, "number")))
    assertNull("One restaurant cannot label unrelated rows", ReplyOptions.from(r, thread, "turn"))
    companion(r, "option-1", JSONArray().put(fact("address", "Yelp", "Observed restaurant address")))
    assertEquals(2, ReplyOptions.from(r, thread, "turn")!!.items.size)
  }
  @Test fun aDifferentProviderDoesNotEnableYelpPollPresentation() {
    val r = withoutRestaurantUrls(reply(count = 1, withYelpEvidence = false))
    companion(r, "option-0", JSONArray().put(fact("cuisine", "Verified local source", "Observed cuisine")))
    assertNull(ReplyOptions.from(r, thread, "turn"))
  }
}
