package org.agentos.agent.home.assistant

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.agentos.audio.AudioClient
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

internal data class LiveCaption(val id: String, val text: String, val final: Boolean) {
  fun json(idKey: String) = JSONObject().put(idKey, id).put("text", text).put("final", final)
}

internal data class LiveTaskView(val requestId: String?, val taskId: String?, val turnId: String?, val state: String, val version: Long, val error: String? = null) {
  fun json() = JSONObject().put("requestId", requestId ?: JSONObject.NULL).put("taskId", taskId ?: JSONObject.NULL)
    .put("turnId", turnId ?: JSONObject.NULL).put("state", state).put("version", version).put("error", error ?: JSONObject.NULL)
}

/** Shared screen or camera state reported by the Audio Engine; `masked` means frames are withheld for privacy. */
internal data class LiveVideoView(val source: String = "none", val active: Boolean = false, val masked: Boolean = false, val reason: String? = null) {
  fun json() = JSONObject().put("source", source).put("active", active).put("masked", masked).put("reason", reason ?: JSONObject.NULL)
}

internal data class LiveTalkSnapshot(
  val sessionId: String,
  val threadId: String?,
  val provider: String? = null,
  val sequence: Long = 0,
  val generation: Long = 0,
  val lifecycle: String = "connecting",
  val muted: Boolean = false,
  val capturing: Boolean = false,
  val speech: Boolean = false,
  val playback: String = "stopped",
  val responseId: String? = null,
  val input: LiveCaption? = null,
  val output: LiveCaption? = null,
  val levels: List<Float> = emptyList(),
  val task: LiveTaskView? = null,
  val error: String? = null,
  /** The `ai-setup` service whose setup page fixes [error] (`fal` for a missing fal key), else null. */
  val setup: String? = null,
  val video: LiveVideoView = LiveVideoView(),
  /** Every task this session tracks: the most recent ([task]) and older ones still running or unannounced. */
  val tasks: List<LiveTaskView> = emptyList(),
  /** `active`, `hot_standby` or `cold_standby`, as reported by the Audio Engine. */
  val standby: String = "active",
  /** The phone is locked: phone actions and private details wait until it is unlocked. */
  val lockScreenMode: Boolean = false,
) {
  val open get() = lifecycle != "closed"
  val disclosure get() = when (provider.takeIf { lifecycle == "connecting" || lifecycle == "active" }) {
    "fal-grok" -> "Live Talk sends microphone audio, conversation context, and task details for spoken replies to Grok Voice via fal."
    "gemini-live" -> "Live Talk sends microphone audio, conversation context, and task details for spoken replies to Google Gemini Live."
    "moss-live" -> "Live Talk sends microphone audio, conversation context, and task details for spoken replies to MOSS-Live by MOSI."
    else -> LiveTalk.DISCLOSURE
  }

  fun json() = JSONObject().put("sessionId", sessionId).put("threadId", threadId ?: JSONObject.NULL)
    .put("sequence", sequence).put("generation", generation).put("lifecycle", lifecycle)
    .put("provider", provider ?: JSONObject.NULL).put("muted", muted).put("capturing", capturing).put("speech", speech)
    .put("playback", JSONObject().put("state", playback).put("responseId", responseId ?: JSONObject.NULL))
    .put("input", input?.json("itemId") ?: JSONObject.NULL)
    .put("output", output?.json("responseId")?.put("source", "generated") ?: JSONObject.NULL)
    .put("levels", JSONArray(levels)).put("task", task?.json() ?: JSONObject.NULL).put("error", error ?: JSONObject.NULL)
    .put("setup", setup ?: JSONObject.NULL).put("video", video.json())
    .put("tasks", JSONArray(tasks.map(LiveTaskView::json))).put("standbyState", standby).put("lockScreenMode", lockScreenMode)
}

internal class LiveTalk(
  private val context: Context,
  private val scope: CoroutineScope,
  private val delivery: LiveTalkDelivery,
  private val publish: (LiveTalkSnapshot) -> Unit,
  private val recoverHandoff: (String) -> Unit,
) {
  private class Permit {
    @Volatile var valid = true
  }

  private data class Command(val callId: String, val action: String, val inputs: List<LiveInput>, val version: Long, val permit: Permit,
    val target: Task? = null, val args: JSONObject = JSONObject())
  private class Task(val request: LiveRequest) {
    var receipt: LiveReceipt? = null
    var turnId: String? = null
    var state = "submitting"
    var terminal = false
    var replacementRequested = false
    var cancellationVersion: Long? = null
    var result: String? = null
    var announcedState: String? = null
    /** Last state shared with the model as silent context. */
    var notedState: String? = null
    /** State already announced without details while the phone was locked. */
    var lockedNoticeState: String? = null
  }
  /** [private] false is the lock-screen notice that a task finished, without its content. */
  private data class Announcement(val offerId: String, val ipcId: String, val task: Task, val outcome: String, val private: Boolean = true)

  private class Session(threadId: String?, var handoff: Boolean) {
    var view = LiveTalkSnapshot(UUID.randomUUID().toString(), threadId)
    var client: AudioClient? = null
    var engineId: String? = null
    var openId: String? = null
    var openMuted = false
    var muteId: String? = null
    var engineSequence = 0L
    var connected = false
    var permit = Permit()
    var version = 0L
    var handoffText: String? = null
    var handoffTextId: String? = null
    var videoId: String? = null
    /** Engine snapshot requests by IPC id, completed by the reply's file (or null when refused). */
    val snapshots = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.CompletableDeferred<android.os.ParcelFileDescriptor?>>()
    var task: Task? = null
    var command: Command? = null
    var work: Job? = null
    var connecting: Job? = null
    var input: LiveInput? = null
    var afterInput = ""
    var announcement: Announcement? = null
    var awaitingResponse = false
    /** Bumped on every provider event; a quiet gap after speech means no reply is coming. */
    var activity = 0L
    var responseWatch: Job? = null
    /** Older tasks that keep running while a newer one is the session's [task]; bounded by [MAX_TASKS]. */
    val background = ArrayList<Task>()
    val seenInputs = HashSet<String>()
    /** (input, action, task, option) already handled; a provider repeating a call for the same input is refused. */
    val inputActions = HashSet<String>()
    val calls = HashSet<String>()
    val history = ArrayDeque<Pair<String, String>>()
  }

  private var session: Session? = null
  private var sequence = 0L
  val snapshot get() = session?.view
  val handingOff get() = session?.handoff == true

  fun begin(threadId: String?, handoff: Boolean = false) {
    val prior = session
    if (!handoff && prior != null && prior.view.open && prior.view.threadId == threadId) {
      if (prior.view.lifecycle !in setOf("disconnected", "failed")) return
      if (prior.handoff) return
      update(prior) { it.copy(lifecycle = "connecting", provider = null, error = null, setup = null) }
      return
    }
    close()
    val next = Session(threadId, handoff)
    session = next
    update(next) { it }
  }

  fun bindHandoff(threadId: String) {
    val s = session?.takeIf { it.view.lifecycle == "connecting" && it.view.generation == 0L } ?: return
    check(s.view.threadId == null || s.view.threadId == threadId)
    update(s) { it.copy(threadId = threadId) }
  }

  fun finishHandoff(text: String?) {
    val s = session?.takeIf { it.handoff } ?: return
    s.handoff = false
    s.handoffText = text?.takeIf(String::isNotBlank)
  }

  fun connect(sessionId: String) {
    val s = session?.takeIf { it.view.sessionId == sessionId && it.view.lifecycle == "connecting" && !it.handoff && it.client == null } ?: return
    val generation = s.view.generation + 1
    update(s) { it.copy(generation = generation, error = null, setup = null) }
    s.engineSequence = 0
    lateinit var client: AudioClient
    client = AudioClient(context, object : AudioClient.Listener {
      override fun file(id: String, descriptor: android.os.ParcelFileDescriptor) {
        val waiter = s.snapshots.remove(id)
        if (s.client !== client || waiter == null || !waiter.complete(descriptor)) runCatching { descriptor.close() }
      }

      override fun connected() {
        if (s.client !== client) return
        val history = JSONArray(s.history.map { (role, text) -> JSONObject().put("role", role).put("text", text) })
        s.openMuted = s.view.muted
        s.openId = send(s, "audio.session.open", JSONObject().put("purpose", "LIVE_TALK")
          .put("requestKey", UUID.randomUUID().toString()).put("muted", s.view.muted).put("history", history))
      }

      override fun event(event: String) {
        if (s.client !== client) return
        try {
          receive(s, JSONObject(event))
        } catch (_: Exception) {
          disconnect(s, "PROVIDER_PROTOCOL_ERROR")
        }
      }

      override fun failed(message: String) {
        if (s.client === client) disconnect(s, "ENGINE_UNAVAILABLE")
      }
    })
    s.client = client
    s.connecting = scope.launch {
      delay(30_000)
      if (s.client === client && !s.connected) disconnect(s, "CONNECT_TIMEOUT")
    }
    try {
      client.connect()
    } catch (_: Exception) {
      disconnect(s, "ENGINE_UNAVAILABLE")
    }
    s.view.threadId?.let { thread ->
      if (s.task == null) scope.launch {
        val pending = try { delivery.reconcileArchived(thread, s.view.sessionId) } catch (_: GatewayException) { null }
        if (pending != null && session === s && s.view.open && s.task == null && s.command == null) {
          val task = LiveTaskView(pending.requestId, null, null, "deliveryUnknown", s.version)
          update(s) { it.copy(task = task) }
        }
      }
    }
    ensureWork(s)
  }

  fun close() {
    val s = session?.takeIf { it.view.open } ?: return
    release(s)
    s.handoff = false
    s.handoffText = null
    update(s) { it.copy(lifecycle = "closed", capturing = false, speech = false, playback = "stopped", input = null,
      output = null, responseId = null, levels = emptyList(), error = null, setup = null) }
  }

  fun failHandoff(code: String) {
    session?.let { disconnect(it, code) }
  }

  fun setMuted(muted: Boolean) {
    val s = session ?: return
    if (s.view.muted == muted) return
    revokeInput(s)
    s.connected = false
    s.handoffText = null
    s.handoffTextId = null
    update(s) { it.copy(muted = muted, capturing = false, speech = false, video = LiveVideoView(),
      lifecycle = if (!muted && s.engineId != null) "connecting" else it.lifecycle,
      generation = if (!muted && s.engineId != null) it.generation + 1 else it.generation,
      playback = if (it.playback == "speaking") "interrupted" else it.playback, input = null, levels = emptyList()) }
    s.engineId?.let { id ->
      s.openMuted = muted
      s.muteId = send(s, "audio.live.mute", JSONObject().put("sessionId", id).put("muted", muted))
    }
  }

  private fun update(s: Session, change: (LiveTalkSnapshot) -> LiveTalkSnapshot) {
    val next = change(s.view)
    s.view = next.copy(sequence = ++sequence)
    publish(s.view)
  }

  private fun release(s: Session) {
    s.snapshots.values.forEach { it.complete(null) }
    s.snapshots.clear()
    revokeInput(s)
    s.connected = false
    s.connecting?.cancel()
    val client = s.client
    s.client = null
    s.engineId?.let { id -> runCatching { client?.cancel(id) } }
    runCatching { client?.close() }
    s.engineId = null
    s.openId = null
    s.muteId = null
    s.afterInput = ""
    s.announcement = null
  }

  private fun revokeInput(s: Session) {
    s.permit.valid = false
    s.permit = Permit()
    s.input = null
    s.awaitingResponse = false
    s.responseWatch?.cancel()
    s.responseWatch = null
    s.command?.permit?.valid = false
    s.command = null
  }

  private fun disconnect(s: Session, code: String) {
    if (session !== s || !s.view.open) return
    release(s)
    s.handoff = false
    val text = s.handoffText
    s.handoffText = null
    s.handoffTextId = null
    if (text != null) recoverHandoff(text)
    val provider = s.view.provider
    update(s) { it.copy(lifecycle = if (code in CONNECTION_ERRORS) "disconnected" else "failed", error = code, video = LiveVideoView(),
      setup = provider?.let { accepted -> LiveTalkSetup.service(code, accepted, null) },
      capturing = false, speech = false, playback = if (it.playback == "speaking") "interrupted" else it.playback,
      input = it.input?.takeIf(LiveCaption::final), levels = emptyList()) }
    if (provider == null && LiveTalkSetup.missingKey(code)) resolveSetup(s, code)
  }

  /**
   * The engine refused the open request before naming a provider, so a missing key may be fal's or
   * Gemini's: ask Taku Audio which one, and offer the fal connection card only for fal. The answer
   * applies to this exact failure; a reconnect or close in the meantime discards it.
   */
  private fun resolveSetup(s: Session, code: String) {
    val generation = s.view.generation
    scope.launch {
      val settings = withContext(Dispatchers.IO) { LiveTalkSetup.readAudioSettings(context) }
      val service = LiveTalkSetup.service(code, null, settings) ?: return@launch
      val same = session === s && s.view.open && s.view.generation == generation && s.view.error == code
      if (same) update(s) { it.copy(setup = service) }
    }
  }

  private fun send(s: Session, method: String, args: JSONObject): String? = try {
    s.client?.call(method, args)
  } catch (_: Exception) {
    disconnect(s, "ENGINE_UNAVAILABLE")
    null
  }

  private fun receive(s: Session, message: JSONObject) {
    if (message.optString("type") == "response") {
      val id = message.getString("id")
      val ok = message.optBoolean("ok")
      val code = message.optJSONObject("error")?.optString("code").orEmpty().ifBlank { "ENGINE_UNAVAILABLE" }
      when (id) {
        s.openId -> {
          if (!ok) return disconnect(s, code)
          val result = message.getJSONObject("result")
          val engine = result.getString("sessionId")
          if (s.engineId != null && s.engineId != engine) return disconnect(s, "PROVIDER_PROTOCOL_ERROR")
          s.engineId = engine
          val provider = (result.opt("profileId") as? String)?.takeIf(String::isNotBlank)
            ?: return disconnect(s, "PROVIDER_PROTOCOL_ERROR")
          update(s) { it.copy(provider = provider) }
          syncInitialMute(s, engine)
        }
        s.muteId -> if (!ok) disconnect(s, code)
        s.handoffTextId -> {
          // A waking engine answers LIVE_CONNECTING; the text is sent again once it reconnects.
          if (!ok && code == "LIVE_CONNECTING") { s.handoffTextId = null; return }
          if (!ok) return disconnect(s, code)
          s.handoffText = null
          s.handoffTextId = null
        }
        s.announcement?.ipcId -> if (!ok) s.announcement = null
        in s.snapshots.keys -> if (!ok) s.snapshots.remove(id)?.complete(null)
        s.videoId -> {
          s.videoId = null
          // A refused video request leaves the voice session running; it only reports why.
          if (!ok) update(s) { it.copy(video = LiveVideoView(reason = code)) }
        }
      }
      return
    }
    val data = message.optJSONObject("data") ?: return
    val engine = data.optString("sessionId")
    if (engine.isBlank() || s.engineId != null && s.engineId != engine) return
    s.engineId = engine
    syncInitialMute(s, engine)
    val seq = data.optLong("sequence")
    if (seq > 0L && seq <= s.engineSequence) return
    if (seq > 0L) s.engineSequence = seq
    when (data.optString("kind", data.optString("type"))) {
      "Running" -> if (s.view.muted) {
        s.connecting?.cancel()
        update(s) { it.copy(lifecycle = "active", capturing = false) }
      }
      "Closed" -> disconnect(s, data.optString("reason").ifBlank { "ENGINE_UNAVAILABLE" })
      "Level" -> if (s.view.capturing) {
        val level = data.optDouble("level", Double.NaN)
        if (level.isFinite()) update(s) { it.copy(levels = (it.levels + level.coerceIn(0.0, 1.0).toFloat()).takeLast(VoiceState.LEVEL_HISTORY)) }
      }
      "Live" -> liveEvent(s, data.getJSONObject("live"))
    }
  }

  private fun syncInitialMute(s: Session, engine: String) {
    if (s.openMuted == s.view.muted) return
    s.openMuted = s.view.muted
    s.muteId = send(s, "audio.live.mute", JSONObject().put("sessionId", engine).put("muted", s.openMuted))
  }

  private fun liveEvent(s: Session, value: JSONObject) {
    val kind = value.getString("kind")
    if (kind == "TaskResult") {
      val offer = s.announcement?.takeIf { it.offerId == value.getString("offerId") } ?: return
      s.announcement = null
      if (value.getBoolean("sent")) { if (offer.private) offer.task.announcedState = offer.outcome else offer.task.lockedNoticeState = offer.outcome }
      return
    }
    if (kind == "Standby") {
      update(s) { it.copy(standby = value.getString("state")) }
      return
    }
    if (kind == "Video") {
      update(s) { it.copy(video = LiveVideoView(value.getString("source"), value.getBoolean("active"),
        value.optBoolean("masked"), value.optString("reason").ifBlank { null })) }
      return
    }
    if (kind == "Mic") {
      val muted = value.getBoolean("muted")
      if (muted == s.view.muted) {
        s.connected = !muted
        s.connecting?.cancel()
        update(s) { it.copy(lifecycle = "active", capturing = !muted) }
        if (!muted) {
          s.afterInput = ""
          s.announcement = null
          s.handoffText?.takeIf { s.handoffTextId == null }?.let { text ->
            s.handoffTextId = send(s, "audio.live.text", JSONObject().put("sessionId", s.engineId).put("text", text))
          }
          if (s.view.lockScreenMode) noteLock(s)
          maybeAnnounce(s)
          ensureWork(s)
        }
      }
      return
    }
    if (s.view.muted || !s.connected) return
    s.activity++
    when (kind) {
      "Speech" -> {
        val active = value.getBoolean("active")
        if (active) {
          s.input = null
          s.awaitingResponse = true
        }
        update(s) { it.copy(speech = active) }
      }
      "Input" -> {
        val caption = LiveCaption(value.getString("itemId"), value.getString("text"), value.getBoolean("final"))
        val key = "${s.view.generation}:${caption.id}"
        if (key in s.seenInputs) return
        s.awaitingResponse = true
        update(s) { it.copy(input = caption) }
        if (caption.final && caption.text.isNotBlank()) {
          s.afterInput = caption.id
          s.seenInputs.add(key)
          s.input = LiveInput(key, caption.text)
          remember(s, "user", caption.text)
        }
      }
      "Output" -> {
        val caption = LiveCaption(value.getString("responseId"), value.getString("text"), value.getBoolean("final"))
        update(s) { it.copy(output = caption) }
      }
      "Playback" -> {
        val id = value.getString("responseId")
        val state = value.getString("state")
        update(s) { it.copy(responseId = id, playback = state) }
        if (state == "stopped") {
          s.view.output?.takeIf { it.id == id && it.final }?.let { remember(s, "model", it.text) }
          if (!s.view.speech && s.view.output?.id == id) {
            s.awaitingResponse = false
            maybeAnnounce(s)
          }
        }
      }
      "ResponseDone" -> {
        if (!s.view.speech && value.getString("itemId") == s.view.input?.id) {
          s.awaitingResponse = false
        }
        maybeAnnounce(s)
      }
      "Tool" -> tool(s, value)
    }
    watchResponse(s)
  }

  /**
   * The provider stays silent for noise and may never answer a turn, so no Playback or
   * ResponseDone arrives. Once speech has ended and no provider event follows for a grace
   * period, stop waiting so held submits, corrections and task announcements can proceed.
   */
  private fun watchResponse(s: Session) {
    s.responseWatch?.cancel()
    s.responseWatch = null
    if (!s.awaitingResponse || s.view.speech || s.view.playback == "speaking") return
    val mark = s.activity
    s.responseWatch = scope.launch {
      delay(RESPONSE_GRACE_MS)
      if (session !== s || !s.view.open || !s.connected || s.activity != mark || !s.awaitingResponse ||
        s.view.speech || s.view.playback == "speaking") return@launch
      s.responseWatch = null
      s.awaitingResponse = false
      maybeAnnounce(s)
      ensureWork(s)
    }
  }

  private fun remember(s: Session, role: String, text: String) {
    if (text.isBlank() || text.length > 12_000) return
    s.history.addLast(role to text)
    while (s.history.size > 24 || s.history.sumOf { it.second.length } > 12_000) s.history.removeFirst()
  }

  private fun tool(s: Session, value: JSONObject) {
    val call = value.getString("callId")
    if (call.isBlank() || !s.calls.add("${s.view.generation}:$call")) return
    val item = value.getString("itemId")
    // The confirmed input stays usable for every call of its turn; repeats are refused below.
    val input = s.input?.takeIf { it.itemId == "${s.view.generation}:$item" }
    val action = value.getString("action")
    val args = runCatching { JSONObject(value.optString("args", "{}")) }.getOrDefault(JSONObject())
    val requested = (args.opt("taskId") as? String)?.takeIf(String::isNotBlank)
    val owned = target(s, requested)
    val prior = s.command
    when {
      input == null || s.view.speech || input.text.length > AssistantSession.MAX_TEXT -> toolReply(s, call, "notSent", "No confirmed user input belongs to this call.")
      // Keyguard Action Gate: nothing that acts on the phone or reads private data while it is locked.
      LiveVoiceAnswer.lockedGate(locked(), action) != null -> {
        if (!s.view.lockScreenMode) update(s) { it.copy(lockScreenMode = true) }
        if (action == "status") toolReply(s, call, "status", lockedStatus(s))
        else toolReply(s, call, "blocked", UNLOCK_MESSAGE, "UNLOCK_REQUIRED")
      }
      action == "status" -> toolReply(s, call, "status", statusSummary(s))
      requested != null && owned == null -> toolReply(s, call, "notSent", "No task of this Live Talk session has that taskId.")
      action == "submit" && prior != null -> toolReply(s, call, "notSent", "Another voice request is still being sent. Try again after its receipt.")
      action == "submit" && s.task?.terminal == false && s.background.count { !it.terminal } >= MAX_TASKS - 1 ->
        toolReply(s, call, "notSent", "$MAX_TASKS tasks are already running. Wait for one to finish or cancel one.")
      action in setOf("correct", "steer", "confirm") && owned == null -> toolReply(s, call, "notSent", "There is no owned task to change or answer.")
      action == "cancel" && (owned == null || !explicitCancel(input.text)) -> toolReply(s, call, "notSent", "Use Stop in chat to cancel the task.")
      action !in COMMANDS -> toolReply(s, call, "notSent", "Unsupported voice action.")
      // One utterance is one request per action and task: a provider repeating the same call is refused.
      !s.inputActions.add("${input.itemId}\u0000$action\u0000${requested.orEmpty()}\u0000${args.optInt("optionIndex", -1)}") ->
        toolReply(s, call, "notSent", "This exact request was already handled for this input.")
      else -> {
        if (prior != null) {
          prior.permit.valid = false
          toolReply(s, prior.callId, "notSent", "A newer user request superseded this one.")
        }
        val version = ++s.version
        val additions = if (action == "correct" && prior?.action == "correct") prior.inputs + input else listOf(input)
        s.command = Command(call, action, additions, version, Permit(), if (action == "submit") null else owned, args)
        s.awaitingResponse = false
        when (action) {
          "submit" -> if (s.task?.terminal != false) showTask(s, null, "submitting")
          "confirm" -> Unit
          else -> if (owned === s.task) showTask(s, owned, if (action == "cancel") "cancelling" else "correcting")
        }
        ensureWork(s)
      }
    }
  }

  private fun ensureWork(s: Session) {
    if (s.work?.isActive == true) return
    s.work = scope.launch {
      while (isActive && s.view.open) {
        val command = s.command
        try {
          if (command != null) process(s, command) else {
            val primary = observe(s)
            val others = observeBackground(s)
            if (!primary && !others && s.command == null) break
          }
        } catch (cancel: CancellationException) {
          throw cancel
        } catch (failure: Exception) {
          val code = (failure as? GatewayException)?.code ?: "GATEWAY_UNAVAILABLE"
          if (command != null && s.command === command && locked()) refuseLocked(s, command)
          else if (command != null && s.command === command && code !in RETRYABLE_REPLACEMENT) {
            s.command = null
            showTask(s, s.task, if (code == "LIVE_TASK_NEEDS_USER") "needs_user" else "blocked", code)
            if (current(s, command)) toolReply(s, command.callId, "notSent", "Continue in the original chat. ($code)")
          } else if (command == null && s.task?.request?.version == s.version) {
            showTask(s, s.task, "unverified", code)
          }
        }
        if (!s.view.open || s.command == null && s.task == null && s.background.isEmpty()) break
        delay(900)
      }
    }
  }

  private fun current(s: Session, command: Command): Boolean =
    session === s && s.view.open && !s.view.muted && command.permit.valid && s.version == command.version

  private suspend fun process(s: Session, command: Command) {
    if (!current(s, command)) return
    // Keyguard Action Gate again after every wait: a request accepted while unlocked never runs locked.
    if (locked()) return refuseLocked(s, command)
    if (s.view.speech || s.awaitingResponse || !s.connected) return
    val old = command.target ?: s.task
    when (command.action) {
      "submit" -> submit(s, command, command.inputs)
      "correct", "cancel", "steer", "confirm" -> {
        old ?: return
        // Receipt lookup is read-only. An unknown publication is never sent a second time.
        val receipt = old.receipt?.takeUnless { it.pending } ?: delivery.reconcile(old.request).also { old.receipt = it }
        if (!current(s, command)) return
        val id = receipt.taskId ?: throw GatewayException("LIVE_TASK_UNCONFIRMED", "The prior request has no verified task")
        if (s.view.speech || s.awaitingResponse) return
        if (command.action == "confirm") return confirm(s, command, old)
        if (command.action == "steer" && steer(s, command, old, id)) return
        if (command.action == "cancel") {
          val permit = s.permit
          val status = delivery.task("live/task-cancel", old.request, id) { permit.valid && command.permit.valid && !locked() }
          old.cancellationVersion = command.version
          if (current(s, command)) {
            s.command = null
            if (status.optBoolean("terminal")) {
              toolReply(s, command.callId, "unchanged", "The Agent task already finished. Report only its actual outcome.")
            } else {
              showTask(s, old, "cancelling")
              toolReply(s, command.callId, "cancellationRequested", "Wait for task status before saying it was cancelled.")
            }
          }
          return
        }
        val status = delivery.task("live/task-read", old.request, id)
        if (!current(s, command) || s.view.speech || s.awaitingResponse) return
        if (status.optBoolean("needsUser") || status.optString("state") == "needs_user")
          throw GatewayException("LIVE_TASK_NEEDS_USER", "Review the pending request in chat")
        val inputs = old.request.inputs + command.inputs
        if (inputs.sumOf { it.text.length } + inputs.size - 1 > AssistantSession.MAX_TEXT)
          throw GatewayException("INVALID_TEXT", "The corrected query exceeds the message limit")
        val replacement = if (old.replacementRequested) status else {
          val permit = s.permit
          delivery.task("live/task-replace", old.request, id) { permit.valid && command.permit.valid && !locked() }.also {
            old.replacementRequested = it.optBoolean("cancellationRequested") || it.optBoolean("terminal")
          }
        }
        if (current(s, command) && replacement.optBoolean("terminal")) submit(s, command, inputs)
      }
    }
  }

  private suspend fun submit(s: Session, command: Command, inputs: List<LiveInput>) {
    // An independent request while the session's task still runs gets its own conversation, so
    // the running turn neither blocks it (THREAD_BUSY) nor is replaced by it.
    val parallel = command.action == "submit" && s.task?.terminal == false
    val id = if (parallel) delivery.createThread() else s.view.threadId ?: delivery.createThread()
    if (!current(s, command)) return
    if (!parallel && s.view.threadId == null) update(s) { it.copy(threadId = id) }
    if (s.view.speech || s.awaitingResponse) return
    val next = Task(LiveRequest(s.view.sessionId, id, command.callId, command.version, inputs))
    // A replaced task leaves the list; a still-running task that is not being replaced keeps running beside the new one.
    command.target?.let { s.background.remove(it) }
    s.task?.takeIf { !it.terminal && it !== command.target }?.let { running ->
      s.background.add(running)
      while (s.background.size > MAX_TASKS - 1) s.background.removeAt(s.background.indexOfFirst { it.terminal }.takeIf { it >= 0 } ?: 0)
    }
    s.task = next
    s.command = null
    showTask(s, next, "submitting")
    val permit = s.permit
    val screenshot = if (command.args.optBoolean("attachScreen")) snapshot(s) else null
    val receipt = try {
      delivery.submit(next.request, { permit.valid && command.permit.valid && !locked() }, screenshot)
    } catch (failure: GatewayException) {
      if (failure.code != "RECEIPT_SAVE_FAILED") throw failure
      LiveReceipt("deliveryUnknown", code = failure.code)
    }
    next.receipt = receipt
    next.state = receipt.state
    next.terminal = receipt.state == "notSent"
    if (session === s && s.view.open && next.request.version == s.version) {
      showTask(s, next, receipt.state, receipt.code)
    }
    if (receipt.state == "notSent" && locked()) {
      if (session === s && s.view.open && next.request.version == s.version) showTask(s, next, "blocked", "UNLOCK_REQUIRED")
      toolReply(s, command.callId, "blocked", UNLOCK_MESSAGE, "UNLOCK_REQUIRED")
      return
    }
    if (session === s && s.view.open && permit.valid && s.connected && !s.view.muted) {
      toolReply(s, command.callId, receipt.state, when {
        receipt.accepted -> "The Agent accepted the task (taskId ${receipt.taskId})${if (screenshot != null) " with the shared screen attached" else
          if (command.args.optBoolean("attachScreen")) " as text only; the screen could not be attached" else ""}. It has not finished."
        receipt.pending -> "Delivery has not been verified. Do not resubmit it."
        else -> "The Agent did not receive this request."
      })
    }
  }

  private suspend fun observe(s: Session): Boolean {
    val task = s.task ?: return false
    val receipt = task.receipt?.takeUnless { it.pending } ?: delivery.reconcile(task.request).also { task.receipt = it }
    if (!s.view.open || session !== s || s.command != null || s.task !== task) return false
    val current = task.request.version == s.version || task.cancellationVersion == s.version
    if (!receipt.accepted) {
      task.terminal = receipt.state == "notSent"
      if (current) showTask(s, task, receipt.state, receipt.code)
      return receipt.pending
    }
    val id = receipt.taskId ?: return false
    if (!task.terminal) {
      val status = delivery.task("live/task-read", task.request, id)
      if (!s.view.open || session !== s || s.command != null || s.task !== task) return false
      val turn = (status.opt("turnId") as? String)?.takeIf(String::isNotBlank)
      if (task.turnId != null && turn != task.turnId) throw GatewayException("LIVE_TASK_MISMATCH", "The task turn changed")
      val state = if (status.optBoolean("needsUser")) "needs_user" else status.optString("state")
      task.turnId = turn
      task.terminal = status.optBoolean("terminal")
      task.state = state
      task.result = when (task.state) {
        "needs_user" -> "The Agent needs your input. Review the request in the original chat."
        "completed" -> (status.opt("reply") as? String)?.take(2_400)?.takeIf { turn != null && it.isNotBlank() }
        "failed" -> "The Agent task failed. See the original chat for details."
        "cancelled" -> "The Agent task was cancelled."
        else -> null
      }
    }
    if (current) {
      showTask(s, task, task.state)
      noteProgress(s, task)
      maybeAnnounce(s)
    }
    return !task.terminal || current && s.connected && !s.view.muted &&
      task.result != null && task.announcedState != task.state
  }

  /**
   * A PNG of the screen the user is sharing, for a task the model asked to see it. Only while the
   * screen is shared and unmasked; the engine and the gateway check the policy and lock state again.
   * Null means the message goes as plain text.
   */
  private suspend fun snapshot(s: Session): android.os.ParcelFileDescriptor? {
    val engine = s.engineId ?: return null
    val video = s.view.video
    if (video.source != "screen" || !video.active || video.masked || !s.connected) return null
    val waiter = kotlinx.coroutines.CompletableDeferred<android.os.ParcelFileDescriptor?>()
    val id = send(s, "audio.live.snapshot", JSONObject().put("sessionId", engine)) ?: return null
    s.snapshots[id] = waiter
    val file = kotlinx.coroutines.withTimeoutOrNull(SNAPSHOT_TIMEOUT_MS) { waiter.await() }
    s.snapshots.remove(id)
    if (file == null && waiter.isCompleted) runCatching { waiter.getCompleted()?.close() }
    return file
  }

  /** Video sharing: `screen`, `camera` or `none`. The engine reports the actual state as a Video event. */
  fun setVideo(source: String) {
    require(source in VIDEO_SOURCES)
    val s = session ?: return
    val engine = s.engineId ?: throw AssistantException("LIVE_CONNECTING", "Wait for Live Talk to connect")
    if (!s.connected || s.view.muted) throw AssistantException("LIVE_CONNECTING", "Resume Live Talk before sharing video")
    if (s.view.provider !in VIDEO_PROVIDERS) throw AssistantException("VIDEO_UNSUPPORTED", "The selected Live Talk provider does not support video")
    s.videoId = send(s, "audio.live.video", JSONObject().put("sessionId", engine).put("source", source))
  }

  /**
   * Background task progress reaches the model as context that never prompts it to speak, so a
   * running task stays current without interrupting the conversation. Only MOSS-Live supports it.
   */
  private fun noteProgress(s: Session, task: Task) {
    val engine = s.engineId ?: return
    if (!s.connected || s.view.muted || s.view.provider !in VIDEO_PROVIDERS || task.notedState == task.state) return
    // Cold standby is disconnected on purpose; progress is shared again after it wakes.
    if (s.view.standby == "cold_standby") return
    task.notedState = task.state
    val label = when (task.state) {
      "queued", "submitting" -> "is queued"
      "running" -> "is running"
      "needs_user" -> "is waiting for the user in the original chat"
      "completed" -> "has finished; its result will be announced separately"
      "failed" -> "has failed"
      "cancelled" -> "was cancelled"
      else -> return
    }
    send(s, "audio.live.context", JSONObject().put("sessionId", engine).put("text", "The delegated Taku Agent task $label."))
  }

  private fun showTask(s: Session, task: Task?, state: String, error: String? = null) {
    if (session !== s || !s.view.open) return
    val next = LiveTaskView(task?.request?.requestId, task?.receipt?.taskId, task?.turnId, state, s.version, error)
    val tasks = s.background.map { it.view() } + listOfNotNull(next.takeIf { task != null || state != "submitting" })
    if (s.view.task != next || s.view.tasks != tasks) update(s) { it.copy(task = next, tasks = tasks) }
  }

  private fun Task.view() = LiveTaskView(request.requestId, receipt?.taskId, turnId, state, request.version)

  private fun publishTasks(s: Session) {
    if (session !== s || !s.view.open) return
    val tasks = s.background.map { it.view() } + listOfNotNull(s.view.task)
    if (s.view.tasks != tasks) update(s) { it.copy(tasks = tasks) }
  }

  /** The session task a tool call names by [taskId], or the most recent task when it names none. */
  private fun target(s: Session, taskId: String?): Task? = when (taskId) {
    null -> s.task
    else -> (listOfNotNull(s.task) + s.background).firstOrNull { it.receipt?.taskId == taskId }
  }

  private fun refuseLocked(s: Session, command: Command) {
    if (s.command === command) s.command = null
    if (!s.view.lockScreenMode) update(s) { it.copy(lockScreenMode = true) }
    if (command.target === s.task || command.action == "submit") showTask(s, s.task, s.task?.state ?: "blocked", "UNLOCK_REQUIRED")
    toolReply(s, command.callId, "blocked", UNLOCK_MESSAGE, "UNLOCK_REQUIRED")
  }

  /** While locked, progress without task ids or details. */
  private fun lockedStatus(s: Session): String {
    val tasks = s.background + listOfNotNull(s.task)
    val running = tasks.count { !it.terminal }
    return LiveVoiceAnswer.lockedStatus(running, tasks.size - running)
  }

  /**
   * Follows the lock state (polled by Home). On a change the snapshot updates and the model learns
   * the restricted mode as silent context; after unlocking, results held back are announced.
   */
  fun refreshLock() {
    val s = session?.takeIf { it.view.open } ?: return
    val locked = locked()
    if (s.view.lockScreenMode == locked) return
    update(s) { it.copy(lockScreenMode = locked) }
    noteLock(s)
    if (!locked) maybeAnnounce(s)
  }

  private fun noteLock(s: Session) {
    val engine = s.engineId ?: return
    if (!s.connected || s.view.muted || s.view.provider !in VIDEO_PROVIDERS || s.view.standby == "cold_standby") return
    send(s, "audio.live.context", JSONObject().put("sessionId", engine).put("text", if (s.view.lockScreenMode)
      "The phone is now locked. Only talk and public information: do not delegate phone actions or private data, and do not read out task details; ask the user to unlock first."
      else "The phone is unlocked again. Normal delegation is available."))
  }

  /** Read-only progress for the model, from the latest observations; it never calls the Agent. */
  private fun statusSummary(s: Session): String {
    val tasks = s.background + listOfNotNull(s.task)
    if (tasks.isEmpty()) return "No task has been delegated in this Live Talk session."
    return tasks.joinToString("; ", "Tasks: ") { task ->
      "${task.receipt?.taskId ?: "unconfirmed"} is ${task.state.replace('_', ' ')}"
    } + ". Report only these states; results are announced separately."
  }

  /** Polls older tasks that still run beside the newest one; returns whether any still needs work. */
  private suspend fun observeBackground(s: Session): Boolean {
    var pending = false
    for (task in s.background.toList()) {
      if (!s.view.open || session !== s || s.command != null) return pending
      val id = task.receipt?.taskId
      if (!task.terminal && id != null) {
        val status = try { delivery.task("live/task-read", task.request, id) } catch (failure: GatewayException) { pending = true; continue }
        val turn = (status.opt("turnId") as? String)?.takeIf(String::isNotBlank)
        task.turnId = turn ?: task.turnId
        task.terminal = status.optBoolean("terminal")
        task.state = if (status.optBoolean("needsUser")) "needs_user" else status.optString("state")
        task.result = when (task.state) {
          "needs_user" -> "A background Agent task needs your input. Review it in its chat."
          "completed" -> (status.opt("reply") as? String)?.take(2_400)?.takeIf { turn != null && it.isNotBlank() }
          "failed" -> "A background Agent task failed. See its chat for details."
          "cancelled" -> "A background Agent task was cancelled."
          else -> null
        }
        noteProgress(s, task)
      }
      if (!task.terminal || task.result != null && task.announcedState != task.state) pending = true
      else if (task.terminal && (task.result == null || task.announcedState == task.state)) s.background.remove(task)
    }
    publishTasks(s)
    maybeAnnounce(s)
    return pending
  }

  /**
   * Steers the running turn when it is known. Returns false for a task still queued without a turn,
   * which takes the correction path (replacement of a read-only query) instead.
   */
  private suspend fun steer(s: Session, command: Command, task: Task, taskId: String): Boolean {
    val status = delivery.task("live/task-read", task.request, taskId)
    if (!current(s, command)) return true
    if (status.optBoolean("needsUser") || status.optString("state") == "needs_user")
      throw GatewayException("LIVE_TASK_NEEDS_USER", "Review the pending request in chat")
    if (status.optBoolean("terminal")) {
      s.command = null
      toolReply(s, command.callId, "unchanged", "The Agent task already finished (${status.optString("state")}). Report only its actual outcome; submit a new task if needed.")
      return true
    }
    val turn = (status.opt("turnId") as? String)?.takeIf(String::isNotBlank) ?: return false
    val text = command.inputs.joinToString("\n", transform = LiveInput::text)
    val permit = s.permit
    val receipt = delivery.steer(task.request.threadId, turn, text) { permit.valid && command.permit.valid && !locked() }
    if (!current(s, command)) return true
    s.command = null
    val state = receipt.optString("state").ifBlank { "deliveryUnknown" }
    if (task === s.task) showTask(s, task, task.state)
    toolReply(s, command.callId, state, when (state) {
      "accepted" -> "The running task received the change. It has not finished."
      "queued" -> "The task's turn had ended, so the change was queued as its next request."
      "notSent" -> "The change was not delivered (${receipt.optString("code")})."
      else -> "Delivery of the change is unverified. Do not repeat it."
    })
    return true
  }

  /**
   * Relays a spoken answer to the task's pending question. Only a choice among `phone/askUser`
   * options and the explicit cancellation of a `phone/confirmAction` can be given by voice; any other
   * confirmation, approval or secret answer must be given on screen.
   */
  private suspend fun confirm(s: Session, command: Command, task: Task) {
    val request = delivery.pendingRequest(task.request.threadId, task.turnId)
    if (!current(s, command)) return
    s.command = null
    val text = command.inputs.joinToString(" ", transform = LiveInput::text)
    val pending = request ?: return toolReply(s, command.callId, "notSent", "Taku is not waiting for an answer on this task.")
    val arguments = pending.optJSONObject("params")?.optJSONObject("arguments") ?: JSONObject()
    val response = when (val decision = LiveVoiceAnswer.decide(pending.optString("method"), arguments, command.args.opt("optionIndex") as? Int, text)) {
      is LiveVoiceAnswer.Refuse -> return toolReply(s, command.callId, "notSent", decision.message)
      is LiveVoiceAnswer.Answer -> decision.response
    }
    val permit = s.permit
    delivery.answer(task.request.threadId, pending.getString("key"), response) { permit.valid && command.permit.valid && !locked() }
    if (current(s, command)) toolReply(s, command.callId, "answered", "Taku received the answer. Wait for the task to continue.")
  }

  private fun maybeAnnounce(s: Session) {
    val engine = s.engineId ?: return
    if (session !== s || !s.connected || s.view.muted || s.view.speech || s.awaitingResponse || s.view.playback == "speaking" ||
      s.command != null || s.announcement != null) return
    // The session's own task first; then a background task's outcome, each announced once.
    val locked = locked()
    if (s.view.lockScreenMode != locked) update(s) { it.copy(lockScreenMode = locked) }
    // Do Not Disturb or silent mode: a session in standby announces nothing until the user talks again.
    if (s.view.standby != "active" && quietMode()) return
    val task = s.task?.takeIf { it.result != null && it.announcedState != it.state && (!locked || it.lockedNoticeState != it.state) &&
      (it.request.version == s.version || it.cancellationVersion == s.version) }
      ?: s.background.firstOrNull { it.terminal && it.result != null && it.announcedState != it.state && (!locked || it.lockedNoticeState != it.state) } ?: return
    // While locked only a notice without the task's content is spoken; the result follows after unlock.
    val result = if (locked) LOCKED_NOTICE else task.result ?: return
    val offerId = UUID.randomUUID().toString()
    val ipcId = send(s, "audio.live.result", JSONObject().put("sessionId", engine).put("offerId", offerId)
      .put("afterInput", s.afterInput).put("text", result)) ?: return
    s.announcement = Announcement(offerId, ipcId, task, task.state, private = !locked)
  }

  private fun quietMode(): Boolean {
    val notifications = context.getSystemService(android.app.NotificationManager::class.java)
    val audio = context.getSystemService(android.media.AudioManager::class.java)
    return notifications?.currentInterruptionFilter.let { it != null && it != android.app.NotificationManager.INTERRUPTION_FILTER_ALL } ||
      audio?.ringerMode.let { it != null && it != android.media.AudioManager.RINGER_MODE_NORMAL }
  }

  /** Locked or screen off, as the system reports it; the delegation gate and announcements follow it. */
  private fun locked(): Boolean = !org.agentos.agent.home.ScreenState.usable(context)

  /** `hot` or `cold` enters that standby now; `wake` returns to the conversation. */
  fun setStandby(mode: String) {
    require(mode in setOf("hot", "cold", "wake"))
    val s = session ?: return
    val engine = s.engineId ?: throw AssistantException("LIVE_CONNECTING", "Wait for Live Talk to connect")
    if (s.view.muted) throw AssistantException("MIC_MUTED", "Resume the microphone first")
    send(s, "audio.live.standby", JSONObject().put("sessionId", engine).put("mode", mode))
  }

  private fun toolReply(s: Session, callId: String, state: String, message: String, code: String? = null) {
    val engine = s.engineId ?: return
    if (!s.connected || s.view.muted) return
    val result = JSONObject().put("state", state).put("message", message).apply { code?.let { put("code", it) } }
    send(s, "audio.live.tool", JSONObject().put("sessionId", engine).put("callId", callId).put("result", result.toString()))
  }

  private fun explicitCancel(text: String): Boolean = LiveVoiceAnswer.explicitCancel(text)

  companion object {
    val VIDEO_SOURCES = setOf("none", "screen", "camera")
    /** Providers whose engine driver accepts video and silent context. */
    private val VIDEO_PROVIDERS = setOf("moss-live")
    const val DISCLOSURE = "Live Talk sends microphone audio, conversation context, and task details for spoken replies to the provider selected in Taku Audio (MOSS-Live, Grok Voice via fal, or Google Gemini Live)."
    private const val RESPONSE_GRACE_MS = 8_000L
    private val COMMANDS = setOf("submit", "correct", "steer", "cancel", "confirm")
    /** Tasks one Live Talk session tracks at once, including the newest. */
    const val MAX_TASKS = 8
    /** All Live Talk may say about a finished task while the phone is locked. */
    const val UNLOCK_MESSAGE = "This action requires unlocking the phone. Ask the user to unlock it; then repeat the request."
    const val LOCKED_NOTICE = "A background task has finished. Unlock your phone to see the details."
    private const val SNAPSHOT_TIMEOUT_MS = 2_500L
    private val CONNECTION_ERRORS = setOf("NETWORK_FAILED", "ENGINE_UNAVAILABLE", "QUEUE_BACKLOG", "CONNECT_TIMEOUT", "PROVIDER_DISCONNECTED", "SESSION_EXPIRED")
    private val RETRYABLE_REPLACEMENT = setOf("LIVE_TASK_STARTING", "GATEWAY_UNAVAILABLE", "RUNTIME_UNAVAILABLE")
  }
}
