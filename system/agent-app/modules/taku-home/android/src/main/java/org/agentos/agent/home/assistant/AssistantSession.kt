package org.agentos.agent.home.assistant

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.agentos.agent.home.menuscan.MenuVoiceHandoff
import org.agentos.agent.home.menuscan.MenuCapture
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.UUID

/** The bottom AI entry's open input: none, press-and-hold voice (1-1) or the text field (1-3). */
enum class EntryMode { None, Voice, Text }

/** Dynamic Island: hidden, expanded (≤ ⅓ screen, 1-1/1-4), full reply, or the camera pill (1-6). */
enum class IslandMode { Hidden, Expanded, Full, Collapsed }

data class AssistantState(
  val entry: EntryMode = EntryMode.None,
  val voiceContinuous: Boolean = false,
  val voicePaused: Boolean = false,
  val hintExpanded: Boolean = false,
  val voiceFailure: String? = null,
  val holdZone: VoiceGesture.Zone = VoiceGesture.Zone.None,
  val island: IslandMode = IslandMode.Hidden,
  /** Explicit user presentation request; survives sampled Expanded -> Expanded updates. */
  val expansionRequest: Long = 0,
  val threadId: String? = null,
  val activityId: String? = null,
  /** Text accepted for sending whose turn has not appeared yet. */
  val pending: String? = null,
  val sending: Boolean = false,
  val turn: TurnView? = null,
  /** Broker task state (`queued`, `running`, `needs_user`, `failed`, `cancelled`, `completed`). */
  val task: String? = null,
  val taskId: String? = null,
  /** Explicit app-development workflow, scoped to one authoritative active turn. */
  val appBuildTurnId: String? = null,
  val error: String? = null,
  /** Unsent text in the field (restored after a rejected send or a failed task). */
  val draft: String = "",
  val draftAttachmentNames: List<String> = emptyList(),
  /** The request that last failed (rejected send, failed task or turn), kept for edit / retry. */
  val failedText: String? = null,
  /** The device has a default network with internet capability. */
  val online: Boolean = true,
  /**
   * The home stopped waiting for the current request (offline past the grace period, issue #25
   * F3): the work was asked to stop and the text given back. A late broker/stream state for it
   * no longer makes the session "working" or replaces the error.
   */
  val abandoned: Boolean = false,
  /** A successful dictation too long to join the draft, kept whole for reading and copying. */
  val retainedVoice: RetainedVoiceResult? = null,
  /** A turn the full chat left running; its result opens the card once (BUG-0027). */
  val handoff: Handoff? = null,
  /** A handed-off result is waiting for SystemUI to draw its card (locked, shade, landscape). */
  val revealPending: Boolean = false,
) {
  val working: Boolean get() = !abandoned && (sending || pending != null || turn?.active == true || task == "queued" || task == "running")
  val buildingApp: Boolean get() = appBuildTurnId != null && turn?.turnId == appBuildTurnId &&
    turn.active && !turn.failed && !abandoned && error == null && !needsUser &&
    task !in setOf("completed", "failed", "cancelled")

  fun withAppBuild(thread: String, turnId: String, active: Boolean): AssistantState {
    if (thread.isBlank() || turnId.isBlank()) throw AssistantException("INVALID_ARGUMENT", "threadId and turnId are required")
    if (threadId != thread || turn?.turnId != turnId) throw AssistantException("STALE_TURN", "not the current conversation turn")
    if (active && (turn.active != true || turn.failed || abandoned || error != null || needsUser ||
        task in setOf("completed", "failed", "cancelled"))) throw AssistantException("NOT_WORKING", "turn is not running")
    return copy(appBuildTurnId = if (active) turnId else null)
  }
  val needsUser: Boolean get() = task == "needs_user"
  /**
   * A new voice hold asks something new, so a finished turn on the card is not its answer and must
   * not show again while it listens, nor after a cancelled or empty hold (BUG-0028). A running
   * turn, a question or an error still needs the user and stays.
   */
  val holdSupersedesTurn: Boolean get() = turn != null && !turn.active && !working && !needsUser
  /** Closing temporary input must not dismiss its pending result, reply or error. */
  val overlayVisible: Boolean get() = entry != EntryMode.None

  /** What "Try again" sends: the draft (restored or edited), else the failed request. */
  val retryText: String? get() = draft.ifBlank { failedText }?.takeIf { it.isNotBlank() }

  /**
   * Marks [text] as failed with [code]: it becomes the draft unless the user already typed a
   * new one, which is never overwritten; [failedText] keeps all of it either way.
   */
  fun failedWith(code: String, text: String?): AssistantState {
    val lost = text?.trim()?.takeIf { it.isNotEmpty() }
    return copy(sending = false, pending = null, error = code, failedText = lost ?: failedText,
      draft = if (draft.isBlank() && lost != null && lost.length <= AssistantSession.MAX_TEXT) lost else draft)
  }
}

/**
 * The home's conversation with Taku: one authority for the AI entry GUI and for Agents
 * (`content://org.agentos.agent.home-assistant`). Messages go through the system gateway's
 * `createThread` / `sendMessage` / `steer`; replies stream from `subscribeSession`. A home
 * session uses one thread until the island is dismissed while idle; "Open conversation"
 * continues it in the full chat.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AssistantSession private constructor(private val context: Context, private val gateway: AgentGateway) {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
  private val worker = Dispatchers.IO.limitedParallelism(1)
  private val flow = MutableStateFlow(AssistantState())
  val state: StateFlow<AssistantState> = flow.asStateFlow()

  /** Speech recognition: the shared Audio Engine, scoped to the desktop entry's saved settings. */
  var voice: VoiceInput = EngineVoiceInput(context, settingsScope = DESKTOP_SCOPE)
    set(value) { field.cancel(); field = value; watchVoice() }

  /** One recording's result may be delivered once; this machine keeps that ownership. */
  private val delivery = VoiceDeliveryMachine(MAX_TEXT)
  private var heldMenu: MenuCapture.Review? = null

  private val streamLock = Any()
  private var stream: SessionStreamState? = null
  private var baseline: Set<String> = emptySet()
  private var streamJob: Job? = null
  private var pollJob: Job? = null
  private var voiceJob: Job? = null
  private var liveLaunchJob: Job? = null
  private var handoffOperation: Long? = null
  private var handoffActivityLaunched = false
  private var handoffEditor: Editor? = null
  private val startedChats = mutableSetOf<android.app.Activity>()
  private val liveFlow = MutableStateFlow<LiveTalkSnapshot?>(null)
  internal val liveState = liveFlow.asStateFlow()
  private val liveDelivery = LiveTalkDelivery(context, gateway, worker)
  private val live = LiveTalk(context, scope, liveDelivery, { snapshot ->
    val paused = snapshot.open && snapshot.muted
    if (flow.value.voiceContinuous != snapshot.open || flow.value.voicePaused != paused)
      flow.update { it.copy(voiceContinuous = snapshot.open, voicePaused = paused) }
    liveFlow.value = snapshot
  }, { text -> handoffEditor?.let { recoverFailure(it, "VOICE_UNAVAILABLE", text) } })
  private var voiceStartSequence = 0L
  internal var voiceStartPending = false
    private set
  private val liveEndpoint = LiveTalkEndpoint()
  private val threadCreation = SessionThreadCreation()
  private var expansionSequence = 0L
  private val composer = HomeComposer(context, gateway, worker)
  private val composerLock = Mutex()
  private class Edit(val text: String, var baseText: String)
  private class Editor(var threadId: String) {
    var saved: HomeComposer.Draft? = null
    var edit: Edit? = null
    var menuSubmission: HomeComposer.MenuSubmission? = null
    var stagingMenu = false
    var writing = false
  }
  private var editor = Editor("")
  private var openingConversation = false

  private val gesturePrefs = context.getSharedPreferences("voice-gesture", 0)
  private val boot = android.provider.Settings.Global.getInt(context.contentResolver, "boot_count", -1)
  val gesture = VoiceGesture({ start ->
    gesturePrefs.edit().putInt("boot", boot).putLong("start", start).commit()
  }, if (gesturePrefs.getInt("boot", -2) == boot) gesturePrefs.getLong("start", -1) else -1)

  init {
    watchVoice(); watchNetwork(); watchHandoff()
    androidx.core.content.ContextCompat.registerReceiver(context, object : android.content.BroadcastReceiver() {
      override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_SCREEN_OFF) interruptVoiceForScreenOff()
      }
    }, android.content.IntentFilter(Intent.ACTION_SCREEN_OFF), androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
    // Activity start precedes the previous activity's stop. This preserves capture during
    // Home → chat, while leaving the visible chat still releases the microphone. React's
    // shared host onPause/AppState signal is too early for this cross-activity handoff.
    (context.applicationContext as? android.app.Application)?.registerActivityLifecycleCallbacks(object : android.app.Application.ActivityLifecycleCallbacks {
      override fun onActivityStarted(activity: android.app.Activity) {
        if (activity.javaClass.name == CHAT_ACTIVITY) startedChats.add(activity)
      }
      override fun onActivityStopped(activity: android.app.Activity) {
        if (activity.javaClass.name != CHAT_ACTIVITY) return
        startedChats.remove(activity)
        if (!activity.isChangingConfigurations && startedChats.isEmpty()) endLiveTalk()
      }
      override fun onActivityCreated(activity: android.app.Activity, savedInstanceState: android.os.Bundle?) = Unit
      override fun onActivityResumed(activity: android.app.Activity) {
        if (activity.javaClass.name == CHAT_ACTIVITY) {
          startedChats.add(activity)
          onChatVoiceFocus(ChatVoiceSession.get(context).visibleThread)
        }
        if (activity is AssistantOverlayActivity || activity.javaClass.name == "org.agentos.agent.home.HomeActivity") {
          if (flow.value.entry == EntryMode.Text) refreshDraft()
        }
      }
      override fun onActivityPaused(activity: android.app.Activity) = Unit
      override fun onActivitySaveInstanceState(activity: android.app.Activity, outState: android.os.Bundle) = Unit
      override fun onActivityDestroyed(activity: android.app.Activity) { startedChats.remove(activity) }
    })
    scope.launch {
      while (isActive) {
        delay(500)
        // Screen-off can precede Activity.onStop; also cover a missed broadcast or a
        // keyguard transition before attempting any automatic microphone restart.
        if (voiceDeviceUnavailable()) { interruptVoiceForScreenOff(); continue }
        if ((gesture.source == "system" && gesture.expired(android.os.SystemClock.uptimeMillis())) ||
            (gesture.held && android.os.SystemClock.uptimeMillis() - gesture.startedAt > 120_000)) cancelVoice()
        val s = flow.value
        val v = voice.state.value
        // Hold to talk records only while held: a pause never sends, and releasing finalizes and
        // sends (releaseHold). A release that heard nothing leaves nothing to show, so the voice
        // page closes instead of listening again.
        if (s.entry == EntryMode.Voice && gesture.phase == VoiceGesture.Phase.Listening &&
            v.emptySuccess && delivery.current?.state == VoiceDeliveryState.Consumed && !s.working && s.error == null) {
          closeEntry()
        }
        if (live.handingOff && v.ready && liveEndpoint.shouldFinish(android.os.SystemClock.uptimeMillis())) finishVoice()
      }
    }
  }

  /**
   * Applies [IslandHandoff] to every state change: a handed-off turn opens the card once with its
   * reply, question or failure. Any new Home input means the user moved on, so it cancels.
   */
  private fun watchHandoff() {
    scope.launch {
      flow.collect { s ->
        val movedOn = s.entry != EntryMode.None || live.snapshot?.open == true
        if (s.revealPending && movedOn) flow.update { it.copy(revealPending = false) }
        val handoff = s.handoff ?: return@collect
        if (movedOn) {
          flow.update { if (it.handoff == handoff) it.copy(handoff = null) else it }
          return@collect
        }
        // Status polling stops when the stream first shows an older, finished turn; a question
        // asked later in this turn only arrives through it.
        if (s.working && s.threadId == handoff.threadId) ensurePolling(handoff.threadId)
        val step = IslandHandoff.step(handoff, s.threadId, s.turn, s.task, s.taskId, s.error)
        when (step.outcome) {
          IslandHandoff.Outcome.WAIT -> flow.update { current ->
            if (current.handoff != handoff) current
            // An app build keeps its own capsule while it runs; nothing else is drawn.
            else current.copy(handoff = step.handoff,
              island = if (current.buildingApp && current.island == IslandMode.Hidden) IslandMode.Collapsed else current.island)
          }
          IslandHandoff.Outcome.DROP -> flow.update { if (it.handoff == handoff) it.copy(handoff = null) else it }
          IslandHandoff.Outcome.REVEAL -> flow.update { current ->
            if (current.handoff != handoff) current
            else current.copy(handoff = null, revealPending = true, island = IslandMode.Expanded,
              expansionRequest = ++expansionSequence)
          }
        }
      }
    }
  }

  /**
   * The full chat left [threadId] running (Home, Back or Recents): follow it with nothing on screen,
   * and open the card once when it has a result. See docs/dynamic-island-chat-handoff-spec.md.
   */
  fun handoff(threadId: String, turnId: String?, taskId: String?, knownTurnIds: Set<String>? = null): JSONObject {
    val s = flow.value
    val shown = org.agentos.agent.home.island.SystemIslandBridge.presentation(context)
    val admission = IslandHandoff.admit(IslandHandoff.Home(
      threadId = s.threadId, islandRequested = s.island != IslandMode.Hidden,
      cardExpanded = s.island != IslandMode.Hidden && shown == "expanded",
      working = s.working, needsUser = s.needsUser, entryOpen = s.entry != EntryMode.None,
      microphoneBusy = gesture.held || voice.state.value.listening || s.voiceContinuous,
      liveTalk = live.snapshot?.open == true, preparingSubmission = editor.stagingMenu), threadId)
    val result = JSONObject().put("handedOff", admission == IslandHandoff.Admission.HANDED_OFF).put("reason", admission.name)
    if (admission == IslandHandoff.Admission.ALREADY_SHOWN) return result
    follow(threadId)
    flow.update { it.copy(island = IslandMode.Hidden, entry = EntryMode.None, revealPending = false,
      activityId = UUID.randomUUID().toString(),
      handoff = Handoff(threadId, turnId?.ifBlank { null }, taskId?.ifBlank { null }, it.error, knownTurnIds)) }
    ensureStream(threadId)
    ensurePolling(threadId)
    refreshDraft()
    return result
  }

  /**
   * The full chat shows [threadId] again: nothing is revealed for it, and a card or capsule for the
   * same conversation goes away. The thread stays, so the Home entry still continues it.
   */
  fun reclaim(threadId: String): Boolean {
    if (!IslandHandoff.validThread(threadId)) throw AssistantException("INVALID_ARGUMENT", "threadId must be a conversation id")
    val s = flow.value
    if (s.threadId != threadId || (s.handoff == null && !s.revealPending && s.island == IslandMode.Hidden)) return false
    flow.update { it.copy(handoff = null, revealPending = false, island = IslandMode.Hidden) }
    return true
  }

  /** SystemUI drew the expanded card: a pending reveal is delivered. */
  fun revealShown() {
    if (flow.value.revealPending) flow.update { it.copy(revealPending = false) }
  }

  private fun voiceDeviceUnavailable(): Boolean = !org.agentos.agent.home.ScreenState.usable(context)

  private fun interruptVoiceForScreenOff() {
    ChatVoiceSession.interrupt()
    endLiveTalk()
    if (gesture.held || gesture.phase == VoiceGesture.Phase.Listening ||
        flow.value.voiceContinuous || voice.state.value.listening) cancelVoice()
  }

  /** A hidden voice page releases capture; rotation and the Continuous handoff retain it. */
  fun onVoiceSurfaceStopped(gestureId: String, changingConfigurations: Boolean) {
    if (!changingConfigurations && gesture.id == gestureId &&
        (gesture.held || gesture.phase == VoiceGesture.Phase.Listening ||
          gesture.phase == VoiceGesture.Phase.Continuous && !handoffActivityLaunched)) cancelVoice()
  }

  /** Automatic retries have no RPC caller to receive failures; never throw on the main loop. */
  private fun restartVoice() {
    try {
      startVoice(VoiceTarget.Hold, VoiceDestination.Submit)
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
      throw cancelled
    } catch (error: Exception) {
      if (error is AssistantException && error.code == "DEVICE_LOCKED") {
        cancelVoice()
      } else {
        val code = (error as? AssistantException)?.code ?: "VOICE_UNAVAILABLE"
        revokeVoice()
        flow.update { it.copy(error = voiceError(code), voiceFailure = code) }
      }
    }
  }

  private fun publishGesture() = flow.update { it.copy(holdZone = gesture.zone) }

  fun prepareHold(id: String, source: String, startedAt: Long): Boolean {
    if (id == gesture.id) return false
    if (live.snapshot?.open == true) throw AssistantException("VOICE_BUSY", "Live Talk is already active")
    if (voice.state.value.listening) throw AssistantException("VOICE_BUSY", "voice is already active")
    val changed = gesture.prepare(id, source, startedAt, android.os.SystemClock.uptimeMillis())
    if (changed) {
      heldMenu = if (source == "system") MenuCapture.currentReview() else null
      publishGesture()
    }
    return changed
  }

  internal fun hasMenuForHold(id: String?): Boolean =
    id != null && gesture.id == id && gesture.held && heldMenu != null

  /** UI handshake: a terminal event received during cold start prevents any later microphone open. */
  fun activateHold(id: String) {
    if (gesture.source == "system" && gesture.expired(android.os.SystemClock.uptimeMillis())) { cancelVoice(); return }
    if (!gesture.activate(id)) return
    publishGesture()
    flow.update { it.copy(expansionRequest = ++expansionSequence) }
    startVoice(VoiceTarget.Hold, VoiceDestination.AwaitGesture)
  }

  fun beginDesktopHold() {
    if (gesture.held || gesture.phase == VoiceGesture.Phase.Listening || gesture.phase == VoiceGesture.Phase.Continuous) return
    val now = android.os.SystemClock.uptimeMillis()
    if (prepareHold(UUID.randomUUID().toString(), "desktop", now)) activateHold(gesture.id)
  }

  fun moveHold(x: Float, y: Float, width: Float, height: Float): Boolean {
    if (!x.isFinite() || !y.isFinite() || !width.isFinite() || !height.isFinite() || width <= 0 || height <= 0)
      throw AssistantException("INVALID_ARGUMENT", "coordinates must be finite and dimensions positive")
    val zone = org.agentos.agent.home.ui.assistant.VoiceArcs(width, height, context.resources.displayMetrics.density)
      .zone(androidx.compose.ui.geometry.Offset(x, y), width * 16f / 412f)
    val changed = zone != gesture.zone
    gesture.move(zone); publishGesture()
    return changed
  }

  fun releaseHold() {
    if (!gesture.held) return // The latched page / Live Talk owns subsequent taps.
    if (gesture.phase == VoiceGesture.Phase.Pending) return cancelVoice()
    when (gesture.zone) {
      VoiceGesture.Zone.Cancel -> cancelVoice()
      VoiceGesture.Zone.KeepTalking -> keepTalking()
      // Release to send: recognition stops here and its complete transcript is sent once. A
      // backend that already finalized while the pointer was held sends that result.
      VoiceGesture.Zone.None -> finishVoice()
    }
  }

  fun keepTalking() {
    if (!gesture.keepTalking()) return
    val gestureId = gesture.id
    val currentEditor = editor
    handoffEditor = currentEditor
    handoffActivityLaunched = false
    live.begin(flow.value.threadId, handoff = true)
    publishGesture()
    handoffOperation = delivery.handoff()
    apply(delivery.settle(voice.state.value, flow.value.draft))
    if (handoffOperation == null && !voiceStartPending) live.finishHandoff(null)
    liveLaunchJob = scope.launch {
      try {
        val started = android.os.SystemClock.uptimeMillis()
        val id = flow.value.threadId ?: liveDelivery.createThread()
        delay((820 - (android.os.SystemClock.uptimeMillis() - started)).coerceAtLeast(0))
        if (live.snapshot?.open != true || gesture.id != gestureId) return@launch
        live.bindHandoff(id)
        flow.update { it.copy(entry = EntryMode.None, island = IslandMode.Hidden) }
        handoffActivityLaunched = true
        context.startActivity(Intent().setClassName(context.packageName, CHAT_ACTIVITY)
          .putExtra("threadId", id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        connectLiveTalk()
        delay(10_000)
        if (live.snapshot?.generation == 0L && !chatVisible(id)) {
          live.failHandoff("HOST_REQUIRED")
          revokeVoice()
        }
      } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
      } catch (_: Exception) {
        if (live.snapshot?.open == true && gesture.id == gestureId) {
          live.failHandoff("THREAD_UNAVAILABLE")
          revokeVoice()
          flow.update { it.copy(error = "THREAD_UNAVAILABLE") }
        }
      }
    }
  }

  fun startLiveTalk(threadId: String) {
    val id = threadId.trim().ifEmpty { null }
    requireLiveHost(id)
    val snapshot = live.snapshot
    if (snapshot?.open == true && snapshot.threadId == id && snapshot.lifecycle in setOf("connecting", "active")) return
    if (snapshot?.open == true && snapshot.threadId != id)
      throw AssistantException("SESSION_CHANGED", "Live Talk belongs to another conversation")
    revokeVoice(cancelCapture = false)
    if (voice !is EngineVoiceInput) voice.cancel()
    flow.update { it.copy(entry = EntryMode.None, island = IslandMode.Hidden) }
    live.begin(id)
    connectLiveTalk()
  }

  fun stopLiveTalk(threadId: String? = null, sessionId: String? = null) {
    requireLiveSession(threadId, sessionId)
    endLiveTalk()
  }

  fun toggleLiveMic(threadId: String? = null) {
    val current = requireLiveSession(threadId, null)?.takeIf { it.open } ?: return
    setLiveMic(current.sessionId, !current.muted)
  }

  fun setLiveMic(sessionId: String, muted: Boolean) {
    val current = requireLiveSession(null, sessionId) ?: return
    if (!current.open || current.muted == muted) return
    if (!muted) requireLiveHost(current.threadId)
    live.setMuted(muted)
    if (muted && live.handingOff) {
      cancelPendingVoiceStart()
      delivery.revoke()
      voice.cancel()
      handoffOperation = null
      live.finishHandoff(null)
      connectLiveTalk()
    }
  }

  private fun requireLiveSession(threadId: String?, sessionId: String?): LiveTalkSnapshot? {
    val current = live.snapshot
    if (sessionId != null && sessionId.isBlank()) throw AssistantException("INVALID_ARGUMENT", "sessionId must be nonempty")
    if ((sessionId != null && current?.sessionId != sessionId) ||
        (threadId != null && (current == null || current.threadId.orEmpty() != threadId)))
      throw AssistantException("SESSION_CHANGED", "Live Talk has changed")
    return current
  }

  private fun chatVisible(threadId: String?) = startedChats.isNotEmpty() &&
    ChatVoiceSession.get(context).visibleThread == threadId.orEmpty()

  private fun requireLiveHost(threadId: String?) {
    if (voiceDeviceUnavailable()) throw AssistantException("DEVICE_LOCKED", "Wake and unlock the device before opening Live Talk")
    if (!chatVisible(threadId)) throw AssistantException("HOST_REQUIRED", "Open the target chat first")
    if (!EngineVoiceInput.available(context)) throw AssistantException("VOICE_UNAVAILABLE", "The Taku Audio Engine is not installed")
  }

  internal fun onChatVoiceFocus(threadId: String?, previousThreadId: String? = null) {
    val current = live.snapshot?.takeIf { it.open } ?: return
    if (handoffEditor != null && current.generation == 0L && current.threadId.orEmpty() != threadId) return
    if (threadId == null) {
      if (previousThreadId == null) return
      if (previousThreadId.isNotEmpty() || current.threadId == null) { endLiveTalk(); return }
      // React releases the empty chat owner immediately before adopting the new thread.
      scope.launch {
        delay(150)
        val latest = live.snapshot
        if (latest?.sessionId == current.sessionId && !chatVisible(latest.threadId)) endLiveTalk()
      }
    } else if (threadId != current.threadId.orEmpty()) {
      endLiveTalk()
    } else {
      connectLiveTalk()
    }
  }

  private fun connectLiveTalk() {
    val current = live.snapshot ?: return
    if (handoffEditor != null && !handoffActivityLaunched) return
    if (live.handingOff || current.lifecycle != "connecting" || !chatVisible(current.threadId)) return
    val chat = ChatVoiceSession.get(context)
    chat.releaseForLive { released ->
      if (live.snapshot?.sessionId != current.sessionId || live.snapshot?.lifecycle != "connecting" ||
          voiceDeviceUnavailable() || !chatVisible(current.threadId)) return@releaseForLive
      if (released) live.connect(current.sessionId) else live.failHandoff("RESOURCE_BUSY")
    }
  }

  private fun endLiveTalk() {
    if (live.snapshot?.open != true) return
    liveLaunchJob?.cancel(); liveLaunchJob = null
    live.close()
    handoffOperation = null
    handoffActivityLaunched = false
    handoffEditor = null
    revokeVoice()
    flow.update { it.copy(entry = EntryMode.None, island = IslandMode.Hidden) }
  }

  fun gestureEvent(p: JSONObject) {
    val action = p.getString("action")
    if (action !in listOf("prepare", "move", "heartbeat", "end", "cancel"))
      throw AssistantException("INVALID_ARGUMENT", "unknown gesture action")
    val id = p.getString("id")
    if (action == "prepare") { prepareHold(id, p.optString("source", "system"), p.getLong("time")); return }
    if (!gesture.event(id, p.getLong("sequence"), p.getLong("time"), android.os.SystemClock.uptimeMillis())) return
    when (action) {
      "move" -> moveHold(p.getDouble("x").toFloat(), p.getDouble("y").toFloat(), p.getDouble("width").toFloat(), p.getDouble("height").toFloat())
      "end" -> releaseHold()
      "cancel" -> cancelVoice()
    }
  }

  private var hintJob: Job? = null
  fun setHint(expanded: Boolean) {
    if (expanded && state.value.entry != EntryMode.None) throw AssistantException("BUSY", "an input is already open")
    hintJob?.cancel()
    flow.update { it.copy(hintExpanded = expanded) }
    if (expanded) hintJob = scope.launch { delay(4_000); flow.update { it.copy(hintExpanded = false) } }
  }

  // ---- Entry and island ---------------------------------------------------------------

  /** Opening another text entry must not discard a recording or its pending final result. */
  val canOpenText: Boolean get() = !voice.state.value.listening && !gesture.held

  /** Opens an input (double tap: text, hold: voice); the island shows the greeting or the current turn. */
  fun open(mode: EntryMode) {
    if (mode == EntryMode.None) return closeEntry()
    if (mode == EntryMode.Voice) { beginDesktopHold(); return }
    if (mode == EntryMode.Text && !canOpenText) return
    if (mode == EntryMode.Text && flow.value.entry != EntryMode.Text) revokeVoice()
    flow.update { it.copy(entry = mode, activityId = it.activityId ?: UUID.randomUUID().toString(), hintExpanded = false, voiceFailure = null, island = if (it.island == IslandMode.Hidden || it.island == IslandMode.Collapsed) IslandMode.Expanded else it.island, error = null) }
    refreshDraft()
  }

  /**
   * Closes the input; an undelivered result loses eligibility. The island stays while there is
   * something to show, including a handed-off result still waiting to be drawn.
   */
  fun closeEntry() {
    endLiveTalk()
    revokeVoice()
    flow.update { s ->
      val keep = s.working || s.turn != null || s.error != null || s.revealPending
      s.copy(entry = EntryMode.None, island = if (keep) s.island else IslandMode.Hidden)
    }
  }

  fun setAppBuild(thread: String, turnId: String, active: Boolean) { flow.update { it.withAppBuild(thread, turnId, active) } }

  fun setDraft(text: String) {
    val current = editor
    current.edit = Edit(text.take(MAX_TEXT), current.edit?.baseText ?: current.saved?.text ?: flow.value.draft)
    showDraft(current)
    saveDraft(current)
  }

  suspend fun commitDraft(text: String) {
    if (text.length > MAX_TEXT) throw AssistantException("INVALID_TEXT", "text must be at most $MAX_TEXT characters")
    val current = editor
    composerLock.withLock {
      if (editor !== current) throw AssistantException("THREAD_CHANGED", "The Home conversation changed")
      current.edit?.let { writeDraft(current, it) }
      val saved = composer.read(current.threadId)
      if (editor !== current) throw AssistantException("THREAD_CHANGED", "The Home conversation changed")
      val written = composer.write(saved, text)
      if (current.edit?.baseText == saved.text) current.edit?.baseText = written.text
      acceptDraft(current, written)
    }
  }

  suspend fun readDraftProjection() {
    val current = editor
    composerLock.withLock {
      if (editor === current) acceptDraft(current, composer.read(current.threadId))
    }
  }

  private fun showDraft(current: Editor) {
    if (editor === current) flow.update {
      it.copy(draft = current.edit?.text ?: current.saved?.text.orEmpty(),
        draftAttachmentNames = current.saved?.attachments?.map(HomeComposer.Attachment::name).orEmpty())
    }
  }

  private fun acceptDraft(current: Editor, saved: HomeComposer.Draft, written: Edit? = null) {
    current.saved = saved
    if (written != null && current.edit === written) current.edit = null
    else if (written != null) current.edit?.baseText = saved.text
    showDraft(current)
  }

  private suspend fun writeDraft(current: Editor, edit: Edit): HomeComposer.Draft {
    var saved = current.saved ?: composer.read(current.threadId).also { acceptDraft(current, it) }
    if (edit.text != saved.text) {
      if (edit.baseText != saved.text) throw draftConflict(current, edit, saved)
      saved = try {
        composer.write(saved, edit.text)
      } catch (failure: GatewayException) {
        if (failure.code != "DRAFT_REVISION_CONFLICT") throw failure
        val fresh = composer.read(current.threadId).also { acceptDraft(current, it) }
        when (fresh.text) {
          edit.text -> fresh
          edit.baseText -> composer.write(fresh, edit.text)
          else -> throw draftConflict(current, edit, fresh)
        }
      }
    }
    acceptDraft(current, saved, edit)
    return saved
  }

  /**
   * The shared draft changed under an unsaved edit. The conflict is reported once and the edit
   * is rebased onto the draft that is saved now: the user's text stays in the field, and if they
   * keep it, their next keystroke or send replaces the other draft. Without the rebase every
   * later save and send would fail on the same stale base.
   */
  private fun draftConflict(current: Editor, edit: Edit, saved: HomeComposer.Draft): GatewayException {
    edit.baseText = saved.text
    current.edit?.baseText = saved.text
    return GatewayException("DRAFT_REVISION_CONFLICT", "The shared draft changed")
  }

  private fun saveDraft(current: Editor) {
    if (current.writing) return
    current.writing = true
    scope.launch {
      try {
        while (current.edit != null) composerLock.withLock { current.edit?.let { writeDraft(current, it) } }
      } catch (failure: Exception) {
        if (failure is kotlinx.coroutines.CancellationException) throw failure
        if (editor === current) flow.update { it.copy(error = composerError(failure)) }
      } finally {
        current.writing = false
      }
    }
  }

  private fun refreshDraft() = scope.launch {
    val current = editor
    try {
      composerLock.withLock {
        if (current !== editor) return@withLock
        val saved = composer.read(current.threadId)
        acceptDraft(current, saved)
        if (!flow.value.sending) pendingRequest(saved)?.let { reconcile(current, it) }
      }
    } catch (failure: Exception) {
      if (failure is kotlinx.coroutines.CancellationException) throw failure
      if (current === editor) flow.update { it.copy(error = composerError(failure)) }
    }
  }

  private fun pendingRequest(saved: HomeComposer.Draft) = composer.savedRequest(saved.threadId) ?: saved.pending

  private fun composerError(failure: Exception) = when (failure) {
    is GatewayException -> failure.code
    is AssistantException -> failure.code
    else -> "DRAFT_SAVE_FAILED"
  }

  /** Starter chips create an editable draft; they never submit a request. */
  fun choosePrompt(text: String) {
    if (text.isBlank() || text.length > MAX_TEXT) throw AssistantException("INVALID_ARGUMENT", "prompt must contain 1..$MAX_TEXT characters")
    cancelVoice()
    val current = editor
    val handoffId = UUID.randomUUID().toString()
    scope.launch {
      try {
        composerLock.withLock {
          if (editor !== current) return@withLock
          current.edit?.let { writeDraft(current, it) }
          val saved = composer.handoff(current.threadId, handoffId, text)
          current.edit?.baseText = saved.text
          acceptDraft(current, saved)
          if (editor !== current) return@withLock
          open(EntryMode.Text)
          context.startActivity(Intent(context, AssistantOverlayActivity::class.java)
            .putExtra("mode", "text").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
      } catch (failure: Exception) {
        if (failure is kotlinx.coroutines.CancellationException) throw failure
        if (editor === current) flow.update { it.copy(error = composerError(failure)) }
      }
    }
  }

  fun expand(full: Boolean) {
    flow.update { if (it.island == IslandMode.Hidden && it.turn == null && !it.working) it else it.copy(island = if (full) IslandMode.Full else IslandMode.Expanded, expansionRequest = ++expansionSequence) }
  }

  fun collapseIsland() {
    flow.update { if (it.island == IslandMode.Hidden) it else it.copy(island = IslandMode.Collapsed) }
    org.agentos.agent.home.island.SystemIslandBridge.request(context, "collapse")
  }

  /**
   * The user followed the "fal API key missing" prompt to the fal setup page: the prompt has done
   * its job, so it leaves the island. Anything else on show (a reply, work in progress) stays. Once
   * a key is saved, the next voice input simply works.
   */
  fun clearVoiceSetupError() {
    if (flow.value.error != VOICE_FAL_KEY_MISSING) return
    revokeVoice()
    flow.update { s ->
      if (s.error != VOICE_FAL_KEY_MISSING) s
      else s.copy(error = null, voiceFailure = null, entry = EntryMode.None,
        island = if (s.working || s.turn != null) s.island else IslandMode.Hidden)
    }
  }

  /** Explicit assistant dismissal; outside card taps are owned by SystemUI and never call this. */
  fun dismiss() {
    endLiveTalk()
    org.agentos.agent.home.island.SystemIslandBridge.request(context, "collapse")
    val s = flow.value
    if (s.working || s.needsUser) { revokeVoice(); flow.update { it.copy(island = IslandMode.Collapsed, entry = EntryMode.None, handoff = null, revealPending = false) }; return }
    reset()
  }

  /** Ends this home conversation; the thread stays in the full chat history. */
  fun reset() = reset(Editor(""))

  private fun reset(next: Editor) {
    endLiveTalk()
    liveLaunchJob?.cancel(); liveLaunchJob = null
    revokeVoice()
    replaceEditor(next)
    flow.value = AssistantState(online = flow.value.online)
  }

  private fun replaceEditor(next: Editor) {
    threadCreation.invalidate()
    editor = next
    openingConversation = false
    val jobs = listOfNotNull(streamJob, pollJob)
    streamJob = null; pollJob = null
    scope.launch { jobs.forEach { it.cancelAndJoin() } }
    synchronized(streamLock) {
      stream = next.threadId.takeIf(String::isNotEmpty)?.let(::SessionStreamState)
      baseline = emptySet()
    }
  }

  // ---- Voice ---------------------------------------------------------------------------

  /**
   * Releasing the hold (or an Agent's `assistant/voice-finish`, its release) or, in Keep Talking,
   * a measured utterance boundary: finalize and send exactly once. Released before capture
   * started, nothing was heard and the operation is dropped.
   */
  fun finishVoice() {
    // Released while the start still waits for a reply reader to stop: latch now, as a release
    // after the start would, so the deferred start aims at Submit (finalize on silence, send once)
    // instead of waiting for a release that already happened.
    if (voiceStartPending && voice.state.value.phase == VoicePhase.Idle && flow.value.entry == EntryMode.Voice) {
      if (!live.handingOff && gesture.latch()) publishGesture()
      return
    }
    val v = voice.state.value
    // No recording: a recycled or repeated finish must not close an unrelated open input.
    if (v.target != VoiceTarget.Hold || v.phase == VoicePhase.Idle || (flow.value.entry != EntryMode.Voice && !flow.value.voiceContinuous)) return
    // Released before capture: drop the operation instead of finishing nothing.
    if (v.phase == VoicePhase.Preparing) return cancelVoice()
    if (!live.handingOff) { gesture.latch(); publishGesture(); delivery.aim(VoiceDestination.Submit) }
    voice.finish()
    apply(delivery.settle(voice.state.value, flow.value.draft))
  }

  /** Slide to "Cancel" and release (1-1-2): revoke first, so no late result can be delivered. */
  fun cancelVoice() {
    closeEntry()
  }

  /**
   * Slide to "Text": the field opens immediately on the existing draft, the same capture finishes,
   * and its complete result is joined once to the draft as it is then. Never sends.
   */
  fun voiceToText() {
    heldMenu = null
    gesture.end(false); publishGesture()
    val v = voice.state.value
    if (v.phase == VoicePhase.Idle || v.phase == VoicePhase.Preparing) {
      // Nothing was captured yet: do not let a delayed start open a recorder; keep the draft.
      revokeVoice()
      flow.update { it.copy(entry = EntryMode.Text, island = if (it.island == IslandMode.Hidden) IslandMode.Expanded else it.island, error = null) }
      refreshDraft()
      return
    }
    flow.update { it.copy(entry = EntryMode.Text, island = if (it.island == IslandMode.Hidden) IslandMode.Expanded else it.island, error = null) }
    refreshDraft()
    delivery.aim(VoiceDestination.Draft)
    voice.finish()
    apply(delivery.settle(voice.state.value, flow.value.draft))
  }

  /** Microphone in the text field (1-5); stopping joins the complete result to the draft once. */
  fun startDictation() = startVoice(VoiceTarget.Dictation, VoiceDestination.Draft)
  fun stopDictation() {
    if (voiceStartPending) { cancelPendingVoiceStart(); return }
    delivery.aim(VoiceDestination.Draft)
    voice.finish()
    apply(delivery.settle(voice.state.value, flow.value.draft))
  }

  /**
   * Cancel arc: revoke delivery and stop the microphone now. The dissolve animation that follows
   * is presentation only, so it can never cancel a newer recording. Returns the revoked operation.
   */
  fun beginCancelledVoice(): Long? {
    heldMenu = null
    gesture.end(true); publishGesture()
    val operation = delivery.revoke()
    if (handoffEditor != null && live.snapshot?.generation == 0L) endLiveTalk()
    if (voice.state.value.phase != VoicePhase.Idle) voice.cancel()
    return operation
  }

  /** After the dissolve: close the input unless a newer recording already owns it. */
  fun endCancelledVoice(operation: Long?) {
    val current = delivery.lastOperationId
    if (operation != null && current != null && current != operation) return
    if (voice.state.value.phase != VoicePhase.Idle) return
    closeEntry()
  }

  private fun startVoice(target: VoiceTarget, destination: VoiceDestination) {
    if (live.snapshot?.open == true) throw AssistantException("VOICE_BUSY", "Live Talk is already active")
    if (voiceDeviceUnavailable())
      throw AssistantException("DEVICE_LOCKED", "Wake and unlock the device before starting voice input")
    if (voiceStartPending) return
    if (voice.state.value.phase != VoicePhase.Idle) voice.cancel()
    if (target == VoiceTarget.Hold) supersedeSettledTurn()
    flow.update {
      it.copy(
        entry = if (target == VoiceTarget.Hold) EntryMode.Voice else EntryMode.Text,
        activityId = it.activityId ?: UUID.randomUUID().toString(),
        island = if (it.island == IslandMode.Hidden || it.island == IslandMode.Collapsed) IslandMode.Expanded else it.island,
        hintExpanded = false, voiceFailure = null, error = null, retainedVoice = null,
      )
    }
    liveEndpoint.reset()
    val sequence = ++voiceStartSequence
    voiceStartPending = true
    val chat = ChatVoiceSession.get(context)
    chat.cancelDictation()
    chat.afterSpeech {
      if (sequence != voiceStartSequence) return@afterSpeech
      voiceStartPending = false
      if (voiceDeviceUnavailable()) { interruptVoiceForScreenOff(); return@afterSpeech }
      voice.start(target)
      val operation = voice.state.value.operationId ?: return@afterSpeech
      val settledDestination = when {
        live.handingOff -> VoiceDestination.Live
        destination == VoiceDestination.AwaitGesture && !gesture.held -> VoiceDestination.Submit
        else -> destination
      }
      if (settledDestination == VoiceDestination.Live) handoffOperation = operation
      delivery.begin(operation, settledDestination)
      apply(delivery.settle(voice.state.value, flow.value.draft))
    }
  }

  /**
   * Hides the finished turn from this session's card: it joins [baseline], so the stream never
   * projects it again, and only a newer turn appears. The thread continues, so the next message
   * keeps its context; the reply stays in the conversation.
   */
  private fun supersedeSettledTurn() {
    val s = flow.value
    if (!s.holdSupersedesTurn) return
    val old = s.turn?.turnId ?: return
    synchronized(streamLock) { baseline = baseline + old }
    flow.update { if (it.turn?.turnId == old) it.copy(turn = null) else it }
  }

  private fun cancelPendingVoiceStart() {
    voiceStartSequence++
    voiceStartPending = false
  }

  /** Any conversation boundary: an undelivered result may no longer touch the session. */
  private fun revokeVoice(cancelCapture: Boolean = true) {
    heldMenu = null
    cancelPendingVoiceStart()
    gesture.end(true); publishGesture()
    delivery.newGeneration()
    if (cancelCapture && voice.state.value.phase != VoicePhase.Idle) voice.cancel()
  }

  private fun watchVoice() {
    voiceJob?.cancel()
    voiceJob = scope.launch { voice.state.collect { v ->
      if (v.ready) liveEndpoint.observe(v.levels.lastOrNull() ?: 0f, android.os.SystemClock.uptimeMillis())
      apply(delivery.settle(v, flow.value.draft))
    } }
  }

  private fun apply(effect: VoiceEffect) {
    if (live.handingOff && effect is VoiceEffect.Failed) {
      live.finishHandoff(null)
      handoffOperation = null
      voice.cancel()
      connectLiveTalk()
      return
    }
    when (effect) {
      VoiceEffect.None -> Unit
      is VoiceEffect.Live -> {
        if (!live.handingOff || handoffOperation != effect.operationId) return
        handoffOperation = null
        live.finishHandoff(effect.text)
        voice.cancel()
        connectLiveTalk()
      }
      is VoiceEffect.Draft -> {
        setDraft(effect.text)
        flow.update { it.copy(retainedVoice = null) }
      }
      is VoiceEffect.Retained -> flow.update {
        it.copy(retainedVoice = effect.result, island = if (it.island == IslandMode.Hidden) IslandMode.Expanded else it.island)
      }
      is VoiceEffect.Send -> {
        val menu = heldMenu
        heldMenu = null
        if (menu == null) {
          try { send(effect.text, keepVoiceEntry = true) }
          catch (e: AssistantException) { recoverFailure(editor, e.code, effect.text) }
        } else {
          val text = MenuVoiceHandoff.message(effect.text.trim(), menu.scan.toJson().toString())
          val submission = HomeComposer.MenuSubmission(text, menu.photo)
          try { send(text, keepVoiceEntry = true, menu = submission) }
          catch (e: AssistantException) {
            if (editor.menuSubmission == null && text.length <= MAX_TEXT) editor.menuSubmission = submission
            flow.update { it.copy(error = e.code,
              failedText = editor.menuSubmission?.text?.let(MenuVoiceHandoff::spokenText) ?: it.failedText) }
          }
        }
      }
      is VoiceEffect.Failed -> {
        heldMenu = null
        // Errors stop recognition, but never silently navigate away from the listening page.
        flow.update { it.copy(error = voiceError(effect.code), voiceFailure = effect.code,
          island = if (it.voiceContinuous) IslandMode.Hidden else if (it.island == IslandMode.Hidden) IslandMode.Expanded else it.island) }
      }
      VoiceEffect.CloseEntry -> if (flow.value.entry != EntryMode.Voice && !flow.value.voiceContinuous) closeEntry()
    }
  }

  // ---- Messages ------------------------------------------------------------------------

  /** Sends the current shared draft or hands off a new text request. */
  fun send(text: String, keepVoiceEntry: Boolean = false) = send(text, keepVoiceEntry, null)

  private fun send(text: String, keepVoiceEntry: Boolean, menu: HomeComposer.MenuSubmission?) {
    val message = if (menu != null) MenuVoiceHandoff.spokenText(text) else text.trim()
    if (text.length > MAX_TEXT)
      throw AssistantException("INVALID_TEXT", if (menu != null) "The scanned menu is too long" else "Enter a message or select an attachment")
    if (message.isEmpty() && flow.value.draftAttachmentNames.isEmpty())
      throw AssistantException("INVALID_TEXT", "Enter a message or select an attachment")
    val now = flow.value
    val current = editor
    if (now.sending || current.stagingMenu) throw AssistantException("BUSY", "a message is already being sent")
    if (openingConversation) throw AssistantException("BUSY", "the conversation is opening")
    val waiting = composer.savedRequest(current.threadId) ?: current.saved?.pending
    if (menu != null && (now.working || now.needsUser || live.snapshot?.open == true ||
        waiting != null && menu.threadId != current.threadId))
      throw AssistantException("BUSY", "Finish the current turn before submitting a menu")
    if (waiting != null) return checkDelivery(current, waiting)
    current.menuSubmission = menu
    current.stagingMenu = menu != null && menu.threadId != current.threadId
    val edit = current.edit?.takeIf { menu == null && it.text == text }
    val generation = threadCreation.generation
    // Never steer into a turn the home already gave up on (it may still read active until the interrupt lands).
    val steerTurn = if (menu == null) now.turn?.takeIf { it.active && !now.abandoned }?.turnId else null
    flow.update { it.copy(expansionRequest = if (keepVoiceEntry || it.voiceContinuous) it.expansionRequest else ++expansionSequence, sending = true, pending = message, error = null, failedText = null, abandoned = false, entry = if (it.entry == EntryMode.Voice && !keepVoiceEntry) EntryMode.None else it.entry,
      // The visible voice card keeps its notification identity through capture → reply.
      activityId = if (steerTurn == null && !(keepVoiceEntry && it.entry == EntryMode.Voice)) UUID.randomUUID().toString() else it.activityId,
      island = if (it.voiceContinuous) IslandMode.Hidden else if (it.island == IslandMode.Full) IslandMode.Full else IslandMode.Expanded,
      task = if (menu == null && steerTurn == null) null else it.task,
      turn = if (menu == null && steerTurn == null) null else it.turn) }
    scope.launch {
      var target = current
      try {
        composerLock.withLock {
          if (editor !== current || !flow.value.sending || flow.value.abandoned) return@withLock
          var saved = if (menu != null) {
            val staged = menu.prepare(
              create = { withContext(worker) { HomeComposer.createThread(gateway) } },
              handoff = composer::handoff,
              attach = composer::attachMenuPhoto,
            )
            if (editor !== current || !flow.value.sending) return@withLock
            if (staged.threadId != current.threadId) {
              requireIdleBeforeMenu(current.threadId)
              if (editor !== current || !flow.value.sending) return@withLock
              target = Editor(staged.threadId).apply { menuSubmission = menu }
              replaceEditor(target)
              flow.update { it.copy(threadId = staged.threadId, pending = message,
                turn = null, task = null, taskId = null, appBuildTurnId = null) }
            }
            staged.also { acceptDraft(target, it) }
          } else {
            val id = ensureComposerThread(generation, current)
            current.saved ?: composer.read(id).also { acceptDraft(current, it) }
          }
          val previous = pendingRequest(saved)
          if (previous != null) {
            flow.update { it.copy(pending = null) }
            reconcile(target, previous)
            return@withLock
          }
          if (edit != null) saved = writeDraft(current, edit)
          else if (menu == null && saved.text != text) {
            saved = composer.handoff(saved.threadId, UUID.randomUUID().toString(), text)
            current.edit?.baseText = saved.text
            acceptDraft(current, saved)
          }
          if (editor !== target || flow.value.abandoned) return@withLock
          val request = HomeComposer.Request.new(saved, steerTurn)
          composer.remember(request)
          if (editor !== target || flow.value.abandoned) {
            composer.forget(request)
            return@withLock
          }
          if (steerTurn == null) synchronized(streamLock) { baseline = stream?.turns?.keys?.toSet() ?: emptySet() }
          ensureStream(request.threadId)
          val receipt = try {
            composer.submit(request)
          } catch (failure: Exception) {
            if (!HomeComposer.deliveryCouldBeUnknown(failure)) {
              composer.forget(request)
              throw failure
            }
            reconcile(target, request)
            return@withLock
          }
          if (receipt.terminal) finishDelivery(target, request, receipt)
          else reconcile(target, request)
        }
      } catch (failure: Exception) {
        if (failure is kotlinx.coroutines.CancellationException) throw failure
        Log.w(TAG, "composer submission failed: ${composerError(failure)}")
        if (flow.value.sending) deliveryError(target, composerError(failure), message)
      } finally { current.stagingMenu = false }
    }
  }

  private suspend fun requireIdleBeforeMenu(threadId: String) {
    val status = if (threadId.isEmpty()) JSONObject() else withContext(worker) {
      gateway.call("readSession", JSONObject().put("threadId", threadId).put("statusOnly", true))
    }
    val state = flow.value
    val active = status.optJSONObject("native")?.optString("activeTurnId").orEmpty()
    val task = status.optJSONObject("task")?.optString("state") ?: state.task
    if (state.turn?.active == true || state.needsUser || active.isNotBlank() ||
        task in setOf("queued", "running", "needs_user") || live.snapshot?.open == true || openingConversation)
      throw AssistantException("BUSY", "Finish the current turn before submitting a menu")
  }

  private suspend fun reconcile(current: Editor, request: HomeComposer.Request) {
    var receipt = composer.receipt(request)
    if (receipt.state == "missing") {
      // The gateway never reserved this request. Its ID makes submitting it again idempotent,
      // so settle it now instead of asking about the same unsent request on every later send.
      receipt = try {
        composer.submit(request)
      } catch (failure: Exception) {
        if (failure is kotlinx.coroutines.CancellationException) throw failure
        val after = try { composer.receipt(request) } catch (lookup: Exception) {
          if (lookup is kotlinx.coroutines.CancellationException) throw lookup
          null
        }
        if (after?.state == "missing") {
          // Refused again and still unreserved: it was not sent. The caller reports why and keeps the text.
          composer.forget(request)
          throw failure
        }
        after ?: return deliveryError(current, "UNKNOWN_DELIVERY", request.text)
      }
    }
    if (receipt.terminal) {
      val saved = composer.read(request.threadId)
      if (saved.pending?.id == request.id) composer.settle(request)
      finishDelivery(current, request, receipt, saved.takeUnless { it.pending?.id == request.id })
    } else deliveryError(current, "UNKNOWN_DELIVERY", request.text)
  }

  private suspend fun finishDelivery(current: Editor, request: HomeComposer.Request, receipt: HomeComposer.Receipt,
    snapshot: HomeComposer.Draft? = null) {
    val saved = snapshot ?: composer.read(request.threadId)
    if (receipt.state != "notSent" && saved.text.isEmpty() && current.saved?.revision == request.revision)
      current.edit?.baseText = saved.text
    acceptDraft(current, saved)
    composer.forget(request)
    if (editor !== current) return
    if (receipt.state == "notSent") {
      deliveryError(current, receipt.code, request.text)
      return
    }
    if (flow.value.abandoned) {
      receipt.taskId?.let { task ->
        withContext(worker) { runCatching { gateway.call("cancelQueued", JSONObject().put("threadId", request.threadId).put("taskId", task)) } }
      }
      return
    }
    flow.update { s -> s.copy(sending = false, pending = if (s.sending) s.pending else null,
      error = if (s.error == "UNKNOWN_DELIVERY") null else s.error,
      taskId = receipt.taskId ?: s.taskId, task = if (receipt.taskId != null && s.turn == null) "queued" else s.task) }
    ensureStream(request.threadId)
    ensurePolling(request.threadId)
    if (current.menuSubmission?.threadId == request.threadId) {
      current.menuSubmission = null
      openConversation()
    }
  }

  private fun deliveryError(current: Editor, code: String, text: String?) {
    if (editor === current && !flow.value.abandoned) {
      flow.update { it.copy(sending = false, pending = null, error = code,
        failedText = text?.let(MenuVoiceHandoff::spokenText) ?: it.failedText) }
    }
  }

  private fun recoverFailure(current: Editor, code: String, text: String?, taskId: String? = null) {
    if (editor !== current) return
    val lost = text?.trim()?.takeIf { it.isNotEmpty() && it.length <= MAX_TEXT }
    flow.update { it.copy(error = code, failedText = lost ?: it.failedText) }
    if ((lost == null && taskId == null) || current.edit != null) return
    scope.launch {
      try {
        composerLock.withLock {
          if (editor !== current || current.edit != null || flow.value.error != code) return@withLock
          val saved = composer.read(current.threadId)
          if (editor !== current || current.edit != null || flow.value.error != code) return@withLock
          val recovered = when {
            saved.text.isNotEmpty() || pendingRequest(saved) != null -> saved
            taskId != null -> composer.restoreTask(saved, taskId)
            lost != null -> composer.write(saved, lost)
            else -> saved
          }
          if (current.edit?.baseText == saved.text) current.edit?.baseText = recovered.text
          acceptDraft(current, recovered)
          if (taskId != null && editor === current && flow.value.error == code && flow.value.failedText == null)
            flow.update { it.copy(failedText = recovered.text.takeIf(String::isNotBlank)) }
        }
      } catch (failure: GatewayException) {
        Log.w(TAG, "could not recover the shared draft: ${failure.code}")
      }
    }
  }

  fun retry() {
    val current = editor
    val request = composer.savedRequest(current.threadId) ?: current.saved?.pending
    if (request != null) return checkDelivery(current, request)
    current.menuSubmission?.let { return send(it.text, keepVoiceEntry = true, menu = it) }
    val text = flow.value.retryText ?: throw AssistantException("NOTHING_TO_RETRY", "there is no failed request or draft to send")
    send(text)
  }

  private fun checkDelivery(current: Editor, request: HomeComposer.Request) {
    if (flow.value.sending) throw AssistantException("BUSY", "a message is already being sent")
    flow.update { it.copy(sending = true) }
    scope.launch {
      try {
        composerLock.withLock { if (editor === current) reconcile(current, request) }
      } catch (failure: Exception) {
        if (failure is kotlinx.coroutines.CancellationException) throw failure
        deliveryError(current, composerError(failure), request.text)
      } finally {
        if (editor === current) flow.update { it.copy(sending = false) }
      }
    }
  }

  suspend fun releaseSubmission() {
    val current = editor
    composerLock.withLock {
      if (editor !== current) throw AssistantException("THREAD_CHANGED", "The Home conversation changed")
      val saved = composer.read(current.threadId).also { acceptDraft(current, it) }
      val request = pendingRequest(saved) ?: throw AssistantException("REQUEST_UNAVAILABLE", "No submission is awaiting release")
      if (editor !== current) throw AssistantException("THREAD_CHANGED", "The Home conversation changed")
      if (saved.pending?.id != request.id && composer.receipt(request).state == "missing") {
        val retained = if (saved.text.isEmpty() && current.edit == null) composer.write(saved, request.text) else saved
        acceptDraft(current, retained)
        composer.archive(request)
      } else {
        acceptDraft(current, composer.release(request))
        composer.forget(request)
      }
      if (editor === current && flow.value.error == "UNKNOWN_DELIVERY") flow.update { it.copy(error = null) }
    }
  }

  /** Sends a reply the agent offered. */
  fun reply(suggestionId: String) {
    val suggestion = flow.value.turn?.suggestions?.firstOrNull { it.id == suggestionId }
      ?: throw AssistantException("SUGGESTION_NOT_FOUND", "no suggestion $suggestionId on the current reply")
    send(suggestion.reply)
  }

  /** Stops the current work: interrupt a started turn, or cancel a queued task. */
  fun stop() {
    if (editor.stagingMenu) {
      flow.update { it.copy(sending = false, pending = null) }
      return
    }
    requestStop(flow.value, reportErrors = true)
  }

  private fun requestStop(s: AssistantState, reportErrors: Boolean) {
    val threadId = s.threadId ?: throw AssistantException("NOT_WORKING", "Taku is not working on anything")
    val turnId = s.turn?.takeIf { it.active }?.turnId
    val taskId = s.taskId
    if (turnId == null && taskId == null) throw AssistantException("NOT_WORKING", "Taku is not working on anything")
    val current = editor
    scope.launch(worker) {
      try {
        if (turnId != null) gateway.call("interrupt", JSONObject().put("threadId", threadId).put("turnId", turnId))
        else gateway.call("cancelQueued", JSONObject().put("threadId", threadId).put("taskId", taskId))
      } catch (e: GatewayException) {
        if (reportErrors) withContext(Dispatchers.Main.immediate) {
          if (editor === current) flow.update { it.copy(error = e.code) }
        } else Log.w(TAG, "stop after giving up: ${e.code}")
      }
    }
  }

  // ---- Connectivity ---------------------------------------------------------------------

  /**
   * Offline, the runtime keeps retrying and the task stays running with no end (issue #25 F3).
   * Track the default network; while Taku is working without one for [OFFLINE_GRACE_MS], stop
   * the work and give the request back with NETWORK_UNAVAILABLE. A brief drop resets the wait.
   * Internet capability (not "validated") is used so networks whose validation probe is
   * blocked but which work through the proxy are never treated as offline.
   */
  private fun watchNetwork() {
    val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return
    fun hasInternet(caps: NetworkCapabilities?) = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    flow.update { it.copy(online = hasInternet(connectivity.getNetworkCapabilities(connectivity.activeNetwork))) }
    runCatching {
      connectivity.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) { flow.update { it.copy(online = hasInternet(caps)) } }
        override fun onLost(network: Network) { flow.update { it.copy(online = false) } }
      })
    }.onFailure { Log.w(TAG, "network callback unavailable", it) }
    scope.launch {
      flow.map { !it.online && it.working }.distinctUntilChanged().collectLatest { stalled ->
        if (stalled) { delay(OFFLINE_GRACE_MS); giveUpOffline() }
      }
    }
  }

  private fun giveUpOffline() {
    val s = flow.value
    if (s.online || !s.working) return
    Log.w(TAG, "offline for ${OFFLINE_GRACE_MS / 1000}s while working; stopping and restoring the request")
    val menu = editor.menuSubmission
    if (menu != null && editor.stagingMenu) {
      flow.update { it.copy(sending = false, pending = null,
        error = "NETWORK_UNAVAILABLE", failedText = MenuVoiceHandoff.spokenText(menu.text)) }
      return
    }
    runCatching { requestStop(s, reportErrors = false) } // nothing started yet is fine: the receipt path cancels it
    flow.update { it.copy(sending = false, pending = null, abandoned = true) }
    recoverFailure(editor, "NETWORK_UNAVAILABLE", s.pending ?: s.turn?.userText)
  }

  /** Continues this conversation in the full chat (the island's expand button). */
  fun openConversation() {
    if (openingConversation) return
    endLiveTalk()
    liveLaunchJob?.cancel(); liveLaunchJob = null
    revokeVoice()
    val generation = threadCreation.generation
    val current = editor
    openingConversation = true
    scope.launch {
      try {
        composerLock.withLock {
          if (editor !== current) return@withLock
          val id = ensureComposerThread(generation, current)
          while (current.edit != null && editor === current) current.edit?.let { writeDraft(current, it) }
          if (editor !== current) return@withLock
          context.startActivity(Intent().setClassName(context.packageName, "org.agentos.agent.MainActivity")
            .putExtra("threadId", id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
          flow.update { it.copy(island = IslandMode.Hidden, entry = EntryMode.None, voicePaused = false, handoff = null, revealPending = false) }
        }
      } catch (failure: Exception) {
        if (failure is kotlinx.coroutines.CancellationException) throw failure
        if (editor === current) flow.update { it.copy(error = composerError(failure)) }
      } finally {
        if (editor === current) openingConversation = false
      }
    }
  }

  /**
   * The full chat collapses back into the island (2-1 → 1-1): the expanded island shows
   * [threadId]'s latest turn (or the greeting when it has none), and the next message from the
   * entry continues that thread. Turns already in the thread count as this session's, so the
   * island shows the reply the chat just showed. A blank [threadId] (a new chat with nothing
   * sent yet) opens the island's greeting on a fresh session.
   */
  fun resume(threadId: String) {
    endLiveTalk()
    val id = threadId.trim()
    liveLaunchJob?.cancel(); liveLaunchJob = null
    revokeVoice()
    if (id.isEmpty()) {
      reset()
      flow.update { it.copy(island = IslandMode.Expanded, expansionRequest = ++expansionSequence) }
      refreshDraft()
      return
    }
    follow(id)
    flow.update { it.copy(island = IslandMode.Expanded, entry = EntryMode.None, error = null, handoff = null, revealPending = false,
      voicePaused = false, expansionRequest = ++expansionSequence,
      activityId = it.activityId ?: UUID.randomUUID().toString()) }
    ensureStream(id)
    ensurePolling(id)
    refreshDraft()
  }

  /** Makes [id] this session's thread; turns already in it count as this session's. */
  private fun follow(id: String) {
    if (flow.value.threadId == id) return
    reset(Editor(id))
    flow.update { it.copy(threadId = id) }
  }

  /** Restores presentation identity only; the thread/turn/task data still come from the gateway. */
  fun restoreIslandIdentity(id: String?) {
    if (!id.isNullOrBlank()) flow.update { it.copy(activityId = id) }
  }


  private suspend fun ensureComposerThread(generation: Long, current: Editor): String {
    return threadCreation.resolve(generation, current = { flow.value.threadId }, create = {
      val source = composer.read("").also { acceptDraft(current, it) }
      val id = withContext(worker) { HomeComposer.createThread(gateway) }
      if (editor !== current) throw kotlinx.coroutines.CancellationException("Assistant session changed")
      val moved = composer.move(source, id)
      current.threadId = id
      acceptDraft(current, moved)
      id
    }, bind = { id ->
      synchronized(streamLock) { stream = SessionStreamState(id); baseline = emptySet() }
      flow.update { it.copy(threadId = id) }
    })
  }

  // ---- Session stream and task status --------------------------------------------------

  private fun ensureStream(threadId: String) {
    if (streamJob?.isActive == true) return
    val current = editor
    synchronized(streamLock) { if (stream?.threadId != threadId) stream = SessionStreamState(threadId) }
    streamJob = scope.launch(Dispatchers.IO) {
      var backoff = 400L
      while (isActive && flow.value.threadId == threadId) {
        var subscription = ""
        var input: InputStream? = null
        try {
          val params = JSONObject().put("threadId", threadId)
          synchronized(streamLock) { stream?.cursor() }?.let { (epoch, seq) -> params.put("brokerEpoch", epoch).put("afterSeq", seq) }
          val lease = gateway.call("subscribeSession", params)
          subscription = lease.optString("subscriptionId")
          input = gateway.openStream(lease.getString("streamUri"))
          backoff = 400L
          input.bufferedReader(StandardCharsets.UTF_8).use { reader ->
            while (isActive) {
              val line = runInterruptible { reader.readLine() } ?: break
              if (line.isEmpty()) continue
              val event = JSONObject(line)
              var applied = false
              var turn: TurnView? = null
              val changed = synchronized(streamLock) {
                val st = stream ?: return@synchronized false
                if (st.threadId != threadId) return@synchronized false
                applied = st.apply(event)
                if (applied) turn = ThreadProjection.latest(st, baseline)
                st.needsResync
              }
              if (applied) withContext(Dispatchers.Main.immediate) { if (editor === current) publish(turn) }
              if (changed) break
            }
          }
        } catch (e: Exception) {
          if (!isActive) break
          Log.w(TAG, "session stream: ${(e as? GatewayException)?.code ?: e.message}")
        } finally {
          runCatching { input?.close() }
          if (subscription.isNotEmpty()) runCatching { gateway.call("unsubscribeSession", JSONObject().put("subscriptionId", subscription)) }
        }
        if (!isActive) break
        delay(backoff)
        backoff = (backoff * 2).coerceAtMost(5_000L)
      }
    }
  }

  private fun publish(turn: TurnView?) {
    val before = flow.value
    flow.update { s ->
      val settled = turn != null && !turn.active
      s.copy(
        turn = turn ?: s.turn,
        pending = if (turn?.userText != null || settled) null else s.pending,
        task = if (settled && (s.task == "queued" || s.task == "running")) "completed" else s.task,
      )
    }
    if (turn?.failed == true && before.error != "TURN_FAILED" && !before.abandoned && !before.sending)
      recoverFailure(editor, "TURN_FAILED", turn.userText ?: before.pending)
  }

  /** `readSession(statusOnly)` while working: queued/approval/failed states the stream does not carry. */
  private fun ensurePolling(threadId: String) {
    if (pollJob?.isActive == true) return
    val current = editor
    pollJob = scope.launch(Dispatchers.IO) {
      while (isActive && flow.value.threadId == threadId) {
        try {
          val read = gateway.call("readSession", JSONObject().put("threadId", threadId).put("statusOnly", true))
          val task = read.optJSONObject("task")
          val taskState = task?.optString("state")?.ifEmpty { null }
          val taskId = task?.optString("id")?.ifEmpty { null }
          withContext(Dispatchers.Main.immediate) {
            if (editor !== current) return@withContext
            val before = flow.value
            if (before.sending || before.abandoned) return@withContext
            flow.update { s -> s.copy(task = taskState ?: s.task, taskId = taskId ?: s.taskId,
              pending = if (taskState in setOf("failed", "cancelled") && s.turn?.active != true) null else s.pending) }
            if (taskState == "failed" && before.turn?.active != true && before.error != "TASK_FAILED")
              recoverFailure(current, "TASK_FAILED", before.pending ?: before.turn?.userText, taskId)
          }
        } catch (e: GatewayException) {
          Log.w(TAG, "status read: ${e.code}")
        }
        if (!flow.value.working && !flow.value.needsUser) break
        delay(POLL_MS)
      }
    }
  }

  // ---- Agent view ----------------------------------------------------------------------

  internal fun liveSnapshotJson(): JSONObject {
    val snapshot = live.snapshot
    return JSONObject().put("liveTalk", snapshot?.json() ?: JSONObject.NULL)
      .put("liveTalkDisclosure", snapshot?.disclosure ?: LiveTalk.DISCLOSURE)
  }

  fun toJson(): JSONObject {
    val s = flow.value
    val liveSnapshot = live.snapshot
    val v = voice.state.value
    val session = v.session
    val voiceJson = JSONObject()
      .put("source", "engine")
      .put("operationId", v.operationId ?: JSONObject.NULL)
      .put("sessionId", v.sessionId ?: JSONObject.NULL)
      .put("target", v.target?.name?.lowercase() ?: JSONObject.NULL)
      .put("phase", v.phase.name)
      .put("ready", v.ready).put("listening", v.listening).put("final", v.final)
      .put("transcript", v.transcript).put("preview", v.preview)
      .put("levels", JSONArray(v.levels.takeLast(VoiceState.LEVEL_HISTORY).map { it.toDouble() }))
      .put("levelSource", VoiceState.LEVEL_SOURCE)
      .put("outcome", v.outcome?.toJson() ?: JSONObject.NULL)
      .put("error", v.error ?: s.voiceFailure ?: JSONObject.NULL)
      .put("settingsScope", session?.settingsScope ?: JSONObject.NULL)
      .put("settingsRevision", session?.settingsRevision ?: JSONObject.NULL)
      .put("requestedProfileId", session?.requestedProfileId ?: JSONObject.NULL)
      .put("profileId", session?.profileId ?: JSONObject.NULL)
      .put("language", session?.language ?: JSONObject.NULL)
      .put("processing", session?.processing ?: JSONObject.NULL)
      .put("fallbackUsed", session?.fallbackUsed ?: false)
      .put("fallbackReason", session?.fallbackReason ?: JSONObject.NULL)
      .put("maxCaptureMs", session?.maxCaptureMs ?: JSONObject.NULL)
      .put("drainTimeoutMs", session?.drainTimeoutMs ?: JSONObject.NULL)
      .put("drainDeadlineMonotonicMs", session?.drainDeadlineMonotonicMs ?: JSONObject.NULL)
      .put("delivery", delivery.current?.let { JSONObject().put("target", it.destination.name).put("state", it.state.name) } ?: JSONObject.NULL)
      .put("retainedResult", s.retainedVoice?.toJson() ?: JSONObject.NULL)
    return JSONObject()
      .put("entry", s.entry.name.lowercase())
      .put("island", org.agentos.agent.home.island.SystemIslandBridge.presentation(context))
      .put("expansionRequest", s.expansionRequest)
      .put("islandRequest", s.island.name.lowercase()).put("islandHost", "systemui")
      .put("listeningHeaderHost", if (context.packageManager.resolveContentProvider("com.android.systemui.island", 0) == null) "unavailable" else "systemui-native")
      .put("sending", s.sending).put("abandoned", s.abandoned)
      .put("hintExpanded", s.hintExpanded).put("voiceContinuous", s.voiceContinuous).put("voicePaused", s.voicePaused).put("holdZone", s.holdZone.name.lowercase())
      .put("gesture", JSONObject().put("id", gesture.id).put("source", gesture.source)
        .put("phase", gesture.phase.name.lowercase()).put("sequence", gesture.sequence)
        .put("startedAt", gesture.startedAt).put("updatedAt", gesture.updatedAt))
      .put("threadId", s.threadId ?: JSONObject.NULL).put("activityId", s.activityId ?: JSONObject.NULL)
      .put("buildingApp", s.buildingApp).put("appBuildTurnId", s.appBuildTurnId ?: JSONObject.NULL)
      .put("handoff", s.handoff != null).put("handoffTurnId", s.handoff?.turnId ?: JSONObject.NULL)
      .put("revealPending", s.revealPending)
      .put("working", s.working).put("needsUser", s.needsUser).put("online", s.online)
      .put("pending", s.pending ?: JSONObject.NULL).put("task", s.task ?: JSONObject.NULL).put("error", s.error ?: JSONObject.NULL)
      .put("draft", s.draft).put("failedText", s.failedText ?: JSONObject.NULL)
      .put("draftAttachmentNames", JSONArray(s.draftAttachmentNames))
      .put("turn", s.turn?.let { t ->
        JSONObject().put("turnId", t.turnId).put("active", t.active).put("failed", t.failed)
          .put("userText", t.userText ?: JSONObject.NULL).put("headline", t.headline ?: JSONObject.NULL).put("reply", t.reply ?: JSONObject.NULL)
          .put("steps", JSONArray(t.steps.map { JSONObject().put("id", it.id).put("kind", it.kind.name.lowercase()).put("text", it.text).put("running", it.running) }))
          .put("suggestions", JSONArray(t.suggestions.map { JSONObject().put("id", it.id).put("label", it.label).put("reply", it.reply) }))
      } ?: JSONObject.NULL)
      .put("voice", voiceJson)
      .put("liveTalk", liveSnapshot?.json() ?: JSONObject.NULL)
      .put("liveTalkDisclosure", liveSnapshot?.disclosure ?: LiveTalk.DISCLOSURE)
  }

  companion object {
    private const val CHAT_ACTIVITY = "org.agentos.agent.MainActivity"
    const val MAX_TEXT = 16_000
    /** Saved dictation settings the desktop entry and its field microphone share. */
    const val DESKTOP_SCOPE = "assistant-entry"
    /** Engine reason → the session error the island explains. */
    fun voiceError(code: String): String = when (code) {
      "POLICY_DISABLED", "POLICY_REVOKED", "CONSUMER_NOT_AUTHORIZED" -> "VOICE_DISABLED"
      "PERMISSION_DENIED" -> "VOICE_PERMISSION_REQUIRED"
      "RESOURCE_BUSY", "CAPTURE_SILENCED" -> "VOICE_BUSY"
      "NETWORK_FAILED" -> "VOICE_NETWORK_FAILED"
      "PROVIDER_TIMEOUT", "PROCESSING_TIMEOUT" -> "VOICE_TIMEOUT"
      "PROVIDER_AUTH_FAILED" -> "VOICE_AUTH_FAILED"
      "PROVIDER_UNAVAILABLE", "PROVIDER_RATE_LIMITED" -> "VOICE_PROVIDER_UNAVAILABLE"
      // The chosen dictation backend runs on fal and no fal key is saved (skipped in onboarding).
      "PROVIDER_NOT_CONFIGURED" -> VOICE_FAL_KEY_MISSING
      else -> "VOICE_UNAVAILABLE"
    }
    /** Voice input needs a fal API key; the island's tap opens the fal setup page (Figma 1379:2782). */
    const val VOICE_FAL_KEY_MISSING = "VOICE_FAL_KEY_MISSING"
    const val EXTRA_DRAFT = "org.agentos.agent.extra.DRAFT"
    private const val POLL_MS = 1_500L
    /** How long Taku may keep "working" with no network before the home stops it (issue #25 F3). */
    const val OFFLINE_GRACE_MS = 30_000L
    private const val TAG = "TakuAssistant"
    @Volatile private var instance: AssistantSession? = null
    fun get(context: Context): AssistantSession = instance ?: synchronized(this) {
      instance ?: AssistantSession(context.applicationContext, AgentGateway(context.applicationContext.contentResolver)).also { instance = it }
    }
  }
}
