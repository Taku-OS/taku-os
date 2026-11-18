package org.agentos.agent.home.apps

import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.AtomicFile
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.agentos.agent.home.feed.FeedException
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** An item on a home page: one app or a folder of apps. */
sealed interface Slot {
  data class Widget(val appWidgetId: Int, val provider: String, val spanX: Int, val spanY: Int) : Slot
  data class App(val key: String) : Slot
  data class Folder(val id: String, val name: String, val apps: List<String>) : Slot
}

/** How the home pages are shown: one page per [HomePages.capacity] (default), or every app on one scrolling page. */
enum class HomeMode(val id: String) {
  PAGES("pages"), SCROLL("scroll");
  companion object { fun of(id: String): HomeMode? = entries.firstOrNull { it.id == id } }
}

/** The home pages (page 0 sits under the widgets); see [HomePages]. */
data class HomeLayout(val pages: List<List<Slot>> = listOf(emptyList()), val revision: Long = 0, val ready: Boolean = false, val mode: HomeMode = HomeMode.PAGES, val hidden: Set<String> = emptySet()) {
  fun locate(key: String): AppLocation? = HomePages.locate(pages, key)
  fun folder(id: String): Slot.Folder? = pages.flatten().firstOrNull { it is Slot.Folder && it.id == id } as Slot.Folder?

  companion object { const val COLUMNS = 4 }
}

/**
 * The home screen pages, iPhone-style: apps and folders the user arranges by dragging, across
 * as many pages as they fill. The first launch lays out every app in the default order
 * ([LauncherPresentation.grid]) over pages of [HomePages.capacity]; afterwards new apps join
 * the last page, uninstalled apps leave, overflow moves to the next page and pages other than
 * page 0 disappear when emptied. [HomeMode] chooses pages or one scrolling page. The GUI and
 * `content://org.agentos.agent.home-layout` share these operations and validation.
 */
class HomeLayoutRepository private constructor(private val context: Context) {
  private val lock = Any()
  private val file = AtomicFile(File(File(context.filesDir, "home-layout").apply { mkdirs() }, "layout.json"))
  private var pages: List<List<Slot>> = listOf(emptyList())
  private var revision = 0L
  private var mode = HomeMode.PAGES
  private var hidden: Set<String> = emptySet()
  /** False until the installed apps have been laid out once (first launch or migration pending). */
  private var seeded = false
  private var widgetsSeeded = false
  /** Pre-pages layout read from disk, applied once the app list is known. */
  private var legacy: Pair<List<Slot>, String>? = null
  private val _layout = MutableStateFlow(HomeLayout())
  val layout: StateFlow<HomeLayout> = _layout.asStateFlow()

  init { restore(); publish() }

  /** Follows the installed apps (called with every [AppCatalog] update). */
  fun sync(apps: List<LaunchableApp>) {
    if (apps.isEmpty()) return
    synchronized(lock) {
      val nextHidden = hidden.intersect(apps.map { it.key }.toSet())
      val installed = apps.map { it.key }.filterNot { it in nextHidden }
      val next = if (!seeded) {
        val old = legacy
        val grid = LauncherPresentation.grid(apps.filterNot { it.key in nextHidden }, old?.second ?: "Taku")
        HomePages.reconcile(if (old == null) listOf(grid) else HomePages.migrate(grid, old.first), installed)
      } else HomePages.reconcile(pages, installed)
      if (seeded && next == pages && nextHidden == hidden) return
      val op = if (seeded) "sync" else if (legacy != null) "migrate" else "seed"
      commit("system", op, JSONObject().put("pages", next.size), next, nextHidden = nextHidden)
      seeded = true; legacy = null
    }
  }

  /** Drag and drop: see [HomePages.move]. */
  fun move(key: String?, folderId: String?, page: Int, index: Int?, into: Boolean, origin: String, widgetId: Int? = null): JSONObject {
    key?.let(::requireApp)
    synchronized(lock) {
      requireReady()
      if (key in hidden) throw FeedException("APP_HIDDEN", "restore this app with apps/show before moving it")
      val change = HomePages.move(pages, key, folderId, page, index, into, { UUID.randomUUID().toString().take(8) }, ::folderName, widgetId)
      val result = JSONObject().put("effect", change.effect).put("page", change.page).put("index", change.index)
        .put("pages", change.pages.size)
      key?.let { result.put("key", it) }
      folderId?.let { result.put("folderId", it) }
      change.folderId?.let { result.put("folderId", it) }
      if (change.pages != pages) commit(origin, "move", result, change.pages)
      return result
    }
  }

  /** Removal from Home never uninstalls. Hidden entries remain discoverable in apps/list. */
  fun setHidden(key: String, hide: Boolean, origin: String): JSONObject {
    requireApp(key)
    synchronized(lock) {
      requireReady()
      val nextHidden = if (hide) hidden + key else hidden - key
      val installed = AppCatalog.get(context).apps.value.map { it.key }.filterNot { it in nextHidden }
      val next = HomePages.reconcile(pages, installed)
      val result = JSONObject().put("key", key).put("hidden", hide)
      if (next != pages || nextHidden != hidden) commit(origin, if (hide) "hide" else "show", result, next, nextHidden = nextHidden)
      return result
    }
  }

  fun ungroup(folderId: String, origin: String): JSONObject {
    synchronized(lock) {
      requireReady()
      val change = HomePages.ungroup(pages, folderId)
      val result = JSONObject().put("folderId", folderId).put("effect", change.effect).put("page", change.page).put("index", change.index)
      commit(origin, "ungroup", result, change.pages)
      return result
    }
  }

  fun renameFolder(id: String, name: String, origin: String): JSONObject {
    synchronized(lock) {
      requireReady()
      val result = JSONObject().put("folderId", id).put("name", name.trim())
      commit(origin, "rename-folder", result, HomePages.rename(pages, id, name))
      return result
    }
  }

  /**
   * Shows the apps on pages (default) or all on one scrolling page under the widgets. The
   * arrangement is the same in both: the scrolling page lists the pages in order, and a move
   * there still fills pages so switching back keeps the order.
   */
  fun setMode(id: String, origin: String): JSONObject {
    val next = HomeMode.of(id) ?: throw FeedException("INVALID_ARGUMENT", "mode must be pages or scroll")
    synchronized(lock) {
      val result = JSONObject().put("mode", next.id).put("effect", if (next == mode) "unchanged" else "changed")
      if (next != mode) commit(origin, "set-mode", result, nextMode = next)
      return result
    }
  }

  /** Back to the first-launch layout: every app on page 0 in the default order. */
  fun reset(origin: String): JSONObject {
    val apps = AppCatalog.get(context).apps.value
    synchronized(lock) {
      requireReady()
      commit(origin, "reset", JSONObject(), HomePages.reconcile(listOf(pages.flatten().filterIsInstance<Slot.Widget>() + LauncherPresentation.grid(apps)), apps.map { it.key }), nextHidden = emptySet())
    }
    return JSONObject().put("reset", true).put("pages", pages.size)
  }

  fun toJson(): JSONObject = synchronized(lock) {
    JSONObject().put("revision", revision).put("ready", seeded).put("mode", mode.id).put("columns", HomeLayout.COLUMNS)
      .put("hidden", JSONArray(hidden.toList()))
      .put("firstPageCapacity", HomePages.FIRST_PAGE_CAPACITY).put("pageCapacity", HomePages.PAGE_CAPACITY)
      .put("widgetsSeeded", widgetsSeeded)
      .put("pages", JSONArray(pages.mapIndexed { p, page ->
        JSONObject().put("page", p).put("widgets", page.any { it is Slot.Widget })
          .put("items", JSONArray(page.mapIndexed { i, s -> slotJson(s).put("index", i).put("cellX", HomeGrid.place(page)[i].x).put("cellY", HomeGrid.place(page)[i].y) }))
      }))
  }

  fun needsDefaultWidgets(): Boolean = synchronized(lock) { seeded && !widgetsSeeded }

  fun seedWidgets(widgets: List<Slot.Widget>) = synchronized(lock) {
    if (!seeded || widgetsSeeded) return@synchronized
    widgetsSeeded = true
    try { commit("system", "seed-widgets", JSONObject(), HomePages.paginate(listOf(widgets + pages[0]) + pages.drop(1))) }
    catch (e: Exception) { widgetsSeeded = false; throw e }
  }

  fun addWidget(widget: Slot.Widget, page: Int, index: Int?, origin: String): JSONObject = synchronized(lock) {
    requireReady()
    if (page !in 0..pages.size) throw FeedException("INVALID_ARGUMENT", "page must be 0..${pages.size}")
    if (pages.flatten().filterIsInstance<Slot.Widget>().any { it.appWidgetId == widget.appWidgetId }) throw FeedException("ALREADY_EXISTS", "widget already placed")
    val work = pages.map { it.toMutableList() }.toMutableList()
    if (page == work.size) work.add(mutableListOf())
    val at = index ?: work[page].size
    if (at !in 0..work[page].size) throw FeedException("INVALID_ARGUMENT", "index out of range")
    work[page].add(at, widget)
    commit(origin, "add-widget", JSONObject().put("appWidgetId", widget.appWidgetId), HomePages.paginate(work))
    widgetJson(widget.appWidgetId)
  }

  fun resizeWidget(id: Int, x: Int, y: Int, origin: String): JSONObject = synchronized(lock) {
    val at = HomePages.locateWidget(pages, id) ?: throw FeedException("NOT_FOUND", "no widget $id")
    val old = pages[at.page][at.index] as Slot.Widget
    val next = old.copy(spanX = x, spanY = y)
    HomeGrid.validate(next)
    val work = pages.map { it.toMutableList() }
    work[at.page][at.index] = next
    commit(origin, "resize-widget", JSONObject().put("appWidgetId", id), HomePages.paginate(work))
    widgetJson(id)
  }

  fun removeWidget(id: Int, origin: String): JSONObject = synchronized(lock) {
    if (HomePages.locateWidget(pages, id) == null) throw FeedException("NOT_FOUND", "no widget $id")
    commit(origin, "remove-widget", JSONObject().put("appWidgetId", id), HomePages.compact(pages.map { p -> p.filterNot { it is Slot.Widget && it.appWidgetId == id } }))
    JSONObject().put("removed", id)
  }

  fun widgetJson(id: Int): JSONObject = synchronized(lock) {
    val at = HomePages.locateWidget(pages, id) ?: throw FeedException("NOT_FOUND", "no widget $id")
    slotJson(pages[at.page][at.index]).put("page", at.page).put("index", at.index)
  }

  // ---- internals -------------------------------------------------------------------

  private fun slotJson(s: Slot): JSONObject = when (s) {
    is Slot.Widget -> JSONObject().put("type", "widget").put("appWidgetId", s.appWidgetId).put("provider", s.provider).put("spanX", s.spanX).put("spanY", s.spanY)
    is Slot.App -> JSONObject().put("type", "app").put("key", s.key)
    is Slot.Folder -> JSONObject().put("type", "folder").put("id", s.id).put("name", s.name).put("apps", JSONArray(s.apps))
  }

  private fun requireReady() {
    if (!seeded) throw FeedException("UNAVAILABLE", "the app list is still loading; retry")
  }

  private fun requireApp(key: String) {
    if (AppCatalog.get(context).app(key) == null) throw FeedException("APP_NOT_FOUND", "$key is not an installed launchable app; see apps/list")
  }

  /** Folder name from the apps' shared Android category (Travel, Social…), else "Apps". */
  private fun folderName(keys: List<String>): String {
    val catalog = AppCatalog.get(context)
    val categories = keys.mapNotNull { catalog.app(it)?.category }.filter { it != ApplicationInfo.CATEGORY_UNDEFINED }.distinct()
    val title = categories.singleOrNull()?.let { ApplicationInfo.getCategoryTitle(context, it)?.toString() }
    return title ?: "Apps"
  }

  /**
   * Writes the next layout to disk first and only then adopts it, so a failed write
   * (`STATE_WRITE_FAILED`) leaves memory, disk and the GUI on the previous layout.
   */
  private fun commit(origin: String, op: String, detail: JSONObject, nextPages: List<List<Slot>> = pages, nextMode: HomeMode = mode, nextHidden: Set<String> = hidden) {
    persist(nextPages, nextMode, revision + 1, nextHidden)
    pages = nextPages; mode = nextMode; hidden = nextHidden; revision++
    Log.i(TAG, "$origin $op $detail")
    publish()
  }

  private fun publish() { _layout.value = synchronized(lock) { HomeLayout(pages, revision, seeded, mode, hidden) } }

  private fun persist(pages: List<List<Slot>>, mode: HomeMode, revision: Long, hidden: Set<String>) {
    val out = try { file.startWrite() } catch (e: Exception) { throw FeedException("STATE_WRITE_FAILED", e.message ?: "write failed") }
    try {
      val json = JSONObject().put("version", VERSION).put("revision", revision).put("mode", mode.id)
        .put("hidden", JSONArray(hidden.toList())).put("widgetsSeeded", widgetsSeeded)
        .put("pages", JSONArray(pages.map { page -> JSONArray(page.map(::slotJson)) }))
      out.write(json.toString().toByteArray(Charsets.UTF_8)); file.finishWrite(out)
    } catch (e: Exception) {
      file.failWrite(out); throw FeedException("STATE_WRITE_FAILED", e.message ?: "write failed")
    }
  }

  private fun parseSlot(c: JSONObject): Slot = when (c.optString("type")) {
    "widget" -> Slot.Widget(c.getInt("appWidgetId"), c.getString("provider"), c.getInt("spanX").coerceIn(1, 4), c.getInt("spanY").coerceIn(1, 6))
    "folder" -> c.getJSONArray("apps").let { a -> Slot.Folder(c.getString("id"), c.optString("name", "Apps"), (0 until a.length()).map { a.getString(it) }) }
    else -> Slot.App(c.getString("key"))
  }

  private fun restore() {
    try {
      if (!file.baseFile.exists()) return
      val o = JSONObject(String(file.readFully(), Charsets.UTF_8))
      revision = o.optLong("revision")
      widgetsSeeded = o.optBoolean("widgetsSeeded")
      if (o.optInt("version") >= 2) {
        hidden = o.optJSONArray("hidden")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }.orEmpty()
        val list = o.getJSONArray("pages")
        mode = HomeMode.of(o.optString("mode")) ?: HomeMode.PAGES
        // Pages saved before capacities existed may be longer: their overflow moves on.
        pages = HomePages.paginate((0 until list.length()).map { p ->
          val page = list.getJSONArray(p)
          (0 until page.length()).map { parseSlot(page.getJSONObject(it)) }
        })
        seeded = true
      } else {
        // Version 1: `cells` of the single extra page (index → item) and the Taku folder name.
        val cells = o.optJSONArray("cells") ?: JSONArray()
        val extra = (0 until cells.length()).map { cells.getJSONObject(it) }.sortedBy { it.optInt("index") }.map(::parseSlot)
        legacy = extra to o.optString("takuFolderName", "Taku")
      }
    } catch (e: Exception) {
      Log.e(TAG, "home layout unreadable; laying out from the app list", e)
    }
  }

  companion object {
    private const val TAG = "TakuHomeLayout"
    private const val VERSION = 4
    @Volatile private var instance: HomeLayoutRepository? = null
    fun get(context: Context): HomeLayoutRepository = instance ?: synchronized(this) {
      instance ?: HomeLayoutRepository(context.applicationContext).also { instance = it }
    }
  }
}
