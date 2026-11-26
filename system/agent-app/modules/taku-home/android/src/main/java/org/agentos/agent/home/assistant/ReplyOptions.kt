package org.agentos.agent.home.assistant

import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.util.Currency
import java.util.Locale

/** A typed fact from a companion collection, not a value extracted from descriptive prose. */
data class OptionFact(val role: String, val text: String, val source: String, val url: String, val note: String) {
  fun json(): JSONObject = JSONObject().put("role", role).put("text", text).put("source", source).put("url", url).put("note", note)
}

/** Read-only projection of a completed native primary reply, shared with the full conversation. */
data class ReplyOption(val id: String, val label: String, val detail: String, val value: String, val facts: List<OptionFact> = emptyList()) {
  fun fact(role: String): OptionFact? = facts.singleOrNull { it.role == role }
  val description: String get() = fact("description")?.text ?: detail
  fun json(): JSONObject = JSONObject().put("id", id).put("label", label).put("detail", detail).put("value", value)
    .put("facts", JSONArray(facts.map { it.json() }))
}

data class ReplyOptions(
  val replyId: String, val key: String, val turnId: String, val revision: Long, val contractHash: String,
  val title: String, val summary: String, val items: List<ReplyOption>,
) {
  val identity: String get() = "$turnId/$replyId"
  val sourceIdentity: String get() = "$identity/$key/$revision/$contractHash"
  fun source(): JSONObject = JSONObject().put("kind", "primary").put("replyId", replyId).put("turnId", turnId)
    .put("revision", revision).put("contractHash", contractHash)
  fun json(): JSONObject = JSONObject().put("replyId", replyId).put("key", key).put("turnId", turnId)
    .put("revision", revision).put("title", title).put("summary", summary).put("items", JSONArray(items.map { it.json() }))

  companion object {
    /** No keyword matching or extraction from prose; native provenance and semantic items own the result. */
    fun from(reply: JSONObject, threadId: String, turnId: String): ReplyOptions? {
      if (!validReply(reply, threadId, turnId) || reply.optJSONObject("lifecycle")?.optString("phase") != "completed") return null
      val request = reply.optJSONObject("card")?.optJSONObject("request") ?: return null
      if (request.optString("mode") != "card" || request.optString("key") != reply.optString("key")) return null
      val blocks = request.optJSONArray("blocks") ?: return null
      val collections = (0 until blocks.length()).mapNotNull { blocks.optJSONObject(it) }
        .filter { it.optString("kind") == "collection" && (it.optJSONArray("items")?.length() ?: 0) > 0 }
      // V2 already supports companion collections: config.group names the owning option ID.
      // Preserve that typed relationship instead of guessing tags/price from a mixed prose line.
      val optionIds = collections.flatMap { collection ->
        val rows = collection.getJSONArray("items")
        (0 until rows.length()).mapNotNull { rows.optJSONObject(it)?.optString("id") }
      }.toSet()
      val companions = collections.filter { factCollection(it) && it.optJSONObject("config")?.optString("group") in optionIds }
      val candidates = collections.filterNot { it in companions }
      // A single options collection has an unambiguous pager. More complex answers stay in full chat.
      val block = candidates.singleOrNull() ?: return null
      val rows = block.getJSONArray("items")
      if (rows.length() > 20) return null
      val items = (0 until rows.length()).map { index ->
        val row = rows.optJSONObject(index) ?: return null
        val id = row.optString("id"); val label = row.optString("label")
        if (!id.matches(Regex("[a-z0-9-]{1,64}")) || label.isBlank() || label.length > 80) return null
        val detailBlocks = companions.filter { it.optJSONObject("config")?.optString("group") == id }
        if (detailBlocks.size > 1) return null
        val facts = facts(detailBlocks.singleOrNull())
        // A generic collection remains in its ordinary renderer. Only typed restaurant
        // evidence may opt every candidate into this restaurant/WhatsApp presentation.
        if (!restaurantCandidate(facts)) return null
        ReplyOption(id, label, row.optString("detail").take(200), value(row.optJSONObject("value")), facts)
      }
      if (items.map { it.id }.distinct().size != items.size) return null
      val context = (0 until blocks.length()).mapNotNull { blocks.optJSONObject(it) }
        .filter { it.optString("kind") == "text" }.map { it.optString("text") }
      return ReplyOptions(reply.getString("id"), reply.getString("key"), turnId, reply.getLong("revision"),
        reply.getJSONObject("provenance").getString("contractHash"), request.optString("title"),
        (listOf(request.optString("summary")) + context + block.optString("text"))
          .filter { it.isNotBlank() }.distinct().joinToString("\n"), items)
    }

    private fun restaurantCandidate(facts: List<OptionFact>): Boolean = facts.any {
      it.source.equals("Yelp", true) &&
        (it.role in setOf("rating", "cuisine", "address") || it.role.startsWith("category-"))
    }

    private val factRoles = setOf("rating", "cuisine", "price", "description", "address", "seats", "availability", "hours")
    private fun factRole(role: String): Boolean = role in factRoles || role.matches(Regex("category-[a-z0-9][a-z0-9-]{0,54}"))
    private fun factCollection(block: JSONObject): Boolean {
      if (block.optJSONObject("config")?.optString("layout") != "list") return false
      val rows = block.optJSONArray("items") ?: return false
      return (0 until rows.length()).any { factRole(rows.optJSONObject(it)?.optString("id").orEmpty()) }
    }
    private fun facts(block: JSONObject?): List<OptionFact> {
      val rows = block?.optJSONArray("items") ?: return emptyList()
      val facts = (0 until rows.length()).mapNotNull { index ->
        val row = rows.optJSONObject(index) ?: return@mapNotNull null
        val role = row.optString("id")
        val text = value(row.optJSONObject("value"))
        val source = row.optString("label").take(80)
        if (!factRole(role) || text.isBlank() || source.isBlank()) return@mapNotNull null
        OptionFact(role, text, source, row.optString("url"), row.optString("detail").take(200))
      }
      // Ambiguous repeated roles must never silently pick one provider's fact.
      return facts.filter { fact -> facts.count { it.role == fact.role } == 1 }
    }

    internal fun validReply(reply: JSONObject, threadId: String, turnId: String): Boolean {
      val provenance = reply.optJSONObject("provenance") ?: return false
      val revision = reply.opt("revision") as? Number ?: return false
      return reply.optString("schema") == "taku.main-reply/1" && provenance.optString("kind") == "primary-assistant-reply"
        && provenance.optString("threadId") == threadId && provenance.optString("turnId") == turnId
        && provenance.optString("contractHash").matches(Regex("[a-f0-9]{64}"))
        && reply.optString("id").isNotBlank() && reply.optString("key").matches(Regex("[a-z0-9-]{1,64}"))
        && revision.toLong() in 1..9_007_199_254_740_991L && revision.toDouble() == revision.toLong().toDouble()
    }

    private fun value(tagged: JSONObject?): String {
      if (tagged == null || tagged.isNull("value")) return ""
      return when (tagged.optString("type")) {
        "text" -> (tagged.opt("value") as? String).orEmpty().take(1000)
        "number" -> (tagged.opt("value") as? Number)?.let { "$it ${tagged.optString("unit")}".trim() }.orEmpty()
        "money" -> runCatching {
          val currency = tagged.getString("currency")
          val amount = BigDecimal(tagged.getString("value")).movePointLeft(tagged.getInt("scale")).stripTrailingZeros().toPlainString()
          "${Currency.getInstance(currency).getSymbol(Locale.US)}$amount${tagged.optString("unit")}".trim()
        }.getOrDefault("")
        // Other tagged values remain in the full native card; do not guess their formatting or units.
        else -> ""
      }
    }
  }
}
