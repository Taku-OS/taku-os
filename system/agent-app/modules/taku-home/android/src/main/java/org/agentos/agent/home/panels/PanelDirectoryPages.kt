package org.agentos.agent.home.panels

import org.json.JSONArray
import org.json.JSONObject

/** One call into the Panel Interface; the real one is the public Assistant provider. */
internal fun interface PanelCall {
  fun call(method: String, arg: JSONObject): JSONObject
}

/**
 * Reads the whole directory, not the first page: the public Interface pages by `(position,
 * panelId)` and reports `LIST_CHANGED` when the revision moves under a walk, so the only correct
 * answer is to start again. Kept free of Android types so the paging rules are testable.
 */
internal object PanelDirectoryPages {
  const val CONTRACT = "taku.panels/1"
  const val UPDATE_REQUIRED = "UPDATE_REQUIRED"
  const val LIST_CHANGED = "LIST_CHANGED"
  const val ASSISTANT_UNAVAILABLE = "ASSISTANT_UNAVAILABLE"

  private const val DESCRIBE = "panels/describe"
  private const val LIST = "panels/list"
  private const val PAGE_SIZE = 100
  private const val MAX_PAGES = 1_000
  private const val MAX_RESTARTS = 3
  private val ORDER = compareBy<PanelDirectory.Entry>({ it.position }, { it.panelId })

  data class Result(val entries: List<PanelDirectory.Entry>, val listRevision: Long)

  /** Fails with [UPDATE_REQUIRED] unless the Assistant serves exactly the contract this build reads. */
  fun verify(call: PanelCall) {
    val described = try {
      call.call(DESCRIBE, JSONObject())
    } catch (e: PanelReadException) {
      if (e.code == ASSISTANT_UNAVAILABLE) throw e
      throw PanelReadException(UPDATE_REQUIRED, "the installed Assistant does not serve the Panel contract: ${e.message}")
    }
    val version = described.optString("version")
    if (version != CONTRACT) {
      throw PanelReadException(UPDATE_REQUIRED, "the installed Assistant serves '$version' and this build needs '$CONTRACT'")
    }
  }

  fun read(call: PanelCall): Result {
    verify(call)
    var restarts = 0
    while (true) {
      try {
        return walk(call)
      } catch (e: PanelReadException) {
        if (e.code != LIST_CHANGED || ++restarts > MAX_RESTARTS) throw e
      }
    }
  }

  private fun walk(call: PanelCall): Result {
    val entries = ArrayList<PanelDirectory.Entry>()
    var cursor: String? = null
    var revision = -1L
    var pages = 0
    do {
      val arg = JSONObject().put("limit", PAGE_SIZE)
      cursor?.let { arg.put("cursor", it) }
      val page = call.call(LIST, arg)
      revision = page.optLong("listRevision", revision)
      val items = page.optJSONArray("items") ?: JSONArray()
      for (i in 0 until items.length()) items.optJSONObject(i)?.let { entries += entry(it) }
      cursor = if (page.isNull("nextCursor")) null else page.optString("nextCursor").ifBlank { null }
      if (++pages > MAX_PAGES) throw PanelReadException("DIRECTORY_TOO_LARGE", "the directory exceeded $MAX_PAGES pages")
    } while (cursor != null)
    return Result(entries.sortedWith(ORDER).distinctBy { it.panelId }, revision)
  }

  private fun entry(item: JSONObject): PanelDirectory.Entry {
    val panelId = item.optString("panelId")
    if (panelId.isBlank()) throw PanelReadException("INVALID_DIRECTORY_RECORD", "a Panel record has no panelId")
    return PanelDirectory.Entry(panelId, item.optString("title"), item.optLong("position", 0L))
  }
}
