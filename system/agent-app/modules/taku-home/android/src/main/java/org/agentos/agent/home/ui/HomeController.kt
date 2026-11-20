package org.agentos.agent.home.ui

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.layer.GraphicsLayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.agentos.agent.home.R
import org.agentos.agent.home.feed.FeedActions
import org.agentos.agent.home.feed.FeedException
import org.agentos.agent.home.feed.FeedOrigin
import org.agentos.agent.home.feed.FeedRepository
import org.agentos.agent.home.assistant.AgentGateway
import org.agentos.agent.home.assistant.AssistantSession
import org.agentos.agent.home.assistant.EntryMode
import org.agentos.agent.home.ui.assistant.HoldZone
import androidx.compose.ui.geometry.Offset
import org.agentos.agent.home.apps.HomeMode
import org.agentos.agent.home.apps.HomePages
import org.agentos.agent.home.apps.Slot
import org.agentos.agent.home.apps.HomePagerOrder
import org.agentos.agent.home.apps.HomeEditSession
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException

/** A card's long-press menu; [fromPin] when opened by tapping a pinned pill. */
data class MenuTarget(val cardId: String, val fromPin: Boolean)

internal data class HomeWorkspaceScreen(
  val threadId: String,
  val screenKey: String,
  val generation: String,
  val displayId: Int,
  val status: String,
  val previewUri: String,
)

@Stable
class DustRun(val dissolve: DustDissolve) {
  val progress = Animatable(0f)
}

/**
 * UI state of the home shell that is not feed data: menu, pin editing, assistant entry,
 * card layers (for the lifted copy and the dust snapshot) and running dissolves.
 * Feed mutations always go through [FeedRepository], shared with Agents.
 */
@Stable
class HomeController(val context: Context, val repo: FeedRepository, val pool: ReactCardPool, private val scope: CoroutineScope) {
  var menu by mutableStateOf<MenuTarget?>(null)
    private set
  var editingPins by mutableStateOf(false)
  var assistantExpanded: Boolean
    get() = assistant.state.value.hintExpanded
    set(value) { assistant.setHint(value) }
  var feedOnScreen by mutableStateOf(false)
  var screenHeight = 0f
  var screenWidth = 0f
  private val workspaceGateway = AgentGateway(context.contentResolver)
  private var workspaceOpen: Job? = null
  /** The visible workspace list shared by every home page. */
  internal var workspaceScreens by mutableStateOf<List<HomeWorkspaceScreen>>(emptyList())
    private set

  internal suspend fun watchWorkspaceScreens() {
    val params = JSONObject().put("scope", "home")
    var retryDelayMs = 0L
    try {
      while (true) {
        val screens = withContext(Dispatchers.IO) {
          try {
            val result = workspaceGateway.call("getWorkspaces", params)
            if (result.optString("schema") != "taku.agent-workspaces/1") return@withContext null
            val rows = result.getJSONArray("screens")
            List(rows.length()) { index ->
              val row = rows.getJSONObject(index)
              HomeWorkspaceScreen(
                threadId = row.getString("threadId"),
                screenKey = row.getString("screenKey"),
                generation = row.getString("generation"),
                displayId = row.getInt("displayId"),
                status = row.getString("status"),
                previewUri = row.getString("previewUri"),
              )
            }
          } catch (_: IOException) {
            null
          } catch (_: JSONException) {
            null
          }
        }
        workspaceScreens = screens.orEmpty()
        retryDelayMs = if (screens == null) (retryDelayMs + 2_500L).coerceAtMost(15_000L) else 0L
        delay(if (retryDelayMs == 0L) 1_000L else retryDelayMs)
      }
    } finally {
      workspaceScreens = emptyList()
    }
  }

  internal suspend fun readWorkspacePreview(previewUri: String): Bitmap? = withContext(Dispatchers.IO) {
    try {
      workspaceGateway.readWorkspacePreview(previewUri)
    } catch (_: IOException) {
      null
    } catch (_: SecurityException) {
      null
    }
  }

  internal fun openWorkspace(screen: HomeWorkspaceScreen) {
    if (workspaceOpen?.isActive == true) return
    workspaceOpen = scope.launch {
      try {
        withContext(Dispatchers.IO) {
          workspaceGateway.call("openWorkspace", JSONObject()
            .put("source", "home")
            .put("threadId", screen.threadId)
            .put("screenKey", screen.screenKey)
            .put("generation", screen.generation))
        }
      } catch (_: IOException) {
        notice(context.getString(R.string.home_workspace_open_failed))
      } catch (_: JSONException) {
        notice(context.getString(R.string.home_workspace_open_failed))
      }
    }
  }

  /** Launcher pages: drag-and-drop, icon menu and the open folder. */
  val drag = AppDragState()
  var openFolderId by mutableStateOf<String?>(null)
  /** The home pages as last published by [org.agentos.agent.home.apps.HomeLayoutRepository]. */
  var homeLayout by mutableStateOf(org.agentos.agent.home.apps.HomeLayout())
    private set
  /** The pages as shown: the home pages, or in scroll mode all of them as one page. */
  val displayPages: List<List<Slot>> get() =
    if (homeLayout.mode == HomeMode.SCROLL) listOf(HomePages.flatten(homeLayout.pages)) else homeLayout.pages

  /** The one native Panel directory, projected for the desktop; the cache itself is disposable. */
  private val directory = org.agentos.agent.home.panels.PanelDirectory.get(context)
  var panelEntries by mutableStateOf<List<org.agentos.agent.home.panels.PanelDirectory.Entry>>(emptyList())
    private set
  /** True once the directory answered at least once, so an open request can be judged. */
  var panelsLoaded by mutableStateOf(false)
    private set
  /** Bumped when a read completes, so an open request can retry or conclude. */
  var directoryReads by mutableIntStateOf(0)
    private set
  var panelsUpdateRequired by mutableStateOf(false)
    private set
  /** Bumped whenever the panel page sequence changes; the pager re-anchors on it. */
  var directoryRevision by mutableIntStateOf(0)
    private set
  val panelIds: List<String> get() = panelEntries.map { it.panelId }
  /** The panel ids the desktop may show; a foreign contract shows no panels at all. */
  val availablePanelIds: List<String> get() = if (panelsUpdateRequired) emptyList() else panelIds
  private val panelSlots: Int get() = if (panelsUpdateRequired) 1 else panelEntries.size
  /** The Panel the pager last settled on; null on the feed, a home page or the update notice. */
  var selectedPanelId by mutableStateOf<String?>(null)
    private set

  /**
   * Pager pages: the feed, all displayed home pages, then panels. While dragging, an empty
   * destination is inserted after the home pages and before panels.
   */
  private val pagerOrder get() = HomePagerOrder(displayPages.size, panelSlots, drag.item != null && homeLayout.mode == HomeMode.PAGES)
  val pageCount: Int get() = pagerOrder.pageCount
  /** The home page shown at pager [page], or null on the feed, the panel pages and the notice. */
  fun homePageAt(page: Int): Int? = pagerOrder.homeAt(page)
  /** The pager page of home page [homePage]. */
  fun pagerPage(homePage: Int): Int = pagerOrder.homePage(homePage)
  fun panelAt(page: Int): String? =
    if (panelsUpdateRequired) null else pagerOrder.panelAt(page)?.let(availablePanelIds::getOrNull)
  /** The page of [panelId], or null when the directory does not hold it now. */
  fun panelPage(panelId: String): Int? = availablePanelIds.indexOf(panelId).takeIf { it >= 0 }?.let(pagerOrder::panelPage)
  val updateRequiredPage: Int? get() = if (panelsUpdateRequired) pagerOrder.panelPage(0) else null
  /** The page's stable key: a Panel page is its `panelId`, so a moved page keeps its identity. */
  fun pageKey(page: Int): String = when {
    page == PAGE_FEED -> "feed"
    else -> homePageAt(page)?.let { "home:$it" }
      ?: panelAt(page)?.let { "panel:$it" }
      ?: if (page == updateRequiredPage) "panels:update" else "page:$page"
  }
  fun panelTitle(panelId: String): String = panelEntries.firstOrNull { it.panelId == panelId }?.title ?: ""
  /** The mounted Panel roots around pager [page]: the page itself and one page either side. */
  fun mountedPanelIds(page: Int): List<String> =
    listOfNotNull(pagerOrder.panelAt(page - 1), pagerOrder.panelAt(page), pagerOrder.panelAt(page + 1))
      .mapNotNull(availablePanelIds::getOrNull).distinct()

  fun selectPanel(panelId: String?) { selectedPanelId = panelId }
  fun refreshPanels() { directory.refresh() }
  var editingHome by mutableStateOf(HomeEditSession.state.value)
    private set
  var removingApp by mutableStateOf<String?>(null)
  var showingHiddenApps by mutableStateOf(false)
  var showingWidgetPicker by mutableStateOf(false)
  var resizingWidget by mutableStateOf<Slot.Widget?>(null)
  val widgetHost = org.agentos.agent.home.widgets.HomeWidgetHost.get(context)
  fun removeWidget(id: Int) { drag.menu = null; guard { widgetHost.remove(id) } }
  fun resizeWidget(id: Int, x: Int, y: Int) { guard { widgetHost.resize(id, x, y); resizingWidget = null } }
  fun addWidget(provider: String) { guard { widgetHost.add(provider, drag.page ?: 0, null, null, null); showingWidgetPicker = false } }
  fun cancelWidget(id: Int) { guard { widgetHost.cancel(id); showingWidgetPicker = false } }
  fun resumeWidget(id: Int) { guard { widgetHost.resume(id); showingWidgetPicker = false } }
  fun configureWidget(id: Int) { drag.menu = null; guard { widgetHost.configure(id) } }
  fun setHomeEditing(enabled: Boolean) {
    drag.menu = null; homeMenu = null
    if (!enabled) { removingApp = null; showingHiddenApps = false; openFolderId = null }
    HomeEditSession.set(enabled)
  }
  fun requestRemove(key: String) { drag.menu = null; removingApp = key }
  fun hideApp(key: String) { guard { layout.setHidden(key, true, ORIGIN) }; removingApp = null }
  fun restoreApp(key: String) { guard { layout.setHidden(key, false, ORIGIN) } }
  fun shareApp(key: String) { drag.menu = null; guard { catalog.share(key) } }
  /** Long-press on empty home space: the layout menu at this root position. */
  var homeMenu by mutableStateOf<Offset?>(null)
    private set

  /**
   * Something Back would close is open: a card, Home or icon menu, a folder, Home or pin editing,
   * a removal, hidden-apps or widget dialog. Otherwise the desktop is being browsed, and Home
   * takes the side edges from the back gesture (HomeActivity) so they swipe between pages.
   */
  val backTargetOpen: Boolean
    get() = menu != null || homeMenu != null || drag.menu != null || openFolderId != null || editingHome || editingPins ||
      removingApp != null || showingHiddenApps || showingWidgetPicker || resizingWidget != null
  private val catalog = org.agentos.agent.home.apps.AppCatalog.get(context)
  private val layout = org.agentos.agent.home.apps.HomeLayoutRepository.get(context)

  /**
   * Whether a removal should animate. Agent removals call it from a provider thread, but
   * [layers], [bounds] and [feedOnScreen] are main-thread state, so it reads them there.
   */
  private val animator: (String) -> Boolean = { id ->
    runCatching {
      org.agentos.agent.home.MainThread.call(ANIMATOR_TIMEOUT_MS, { java.util.concurrent.TimeoutException() }) {
        feedOnScreen && layers.containsKey(id) && bounds[id]?.let { it.bottom > 0 && it.top < screenHeight } == true
      }
    }.getOrDefault(false)
  }

  private val layers = HashMap<String, GraphicsLayer>()
  private val bounds = HashMap<String, Rect>()
  val dusts = mutableStateListOf<DustRun>()

  init {
    repo.animator = animator
    scope.launch { repo.leaves.collect { dissolve(it.cardId) } }
    // The pages follow installs and uninstalls.
    scope.launch { catalog.apps.collect { list -> layout.sync(list); widgetHost.seedDefaults() } }
    scope.launch { layout.layout.collect { homeLayout = it } }
    // The panel pages are the native directory, so insertion, reorder and removal reach the pager.
    scope.launch {
      directory.state.collect { next ->
        val ids = next.ids
        val required = next.updateRequired
        val moved = ids != panelIds || required != panelsUpdateRequired
        panelEntries = next.entries
        panelsUpdateRequired = required
        panelsLoaded = true
        directoryReads = next.read.toInt()
        if (moved) directoryRevision++
      }
    }
    scope.launch { HomeEditSession.state.collect {
      editingHome = it
      drag.menu = null; homeMenu = null
      if (!it) { removingApp = null; showingHiddenApps = false; openFolderId = null }
    } }
    drag.base = { page -> displayPages.getOrElse(page) { emptyList() }.filterNot { it.itemKey == drag.item?.slot?.itemKey } }
    drag.shown = ::pageItems
    drag.gapLimit = { page, base -> if (homeLayout.mode == HomeMode.PAGES) minOf(base.size, HomePages.capacity(page) - 1) else base.size }
  }

  /** Detaches from the process-wide [FeedRepository] so it does not keep this screen alive. */
  fun dispose() {
    if (repo.animator === animator) repo.animator = null
  }

  fun register(cardId: String, layer: GraphicsLayer) { layers[cardId] = layer }
  fun unregister(cardId: String, layer: GraphicsLayer) { if (layers[cardId] === layer) { layers.remove(cardId); bounds.remove(cardId) } }
  fun place(cardId: String, rect: Rect) { bounds[cardId] = rect }
  fun layer(cardId: String): GraphicsLayer? = layers[cardId]
  fun bounds(cardId: String): Rect? = bounds[cardId]

  fun openMenu(cardId: String, fromPin: Boolean = false) {
    editingPins = false
    assistantExpanded = false
    menu = MenuTarget(cardId, fromPin)
  }

  fun closeMenu() { menu = null }

  fun remove(cardId: String) {
    closeMenu()
    guard { repo.remove(cardId, FeedOrigin.GUI) }
  }

  fun pinToTop(cardId: String) {
    closeMenu()
    guard { repo.pin(cardId, FeedOrigin.GUI, 0) }
  }

  fun unpin(cardId: String) {
    closeMenu()
    guard { repo.unpin(cardId, FeedOrigin.GUI) }
  }

  fun askTaku(cardId: String) {
    closeMenu()
    scope.launch { guard { FeedActions.askAbout(context, cardId, FeedOrigin.GUI) } }
  }

  fun pinAction(cardId: String, actionId: String) {
    scope.launch { guard { FeedActions.dispatch(context, cardId, actionId, org.json.JSONObject(), FeedOrigin.GUI) } }
  }

  // ---- AI entry ----------------------------------------------------------------------

  /** Conversation with Taku; the same session Agents drive through `home-assistant`. */
  val assistant = AssistantSession.get(context)
  /** Arc under a held finger (1-1 / 1-1-2). */
  val holdZone get() = assistant.state.value.holdZone

  /** Double tap on the entry: the text field (1-2 → 1-3). */
  fun openTextInput() {
    assistantExpanded = false
    assistant.open(EntryMode.Text)
  }

  /** Long press on the entry: start listening (1-1). */
  fun startVoiceHold() {
    assistantExpanded = false
    assistant.beginDesktopHold()
  }

  /** Finger moved while holding ([position] in root px); returns true when the zone changed. */
  fun moveVoiceHold(position: androidx.compose.ui.geometry.Offset): Boolean {
    return assistant.moveHold(position.x, position.y, screenWidth, screenHeight)
  }

  /** Release: Cancel dissolves the transcript (1-1-2), Keep talking continues recording, elsewhere sends. */
  fun releaseVoiceHold() { assistant.releaseHold() }

  /**
   * The hold ended without the user lifting the finger (system gesture, pointer cancel, focus
   * loss): drop it like Cancel. Nothing is sent, and a late recogniser final is ignored too.
   */
  fun abortVoiceHold() {
    // A system-owned stream survives Home losing focus to the assistant overlay.
    if (assistant.gesture.source == "desktop" && assistant.gesture.held) assistant.cancelVoice()
  }



  // ---- Launcher pages ---------------------------------------------------------------

  fun openSlot(slot: org.agentos.agent.home.apps.Slot, bounds: Rect) {
    when (slot) {
      is org.agentos.agent.home.apps.Slot.App -> catalog.launch(slot.key, android.graphics.Rect(bounds.left.toInt(), bounds.top.toInt(), bounds.right.toInt(), bounds.bottom.toInt()))
      is org.agentos.agent.home.apps.Slot.Folder -> openFolderId = slot.id
      is Slot.Widget -> Unit
    }
  }

  /**
   * What displayed [page] shows: its items, or while dragging the items with the gap open at the
   * target (on pages, overflow past the capacity is already off to the next page).
   */
  fun pageItems(page: Int): List<Slot> {
    val dragged = drag.item?.slot ?: return displayPages.getOrElse(page) { emptyList() }
    val shown = AppDragState.preview(page, drag.base(page), dragged, drag.target)
    return if (homeLayout.mode == HomeMode.PAGES) shown.take(org.agentos.agent.home.apps.HomeGrid.place(shown).indexOfFirst { it.y + it.h > HomePages.capacity(page) / 4 }.let { if (it < 0) shown.size else it }) else shown
  }

  /** The lifted item after it left the page it was dragged from (kept composed there). */
  fun ghost(page: Int, shown: List<Slot>): Slot? {
    val dragged = drag.item?.slot ?: return null
    val from = displayPages.getOrElse(page) { emptyList() }
    return dragged.takeIf { d -> from.any { it.itemKey == d.itemKey } && shown.none { it.itemKey == d.itemKey } }
  }

  /**
   * Drop handling: the gap on a displayed page becomes a move before the item that follows it
   * (or to the end of that page; in scroll mode the end of the last page), a folder target a move
   * into that item. Dropped nowhere (a cancel) leaves it in place.
   */
  fun dropDragged(cancelled: Boolean) {
    val (item, target) = drag.end() ?: return
    openFolderId = null
    if (cancelled || target == null) return
    val pages = homeLayout.pages
    val key = (item.slot as? Slot.App)?.key
    val folderId = (item.slot as? Slot.Folder)?.id
    val widgetId = (item.slot as? Slot.Widget)?.appWidgetId
    guard {
      val intoKey = target.intoKey
      if (intoKey != null) {
        val at = HomePages.locate(pages, intoKey)?.takeIf { it.folderId == null } ?: HomePages.locateFolder(pages, intoKey) ?: return
        layout.move(key, folderId, at.page, at.index, key != null, ORIGIN)
        return
      }
      val base = displayPages.getOrElse(target.page) { emptyList() }.filterNot { it.itemKey == item.slot.itemKey }
      val follower = base.getOrNull(target.gap)
      val (page, index) = when {
        follower != null -> (HomePages.locate(pages, follower.itemKey)?.takeIf { it.folderId == null } ?: HomePages.locateFolder(pages, follower.itemKey) ?: (follower as? Slot.Widget)?.let { HomePages.locateWidget(pages, it.appWidgetId) }!!).let { it.page to it.index }
        homeLayout.mode == HomeMode.SCROLL -> pages.lastIndex to null
        else -> target.page to null
      }
      layout.move(key, folderId, page, index, false, ORIGIN, widgetId)
    }
  }

  fun openHomeMenu(at: Offset) { if (drag.item == null) homeMenu = at }
  fun closeHomeMenu() { homeMenu = null }
  fun setHomeMode(mode: HomeMode) { homeMenu = null; guard { layout.setMode(mode.id, ORIGIN) } }

  fun renameFolder(id: String, name: String) { guard { layout.renameFolder(id, name, ORIGIN) } }
  fun ungroup(id: String) { drag.menu = null; guard { layout.ungroup(id, ORIGIN) } }
  fun appInfo(key: String) { drag.menu = null; catalog.openAppInfo(key) }
  fun uninstall(key: String) { drag.menu = null; catalog.app(key)?.let { catalog.uninstall(it.packageName) } }

  /** The feed avatar: Taku AI settings (the assistant), else the system settings. */
  fun openProfile() {
    val ai = android.content.Intent("org.agentos.action.AI_SETTINGS")
    if (ai.resolveActivity(context.packageManager) != null) startSafely(ai, "Settings could not be opened")
    else startSafely(android.content.Intent(android.provider.Settings.ACTION_SETTINGS), "Settings could not be opened")
  }

  private fun startSafely(intent: android.content.Intent, failure: String) {
    try { context.startActivity(intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: RuntimeException) { Log.w(TAG, "no activity for $intent"); notice(failure) }
  }

  private fun notice(text: String) = android.widget.Toast.makeText(context, text, android.widget.Toast.LENGTH_SHORT).show()

  /** The widget block on page 0 (root coordinates): its long-press is not the layout menu (issue #32 H4). */


  private fun dissolve(cardId: String) {
    val layer = layers[cardId]
    val rect = bounds[cardId]
    if (layer == null || rect == null) { repo.commitLeave(cardId); return }
    scope.launch {
      try {
        val bitmap = layer.toImageBitmap().asAndroidBitmap()
        val run = DustRun(DustDissolve(bitmap, rect, context.resources.displayMetrics.density))
        dusts += run
        repo.commitLeave(cardId)
        pool.drop(cardId)
        run.progress.animateTo(1f, tween(DUST_MS, easing = LinearEasing))
        dusts -= run
      } catch (e: Exception) {
        Log.e(TAG, "dissolve failed for $cardId", e)
        repo.commitLeave(cardId)
      }
    }
  }

  private inline fun guard(block: () -> Unit) {
    try { block() } catch (e: FeedException) { Log.w(TAG, "${e.code}: ${e.message}") }
  }

  private companion object {
    const val ORIGIN = "gui"
    /** Main-thread wait for an Agent removal; on timeout the card is removed without dust. */
    const val ANIMATOR_TIMEOUT_MS = 500L
    const val TAG = "TakuHome"
    const val DUST_MS = 1100
  }
}
