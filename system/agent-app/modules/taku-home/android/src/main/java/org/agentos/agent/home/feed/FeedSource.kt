package org.agentos.agent.home.feed

import android.content.Context
import org.json.JSONObject

/** One page of source cards. [next] is null when the source is exhausted. */
data class SourcePage(val cards: List<FeedCard>, val next: String?)

/**
 * A producer of [FeedCard]s. The design sample is one implementation; a recommendation
 * service will be another. Sources never decide pin/remove state – the repository does.
 */
interface FeedSource {
  val id: String
  /** Loads the page after [cursor] (null = first page). Runs off the main thread. */
  fun page(cursor: String?): SourcePage
}

/**
 * Serves `assets/feed/sample-feed.json` (cards transcribed from the Figma design).
 *
 * The design contains one screen of cards, so to exercise the infinite feed the sample
 * source keeps paging through the same set as further "cycles" with derived ids
 * (`<id>:<cycle>`) and decaying rank. This repetition is a property of the sample source
 * only; a real source returns real pages and a null cursor when exhausted.
 */
class SampleFeedSource(private val load: () -> String, private val pageSize: Int = 4, private val maxCycles: Int = 40) : FeedSource {
  constructor(context: Context) : this({ context.assets.open("feed/sample-feed.json").bufferedReader().use { it.readText() } })

  override val id = "sample"

  private val cards: List<FeedCard> by lazy {
    val root = JSONObject(load())
    val list = root.getJSONArray("cards")
    (0 until list.length()).map { FeedCard.parse(list.getJSONObject(it), forcedSource = "sample") }
  }

  override fun page(cursor: String?): SourcePage {
    val position = cursor?.toIntOrNull() ?: 0
    val first = cards
    val repeating = cards.filterNot { it.pinnedByDefault }
    if (repeating.isEmpty()) return SourcePage(if (position == 0) first else emptyList(), null)
    // Cycle 0 is the design as drawn (including the default pinned glances).
    val out = ArrayList<FeedCard>(pageSize)
    var p = position
    while (out.size < pageSize) {
      val cycle = if (p < first.size) 0 else 1 + (p - first.size) / repeating.size
      if (cycle > maxCycles) break
      val card = if (cycle == 0) first[p] else repeating[(p - first.size) % repeating.size].derived(cycle)
      out += card
      p++
    }
    val exhausted = p >= first.size && 1 + (p - first.size) / repeating.size > maxCycles
    return SourcePage(out, if (exhausted) null else p.toString())
  }

  private fun FeedCard.derived(cycle: Int): FeedCard {
    val json = JSONObject(json.toString())
      .put("id", "$id:$cycle")
      .put("rank", rank - cycle)
      .put("createdAt", createdAt - cycle * 3_600_000L)
      .put("pinned", false)
    return FeedCard.parse(json, forcedSource = "sample")
  }
}
