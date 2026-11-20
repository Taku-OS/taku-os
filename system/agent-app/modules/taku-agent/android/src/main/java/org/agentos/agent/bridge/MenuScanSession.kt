package org.agentos.agent.bridge

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import expo.modules.kotlin.exception.CodedException
import java.io.File
import java.io.FileNotFoundException
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

internal class MenuScanException(code: String, message: String) : CodedException(code, message, null)

internal object MenuScanSession {
  private enum class SuggestionView(val action: String, val value: String) {
    PICKS("showPicks", "picks"),
    LIGHTER("showLighter", "lighter"),
    COMPARISON("comparePicks", "comparison"),
    PREFERENCES("openPreferences", "preferences"),
  }

  private enum class ArtworkState(val value: String) {
    QUEUED("queued"),
    GENERATING("generating"),
    READY("ready"),
    UNAVAILABLE("unavailable"),
  }

  private enum class SearchRound { FIRST, SUPPLEMENT, RETRY }

  private data class SearchJob(val round: SearchRound, val future: Future<*>)

  private data class Artwork(
    val request: MenuDishArtwork.Request, val state: ArtworkState, val file: File? = null,
    val cached: Boolean = false, val startedMs: Long? = null, val settledMs: Long? = null,
  )

  private data class SearchTiming(
    val queuedMs: Long, val startedMs: Long? = null, val firstPostMs: Long? = null, val twoPlatformsMs: Long? = null,
    val threePlatformsMs: Long? = null, val firstRoundFinishedMs: Long? = null, val supplementStartedMs: Long? = null,
    val settledMs: Long? = null,
  ) {
    fun json(): JSONObject = JSONObject().put("queuedMs", queuedMs).put("startedMs", startedMs).put("firstPostMs", firstPostMs)
      .put("twoPlatformsMs", twoPlatformsMs).put("threePlatformsMs", threePlatformsMs)
      .put("firstRoundFinishedMs", firstRoundFinishedMs).put("supplementStartedMs", supplementStartedMs).put("settledMs", settledMs)
  }

  private data class Scan(
    val id: String,
    val owner: Any,
    val context: Context,
    val photo: File,
    val width: Int,
    val height: Int,
    val scanMs: Long,
    val enrichmentStartMs: Long,
    val dishes: JSONArray,
    val dishIds: Set<String>,
    val venue: JSONObject?,
    val artworkStore: MenuDishArtwork,
    val artwork: Map<String, Artwork>,
    val artworkPriority: List<String>,
    val videoSearch: Map<String, JSONObject> = emptyMap(),
    val searchTiming: Map<String, SearchTiming> = emptyMap(),
    val orderItems: Map<String, Int> = emptyMap(),
    val selectedDishId: String? = null,
    val view: String = "menu",
    val suggestionView: SuggestionView = SuggestionView.PICKS,
    val revision: Long = 0,
  ) {
    fun elapsed(): Long = SystemClock.elapsedRealtime() - enrichmentStartMs

    fun snapshot(): JSONObject = JSONObject()
      .put("scanId", id)
      .put("revision", revision)
      .put("width", width)
      .put("height", height)
      .put("dishes", dishes)
      .put("venue", venue)
      .put("videoSearch", JSONObject(videoSearch))
      .put("timing", JSONObject().put("scanMs", scanMs)
        .put("artwork", JSONObject(artwork.mapValues { (_, item) ->
          JSONObject().put("cached", item.cached).put("startedMs", item.startedMs).put("settledMs", item.settledMs)
        }))
        .put("social", JSONObject(searchTiming.mapValues { (_, item) -> item.json() })))
      .put("artwork", JSONObject(artwork.mapValues { (dishId, item) ->
        JSONObject().put("state", item.state.value).apply {
          if (item.file != null) put("uri", Uri.Builder().scheme("content")
            .authority(MenuScanProvider.AUTHORITY).appendPath("artwork").appendPath(id).appendPath(dishId).build())
        }
      }))
      .put("orderIds", JSONArray(orderItems.keys))
      .put("orderItems", JSONArray(orderItems.map { (dishId, quantity) ->
        JSONObject().put("dishId", dishId).put("quantity", quantity)
      }))
      .put("selectedDishId", selectedDishId ?: JSONObject.NULL)
      .put("view", view)
      .put("suggestionView", suggestionView.value)
  }

  private val lock = Any()
  private val active = MutableStateFlow<Scan?>(null)
  private const val SEARCH_WORKERS = 4
  private val searchPool = Executors.newFixedThreadPool(SEARCH_WORKERS)
  private val searches = mutableMapOf<String, SearchJob>()
  private val pendingSearches = mutableMapOf<String, SearchRound>()
  private val searchContexts = mutableMapOf<String, MenuVideoSearch.DishSearch>()
  private const val ARTWORK_WORKERS = 2
  private val artworkPool = Executors.newFixedThreadPool(ARTWORK_WORKERS)
  private val artworkJobs = mutableMapOf<MenuDishArtwork.Request, Future<*>>()
  val changes = active.map { it?.snapshot().toString() }

  fun read(): JSONObject? = active.value?.snapshot()

  fun begin(owner: Any, context: Context, photo: File, scanJson: String): JSONObject {
    val scan = try {
      val input = JSONObject(scanJson)
      val width = input.getInt("width")
      val height = input.getInt("height")
      val dishes = input.getJSONArray("dishes")
      val items = (0 until dishes.length()).map(dishes::getJSONObject)
      val dishIds = items.map { it.get("id") as? String ?: "" }.toSet()
      if (dishIds.size != items.size || dishIds.any(String::isBlank)) {
        throw MenuScanException("INVALID_ARGUMENT", "Menu dishes must have unique, nonempty IDs")
      }
      if (width <= 0 || height <= 0) {
        throw MenuScanException("INVALID_ARGUMENT", "Menu photo dimensions must be positive")
      }
      val artworkStore = MenuDishArtwork(File(context.cacheDir, "menu-dish-artwork"))
      artworkStore.prune()
      val artwork = items.associate { dish ->
        val request = artworkStore.request(dish)
        val file = artworkStore.cached(request)
        dish.getString("id") to Artwork(request, if (file == null) ArtworkState.QUEUED else ArtworkState.READY,
          file, cached = file != null, settledMs = 0L.takeIf { file != null })
      }
      val priority = items.sortedWith(compareByDescending<JSONObject> {
        it.optJSONObject("evidence")?.optJSONArray("socialPostUrls")?.length() ?: 0
      }.thenByDescending { it.optJSONObject("suggestions")?.has("picks") == true })
        .map { it.getString("id") }
      Scan(UUID.randomUUID().toString(), owner, context, photo, width, height, input.optLong("scanMs").coerceAtLeast(0),
        SystemClock.elapsedRealtime(), dishes, dishIds,
        input.optJSONObject("venue"), artworkStore, artwork, priority)
    } catch (_: JSONException) {
      throw MenuScanException("INVALID_ARGUMENT", "Expected menu photo dimensions and a list of dishes")
    }
    synchronized(lock) {
      cancelSearches()
      cancelArtwork()
      active.value = if (scan.venue == null) scan else scan.copy(
        videoSearch = scan.dishIds.associateWith { searchResult("queued") },
        searchTiming = scan.dishIds.associateWith { SearchTiming(scan.elapsed()) },
      )
      if (scan.venue != null) scan.dishIds.forEach { pendingSearches[it] = SearchRound.FIRST }
      scheduleSearches()
      return scheduleArtwork().snapshot()
    }
  }

  fun action(action: String, scanId: String, dishId: String, quantity: Number? = null): JSONObject = synchronized(lock) {
    if (scanId.isBlank()) throw MenuScanException("INVALID_ARGUMENT", "scanId is required")
    val scan = active.value ?: throw MenuScanException("NO_ACTIVE_SCAN", "There is no active menu scan")
    if (scan.id != scanId) throw MenuScanException("STALE_SCAN", "The menu scan has changed")
    val next = when (action) {
      "select" -> {
        requireDish(scan, dishId)
        scan.copy(selectedDishId = dishId, view = "menu")
      }
      "retryArtwork" -> {
        requireDish(scan, dishId)
        val item = scan.artwork.getValue(dishId)
        if (item.state != ArtworkState.UNAVAILABLE && item.state != ArtworkState.READY) return@synchronized scan.snapshot()
        item.file?.delete()
        scan.copy(
          artwork = scan.artwork.mapValues { (_, other) ->
            if (other.request == item.request) Artwork(other.request, ArtworkState.QUEUED) else other
          },
          artworkPriority = listOf(dishId) + scan.artworkPriority.filterNot { it == dishId },
        )
      }
      "searchVideo" -> {
        requireDish(scan, dishId)
        if (scan.venue == null) throw MenuScanException("VENUE_UNKNOWN", "No confirmed restaurant for this menu")
        val result = scan.videoSearch[dishId]
        val state = result?.optString("state")
        if (state == "searching" || state == "queued" || state == "found") return@synchronized scan.snapshot()
        val previous = result?.optJSONArray("videos")
        pendingSearches[dishId] = SearchRound.RETRY
        scan.copy(videoSearch = scan.videoSearch + (dishId to searchResult("queued", previous)),
          searchTiming = scan.searchTiming + (dishId to SearchTiming(scan.elapsed())))
      }
      "add" -> {
        requireDish(scan, dishId)
        val order = if (dishId in scan.orderItems) scan.orderItems else scan.orderItems + (dishId to 1)
        scan.copy(orderItems = order, selectedDishId = null)
      }
      "remove" -> {
        requireDish(scan, dishId)
        val order = scan.orderItems - dishId
        scan.copy(orderItems = order, view = if (order.isEmpty()) "menu" else scan.view)
      }
      "setQuantity" -> {
        requireDish(scan, dishId)
        val count = quantity?.toDouble()?.takeIf { it in 1.0..99.0 && it % 1.0 == 0.0 }?.toInt()
          ?: throw MenuScanException("INVALID_ARGUMENT", "quantity must be a whole number from 1 to 99")
        if (dishId !in scan.orderItems) throw MenuScanException("DISH_NOT_IN_ORDER", "Dish is not part of this order")
        scan.copy(orderItems = scan.orderItems + (dishId to count))
      }
      "openOrder", "showToStaff" -> {
        if (scan.orderItems.isEmpty()) throw MenuScanException("ORDER_EMPTY", "The menu order is empty")
        scan.copy(view = if (action == "showToStaff") "staff" else "order", selectedDishId = null)
      }
      "back" -> scan.copy(view = if (scan.view == "staff") "order" else "menu", selectedDishId = null)
      else -> {
        val suggestionView = SuggestionView.entries.firstOrNull { it.action == action }
          ?: throw MenuScanException("UNKNOWN_ACTION", "Unknown menu scan action")
        if (scan.view != "menu" || scan.selectedDishId != null) {
          throw MenuScanException("SUGGESTIONS_UNAVAILABLE", "Menu suggestions require the main menu with no dish selected")
        }
        scan.copy(suggestionView = suggestionView)
      }
    }
    if (next == scan) return@synchronized scan.snapshot()
    val updated = next.copy(revision = scan.revision + 1)
    active.value = updated
    scheduleSearches()
    scheduleArtwork().snapshot()
  }

  private fun scheduleArtwork(): Scan {
    var scan = requireNotNull(active.value)
    val menuOrder = scan.dishIds.toList()
    val selectedIndex = menuOrder.indexOf(scan.selectedDishId)
    val nearby = if (selectedIndex < 0) emptyList() else
      listOfNotNull(menuOrder[selectedIndex], menuOrder.getOrNull(selectedIndex + 1), menuOrder.getOrNull(selectedIndex - 1))
    for (dishId in (nearby + scan.artworkPriority).distinct()) {
      if (artworkJobs.size >= ARTWORK_WORKERS) break
      val item = scan.artwork.getValue(dishId)
      if (item.state != ArtworkState.QUEUED) continue
      scan = publishArtwork(scan, item.request, ArtworkState.GENERATING)
      val scheduled = scan
      artworkJobs[item.request] = artworkPool.submit {
        val file = try {
          scheduled.artworkStore.generate(scheduled.context.contentResolver, item.request)
        } catch (_: InterruptedException) {
          return@submit
        } catch (_: Exception) {
          null
        }
        synchronized(lock) {
          val current = active.value?.takeIf { it.id == scheduled.id } ?: return@synchronized
          artworkJobs.remove(item.request)
          publishArtwork(current, item.request, if (file == null) ArtworkState.UNAVAILABLE else ArtworkState.READY, file)
          scheduleArtwork()
        }
      }
    }
    return scan
  }

  private fun publishArtwork(scan: Scan, request: MenuDishArtwork.Request, state: ArtworkState, file: File? = null): Scan {
    val elapsed = scan.elapsed()
    val updated = scan.copy(
      artwork = scan.artwork.mapValues { (_, item) ->
        if (item.request != request) item
        else if (state == ArtworkState.GENERATING) item.copy(state = state, startedMs = elapsed)
        else item.copy(state = state, file = file, settledMs = elapsed)
      },
      revision = scan.revision + 1,
    )
    active.value = updated
    return updated
  }

  private fun scheduleSearches() {
    val scan = active.value ?: return
    if (scan.venue == null) return
    val selected = scan.selectedDishId
    val priority = listOfNotNull(selected) + scan.artworkPriority.filterNot { it == selected }
    val firstRoundOpen = pendingSearches.containsValue(SearchRound.FIRST) || searches.values.any { it.round == SearchRound.FIRST }
    for (dishId in priority) {
      if (searches.size >= SEARCH_WORKERS) break
      val round = pendingSearches[dishId] ?: continue
      if (firstRoundOpen && round != SearchRound.FIRST) continue
      pendingSearches.remove(dishId)
      startSearch(requireNotNull(active.value), dishId, round)
    }
  }

  private fun startSearch(scan: Scan, dishId: String, round: SearchRound) {
    val previous = scan.videoSearch[dishId]?.optJSONArray("videos")
    val elapsed = scan.elapsed()
    val timing = scan.searchTiming.getValue(dishId)
    val updatedTiming = timing.copy(startedMs = timing.startedMs ?: elapsed,
      supplementStartedMs = elapsed.takeIf { round != SearchRound.FIRST })
    val started = scan.copy(
      videoSearch = scan.videoSearch + (dishId to searchResult("searching", previous)),
      searchTiming = scan.searchTiming + (dishId to updatedTiming),
      revision = scan.revision + 1,
    )
    active.value = started
    val search = searchContexts.getOrPut(dishId) { newSearch(scan, dishId) }
    searches[dishId] = SearchJob(round, searchPool.submit {
      val result = try {
        when (round) {
          SearchRound.FIRST -> search.firstRound()
          SearchRound.SUPPLEMENT -> search.supplement()
          SearchRound.RETRY -> search.supplement(retry = true)
        }
      } catch (_: InterruptedException) {
        return@submit
      } catch (_: Exception) {
        null
      }
      synchronized(lock) {
        val current = active.value?.takeIf { it.id == scan.id } ?: return@synchronized
        searches.remove(dishId)
        val posts = result?.let { JSONArray(it.posts.map(MenuVideo::json)) }
          ?: current.videoSearch[dishId]?.optJSONArray("videos")
        val supplement = round == SearchRound.FIRST && (result?.needsSupplement ?: true)
        val state = when {
          supplement -> "queued"
          (posts?.length() ?: 0) > 0 -> "found"
          result == null || result.unavailable -> "unavailable"
          else -> "notFound"
        }
        if (supplement) pendingSearches[dishId] = SearchRound.SUPPLEMENT else searchContexts.remove(dishId)
        publishSearch(scan.id, dishId, searchResult(state, posts), finishedRound = round, settled = !supplement)
        scheduleSearches()
      }
    })
  }

  private fun newSearch(scan: Scan, dishId: String): MenuVideoSearch.DishSearch {
    val venue = requireNotNull(scan.venue)
    val dish = (0 until scan.dishes.length()).asSequence().map(scan.dishes::getJSONObject)
      .first { it.getString("id") == dishId }
    val evidence = dish.optJSONObject("evidence")
    return MenuVideoSearch.DishSearch(
      scan.context, dish.getString("name"), venue.getString("name"), venue.getString("address"),
      evidence?.optJSONArray("videoDishAliases").strings(), evidence?.optJSONArray("videoSourcePages").strings(),
      evidence?.optJSONArray("socialPostUrls").strings(), venue.optString("instagramHandle").takeIf(String::isNotBlank),
    ) { publishSearch(scan.id, dishId, searchResult("searching", JSONArray(it.map(MenuVideo::json)))) }
  }

  private fun publishSearch(scanId: String, dishId: String, result: JSONObject,
    finishedRound: SearchRound? = null, settled: Boolean = false) = synchronized(lock) {
    val current = active.value ?: return@synchronized
    if (current.id != scanId) return@synchronized
    val timing = current.searchTiming.getValue(dishId)
    val elapsed = current.elapsed()
    val posts = result.optJSONArray("videos")
    val platforms = (0 until (posts?.length() ?: 0)).mapNotNull {
      posts?.optJSONObject(it)?.optString("platform")?.takeIf(String::isNotBlank)
    }.toSet()
    val updatedTiming = timing.copy(
      firstPostMs = timing.firstPostMs ?: elapsed.takeIf { platforms.isNotEmpty() },
      twoPlatformsMs = timing.twoPlatformsMs ?: elapsed.takeIf { platforms.size >= 2 },
      threePlatformsMs = timing.threePlatformsMs ?: elapsed.takeIf { platforms.size >= 3 },
      firstRoundFinishedMs = timing.firstRoundFinishedMs ?: elapsed.takeIf { finishedRound == SearchRound.FIRST },
      settledMs = elapsed.takeIf { settled },
    )
    active.value = current.copy(videoSearch = current.videoSearch + (dishId to result),
      searchTiming = current.searchTiming + (dishId to updatedTiming), revision = current.revision + 1)
  }

  private fun searchResult(state: String, videos: JSONArray? = null): JSONObject =
    JSONObject().put("state", state).apply {
      if (videos != null && videos.length() > 0) put("videos", videos)
    }

  private fun JSONArray?.strings(): List<String> =
    (0 until (this?.length() ?: 0)).mapNotNull { this?.optString(it)?.takeIf(String::isNotBlank) }

  private fun cancelSearches() {
    searches.values.forEach { it.future.cancel(true) }
    searches.clear()
    pendingSearches.clear()
    searchContexts.clear()
  }

  private fun cancelArtwork() {
    artworkJobs.values.forEach { it.cancel(true) }
    artworkJobs.clear()
  }

  private fun requireDish(scan: Scan, dishId: String) {
    if (dishId.isBlank()) throw MenuScanException("INVALID_ARGUMENT", "dishId is required")
    if (dishId !in scan.dishIds) throw MenuScanException("DISH_NOT_FOUND", "Dish is not part of this menu scan")
  }

  fun finish(scanId: String) = synchronized(lock) {
    if (active.value?.id == scanId) {
      active.value = null
      cancelSearches()
      cancelArtwork()
    }
  }

  fun finishOwner(owner: Any) = synchronized(lock) {
    if (active.value?.owner === owner) {
      active.value = null
      cancelSearches()
      cancelArtwork()
    }
  }

  fun openPhoto(scanId: String): ParcelFileDescriptor = synchronized(lock) {
    val scan = active.value?.takeIf { it.id == scanId }
      ?: throw FileNotFoundException("Menu scan photo is no longer available")
    ParcelFileDescriptor.open(scan.photo, ParcelFileDescriptor.MODE_READ_ONLY)
  }

  fun openArtwork(scanId: String, dishId: String): ParcelFileDescriptor = synchronized(lock) {
    val file = active.value?.takeIf { it.id == scanId }?.artwork?.get(dishId)?.file
      ?: throw FileNotFoundException("Menu dish artwork is not available")
    ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
  }
}
