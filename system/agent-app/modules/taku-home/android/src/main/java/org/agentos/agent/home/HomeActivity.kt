package org.agentos.agent.home

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.unit.IntSize
import androidx.core.content.ContextCompat
import com.facebook.react.modules.core.DefaultHardwareBackBtnHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.agentos.agent.home.apps.HomePanelAnchor
import org.agentos.agent.home.panels.PanelDirectory
import org.agentos.agent.home.panels.PanelNavigation
import org.agentos.agent.home.feed.FeedRepository
import org.agentos.agent.home.design.Backdrop
import org.agentos.agent.home.design.TakuTheme
import org.agentos.agent.home.ui.HomeController
import org.agentos.agent.home.ui.HomeScreen
import org.agentos.agent.home.ui.PAGE_HOME
import org.agentos.agent.home.ui.ReactCardPool
import org.agentos.agent.home.ui.HomeGesturePolicy

/**
 * The TakuOS home (HOME intent). Native Compose shell; feed card bodies are React Native
 * surfaces from the app's shared ReactHost. After unlock (or the Home button) home page 0
 * (widgets + apps) is shown; the feed is the page to its left, further home pages to its right.
 */
class HomeActivity : AppCompatActivity(), DefaultHardwareBackBtnHandler {
  private lateinit var pool: ReactCardPool
  private var homeSignal by mutableIntStateOf(0)
  /**
   * `OPEN_PANEL`: the Panel to select once the directory holds it, with a counter so asking
   * twice still scrolls. The registry validated the id before starting us; the desktop checks
   * its own projection and stops at `PANEL_NOT_FOUND` rather than inventing a page.
   */
  private var panelRequest by mutableStateOf<Pair<String, Int>?>(null)
  private var openAttempt = 0
  private var resetOnResume = false
  private val gestures = HomeGesturePolicy()
  private var gesturePointer = -1

  private val screenOff = object : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) { resetOnResume = true }
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    enableEdgeToEdge(
      statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
      navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
    )
    super.onCreate(savedInstanceState)
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) window.isNavigationBarContrastEnforced = false
    pool = ReactCardPool(this)
    ContextCompat.registerReceiver(this, screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED)
    val repo = FeedRepository.get(this)
    requestPanel(intent)
    if (savedInstanceState == null) requestAssistant(intent)
    HomeRole.claim(this)
    setContent {
      val scope = rememberCoroutineScope()
      val controller = remember { HomeController(applicationContext, repo, pool, scope) }
      DisposableEffect(controller) { onDispose { controller.dispose() } }
      val pager = rememberPagerState(initialPage = PAGE_HOME) { controller.pageCount }
      val feedList = rememberLazyListState()
      val homeScroll = rememberScrollState()
      val metrics = resources.displayMetrics
      val backdrop by produceState<Backdrop?>(null) {
        value = withContext(Dispatchers.Default) {
          runCatching { Backdrop.load(applicationContext, IntSize(metrics.widthPixels, metrics.heightPixels), 16f * metrics.density) }.getOrNull()
        }
      }
      LaunchedEffect(homeSignal) {
        if (homeSignal == 0) return@LaunchedEffect
        controller.closeMenu(); controller.editingPins = false; controller.assistantExpanded = false
        // Home: close the AI input; a running task stays in the camera pill.
        controller.assistant.closeEntry()
        if (controller.assistant.state.value.island != org.agentos.agent.home.assistant.IslandMode.Hidden) controller.assistant.dismiss()
        controller.drag.menu = null; controller.openFolderId = null
        controller.setHomeEditing(false)
        if (pager.currentPage != PAGE_HOME) pager.animateScrollToPage(PAGE_HOME) else homeScroll.animateScrollTo(0)
      }
      LaunchedEffect(panelRequest, controller.panelsLoaded, controller.directoryReads) {
        val request = panelRequest ?: return@LaunchedEffect
        if (!controller.panelsLoaded) return@LaunchedEffect
        controller.closeMenu(); controller.drag.menu = null; controller.openFolderId = null; controller.editingPins = false
        val page = controller.panelPage(request.first)
        if (page != null) {
          pager.animateScrollToPage(page)
          panelRequest = null
          return@LaunchedEffect
        }
        if (openAttempt++ > 0 || controller.panelsUpdateRequired) {
          Log.w(TAG, "PANEL_NOT_FOUND: ${request.first} is not in the current directory")
          panelRequest = null
          return@LaunchedEffect
        }
        // The id was valid when navigation was accepted; our projection may just be behind.
        controller.refreshPanels()
      }
      // Selection is an ID: a directory change re-anchors the pager on the same Panel, or on a
      // surviving neighbour when it is gone. Nothing is reported until the pager settles on it.
      val selection = remember { PanelPagerTracker() }
      LaunchedEffect(Unit) {
        snapshotFlow { pager.settledPage to controller.directoryRevision }
          .collect { (page, revision) ->
            if (selection.revision < 0) { selection.revision = revision; selection.order = controller.panelIds }
            if (revision != selection.revision) {
              val previous = selection.order
              val next = controller.panelIds
              selection.order = next
              selection.revision = revision
              var settled = true
              var selected: String? = null
              when (val target = HomePanelAnchor.afterDirectoryChange(selection.anchorPanelId, previous, next)) {
                HomePanelAnchor.Target.Stay ->
                  if (page >= controller.pageCount) { settled = false; pager.scrollToPage((controller.pageCount - 1).coerceAtLeast(0)) }
                HomePanelAnchor.Target.Home -> { settled = false; pager.scrollToPage(PAGE_HOME) }
                is HomePanelAnchor.Target.Panel -> {
                  val targetPage = controller.panelPage(target.panelId)
                  when {
                    targetPage == null -> { settled = false; pager.scrollToPage(PAGE_HOME) }
                    targetPage == page -> selected = target.panelId
                    else -> { settled = false; pager.scrollToPage(targetPage) }
                  }
                }
              }
              selection.settled = settled
              if (settled) {
                selection.page = page
                selection.anchorPanelId = selected ?: controller.panelAt(page)
                controller.selectPanel(selection.anchorPanelId)
              } else controller.selectPanel(null)
              return@collect
            }
            if (!selection.settled || page != selection.page) {
              selection.settled = true
              selection.page = page
              selection.anchorPanelId = controller.panelAt(page)
              controller.selectPanel(selection.anchorPanelId)
            }
          }
      }
      TakuTheme { HomeScreen(controller, backdrop, pager, feedList, homeScroll, gestures) }
    }
  }

  override fun dispatchTouchEvent(event: MotionEvent): Boolean {
    when (event.actionMasked) {
      MotionEvent.ACTION_DOWN -> {
        val view = window.decorView
        val insets = ViewCompat.getRootWindowInsets(view)?.getInsets(
          WindowInsetsCompat.Type.systemGestures() or WindowInsetsCompat.Type.mandatorySystemGestures(),
        ) ?: androidx.core.graphics.Insets.NONE
        gesturePointer = event.getPointerId(0)
        gestures.down(event.x, event.y, HomeGesturePolicy.Region(view.width, view.height,
          insets.left, insets.top, insets.right, insets.bottom), ViewConfiguration.get(this).scaledTouchSlop.toFloat())
      }
      MotionEvent.ACTION_MOVE -> {
        val index = event.findPointerIndex(gesturePointer)
        if (index >= 0) gestures.move(event.getX(index), event.getY(index))
      }
      MotionEvent.ACTION_UP -> gestures.end(false)
      MotionEvent.ACTION_CANCEL -> gestures.end(true)
      // Keep the original start/owner on pointer handoff; never reclassify an edge gesture.
      MotionEvent.ACTION_POINTER_UP -> if (event.getPointerId(event.actionIndex) == gesturePointer) gesturePointer = -1
    }
    return super.dispatchTouchEvent(event)
  }

  override fun onWindowFocusChanged(hasFocus: Boolean) {
    super.onWindowFocusChanged(hasFocus)
    if (!hasFocus) gestures.end(true)
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    val assistantEntry = requestAssistant(intent)
    if (!requestPanel(intent) && !assistantEntry && intent.hasCategory(Intent.CATEGORY_HOME)) homeSignal++
  }

  private fun requestAssistant(intent: Intent?): Boolean {
    if (intent == null) return false
    if (getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked) return false
    val key = org.agentos.agent.home.assistant.SystemAssistantEntry.EXTRA_OPEN
    val voice = intent.action == org.agentos.agent.home.assistant.SystemAssistantEntry.ACTION_VOICE_ASSIST || intent.action == Intent.ACTION_VOICE_COMMAND
    if (intent.getBooleanExtra(key, false) || intent.action == Intent.ACTION_ASSIST || voice) {
      resetOnResume = false
      intent.removeExtra(key)
      val session = org.agentos.agent.home.assistant.AssistantSession.get(this)
      if (voice) {
        session.beginDesktopHold()
        session.keepTalking()
      } else session.open(org.agentos.agent.home.assistant.EntryMode.Text)
      return true
    }
    return false
  }

  private fun requestPanel(intent: Intent?): Boolean {
    if (intent == null) return false
    val opens = intent.action == PanelNavigation.ACTION_OPEN_PANEL || intent.hasExtra(PanelNavigation.EXTRA_PANEL_ID)
    val id = intent.getStringExtra(PanelNavigation.EXTRA_PANEL_ID)?.trim()
    intent.removeExtra(PanelNavigation.EXTRA_PANEL_ID)
    if (intent.action == PanelNavigation.ACTION_OPEN_PANEL) intent.action = null
    if (!opens || id.isNullOrBlank()) return false
    openAttempt = 0
    panelRequest = id to ((panelRequest?.second ?: 0) + 1)
    return true
  }

  override fun onStart() {
    super.onStart()
    // STARTED, not RESUMED: the translucent chat pauses the desktop but still shows it, so the
    // mounted Panel pages keep their readers while both activities are visible.
    PanelNavigation.setForeground(true)
    org.agentos.agent.home.widgets.HomeWidgetHost.get(this).start()
    PanelDirectory.get(this).start()
  }
  override fun onStop() {
    val session = org.agentos.agent.home.assistant.AssistantSession.get(this)
    if (session.gesture.source == "desktop")
      session.onVoiceSurfaceStopped(session.gesture.id, isChangingConfigurations)
    PanelNavigation.setForeground(false)
    org.agentos.agent.home.widgets.HomeWidgetHost.get(this).stop()
    super.onStop()
  }

  override fun onResume() {
    super.onResume()
    pool.host?.onHostResume(this, this)
    // Invalidation is only a hint; a resume reads the revision again.
    PanelDirectory.get(this).refresh()
    if (resetOnResume) { resetOnResume = false; homeSignal++ }
  }

  override fun onPause() {
    pool.host?.onHostPause(this)
    super.onPause()
  }

  override fun onDestroy() {
    unregisterReceiver(screenOff)
    panelRequest = null
    PanelNavigation.publish(PanelNavigation.Snapshot())
    pool.destroy()
    pool.host?.onHostDestroy(this)
    super.onDestroy()
  }

  /** React Native asks us to perform the default back action: the home stays put. */
  override fun invokeDefaultOnBackPressed() {}

  /** Pager bookkeeping for the selected Panel; plain fields, touched only by the pager observer. */
  private class PanelPagerTracker {
    var anchorPanelId: String? = null
    var revision = -1
    var order: List<String> = emptyList()
    var page = -1
    var settled = true
  }

  private companion object { const val TAG = "TakuHomePanels" }
}
