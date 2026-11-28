package org.agentos.agent.home.island

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import org.agentos.agent.home.assistant.AssistantSession
import org.agentos.agent.home.assistant.AssistantState
import org.agentos.agent.home.assistant.IslandMode
import org.agentos.agent.home.assistant.VoicePhase
import org.agentos.agent.home.assistant.VoiceState
import org.agentos.agent.home.assistant.VoiceTarget
import org.json.JSONArray
import org.json.JSONObject

/** Bound by SystemUI, not an Activity. Backend thread state survives UI/content-host recreation. */
@OptIn(FlowPreview::class)
class IslandPublisherService : Service() {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
  private val binder = Binder()
  private val manager by lazy { getSystemService(NotificationManager::class.java) }
  private val prefs by lazy { getSharedPreferences("system_island_session", MODE_PRIVATE) }
  private var fingerprint: String? = null
  private var lastExpansionRequest = 0L
  private var lastRequest = IslandMode.Hidden
  private var postedTag: String? = null
  /** The hidden state is already applied for [hiddenHandoff]; see [publish]. */
  private var hiddenApplied = false
  private var hiddenHandoff: org.agentos.agent.home.assistant.Handoff? = null

  override fun onCreate() {
    super.onCreate()
    manager.createNotificationChannel(NotificationChannel(CHANNEL, "Taku activities", NotificationManager.IMPORTANCE_LOW))
    val session = AssistantSession.get(this)
    val saved = prefs.getString("thread", null)
    if (session.state.value.threadId == null && !saved.isNullOrBlank() &&
        System.currentTimeMillis() - prefs.getLong("created", 0) in 0..28_800_000) {
      session.resume(saved)
      session.restoreIslandIdentity(prefs.getString("activity", null))
      lastExpansionRequest = session.state.value.expansionRequest
      lastRequest = session.state.value.island // Recovery must not unexpectedly expand over an app.
    } else if (session.state.value.threadId == null) restoreHandoff(session)
    SystemIslandBridge.observe(this)
    watchReveal(session)
    scope.launch {
      combine(session.state, session.voice.state) { state, voice -> state to voice }.sample(250).collect { (state, voice) ->
        // Agent readers observe the same state published to the native SystemUI card.
        contentResolver.notifyChange(Uri.parse("content://org.agentos.agent.home-assistant"), null)
        publish(state, voice)
      }
    }
    scope.launch {
      combine(session.liveState, session.liveIsland) { live, shown -> live.takeIf { shown } }.sample(250).collect(::publishLive)
    }
  }

  private var liveFingerprint: String? = null

  /** The Live capsule: its own notification, posted only while Live Talk runs without its chat. */
  private fun publishLive(live: org.agentos.agent.home.assistant.LiveTalkSnapshot?) {
    if (live == null || !live.open) {
      if (liveFingerprint != null) runCatching { manager.cancel(LiveIslandCard.TAG, ID) }
      liveFingerprint = null
      return
    }
    val view = LiveIslandCard.View(live.lifecycle, live.muted, live.speech, live.playback,
      live.input?.text, live.output?.text, live.task?.state, live.standby, live.video.source, live.video.active, live.video.masked)
    val payload = LiveIslandCard.payload(view)
    val actionIds = LiveIslandCard.actions(view)
    val value = payload.toString() + LiveIslandCard.muteLabel(view) + live.threadId
    if (value == liveFingerprint) return
    val open = PendingIntent.getActivity(this, ID + 10, Intent().setClassName(packageName, "org.agentos.agent.MainActivity")
      .putExtra("threadId", live.threadId).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    fun broadcast(kind: String, code: Int) = PendingIntent.getBroadcast(this, ID + code,
      Intent(this, IslandActionReceiver::class.java).setAction(kind).setData(Uri.parse("taku-island://$kind/live")),
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    val intents = mapOf(LiveIslandCard.OPEN to ("Open conversation" to open),
      LiveIslandCard.MUTE to (LiveIslandCard.muteLabel(view) to broadcast(LiveIslandCard.MUTE, 11)),
      LiveIslandCard.STOP to ("End Live Talk" to broadcast(LiveIslandCard.STOP, 12)))
    val actions = Bundle()
    val icon = Icon.createWithResource(this, applicationInfo.icon.takeIf { it != 0 } ?: android.R.drawable.ic_dialog_info)
    val notification = Notification.Builder(this, CHANNEL).setSmallIcon(icon)
      .setContentTitle(LiveIslandCard.title(view)).setContentText(LiveIslandCard.body(view).take(200))
      .setContentIntent(open).setOnlyAlertOnce(true).setCategory(Notification.CATEGORY_SERVICE).setOngoing(true)
      .setShortCriticalText(LiveIslandCard.status(view)).setRequestPromotedOngoing(true)
    for (id in actionIds) intents[id]?.let { (label, intent) ->
      val action = Notification.Action.Builder(null as Icon?, label, intent).build()
      actions.putParcelable(id, action)
      notification.addAction(action)
    }
    notification.addExtras(Bundle().apply {
      putString("taku.island.v2", payload.toString())
      putBundle("taku.island.pics", Bundle().apply { putParcelable("taku", icon) })
      putBundle("taku.island.actions", actions)
    })
    runCatching { manager.notify(LiveIslandCard.TAG, ID, notification.build()) }
      .onSuccess { liveFingerprint = value }
      .onFailure { android.util.Log.w("TakuIsland", "Live capsule unavailable", it) }
  }

  private fun publish(state: AssistantState, voice: VoiceState) {
    if (state.island == IslandMode.Hidden) {
      // A long handed-off turn streams many updates while hidden; nothing to redo unless the
      // handoff itself changed (its awaited turn, or it ended).
      if (hiddenApplied && hiddenHandoff == state.handoff) return
      hiddenApplied = true; hiddenHandoff = state.handoff
      manager.activeNotifications.filter { it.id == ID && it.tag?.startsWith(TAG) == true && it.tag != LiveIslandCard.TAG }.forEach { manager.cancel(it.tag, ID) }
      val created = prefs.getLong("handoffCreated", 0).takeIf { it > 0 && prefs.getString("handoffThread", null) == state.handoff?.threadId }
      prefs.edit().clear().apply()
      // A handed-off turn draws nothing yet, but must still open its card if this process restarts.
      state.handoff?.let { saveHandoff(it, created ?: System.currentTimeMillis()) }
      fingerprint = null
      postedTag = null
      lastRequest = IslandMode.Hidden
      return
    }
    hiddenApplied = false
    val thread = state.threadId ?: "entry"
    val tag = "$TAG:${state.activityId ?: thread}"
    if (postedTag != null && postedTag != tag) manager.cancel(postedTag, ID)
    if (postedTag == null) manager.activeNotifications.filter { it.id == ID && it.tag?.startsWith(TAG) == true && it.tag != tag && it.tag != LiveIslandCard.TAG }.forEach { manager.cancel(it.tag, ID) }
    postedTag = tag
    val token = IslandIdentity.token(state.threadId, state.activityId)
    if (prefs.getString("activity", null) != state.activityId) prefs.edit().putLong("created", System.currentTimeMillis()).apply()
    prefs.edit().putString("activity", state.activityId).putString("token", token).apply()
    if (prefs.getString("thread", null) != state.threadId) {
      prefs.edit().putString("thread", state.threadId).putLong("created", System.currentTimeMillis()).apply()
    }
    // A hold whose microphone has not started recording yet shows no listening cue at all.
    val holdStarting = voice.target == VoiceTarget.Hold && voice.phase == VoicePhase.Preparing
    val falKeyMissing = state.error == AssistantSession.VOICE_FAL_KEY_MISSING
    val title = when {
      falKeyMissing -> "fal API key missing"
      state.error != null -> "Taku couldn’t finish"
      state.needsUser -> "Taku needs your confirmation"
      // Only once the microphone records (Capturing), never while it is still starting.
      voice.listening && !holdStarting -> "Taku is listening..."
      state.working -> "Taku is working"
      else -> "Taku"
    }
    val body = assistantCardHeadline(state, voice)
    val listening = assistantCardListening(state, voice)
    val short = if (state.buildingApp) "Building..." else ""
    val actions = Bundle()
    val ids = JSONArray()
    val suggestions = JSONArray()
    fun add(id: String, label: String, intent: PendingIntent) {
      if (ids.length() >= 4) return
      actions.putParcelable(id, Notification.Action.Builder(null as Icon?, label, intent).build())
      ids.put(id)
    }
    if (state.working || state.needsUser) add("stop", "Stop", action("stop", thread, state.activityId))
    // Voice needs a fal key: tapping the prompt (or its action) opens the fal setup page directly.
    val falSetup = if (!falKeyMissing) null else PendingIntent.getActivity(this, ID + 2,
      org.agentos.agent.home.onboarding.ServiceSetup.intent(this, org.agentos.agent.home.onboarding.OnboardingService.FAL),
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    if (falSetup != null) add("falKey", "Add fal key", falSetup)
    val open = falSetup ?: PendingIntent.getActivity(this, ID, Intent().setClassName(packageName, "org.agentos.agent.MainActivity")
      .putExtra("threadId", state.threadId).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    add("open", "Open conversation", action("open", thread, state.activityId))
    if (state.error != null && state.retryText != null) add("retry", "Try again", action("retry", thread, state.activityId))
    if ((state.error != null && state.retryText != null) || state.retainedVoice != null) {
      val input = PendingIntent.getActivity(this, ID + 1,
        Intent(this, org.agentos.agent.home.assistant.AssistantOverlayActivity::class.java)
          .putExtra("mode", "text").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
      add("input", if (state.retainedVoice != null) "Show dictation" else "Edit", input)
    }
    if (!state.working && !listening) state.turn?.suggestions?.take(2)?.forEachIndexed { index, suggestion ->
      val id = "reply_$index"
      add(id, suggestion.label, action("reply", thread, state.activityId, suggestion.id))
      if (actions.containsKey(id)) suggestions.put(id)
    }
    val steps = JSONArray(state.turn?.steps.orEmpty().takeLast(8).map { step ->
      JSONObject().put("kind", step.kind.name.lowercase()).put("text", bounded(step.text, 120)).put("running", step.running)
    })
    val primary = if (falSetup != null) "falKey" else "open"
    val payload: JSONObject = JSONObject().put("version", 2).put("timeoutS", 28800)
      .put("expanded", JSONObject().put("template", "assistant")
        .put("title", title).put("body", bounded(body, 1200)).put("steps", if (listening) JSONArray() else steps)
        .put("actions", ids).put("primaryAction", primary).put("tapAction", primary)
        .put("assistant", JSONObject().put("token", token).put("phase", if (listening) "listening" else if (state.working) "working" else "message")
          .put("suggestions", suggestions)
          .put("heightDp", if (listening) kotlin.math.ceil(68 + 27.9 * resources.configuration.fontScale).toInt().coerceIn(96, 340) else 300)))
    // Taku Agent supplies its own capsule (AI animation plus "Building...") only while building an
    // app; otherwise it has none.
    if (state.buildingApp) payload.put("compact", JSONObject()
      .put("a", when {
        state.working -> JSONObject().put("type", "animation").put("name", "ai-working")
        voice.listening && !holdStarting -> JSONObject().put("type", "animation").put("name", "ai-listening")
        else -> JSONObject().put("type", "icon").put("pic", "taku")
      })
      .put("b", JSONObject().put("type", "text").put("text", short)))
    // UTF-8, not String.length: leave ample headroom for CJK/emoji and structural overhead.
    while (payload.toString().toByteArray(Charsets.UTF_8).size > 8192 && payload.getJSONObject("expanded").getString("body").isNotEmpty()) {
      val text = payload.getJSONObject("expanded").getString("body")
      payload.getJSONObject("expanded").put("body", bounded(text, (text.codePointCount(0, text.length) / 2)))
    }
    if (payload.toString().toByteArray(Charsets.UTF_8).size > 8192) payload.getJSONObject("expanded").put("steps", JSONArray())
    val value = payload.toString() + state.working + state.island.name + tag + state.turn?.suggestions?.take(2).toString()
    if (fingerprint != value) {
      val icon = Icon.createWithResource(this, applicationInfo.icon.takeIf { it != 0 } ?: android.R.drawable.ic_dialog_info)
      val extras = Bundle().apply {
        putString("taku.island.v2", payload.toString())
        putBundle("taku.island.pics", Bundle().apply { putParcelable("taku", icon) })
        putBundle("taku.island.actions", actions)
      }
      val notification = Notification.Builder(this, CHANNEL).setSmallIcon(icon).setContentTitle(title)
        .setContentText(bounded(body, 200)).setContentIntent(open).setOnlyAlertOnce(true)
        .setCategory(Notification.CATEGORY_SERVICE).setOngoing(state.working || voice.listening)
        .setShortCriticalText(short).setRequestPromotedOngoing(state.buildingApp).addExtras(extras)
      for (index in 0 until ids.length()) actions.getParcelable(ids.getString(index), Notification.Action::class.java)?.let(notification::addAction)
      runCatching { manager.notify(tag, ID, notification.build()) }
        .onSuccess { fingerprint = value }
        .onFailure { android.util.Log.w("TakuIsland", "Notification unavailable", it) }
    }
    // The text input stays open under the expanded card (Figma 1-3): SystemUI's card never takes
    // focus and moves below the status bar while the keyboard shows, so a reply appears at the top
    // as soon as it arrives instead of after the keyboard closes.
    if (state.island != lastRequest || state.expansionRequest != lastExpansionRequest) {
      if (state.island == IslandMode.Expanded || state.island == IslandMode.Full) requestExpand()
      else if (state.island == IslandMode.Collapsed) SystemIslandBridge.request(this, "collapse")
      lastRequest = state.island
      lastExpansionRequest = state.expansionRequest
    }
  }

  /**
   * Expansion whose acceptance delivers a pending handed-off reveal. A reveal is only asked for, and
   * only counts as delivered, while the screen is on and unlocked: SystemUI can accept a card on a
   * dark screen, and the keyguard then folds it before anyone sees it.
   */
  private fun requestExpand() {
    val session = AssistantSession.get(this)
    if (session.state.value.revealPending && !deviceUsable()) return
    SystemIslandBridge.request(this, "expand") { accepted ->
      if (accepted) scope.launch { if (deviceUsable()) session.revealShown() }
    }
  }

  private fun deviceUsable(): Boolean = org.agentos.agent.home.ScreenState.usable(this)

  /**
   * SystemUI cannot draw a card on the lock screen, with the shade open or in landscape, and the
   * bridge gives up after about 1.5 s. A handed-off result keeps asking until it is drawn or the
   * user moves on: on unlock at once, otherwise every few seconds while the screen is usable.
   */
  private fun watchReveal(session: AssistantSession) {
    val unlocked = object : android.content.BroadcastReceiver() {
      override fun onReceive(context: android.content.Context, intent: Intent) {
        if (session.state.value.revealPending) requestExpand()
      }
    }
    androidx.core.content.ContextCompat.registerReceiver(this, unlocked, android.content.IntentFilter(Intent.ACTION_USER_PRESENT),
      androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
    receivers += unlocked
    scope.launch {
      session.state.map { it.revealPending }.distinctUntilChanged().collectLatest { pending ->
        while (pending) {
          delay(REVEAL_RETRY_MS)
          if (!deviceUsable()) continue
          // Drawn already (its answer was superseded), or no longer wanted: never expand again a
          // card the user has since swiped away. Only judged on a usable screen.
          if (session.state.value.island != IslandMode.Expanded ||
              SystemIslandBridge.presentation(this@IslandPublisherService) == "expanded") { session.revealShown(); break }
          requestExpand()
        }
      }
    }
  }

  private fun saveHandoff(handoff: org.agentos.agent.home.assistant.Handoff, created: Long) {
    prefs.edit().putString("handoffThread", handoff.threadId).putString("handoffTurn", handoff.turnId)
      .putString("handoffTask", handoff.taskId).putLong("handoffCreated", created)
      .putString("handoffKnown", handoff.knownTurnIds?.let { org.json.JSONArray(it.toList()).toString() }).apply()
  }

  /** Follows a handoff saved before this process died, within the same 8 h window as a thread. */
  private fun restoreHandoff(session: AssistantSession) {
    val thread = prefs.getString("handoffThread", null) ?: return
    if (System.currentTimeMillis() - prefs.getLong("handoffCreated", 0) !in 0..28_800_000) return
    runCatching {
      val known = prefs.getString("handoffKnown", null)?.let { org.json.JSONArray(it) }?.let { list -> List(list.length()) { list.opt(it) } }
      session.handoff(thread, prefs.getString("handoffTurn", null), prefs.getString("handoffTask", null),
        org.agentos.agent.home.assistant.IslandHandoff.knownTurns(known))
    }
      .onFailure { android.util.Log.w("TakuIsland", "Handoff not restored: ${it.message}") }
  }

  private fun action(kind: String, thread: String, activity: String?, suggestion: String = ""): PendingIntent {
    val intent = Intent(this, IslandActionReceiver::class.java).setAction(kind)
      .setData(Uri.Builder().scheme("taku-island").authority(kind).appendPath(thread).appendPath(activity ?: "entry").appendPath(suggestion).build())
      .putExtra("thread", thread).putExtra("activity", activity ?: "entry").putExtra("suggestion", suggestion)
    return PendingIntent.getBroadcast(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
  }
  private fun bounded(value: String, count: Int) = value.substring(0, value.offsetByCodePoints(0, minOf(count, value.codePointCount(0, value.length))))
  override fun onBind(intent: Intent?): IBinder = binder
  private val receivers = mutableListOf<android.content.BroadcastReceiver>()
  override fun onDestroy() {
    receivers.forEach { runCatching { unregisterReceiver(it) } }
    scope.cancel(); super.onDestroy()
  } // Do not cancel backend work or its last notification.
  companion object {
    const val CHANNEL = "taku_island"; const val TAG = "taku-island"; const val ID = 2801
    private const val REVEAL_RETRY_MS = 3_000L
  }
}
