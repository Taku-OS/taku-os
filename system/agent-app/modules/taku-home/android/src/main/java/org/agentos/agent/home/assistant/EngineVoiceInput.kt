package org.agentos.agent.home.assistant

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.agentos.audio.AudioClient
import org.json.JSONObject
import kotlin.math.max

/**
 * [VoiceInput] backed by the shared Audio Engine's DICTATION session. The desktop and Live Talk
 * use the saved scope. Chat dictation uses its saved language with local recognition.
 * Audio, recognition and credentials stay behind the Engine; this app only receives text and the
 * Engine's already normalized PCM level readings.
 *
 * Each instance owns one Engine connection per recording, and the Engine's single microphone
 * lease means starting another [EngineVoiceInput] cancels this one. An operation is identified by
 * a monotonic [VoiceState.operationId]; events may arrive before the open response, so metadata
 * and the session ID are taken from whichever arrives first.
 *
 * [VoiceState.ready] becomes true only on the Engine's `Running` event, i.e. actual capture.
 * Finishing waits for the Engine's own drain deadline (plus a bounded transport grace) instead of
 * a fixed local timeout, and [voiceOutcome] is the only place that decides success.
 *
 * Main thread only.
 */
class EngineVoiceInput(context: Context, private val settingsScope: String, private val localOnly: Boolean = false) : VoiceInput {
  private val app = context.applicationContext
  private val flow = MutableStateFlow(VoiceState())
  override val state: StateFlow<VoiceState> = flow.asStateFlow()

  private val main = Handler(Looper.getMainLooper())
  private var client: AudioClient? = null
  private var generation = 0L
  private var nextOperation = 0L
  private var languageRequest = ""
  private var openRequest = ""
  private var sessionId = ""
  private var lastSequence = 0L
  private var deadline = 0L
  private var deadlineArmed = false
  private val heard = DictationTranscript()
  private val releaseWaiters = mutableListOf<(Boolean) -> Unit>()
  private var releaseRequested = false
  private val releaseTimeout = Runnable { finishRelease(false) }

  private val finishTimeout = Runnable {
    if (flow.value.phase != VoicePhase.Finalizing) return@Runnable
    terminal(VoiceOutcome.Failed("PROCESSING_TIMEOUT", "Recognition did not finish in time"), cancel = true)
  }

  /**
   * A connection bound and handshaken ahead of the next recording. Binding (and creating the
   * Engine service when nothing holds it), then waiting for the Engine's configuration, otherwise
   * runs after the press and delays "Speak now". The next [start] adopts it as its own connection,
   * so each recording still owns exactly one; a spare only binds and never opens a session.
   */
  private class Spare {
    lateinit var client: AudioClient
    /** The adopting recording; until then only the handshake's outcome is kept. */
    var target: AudioClient.Listener? = null
    var configured = false
    var dead = false
  }
  private var spare: Spare? = null
  private var warmUntil = 0L
  private val spareExpiry = Runnable { dropSpare() }

  override val prewarmed: Boolean get() = spare?.let { it.configured && !it.dead } == true

  override fun prewarm() {
    if (!available(app)) return
    warmUntil = android.os.SystemClock.uptimeMillis() + SPARE_IDLE_MS
    main.removeCallbacks(spareExpiry)
    main.postDelayed(spareExpiry, SPARE_IDLE_MS)
    ensureSpare()
  }

  override fun releasePrewarm() {
    warmUntil = 0
    main.removeCallbacks(spareExpiry)
    dropSpare()
  }

  private fun ensureSpare() {
    if (spare?.dead == true) dropSpare()
    // A recording's own connection already keeps the Engine up; the next spare follows its close.
    if (spare != null || client != null || android.os.SystemClock.uptimeMillis() >= warmUntil) return
    val next = Spare()
    next.client = AudioClient(app, object : AudioClient.Listener {
      override fun connected() { next.configured = true; next.target?.connected() }
      override fun event(event: String) { next.target?.event(event) }
      override fun failed(message: String) {
        next.dead = true
        next.target?.failed(message)
        // Idle and broken (Engine died, handshake gave up): forget it; the next start binds anew.
        if (next.target == null && spare === next) dropSpare()
      }
    })
    spare = next
    try { next.client.connect() } catch (_: Exception) { dropSpare() }
  }

  private fun dropSpare() {
    val old = spare ?: return
    spare = null
    if (old.target == null) runCatching { old.client.close() }
  }

  /** The spare for a new recording, or null when there is none worth adopting. */
  private fun takeSpare(): Spare? {
    val taken = spare ?: return null
    spare = null
    if (taken.dead) { runCatching { taken.client.close() }; return null }
    return taken
  }

  override fun start(target: VoiceTarget) {
    if (flow.value.phase != VoicePhase.Idle) cancel()
    active?.takeIf { it !== this }?.cancel()
    active = this
    val run = ++generation
    val operation = ++nextOperation
    heard.reset()
    lastSequence = 0
    sessionId = ""
    languageRequest = ""
    openRequest = ""
    deadline = 0
    deadlineArmed = false
    flow.value = VoiceState(operationId = operation, target = target, phase = VoicePhase.Preparing)
    val listener = object : AudioClient.Listener {
      override fun connected() {
        if (run != generation) return
        try {
          val engine = client ?: return
          val scope = JSONObject().put("settingsScope", settingsScope)
          if (localOnly) languageRequest = engine.call("audio.capabilities", scope)
          else openRequest = engine.open(scope.put("purpose", "DICTATION"))
        } catch (_: Exception) {
          fail("ENGINE_UNAVAILABLE")
        }
      }

      override fun event(event: String) {
        if (run == generation && releaseWaiters.isNotEmpty()) {
          try { handleRelease(JSONObject(event)) } catch (_: Exception) { finishRelease(false) }
          return
        }
        if (run == generation) runCatching { handle(JSONObject(event)) }.onFailure { fail("ENGINE_UNAVAILABLE") }
      }

      override fun failed(message: String) {
        if (run == generation && releaseWaiters.isNotEmpty()) { finishRelease(false); return }
        if (run == generation) fail("ENGINE_UNAVAILABLE")
      }
    }
    val adopted = takeSpare()
    if (adopted != null) {
      client = adopted.client
      adopted.target = listener
      // Already handshaken: open at once. Otherwise its handshake completes into this recording.
      if (adopted.configured) listener.connected()
      return
    }
    val connection = AudioClient(app, listener)
    client = connection
    try {
      connection.connect()
    } catch (_: Exception) {
      fail("ENGINE_UNAVAILABLE")
    }
  }

  override fun finish() {
    val s = flow.value
    when (s.phase) {
      // Released before capture: no future finish, no delayed recording.
      VoicePhase.Preparing -> cancel()
      VoicePhase.Capturing -> {
        flow.value = s.copy(phase = VoicePhase.Finalizing)
        val id = sessionId
        if (id.isEmpty()) return fail("ENGINE_UNAVAILABLE")
        runCatching { client?.finish(id) }.onFailure { fail("ENGINE_UNAVAILABLE") }
        arm(null)
      }
      else -> Unit
    }
  }

  override fun cancel() {
    if (flow.value.phase == VoicePhase.Idle) return
    closeTransport(cancelSession = true)
    flow.value = VoiceState()
  }

  private fun releaseForLive(released: (Boolean) -> Unit) {
    if (client == null || openRequest.isEmpty()) { cancel(); released(true); return }
    releaseWaiters.add(released)
    if (releaseWaiters.size != 1) return
    main.removeCallbacks(finishTimeout)
    flow.value = VoiceState()
    main.postDelayed(releaseTimeout, TRANSPORT_GRACE_MS)
    requestRelease()
  }

  private fun requestRelease() {
    if (sessionId.isEmpty() || releaseRequested) return
    releaseRequested = true
    runCatching { client?.cancel(sessionId) }.onFailure { finishRelease(false) }
  }

  private fun handleRelease(message: JSONObject) {
    if (message.optString("type") == "response") {
      if (message.optString("id") != openRequest) return
      if (!message.optBoolean("ok")) { finishRelease(true); return }
      sessionId = message.getJSONObject("result").getString("sessionId")
      requestRelease()
      return
    }
    val data = message.optJSONObject("data") ?: return
    val id = data.optString("sessionId")
    if (id.isEmpty() || sessionId.isNotEmpty() && sessionId != id) return
    sessionId = id
    if (data.optString("kind") == "Closed") finishRelease(data.optBoolean("restored")) else requestRelease()
  }

  private fun finishRelease(released: Boolean) {
    val waiters = releaseWaiters.toList()
    releaseWaiters.clear()
    releaseRequested = false
    main.removeCallbacks(releaseTimeout)
    closeTransport(cancelSession = !released)
    waiters.forEach { it(released) }
  }

  private fun handle(message: JSONObject) {
    if (message.optString("type") == "response") {
      if (languageRequest.isNotEmpty() && message.optString("id") == languageRequest) {
        languageRequest = ""
        val view = message.optJSONObject("result")?.optJSONObject("dictation")?.optJSONObject(settingsScope)
        val resolved = view?.opt("resolvedLanguage") as? String
        if (resolved != "en-US" && resolved != "zh-CN") {
          val code = view?.optJSONObject("error")?.opt("code") as? String
            ?: message.optJSONObject("error")?.opt("code") as? String
            ?: "ENGINE_UNAVAILABLE"
          return fail(code)
        }
        val params = JSONObject().put("purpose", "DICTATION").put("profileId", "zipformer").put("language", resolved)
        openRequest = client?.open(params) ?: return fail("ENGINE_UNAVAILABLE")
        return
      }
      if (message.optString("id") != openRequest) return
      if (!message.optBoolean("ok")) {
        return fail(message.optJSONObject("error")?.optString("code")?.ifBlank { null } ?: "ENGINE_UNAVAILABLE")
      }
      val result = message.optJSONObject("result") ?: return
      result.optString("sessionId").takeIf { it.isNotEmpty() }?.let { sessionId = it }
      val merged = VoiceSessionInfo.from(result, flow.value.session)
      if (flow.value.phase != VoicePhase.Idle) flow.value = flow.value.copy(sessionId = sessionId.ifEmpty { null }, session = merged)
      return
    }
    val data = message.optJSONObject("data") ?: return
    val sequence = data.optLong("sequence")
    if (sequence > 0) {
      if (sequence <= lastSequence) return
      lastSequence = sequence
    }
    val id = data.optString("sessionId")
    if (sessionId.isEmpty()) sessionId = id else if (id != sessionId) return
    if (flow.value.phase == VoicePhase.Idle) return
    val merged = VoiceSessionInfo.from(data, flow.value.session)
    val base = flow.value.copy(sessionId = sessionId.ifEmpty { null }, session = merged)
    when (data.optString("kind")) {
      "Running" -> flow.value = if (base.phase == VoicePhase.Preparing) base.copy(phase = VoicePhase.Capturing) else base
      "SourceEnded", "Draining" -> enterFinalizing(base, data)
      "Level" -> if (base.phase == VoicePhase.Capturing) {
        // Already normalized by the Engine; this is measured PCM, so silence reads zero and a
        // missing or malformed value leaves the history alone instead of inventing motion.
        val level = data.optDouble("level", Double.NaN)
        if (!level.isNaN()) {
          flow.value = base.copy(levels = (base.levels + level.coerceIn(0.0, 1.0).toFloat()).takeLast(VoiceState.LEVEL_HISTORY))
        }
      }
      "Text" -> {
        val text = data.optString("text")
        flow.value = base.copy(preview = heard.apply(text, data.optBoolean("final"), data.optLong("segmentId"), sequence))
      }
      "Closed" -> {
        flow.value = base
        val reason = data.optString("reason").ifBlank { "ENGINE_UNAVAILABLE" }
        val outcome = voiceOutcome(reason, data.optString("completion"), data.optBoolean("restored", false), heard.text)
        terminal(outcome, cancel = outcome is VoiceOutcome.Failed)
      }
      else -> flow.value = base
    }
  }

  /** Capture stopped on its own (the backend limit) or the Engine entered Draining: wait, don't send. */
  private fun enterFinalizing(base: VoiceState, data: JSONObject) {
    when (base.phase) {
      VoicePhase.Capturing, VoicePhase.Preparing -> {
        flow.value = base.copy(phase = VoicePhase.Finalizing)
      }
      VoicePhase.Finalizing -> flow.value = base
      else -> { flow.value = base; return }
    }
    // Manual finish and SourceEnded both enter Finalizing before the Engine's Draining arrives;
    // the absolute deadline must still shorten the client watchdog on those paths.
    val incoming = if (data.has("drainDeadlineMonotonicMs") && !data.isNull("drainDeadlineMonotonicMs")) data.optLong("drainDeadlineMonotonicMs") else null
    arm(incoming)
  }

  private fun arm(incoming: Long?, timeoutMs: Long = flow.value.session?.drainTimeoutMs ?: DEFAULT_DRAIN_MS) {
    val now = monotonicMs()
    val current = if (deadlineArmed) deadline else null
    val at = settleFinishDeadline(current, incoming, now, timeoutMs)
    if (current != null && at >= current) return
    deadline = at
    deadlineArmed = true
    main.removeCallbacks(finishTimeout)
    main.postDelayed(finishTimeout, max(0L, at - now) + TRANSPORT_GRACE_MS)
  }

  private fun fail(code: String) = terminal(VoiceOutcome.Failed(code), cancel = true)

  /** Recognition is over: keep the assembled text, publish the outcome and let the Engine go. */
  private fun terminal(outcome: VoiceOutcome, cancel: Boolean) {
    val s = flow.value
    if (s.phase == VoicePhase.Closed || s.phase == VoicePhase.Idle) return
    closeTransport(cancelSession = cancel)
    flow.value = s.copy(phase = VoicePhase.Closed, outcome = outcome, preview = heard.text)
  }

  private fun closeTransport(cancelSession: Boolean) {
    generation++
    main.removeCallbacks(finishTimeout)
    val connection = client
    client = null
    if (connection != null) {
      if (cancelSession && sessionId.isNotEmpty()) runCatching { connection.cancel(sessionId) }
      runCatching { connection.close() }
    }
    languageRequest = ""
    openRequest = ""
    sessionId = ""
    deadline = 0
    deadlineArmed = false
    if (active === this) active = null
    // Still in the warm window (the home is in front): have the next hold's connection ready too.
    if (connection != null && warmUntil > android.os.SystemClock.uptimeMillis()) main.post { ensureSpare() }
  }

  companion object {
    /** The Engine's local dictation drain bound, used until the session reports its own. */
    private const val DEFAULT_DRAIN_MS = 5_000L
    /** Bounded transport grace after the Engine's drain deadline. */
    private const val TRANSPORT_GRACE_MS = 3_000L
    /**
     * How long a [prewarm] keeps a spare connection without another one. Bounded because a bound
     * Engine stays alive and keeps its configuration subscription (and the broker's periodic
     * snapshot) running; the entry's touch renews it.
     */
    private const val SPARE_IDLE_MS = 60_000L
    private val SERVICE = ComponentName("org.agentos.audio", "org.agentos.audio.AudioEngineService")

    /** The instance currently holding the microphone, across the home and the chat. */
    internal fun releaseActive(released: (Boolean) -> Unit) {
      active?.releaseForLive(released) ?: released(true)
    }

    private var active: EngineVoiceInput? = null

    /** The engine is installed and its service resolves (dev images may omit it). */
    fun available(context: Context): Boolean =
      context.packageManager.resolveService(Intent().setComponent(SERVICE), 0) != null

    /** Same clock the Engine runtime stamps its drain deadlines with. */
    fun monotonicMs(): Long = android.os.SystemClock.elapsedRealtime()
  }
}

/**
 * The absolute wait for one finalization. The first observation fixes it and a later one may only
 * shorten it, so a late `Draining` event can never start a fresh, longer interval.
 */
internal fun settleFinishDeadline(current: Long?, incoming: Long?, now: Long, timeoutMs: Long): Long = when {
  incoming != null -> current?.let { minOf(it, incoming) } ?: incoming
  current != null -> current
  else -> now + timeoutMs
}

/**
 * The running dictation text from Engine `Text` events. A partial result replaces the open
 * segment, a final one commits it once; sequence numbers and segment IDs make late or duplicated
 * events harmless. Chinese segments run together; other scripts get a space between them.
 */
class DictationTranscript {
  private var committed = ""
  private var partial = ""
  private var lastSequence = 0L
  private var openSegment: Long? = null
  private val committedSegments = HashSet<Long>()
  val text: String get() = join(committed, partial)

  /**
   * [segmentId] is the Engine's segment (-1 when the caller has none); [sequence] its session
   * event sequence (0 when absent). Older sequences and already committed segments are ignored.
   */
  fun apply(text: String, final: Boolean, segmentId: Long = -1, sequence: Long = 0): String {
    if (sequence > 0) {
      if (sequence <= lastSequence) return this.text
      lastSequence = sequence
    }
    val value = text.trim()
    if (segmentId < 0) {
      if (final) {
        committed = join(committed, value)
        partial = ""
      } else {
        partial = value
      }
      return this.text
    }
    if (segmentId in committedSegments) return this.text
    if (final) {
      committedSegments += segmentId
      committed = join(committed, value)
      partial = ""
      openSegment = segmentId + 1
      return this.text
    }
    val open = openSegment
    if (open != null && segmentId < open) return this.text
    openSegment = segmentId
    partial = value
    return this.text
  }

  fun reset() {
    committed = ""
    partial = ""
    lastSequence = 0
    openSegment = null
    committedSegments.clear()
  }

  companion object {
    fun join(a: String, b: String): String {
      if (a.isEmpty()) return b
      if (b.isEmpty()) return a
      val han = { c: Char -> Character.UnicodeScript.of(c.code) == Character.UnicodeScript.HAN }
      return if (han(a.last()) || han(b.first())) a + b else "$a $b"
    }
  }
}
