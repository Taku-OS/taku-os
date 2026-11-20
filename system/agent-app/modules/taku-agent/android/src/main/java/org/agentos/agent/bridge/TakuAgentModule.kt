package org.agentos.agent.bridge

import android.app.Application
import android.content.Context
import android.content.Intent
import android.view.WindowInsets
import android.view.inputmethod.InputMethodManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.util.Log
import java.util.function.Consumer
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import expo.modules.kotlin.exception.CodedException
import expo.modules.kotlin.functions.Queues
import expo.modules.kotlin.functions.Coroutine
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.UUID

/** A Panel navigation failure carrying the native code, so the chat can show the real reason. */
private class PanelTargetException(code: String) : CodedException(code, code, null)

/** The Panel a conversation was opened for; navigation context only, never content or authority. */
internal data class PanelTarget(val panelId: String, val sourceVersion: Long, val originThreadId: String, val launchId: String) {
  fun toJson(): String = JSONObject()
    .put("panelId", panelId)
    .put("sourceVersion", sourceVersion)
    .put("originThreadId", originThreadId)
    .put("launchId", launchId)
    .toString()

  fun toEvent(): Map<String, Any> =
    mapOf("panelId" to panelId, "sourceVersion" to sourceVersion.toDouble(), "originThreadId" to originThreadId, "launchId" to launchId)
}

/** No execution or credentials live in this client; the system gateway owns all decisions. */
class TakuAgentModule : Module() {
  companion object {

    /**
     * Standard Android extras the exported conversation Activity accepts. A Panel target is
     * navigation context; `panels/continue` revalidates the thread and version at the actual send.
     */
    const val EXTRA_THREAD_ID = "threadId"
    const val EXTRA_PANEL_ID = "panelId"
    const val EXTRA_SOURCE_VERSION = "sourceVersion"
    const val EXTRA_ORIGIN_THREAD_ID = "originThreadId"
    internal const val GATEWAY_AUTHORITY = "org.agentos.assistant.agent-gateway"

    /** How long an awaited launch route may wait for `panels/get` before JS uses its default. */
    private const val ROUTE_TIMEOUT_MS = 3_000L
    private const val OPEN_ACCESS = "content://org.agentos.assistant.open-access"
    private const val CONVERSATION = "org.agentos.agent.MainActivity"
    private const val TAG = "TakuAgentPanels"
  }

  private val authority = GATEWAY_AUTHORITY
  /** Written from the intent callbacks and the route read; read by JS on its own thread. */
  @Volatile private var latestThreadId: String? = null
  private val mainHandler = Handler(Looper.getMainLooper())
  private var observingIme = false
  private var imeObserver: ImeInsetsObserver? = null
  /** Window backdrop of the full-screen chat (2-1); null while another screen shows. */
  private var backdrop: Backdrop? = null
  private var backdropApplication: android.app.Application? = null
  @Volatile private var chatActivity: android.app.Activity? = null
  private val backdropLifecycle = object : android.app.Application.ActivityLifecycleCallbacks {
    override fun onActivityResumed(activity: android.app.Activity) {
      if (activity.javaClass.name != CONVERSATION) return
      chatActivity = activity
      mainHandler.post {
        if (chatActivity === activity) { applyBackdrop(backdrop); attachImeObserver() }
      }
    }
    override fun onActivityDestroyed(activity: android.app.Activity) {
      if (backdrop?.activity === activity) releaseBackdrop(backdrop)
      if (chatActivity === activity) { chatActivity = null; detachImeObserver() }
    }
    override fun onActivityCreated(activity: android.app.Activity, state: android.os.Bundle?) {
      if (activity.javaClass.name == CONVERSATION) chatActivity = activity
    }
    override fun onActivityStarted(activity: android.app.Activity) = Unit
    override fun onActivityPaused(activity: android.app.Activity) = Unit
    override fun onActivityStopped(activity: android.app.Activity) = Unit
    override fun onActivitySaveInstanceState(activity: android.app.Activity, state: android.os.Bundle) = Unit
  }

  /** ReactHost is shared with Home; conversation routes/windows belong only to MainActivity. */
  private fun conversationActivity(): android.app.Activity? =
    conversationHost(chatActivity, appContext.currentActivity) {
      it.javaClass.name == CONVERSATION && !it.isDestroyed && !it.isFinishing
    }

  // Each lease is one surface's demand: a new subscription ID never stops another surface's reader.
  private val sessionLock = Any()
  private val sessionReaders = HashMap<String, SessionEventReader>()
  private val matterLock = Any()
  private val matterReaders = HashMap<String, MatterEventReader>()
  private val menuScanOwner = Any()
  private var menuScanObserver: Job? = null

  /** The Panel half of the conversation route: one fenced `panels/get` per route. */
  private val panelLaunch by lazy {
    val context = appContext.reactContext?.applicationContext ?: throw IOException("Agent UI is not ready")
    PanelLaunch(appContext.backgroundCoroutineScope,
      consumed = { PanelLaunchIntents.consumed(context, it) },
      persistConsumption = { PanelLaunchIntents.consume(context, it) },
    ) { path -> panelAnswer(path) }
  }
  /** Where the conversation activity is started, so hidden chat cards can drop their demand. */
  private var conversationVisibility: ConversationVisibility? = null
  private var conversationApplication: Application? = null

  private fun stopMatter(subscriptionId: String) {
    synchronized(matterLock) { matterReaders.remove(subscriptionId) }?.stop()
  }

  private fun stopSession(subscriptionId: String) {
    synchronized(sessionLock) { sessionReaders.remove(subscriptionId) }?.stop()
  }

  /**
   * Readers are released by their exact lease ID, so leaving one entry (chat to desktop, or the
   * reverse) never stops the other. Only the shared host going away drops all of them.
   */
  private fun stopAllReaders() {
    val sessions = synchronized(sessionLock) { sessionReaders.values.toList().also { sessionReaders.clear() } }
    val matters = synchronized(matterLock) { matterReaders.values.toList().also { matterReaders.clear() } }
    sessions.forEach { it.stop() }
    matters.forEach { it.stop() }
  }

  private fun detachImeObserver() {
    imeObserver?.let { (it.parent as? ViewGroup)?.removeView(it) }
    imeObserver = null
  }

  private fun attachImeObserver() {
    if (!observingIme || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
    val activity = conversationActivity() ?: return
    val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
    if (imeObserver?.parent === content) {
      imeObserver?.refresh()
      return
    }
    detachImeObserver()
    val observer = ImeInsetsObserver(activity, content) { geometry ->
      if (observingIme) sendEvent("onImeInsets", geometry)
    }
    imeObserver = observer
    // Our non-interactive observer owns its Insets callbacks. Never replace React's listeners.
    content.addView(observer, ViewGroup.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT))
  }

  private class Backdrop(val sigmaDp: Double, val tint: Int, val fallback: Int) {
    var listener: Consumer<Boolean>? = null
    /** Where [listener] is registered; unregistering must not depend on the current activity. */
    var windowManager: android.view.WindowManager? = null
    var activity: android.app.Activity? = null
  }

  private fun releaseBackdrop(previous: Backdrop?) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      previous?.listener?.let { listener -> previous.windowManager?.removeCrossWindowBlurEnabledListener(listener) }
      previous?.activity?.window?.setBackgroundBlurRadius(0)
    }
    previous?.activity?.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
    previous?.listener = null
    previous?.windowManager = null
    previous?.activity = null
  }

  /**
   * The chat window alone blurs whatever lies under it (Feed, Home or another app, all alike)
   * with the system cross-window blur, under [Backdrop.tint]. Where the device cannot blur (no
   * GPU blur, battery saver, Android 11) the window shows the opaque [Backdrop.fallback] instead.
   * Android 16's Kawase blur shows a grid at this radius; Android 17's renders it smoothly.
   */
  private fun applyBackdrop(next: Backdrop?) {
    // Release the previous listener first, even when the activity is already gone (destroy),
    // so the WindowManager never keeps a destroyed window alive.
    val previous = backdrop
    backdrop = null
    releaseBackdrop(previous)
    // JS can mount before ReactActivity resumes. Keep the request and attach on foreground;
    // never tint HomeActivity merely because it is still ReactHost's current activity.
    backdrop = next
    if (next == null) return
    val activity = conversationActivity() ?: return
    val window = activity.window
    next.activity = activity
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
      window.setBackgroundDrawable(ColorDrawable(next.fallback))
      return
    }
    // Figma blur R is a Gaussian of σ = R/2; SurfaceFlinger's blur radius r gives σ ≈ 0.57735 r.
    val sigmaPx = next.sigmaDp * activity.resources.displayMetrics.density
    val radius = (sigmaPx / 0.57735).toInt().coerceAtLeast(0)
    val listener = Consumer<Boolean> { enabled ->
      if (backdrop !== next) return@Consumer
      window.setBackgroundDrawable(ColorDrawable(if (enabled) next.tint else next.fallback))
      window.setBackgroundBlurRadius(if (enabled) radius else 0)
      android.util.Log.d("TakuBackdrop", "blur=$enabled radius=${if (enabled) radius else 0} window=${activity.javaClass.simpleName}")
    }
    next.listener = listener
    next.windowManager = activity.windowManager
    // Called right away with the current state, then on every change.
    activity.windowManager.addCrossWindowBlurEnabledListener(listener)
  }

  /**
   * Reads one Panel through the public Interface and accepts it as a continuation target only when
   * the native record says the original thread can be continued. No task is created here.
   */
  private fun resolveTarget(panelId: String, launchId: String): PanelTarget {
    val context = appContext.reactContext ?: throw PanelTargetException("AGENT_UI_UNAVAILABLE")
    val response = context.contentResolver.call(
      Uri.parse(OPEN_ACCESS), "panels/get", JSONObject().put("panelId", panelId).toString(), null,
    ) ?: throw PanelTargetException("PANEL_NOT_FOUND")
    val envelope = try {
      JSONObject(response.getString("result") ?: "")
    } catch (e: Exception) {
      throw PanelTargetException("PANEL_NOT_FOUND")
    }
    if (!envelope.optBoolean("ok")) throw PanelTargetException(envelope.optString("code").ifBlank { "PANEL_NOT_FOUND" })
    val detail = envelope.optJSONObject("result") ?: throw PanelTargetException("PANEL_NOT_FOUND")
    val panel = detail.optJSONObject("panel") ?: throw PanelTargetException("PANEL_NOT_FOUND")
    val canContinue = detail.optJSONObject("capabilities")?.optBoolean("canContinue") == true
    if (!canContinue) {
      val code = detail.optJSONObject("sourceState")?.optString("code")?.takeIf { it.isNotBlank() }
      throw PanelTargetException(code ?: "SOURCE_THREAD_NOT_FOUND")
    }
    val originThreadId = panel.optString("originThreadId")
    if (originThreadId.isBlank()) throw PanelTargetException("SOURCE_THREAD_NOT_FOUND")
    val sourceVersion = panel.optLong("sourceVersion", -1L)
    if (sourceVersion < 0) throw PanelTargetException("PANEL_NOT_FOUND")
    return PanelTarget(panel.optString("panelId", panelId), sourceVersion, originThreadId, launchId)
  }

  /** The route a conversation intent names, or null when it names no thread. */
  private fun routePath(intent: Intent?): LaunchRoute.Path? {
    val threadId = intent?.getStringExtra(EXTRA_THREAD_ID)?.takeIf { it.isNotBlank() } ?: return null
    return LaunchRoute.Path(
      threadId = threadId,
      panelId = intent.getStringExtra(EXTRA_PANEL_ID)?.takeIf { it.isNotBlank() },
      originThreadId = intent.getStringExtra(EXTRA_ORIGIN_THREAD_ID)?.takeIf { it.isNotBlank() },
      launchId = intent.getStringExtra(PanelLaunchIntents.EXTRA_LAUNCH_ID)?.takeIf { it.isNotBlank() },
    )
  }

  /** The conversation that is open now: the live intent first, else the route already remembered. */
  private fun openRoute(): LaunchRoute.Path? = routePath(conversationActivity()?.intent) ?: panelLaunch.path()

  /**
   * Reads one Panel through `panels/get` and accepts it as a continuation target only when the
   * native record says the original thread can be continued and it is this intent's thread. The
   * target stays navigation context; a refusal keeps its native code so JS can tell the user why
   * instead of silently sending an ordinary message.
   */
  private fun panelAnswer(path: LaunchRoute.Path): LaunchAnswer {
    val panelId = path.panelId ?: return LaunchAnswer.NoPanel
    val launchId = path.launchId ?: return LaunchAnswer.Rejected("PANEL_TARGET_INVALID")
    val target = try {
      resolveTarget(panelId, launchId)
    } catch (error: PanelTargetException) {
      Log.w(TAG, "panel target rejected: ${error.code}")
      return LaunchAnswer.Rejected(error.code)
    } catch (error: Exception) {
      Log.w(TAG, "panel target unavailable: ${error.message}")
      return LaunchAnswer.Rejected(PanelLaunch.SOURCE_UNAVAILABLE)
    }
    val belongsToThread = target.originThreadId == path.threadId &&
      (path.originThreadId == null || path.originThreadId == target.originThreadId)
    return if (belongsToThread) LaunchAnswer.Accepted(target) else LaunchAnswer.Rejected("SOURCE_THREAD_NOT_FOUND")
  }

  /**
   * Announces a route to JS: the thread, plus the Panel target it was opened for. The route is
   * checked again on the main thread, so an event queued just before a newer intent cannot pull
   * the chat back to the conversation the user already left.
   */
  private fun deliverRoute(pending: PanelLaunch.Resolution, answer: LaunchAnswer) {
    mainHandler.post {
      if (!panelLaunch.current(pending)) return@post
      val event = HashMap<String, Any>(2)
      event["threadId"] = pending.path.threadId
      event["generation"] = pending.generation.toString()
      (answer as? LaunchAnswer.Accepted)?.let { event["panelTarget"] = it.target.toEvent() }
      if (answer is LaunchAnswer.Rejected) event["errorCode"] = answer.code
      sendEvent("onOpenThread", event)
    }
  }

  private fun rememberLaunchIntent(intent: Intent?, announce: Boolean) {
    val path = routePath(intent) ?: return
    latestThreadId = path.threadId
    val pending = if (announce) panelLaunch.reopen(path) else panelLaunch.observe(path)
    if (!announce) return
    // Every request announces once: a repeat intent still moves the chat to that conversation,
    // and a route superseded before its answer lands announces nothing.
    appContext.backgroundCoroutineScope.launch {
      deliverRoute(pending, pending.answer.await())
    }
  }

  override fun definition() = ModuleDefinition {
    Name("TakuAgent")
    Events("onOpenConversation", "onOpenThread", "onDraft", "onImeInsets", "onSessionEventBatch", "onMatterEventBatch",
      "onConversationVisibility", "onMenuScanChanged", "onChatCameraLaunch")
    OnCreate {
      val application = appContext.reactContext?.applicationContext as? Application
        ?: appContext.currentActivity?.application
      // Expo can create this module after MainActivity has already resumed. Seed
      // ownership now, before ReactHost switches to Home and the callback is lost.
      chatActivity = conversationActivity()
      backdropApplication = application
      application?.registerActivityLifecycleCallbacks(backdropLifecycle)
      conversationApplication = application
      conversationVisibility = application?.let { host ->
        ConversationVisibility(
          hostsConversation = { it.javaClass.name == CONVERSATION },
          onChange = { visible -> mainHandler.post { sendEvent("onConversationVisibility", mapOf("visible" to visible)) } },
        ).also { it.attach(host, appContext.currentActivity) }
      }
    }
    OnStartObserving("onImeInsets") {
      mainHandler.post { observingIme = true; attachImeObserver() }
    }
    OnStopObserving("onImeInsets") {
      mainHandler.post { observingIme = false; detachImeObserver() }
    }
    OnStartObserving("onMenuScanChanged") {
      menuScanObserver = appContext.backgroundCoroutineScope.launch {
        MenuScanSession.changes.collect { state -> sendEvent("onMenuScanChanged", mapOf("state" to state)) }
      }
    }
    OnStopObserving("onMenuScanChanged") {
      menuScanObserver?.cancel()
      menuScanObserver = null
    }
    OnActivityEntersForeground {
      mainHandler.post {
        attachImeObserver()
        conversationVisibility?.seed(appContext.currentActivity)
        rememberLaunchIntent(conversationActivity()?.intent, announce = false)
      }
    }
    OnActivityDestroys { mainHandler.post {
      detachImeObserver()
      if (backdrop?.activity?.isDestroyed == true) releaseBackdrop(backdrop)
    } }
    OnDestroy {
      menuScanObserver?.cancel()
      MenuScanSession.finishOwner(menuScanOwner)
      conversationVisibility?.let { tracker -> conversationApplication?.let(tracker::detach) }
      conversationVisibility = null
      conversationApplication = null
      stopAllReaders()
      mainHandler.post {
        observingIme = false; detachImeObserver(); applyBackdrop(null)
        backdropApplication?.unregisterActivityLifecycleCallbacks(backdropLifecycle)
        backdropApplication = null; chatActivity = null
      }
    }

    Function("subscribeSession") { subscriptionId: String, params: String ->
      val resolver = appContext.reactContext?.contentResolver ?: throw IOException("Agent UI is not ready")
      val reader = SessionEventReader(resolver, authority, mainHandler, subscriptionId, params) { batch ->
        sendEvent("onSessionEventBatch", batch)
      }
      synchronized(sessionLock) {
        sessionReaders.remove(subscriptionId)?.stop()
        sessionReaders[subscriptionId] = reader
      }
      reader.start()
    }
    Function("unsubscribeSession") { subscriptionId: String ->
      stopSession(subscriptionId)
    }

    Function("subscribeMatter") { subscriptionId: String, params: String ->
      val resolver = appContext.reactContext?.contentResolver ?: throw IOException("Agent UI is not ready")
      val reader = MatterEventReader(resolver, authority, mainHandler, subscriptionId, params) { batch ->
        sendEvent("onMatterEventBatch", batch)
      }
      synchronized(matterLock) {
        matterReaders.remove(subscriptionId)?.stop()
        matterReaders[subscriptionId] = reader
      }
      reader.start()
    }
    Function("unsubscribeMatter") { subscriptionId: String ->
      stopMatter(subscriptionId)
    }

    OnNewIntent { intent ->
      if (intent.data == null) sendEvent("onOpenConversation", emptyMap<String, Any>())
      if (ConversationDrafts.hasPending()) sendEvent("onDraft", emptyMap<String, Any>())
      if (intent.getStringExtra(EXTRA_THREAD_ID)?.isNotBlank() == true) {
        // Retain the route when JS has not attached its listener or its context is rebuilt.
        rememberLaunchIntent(intent, announce = true)
      }
      if (conversationActivity()?.intent === intent && ChatCameraLaunch.isPending(intent)) {
        sendEvent("onChatCameraLaunch", emptyMap<String, Any>())
      }
    }

    Function("consumeChatCameraLaunch") { threadId: String ->
      ChatCameraLaunch.consume(conversationActivity()?.intent, threadId)
    }

    Function("consumeChatCameraDraft") { threadId: String ->
      ChatCameraLaunch.consumeDraft(conversationActivity()?.intent, threadId)
    }

    Function("beginMenuScan") { photoUri: String, scanJson: String ->
      val context = appContext.reactContext ?: throw IOException("Agent UI is not ready")
      val photo = MenuTextRecognition.cameraPhoto(appContext.cacheDirectory, photoUri)
      MenuScanSession.begin(menuScanOwner, context.applicationContext, photo, scanJson).toString()
    }

    Function("readMenuScan") { MenuScanSession.read().toString() }

    Function("menuScanAction") { action: String, scanId: String, dishId: String, quantity: Double? ->
      MenuScanSession.action(action, scanId, dishId, quantity).toString()
    }

    Function("finishMenuScan") { scanId: String -> MenuScanSession.finish(scanId) }

    AsyncFunction("recognizeMenuText") Coroutine { photoUri: String ->
      MenuTextRecognition.recognize(appContext.cacheDirectory, photoUri)
    }

    AsyncFunction("call") { action: String, params: String ->
      val context = appContext.reactContext ?: throw IOException("Agent UI is not ready")
      val response = context.contentResolver.call(
        Uri.parse("content://$authority"), action, params, null
      ) ?: throw IOException("Taku Agent gateway is unavailable")
      val envelope = response.getString("result") ?: throw IOException("Invalid gateway response")
      val json = JSONObject(envelope)
      val resultUri = json.optString("resultUri")
      if (resultUri.isEmpty()) envelope else {
        val uri = Uri.parse(resultUri)
        require(uri.scheme == "content" && uri.authority == authority && uri.path?.startsWith("/result/") == true) {
          "Invalid gateway result URI"
        }
        val stream = context.contentResolver.openInputStream(uri)
          ?: throw IOException("Gateway result expired")
        stream.use { input ->
          val output = ByteArrayOutputStream()
          val buffer = ByteArray(8192)
          while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (output.size() + count > 8 * 1024 * 1024) throw IOException("Gateway result exceeds client limit")
            output.write(buffer, 0, count)
          }
          output.toString("UTF-8")
        }
      }
    }.runOnQueue(appContext.backgroundCoroutineScope)

    Function("launchThreadId") {
      latestThreadId ?: conversationActivity()?.intent?.getStringExtra(EXTRA_THREAD_ID) ?: ""
    }

    Function("isLaunchCurrent") { generation: String ->
      generation.toLongOrNull()?.let { panelLaunch.current(it) } ?: false
    }

    Function("isPanelLaunchConsumed") { launchId: String ->
      val context = appContext.reactContext ?: throw IOException("Agent UI is not ready")
      PanelLaunchIntents.consumed(context, launchId)
    }

    AsyncFunction("consumePanelLaunch") { launchId: String ->
      require(launchId.isNotBlank()) { "Missing Panel launch identity" }
      panelLaunch.consume(launchId)
      mainHandler.post {
        conversationActivity()?.let { activity ->
          if (activity.intent?.getStringExtra(PanelLaunchIntents.EXTRA_LAUNCH_ID) == launchId) {
            activity.intent = Intent(activity.intent).also(PanelLaunchIntents::clearPanel)
          }
        }
      }
      Unit
    }

    Function("selectConversation") { threadId: String? ->
      panelLaunch.path()?.takeIf { it.panelId != null }?.launchId?.let(panelLaunch::consume)
      panelLaunch.clear()
      latestThreadId = threadId ?: ""
      if (threadId != null) panelLaunch.observe(LaunchRoute.Path(threadId, null, null))
      conversationActivity()?.let { activity ->
        val intent = Intent(activity.intent).apply {
          PanelLaunchIntents.clearPanel(this)
        }
        if (threadId == null) intent.removeExtra(EXTRA_THREAD_ID) else intent.putExtra(EXTRA_THREAD_ID, threadId)
        activity.intent = intent
      }
    }

    /**
     * The Panel this conversation was opened for, including its durable launch identity, or "".
     * It suspends until that route's Panel has been validated, so a cold
     * Panel launch is never mistaken for an ordinary conversation while `panels/get` runs. The
     * send itself goes through `panels/continue`.
     */
    AsyncFunction("launchPanelTarget") Coroutine { refresh: Boolean ->
      val path = openRoute() ?: return@Coroutine ""
      latestThreadId = path.threadId
      if (refresh) panelLaunch.reopen(path) else panelLaunch.observe(path)
      val answer = panelLaunch.answer(ROUTE_TIMEOUT_MS)
      // The answered route may be newer than the intent this call started with; the thread the
      // chat must restore is that one, not the superseded one.
      panelLaunch.path()?.let { latestThreadId = it.threadId }
      when (answer) {
        LaunchAnswer.NoPanel -> ""
        is LaunchAnswer.Accepted -> answer.target.toJson()
        is LaunchAnswer.Rejected -> throw PanelTargetException(answer.code)
        // The wait follows a superseded route, so an answer here is already the current one.
        LaunchAnswer.Superseded -> throw PanelTargetException(PanelLaunch.SOURCE_UNAVAILABLE)
      }
    }

    /** Whether the conversation activity is started; a stopped chat is behind the desktop. */
    Function("conversationVisible") { conversationVisibility?.visible ?: true }

    /**
     * Opens the Panel's original conversation with the standard Android extras, so the same route
     * works from the GUI and from `am start`. No task is sent and no content is transferred.
     */
    AsyncFunction("openPanelConversation") { panelId: String ->
      val context = appContext.reactContext ?: throw IOException("Agent UI is not ready")
      val target = resolveTarget(panelId, UUID.randomUUID().toString())
      val path = LaunchRoute.Path(target.originThreadId, target.panelId, target.originThreadId, target.launchId)
      panelLaunch.seed(path, LaunchAnswer.Accepted(target))
      latestThreadId = target.originThreadId
      context.startActivity(
        Intent().setClassName(context.packageName, CONVERSATION)
          .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
          .putExtra(EXTRA_THREAD_ID, target.originThreadId)
          .putExtra(EXTRA_PANEL_ID, target.panelId)
          .putExtra(EXTRA_SOURCE_VERSION, target.sourceVersion.toString())
          .putExtra(EXTRA_ORIGIN_THREAD_ID, target.originThreadId)
          .putExtra(PanelLaunchIntents.EXTRA_LAUNCH_ID, target.launchId),
      )
    }

    // Editable composer text from Home; never sent without the user pressing Send.
    Function("launchDraft") { ConversationDrafts.take() }

    Function("requestId") { UUID.randomUUID().toString() }

    View(EdgeBlurView::class) {
      Prop("topSharp") { view: EdgeBlurView, value: Float -> view.topSharp = value }
      Prop("topFull") { view: EdgeBlurView, value: Float -> view.topFull = value }
      Prop("bottomSharp") { view: EdgeBlurView, value: Float -> view.bottomSharp = value }
      Prop("bottomFull") { view: EdgeBlurView, value: Float -> view.bottomFull = value }
      Prop("sigma") { view: EdgeBlurView, value: Float -> view.sigma = value }
      Prop("fade") { view: EdgeBlurView, value: Boolean -> view.fade = value }
      Prop("uniformSigma") { view: EdgeBlurView, value: Float -> view.uniformSigma = value }
      OnViewDidUpdateProps { view: EdgeBlurView -> view.update() }
    }

    /** [enabled] false restores the plain translucent window. Colours are #AARRGGBB. */
    AsyncFunction("setWindowBackdrop") { enabled: Boolean, sigmaDp: Double, tint: String, fallback: String ->
      android.util.Log.d("TakuBackdrop", "enabled=$enabled host=${appContext.currentActivity?.javaClass?.simpleName} chat=${chatActivity?.javaClass?.simpleName}")
      applyBackdrop(if (enabled) Backdrop(sigmaDp, Color.parseColor(tint), Color.parseColor(fallback)) else null)
    }.runOnQueue(Queues.MAIN)

    // The composer asks for the keyboard explicitly once focused (WindowInsetsController on 11+).
    AsyncFunction("showKeyboard") {
      val activity = conversationActivity() ?: return@AsyncFunction Unit
      val focused = activity.currentFocus
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) activity.window.insetsController?.show(WindowInsets.Type.ime())
      else if (focused != null) (activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(focused, 0)
      Unit
    }.runOnQueue(Queues.MAIN)

    // Collapse (2-1 → 1-1): the home island takes over this thread, then Home comes forward.
    AsyncFunction("collapseToIsland") { threadId: String ->
      val activity = conversationActivity() ?: throw IOException("Agent UI is not ready")
      val response = activity.contentResolver.call(
        Uri.parse("content://org.agentos.agent.home-assistant"), "assistant/resume",
        JSONObject().put("threadId", threadId).put("home", false).toString(), null,
      ) ?: throw IOException("Taku Home is unavailable")
      val result = JSONObject(response.getString("result") ?: "{}")
      if (!result.optBoolean("ok")) throw IOException(result.optString("code", "HOME_UNAVAILABLE"))
      activity.startActivity(Intent().setClassName(activity.packageName, "org.agentos.agent.home.HomeActivity"))
      Unit
    }.runOnQueue(Queues.MAIN)

    AsyncFunction("close") {
      conversationActivity()?.let { activity ->
        if (activity.intent?.data != null) {
          activity.intent = Intent(activity.intent).apply {
            data = null
            action = Intent.ACTION_MAIN
          }
        }
        activity.moveTaskToBack(true)
      }
      Unit
    }.runOnQueue(Queues.MAIN)
  }
}
