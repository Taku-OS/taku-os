package org.agentos.agent.home.feed

import android.content.Context
import android.os.CancellationSignal
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable

/** What the shell renders. [cards] excludes pinned, removed and leaving cards. */
data class FeedState(
  val cards: List<FeedCard> = emptyList(),
  val pins: List<FeedCard> = emptyList(),
  val leaving: Set<String> = emptySet(),
  /** Card body heights (dp) reported by React Native. */
  val heights: Map<String, Float> = emptyMap(),
  val loading: Boolean = false,
  val endReached: Boolean = false,
  val error: String? = null,
  val revision: Long = 0,
) {
  /** Presentation-only invitation, shared by the GUI and Agent snapshot; never stored as a recommendation. */
  val emptyState: FeedWelcome? get() = if (cards.isEmpty() && pins.isEmpty() && !loading && endReached && error == null) FeedWelcome else null
}

object FeedWelcome {
  const val app = "Taku AI"
  const val title = "How can I help you?"
  const val message = "Ask a question, plan your day, or get something done with Taku."
  const val actionLabel = "Ask Taku"

  fun toJson() = JSONObject().put("type", "ask-taku").put("app", app).put("title", title).put("message", message)
    .put("action", JSONObject().put("label", actionLabel).put("uri", "content://org.agentos.agent.home-assistant")
      .put("method", "assistant/open").put("arg", JSONObject().put("mode", "text")))
}

/** Removal requested while the card may be on screen; the state is already committed while the UI animates. */
data class LeaveRequest(val cardId: String, val origin: FeedOrigin)

class FeedRepository private constructor(context: Context, private val sources: List<FeedSource>) {
  private val lock = Object()
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val store = FeedStore(context)
  private var preferences: FeedPreferences
  private val published = LinkedHashMap<String, FeedCard>()
  private var eventHead: Long

  private val loaded = LinkedHashMap<String, FeedCard>()
  private val cursors = HashMap<String, String?>()
  private val exhausted = HashSet<String>()
  private val leaving = LinkedHashSet<String>()
  private val heights = HashMap<String, Float>()
  private var loading = false
  /** Bumped by [reset]; a page load started under an older generation discards its result. */
  private var generation = 0L
  private var error: String? = null
  private var revision = 0L

  private val _state = MutableStateFlow(FeedState())
  val state: StateFlow<FeedState> = _state.asStateFlow()
  private val _leaves = MutableSharedFlow<LeaveRequest>(extraBufferCapacity = 16)
  val leaves: SharedFlow<LeaveRequest> = _leaves.asSharedFlow()
  private val _changes = MutableSharedFlow<String>(extraBufferCapacity = 64)
  /** Card ids whose JSON changed (for React Native `onCardChanged`). */
  val changes: SharedFlow<String> = _changes.asSharedFlow()

  /** Set by the shell while a feed page is visible; committed removals then animate. */
  @Volatile var animator: ((String) -> Boolean)? = null

  init {
    try {
      preferences = store.restore(published)
      eventHead = store.head()
      if (sources.isEmpty()) {
        // Push-only live Feed: retire legacy sample preferences without touching published cards.
        val next = preferences.copy(
          pins = preferences.pins.filter { it in published },
          removed = LinkedHashSet(preferences.removed.filter { it in published }), defaultsApplied = true,
        )
        store.applyDefaults(next)
        preferences = next
      }
    } catch (e: Exception) {
      store.close()
      if (e is FeedException) throw e
      throw FeedException("STATE_READ_FAILED", "Could not restore Feed state")
    }
    publish()
  }

  fun card(id: String): FeedCard? = synchronized(lock) { published[id] ?: loaded[id] }

  fun isRemoved(id: String): Boolean = synchronized(lock) { id in preferences.removed }

  fun height(id: String): Float? = synchronized(lock) { heights[id] }

  fun setHeight(id: String, dp: Float) {
    synchronized(lock) {
      if (heights[id] == dp) return
      heights[id] = dp
      revision++
    }
    publish()
  }

  fun snapshot(): JSONObject = synchronized(lock) {
    val s = computeState()
    JSONObject()
      .put("revision", revision)
      .put("cards", JSONArray(s.cards.map { summary(it) }))
      .put("pins", JSONArray(s.pins.map { summary(it) }))
      .put("emptyState", s.emptyState?.toJson() ?: JSONObject.NULL)
      .put("removed", JSONArray(preferences.removed.toList()))
      .put("loading", loading)
      .put("endReached", s.endReached)
      .put("error", error ?: JSONObject.NULL)
      .put("sources", JSONArray(sources.map { src ->
        JSONObject().put("id", src.id).put("exhausted", src.id in exhausted).put("cursor", cursors[src.id] ?: JSONObject.NULL)
      }))
      .put("eventCursor", FeedCursor(store.epoch, eventHead).toString())
  }

  private fun summary(card: FeedCard) = JSONObject()
    .put("id", card.id).put("app", card.app).put("source", card.sourceId)
    .put("span", card.span.name.lowercase()).put("surface", card.surface.name.lowercase())
    .put("rank", card.rank).put("summary", card.summary).put("pinned", card.id in preferences.pins)
    .put("pin", card.json.optJSONObject("pin"))
    .put("actions", JSONArray(card.actions.values.map { JSONObject().put("id", it.id).put("label", it.label) }))

  // ---- Paging ------------------------------------------------------------------------

  /**
   * Loads the next page from every non-exhausted source. Safe to call repeatedly. A page that
   * adds nothing visible (all its cards pinned or removed) continues to the next one, up to
   * [MAX_EMPTY_PAGES], so the feed never stops at only its pinned row (issue #32 F2). [FeedState.loading]
   * stays true across those chained pages, so a caller waiting for idle sees the final result.
   */
  fun loadMore() {
    val gen: Long
    synchronized(lock) {
      if (loading || sources.all { it.id in exhausted }) return
      loading = true
      error = null
      gen = generation
    }
    publish()
    scope.launch { loadPages(gen) }
  }

  private fun loadPages(gen: Long) {
    var emptyPages = 0
    while (true) {
      try {
        val pending = synchronized(lock) {
          if (gen != generation) return
          sources.filter { it.id !in exhausted }.map { it to cursors[it.id] }
        }
        val pages = pending.map { (src, cursor) -> src to src.page(cursor) }
        synchronized(lock) {
          // reset() ran while this page was loading: its state is gone and it reloads itself.
          if (gen != generation) return
          for ((src, page) in pages) {
            for (card in page.cards) loaded.putIfAbsent(card.id, card)
            cursors[src.id] = page.next
            if (page.next == null) exhausted += src.id
          }
          if (!preferences.defaultsApplied) {
            val pins = preferences.pins.toMutableList()
            for (card in loaded.values) {
              if (card.pinnedByDefault && card.id !in pins && card.id !in preferences.removed) pins += card.id
            }
            val next = preferences.copy(pins = pins, defaultsApplied = true)
            store.applyDefaults(next)
            preferences = next
          }
        }
      } catch (e: Exception) {
        Log.e(TAG, "feed page failed", e)
        synchronized(lock) {
          if (gen != generation) return
          loading = false; error = (e as? FeedException)?.code ?: "SOURCE_FAILED"; revision++
        }
        publish()
        return
      }
      val again = synchronized(lock) {
        if (gen != generation) return
        val more = !sources.all { it.id in exhausted } && computeState().cards.isEmpty() && emptyPages < MAX_EMPTY_PAGES
        if (!more) loading = false
        revision++
        more
      }
      publish()
      if (!again) return
      emptyPages++
    }
  }

  fun ensureLoaded() {
    if (synchronized(lock) { loaded.isEmpty() && !loading }) loadMore()
  }

  /** Removes a card. If the shell is showing it, the card leaves with the dust animation first. */
  fun remove(id: String, origin: FeedOrigin): JSONObject {
    val wasVisible: Boolean
    synchronized(lock) {
      if (id in preferences.removed) return JSONObject().put("id", id).put("removed", true).put("changed", false)
      if (published[id] == null && loaded[id] == null) notFound(id)
      wasVisible = computeState().cards.any { it.id == id }
      val next = preferences.removing(id)
      commit(FeedEvent.mutation(origin, FeedOperation.REMOVE, id), next)
      revision++
    }
    val animating = wasVisible && animator?.invoke(id) == true
    if (animating) {
      synchronized(lock) { if (id in preferences.removed) { leaving += id; revision++ } }
      _leaves.tryEmit(LeaveRequest(id, origin))
      scope.launch { delay(LEAVE_TIMEOUT_MS); commitLeave(id) }
    }
    publish()
    return JSONObject().put("id", id).put("removed", true).put("changed", true).put("animated", animating)
  }

  /** Called by the shell when the removal animation no longer needs the live card. */
  fun commitLeave(id: String) {
    synchronized(lock) { if (!leaving.remove(id)) return; revision++ }
    publish()
  }

  fun restore(id: String, origin: FeedOrigin): JSONObject {
    synchronized(lock) {
      if (id !in preferences.removed) {
        if (published[id] == null && loaded[id] == null) notFound(id)
        return JSONObject().put("id", id).put("removed", false).put("changed", false)
      }
      val next = preferences.copy(removed = LinkedHashSet(preferences.removed).apply { remove(id) })
      commit(FeedEvent.mutation(origin, FeedOperation.RESTORE, id), next)
      leaving.remove(id)
      revision++
    }
    publish()
    return JSONObject().put("id", id).put("removed", false).put("changed", true)
  }

  /** Index addresses visible pins; unresolved stored pins retain their relative position. */
  fun pin(id: String, origin: FeedOrigin, index: Int? = null): JSONObject {
    val at: Int
    synchronized(lock) {
      if (published[id] == null && loaded[id] == null) notFound(id)
      if (id in preferences.removed) throw FeedException("CARD_REMOVED", "restore $id before pinning it")
      val shown = shownPins()
      if (id in preferences.pins && index == null) return JSONObject().put("id", id).put("pinned", true).put("changed", false).put("index", shown.indexOf(id))
      val others = shown - id
      at = (index ?: 0).coerceIn(0, others.size)
      val pins = preferences.pins.toMutableList().apply { remove(id) }
      val anchor = others.getOrNull(at)
      pins.add(if (anchor != null) pins.indexOf(anchor) else pins.size, id)
      val next = preferences.copy(pins = pins)
      commit(FeedEvent.mutation(origin, FeedOperation.PIN, id, JSONObject().put("index", at)), next)
      revision++
    }
    publish()
    return JSONObject().put("id", id).put("pinned", true).put("changed", true).put("index", at)
  }

  fun unpin(id: String, origin: FeedOrigin): JSONObject {
    synchronized(lock) {
      if (id !in preferences.pins) {
        if (published[id] == null && loaded[id] == null) notFound(id)
        return JSONObject().put("id", id).put("pinned", false).put("changed", false)
      }
      val next = preferences.copy(pins = preferences.pins.filterNot { it == id })
      commit(FeedEvent.mutation(origin, FeedOperation.UNPIN, id), next)
      revision++
    }
    publish()
    return JSONObject().put("id", id).put("pinned", false).put("changed", true)
  }

  /** Reorder the visible row, preserving unresolved stored pins. */
  fun reorderPins(ids: List<String>, origin: FeedOrigin): JSONObject {
    synchronized(lock) {
      val shown = shownPins()
      if (ids.size != shown.size || ids.toSet() != shown.toSet()) {
        throw FeedException("INVALID_ORDER", "ids must be a permutation of the current pins $shown")
      }
      val shownSet = shown.toHashSet()
      val order = ids.iterator()
      val next = preferences.copy(pins = preferences.pins.map { if (it in shownSet) order.next() else it })
      commit(FeedEvent.mutation(origin, FeedOperation.REORDER_PINS, null, JSONObject().put("ids", JSONArray(ids))), next)
      revision++
    }
    publish()
    return JSONObject().put("pins", JSONArray(ids))
  }

  fun publishCard(cardJson: JSONObject, origin: FeedOrigin): JSONObject {
    val card = FeedCard.parse(cardJson, forcedSource = "agent")
    val replaced: Boolean
    synchronized(lock) {
      if (loaded.containsKey(card.id)) throw FeedException("ID_CONFLICT", "${card.id} belongs to source ${loaded[card.id]?.sourceId}")
      replaced = card.id in published
      val pins = if (card.pinnedByDefault && card.id !in preferences.pins) listOf(card.id) + preferences.pins else preferences.pins
      val next = preferences.copy(removed = LinkedHashSet(preferences.removed).apply { remove(card.id) }, pins = pins)
      commit(FeedEvent.mutation(origin, FeedOperation.PUBLISH, card.id), next, FeedCardWrite.Put(card))
      published[card.id] = card
      leaving.remove(card.id)
      revision++
    }
    _changes.tryEmit(card.id)
    publish()
    return JSONObject().put("id", card.id).put("replaced", replaced)
  }

  fun publishGeneratedCard(cardJson: JSONObject, id: String, origin: FeedOrigin): JSONObject {
    val candidate = FeedCard.parse(cardJson, forcedSource = "agent")
    synchronized(lock) {
      if (loading || !preferences.defaultsApplied || error != null) throw FeedException("FEED_UNAVAILABLE", "Feed sources are not ready")
      if (id in loaded || id in published || id in preferences.removed) throw FeedException("ID_CONFLICT", "Generated card identity is already in use")
      val now = System.currentTimeMillis()
      if (candidate.expired(now)) throw FeedException("INVALID_CARD", "Card has already expired")
      val maximum = (published.values.asSequence() + loaded.values.asSequence()).map { it.rank }.filter { it.isFinite() }.maxOrNull()
      val rank = maximum?.let(Math::nextUp) ?: 0.0
      if (!rank.isFinite()) throw FeedException("RANK_EXHAUSTED", "No finite insertion rank is available")
      candidate.json.put("id", id).put("rank", rank).put("createdAt", now).put("pinned", false)
      val card = candidate.copy(id = id, rank = rank, createdAt = now, pinnedByDefault = false)
      commit(FeedEvent.mutation(origin, FeedOperation.GENERATE, id), cards = FeedCardWrite.Put(card))
      published[id] = card
      revision++
    }
    _changes.tryEmit(id)
    publish()
    return JSONObject().put("id", id).put("replaced", false)
  }

  fun retract(id: String, origin: FeedOrigin): JSONObject {
    synchronized(lock) {
      if (id !in published) throw FeedException(if (loaded.containsKey(id)) "NOT_AGENT_CARD" else "NOT_FOUND",
        if (loaded.containsKey(id)) "$id is not an Agent card; use feed/remove" else "no card $id")
      val next = preferences.copy(pins = preferences.pins.filterNot { it == id })
      commit(FeedEvent.mutation(origin, FeedOperation.RETRACT, id), next, FeedCardWrite.Retract(id))
      published.remove(id)
      leaving.remove(id)
      revision++
    }
    publish()
    return JSONObject().put("id", id).put("retracted", true)
  }

  fun reset(origin: FeedOrigin): JSONObject {
    synchronized(lock) {
      val next = FeedPreferences(defaultsApplied = sources.isEmpty())
      commit(FeedEvent.mutation(origin, FeedOperation.RESET, null), next, FeedCardWrite.Reset)
      generation++
      published.clear(); loaded.clear(); cursors.clear(); exhausted.clear(); leaving.clear()
      loading = false; error = null
      revision++
    }
    publish()
    loadMore()
    return JSONObject().put("reset", true)
  }

  internal fun acceptAction(request: FeedActionRequest, origin: FeedOrigin): AcceptedFeedAction = synchronized(lock) {
    val card = published[request.cardId] ?: loaded[request.cardId] ?: notFound(request.cardId)
    val action: CardAction
    val values: FeedSelectionValues
    when (request) {
      is FeedActionRequest.Declared -> {
        action = card.actions[request.actionId]
          ?: throw FeedException("UNKNOWN_ACTION", "card ${card.id} has no action ${request.actionId}; known: ${card.actions.keys}")
        values = request.values
      }
      is FeedActionRequest.AskAbout -> {
        action = CardAction("ask-taku", "Ask Taku", Intent.AskTaku("About this ${card.app} card from my home feed: ${card.summary}\n\n"))
        values = FeedSelectionValues.EMPTY
      }
    }
    if (action.intent == Intent.ConfirmDiningShare) requireDiningShare(card)
    val cursor = commit(FeedEvent.accepted(origin, card.id, action, values, request is FeedActionRequest.AskAbout))
    AcceptedFeedAction(card, action, values, cursor)
  }

  private fun requireDiningShare(card: FeedCard): Intent.ShareText {
    val share = card.actions["share"]?.intent as? Intent.ShareText
    if (card.actions[Intent.ConfirmDiningShare.ID]?.intent != Intent.ConfirmDiningShare || share == null ||
      card.id in preferences.removed || card.expired(System.currentTimeMillis())) {
      throw FeedException("CARD_CHANGED", "The reservation card has changed")
    }
    return share
  }

  internal fun recordDiningShareDecision(cardId: String, expected: Intent.ShareText, requested: FeedCursor, confirmed: Boolean) {
    synchronized(lock) {
      val card = published[cardId] ?: loaded[cardId] ?: notFound(cardId)
      if (requireDiningShare(card) != expected)
        throw FeedException("CARD_CHANGED", "The reservation card has changed")
      if (!store.isDiningShareRequestPending(cardId, requested))
        throw FeedException("REQUEST_EXPIRED", "This share confirmation has already ended")
      commit(FeedEvent.diningShareResponse(cardId, requested, confirmed))
    }
  }

  internal fun recordDispatch(accepted: AcceptedFeedAction, origin: FeedOrigin): FeedCursor? {
    val event = FeedEvent.dispatched(origin, accepted) ?: return null
    return synchronized(lock) { commit(event) }
  }

  private fun commit(event: FeedEvent, preferences: FeedPreferences? = null, cards: FeedCardWrite? = null): FeedCursor {
    val requested = preferences ?: this.preferences
    val now = System.currentTimeMillis()
    val next = requested.copy(pins = requested.pins.filterNot { id ->
      val card = if (cards is FeedCardWrite.Put && cards.card.id == id) cards.card else published[id] ?: loaded[id]
      card?.expired(now) == true
    })
    val cursor = store.commit(event, next, cards)
    this.preferences = next
    eventHead = cursor.sequence
    lock.notifyAll()
    return cursor
  }

  internal sealed interface EventRead {
    data class Event(val stored: StoredFeedEvent) : EventRead
    data class Checkpoint(val through: FeedCursor) : EventRead
    data object End : EventRead
  }

  internal fun openEventReader(request: FeedEventRequest): EventReader = synchronized(lock) {
    val through = FeedCursor(store.epoch, eventHead)
    val after = when (val start = request.start) {
      FeedStart.Beginning -> FeedCursor(store.epoch, 0)
      FeedStart.Now -> through
      is FeedStart.After -> start.cursor.also {
        if (it.epoch != store.epoch) throw FeedException("CURSOR_EPOCH_MISMATCH", "cursor belongs to a different Feed database")
        if (it.sequence > eventHead) throw FeedException("CURSOR_AHEAD", "cursor is ahead of committed Feed history")
      }
    }
    EventReader(request, after, through)
  }

  internal inner class EventReader(
    private val request: FeedEventRequest,
    after: FeedCursor,
    through: FeedCursor,
  ) : Closeable {
    val ready: JSONObject = JSONObject().put("type", "ready").put("schema", "taku.feed-event-stream/1")
      .put("after", after.toString()).put("through", through.toString()).put("follow", request.follow)
      .put("legacyImportedCount", store.legacyImportedCount).put("legacyHistoryMayBeIncomplete", store.legacyHistoryMayBeIncomplete)
      .put("leaseMillis", FeedEventStreams.LEASE_MILLIS)
    private var position = after.sequence
    private var highWater = through.sequence
    private var windowComplete = false
    private var batch: List<StoredFeedEvent> = emptyList()
    private var batchIndex = 0
    private val cancellation = CancellationSignal()
    @Volatile private var closed = false

    fun next(): EventRead {
      if (closed) return EventRead.End
      if (windowComplete) {
        if (!request.follow) return EventRead.End
        val deadline = SystemClock.elapsedRealtime() + 10_000
        synchronized(lock) {
          while (!closed && eventHead <= position) {
            val remaining = deadline - SystemClock.elapsedRealtime()
            if (remaining <= 0) return EventRead.Checkpoint(FeedCursor(store.epoch, position))
            lock.wait(remaining)
          }
          if (closed) return EventRead.End
          highWater = eventHead
        }
        windowComplete = false
      }
      if (batchIndex == batch.size) {
        batch = store.events(position, highWater, request.filter, cancellation)
        batchIndex = 0
      }
      if (batch.isEmpty()) {
        position = highWater
        windowComplete = true
        return EventRead.Checkpoint(FeedCursor(store.epoch, highWater))
      }
      val event = batch[batchIndex++]
      position = event.sequence
      return EventRead.Event(event)
    }

    fun chunk(event: StoredFeedEvent, offset: Long): ByteArray {
      if (closed) throw FeedException("STREAM_CLOSED", "Feed stream is closed")
      return store.chunk(event.sequence, offset, cancellation)
    }

    override fun close() {
      closed = true
      cancellation.cancel()
      synchronized(lock) { lock.notifyAll() }
    }
  }

  /** Stored pins that resolve to a live card, in order: what `feed/list` and the pinned row show. */
  private fun shownPins(): List<String> = computeState().pins.map { it.id }

  private fun notFound(id: String): Nothing = throw FeedException("NOT_FOUND", "no loaded card $id; use feed/list or feed/load-more")

  private fun computeState(): FeedState {
    val now = System.currentTimeMillis()
    // One card per id: an Agent card shadows a source card with the same id that a later page
    // brought in (publishing only rejects ids already loaded). Rows are keyed by id.
    val all = (published.values + loaded.values.filter { it.id !in published }).filterNot { it.expired(now) }
    val presentation = projectFeedCards(all, preferences, leaving)
    return FeedState(presentation.cards, presentation.pins, presentation.leaving, HashMap(heights), loading, sources.all { it.id in exhausted }, error, revision)
  }

  private fun publish() {
    synchronized(lock) { _state.value = computeState() }
  }

  companion object {
    private const val TAG = "TakuHomeFeed"
    /** Bound chained pages with no visible cards. */
    const val MAX_EMPTY_PAGES = 8
    private const val LEAVE_TIMEOUT_MS = 2_000L

    @Volatile private var instance: FeedRepository? = null

    fun get(context: Context): FeedRepository = instance ?: synchronized(this) {
      instance ?: FeedRepository(context.applicationContext, emptyList()).also { instance = it }
    }
  }
}
