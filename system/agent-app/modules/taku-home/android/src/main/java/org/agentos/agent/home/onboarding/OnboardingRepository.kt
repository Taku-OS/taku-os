package org.agentos.agent.home.onboarding

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.UserManager
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.agentos.agent.home.assistant.AgentGateway
import org.agentos.agent.home.assistant.GatewayException
import org.json.JSONObject

/**
 * The one onboarding state for this Android user, shared by the Home card and Agents
 * (`content://org.agentos.agent.home-onboarding`). Sources of truth:
 * - progress: `Settings.Secure` [OnboardingRules.SETTING] (per user; cleared by factory reset);
 * - AI and ChatGPT, including a pending device code: the broker account view through the
 *   assistant gateway (`readAccount`);
 * - Jev: the assistant's Jev switch and decision-model binding (gateway `readJev`);
 * - Fal: Taku Audio's key status (`content://org.agentos.audio.open-access` `settings`);
 * - name: Memory `userName` (`content://org.agentos.memory.open-access`).
 * Blocking methods run on the caller's thread: an IO dispatcher or a provider binder thread.
 */
class OnboardingRepository private constructor(context: Context) {
  /** A one-off message for the screen setting up [service], such as why its connection failed. */
  data class Notice(val service: OnboardingService, val text: String)

  data class State(
    val facts: OnboardingFacts,
    val decision: OnboardingDecision,
    val userName: String?,
    val services: Services,
    /** The device code and official URL while a ChatGPT sign-in waits for them; else null. */
    val signIn: DeviceCodeSignIn?,
  ) {
    val step: OnboardingStep? get() = (decision as? OnboardingDecision.Show)?.step
    val canFinish: Boolean get() = step == OnboardingStep.CONNECT_AI && OnboardingRules.canFinish(facts)
  }

  private val app = context.applicationContext
  private val resolver = app.contentResolver
  private val gateway = AgentGateway(resolver)
  // A failed background read or write is logged; it must never take Home down.
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e -> Log.w(TAG, "onboarding update failed", e) })
  private val lock = Any()
  private val island = OnboardingIsland(app)

  @Volatile private var ai = AiAccess.UNKNOWN
  @Volatile private var services = Services.UNKNOWN
  @Volatile private var signIn: DeviceCodeSignIn? = null
  /** Guards read-modify-write of [ai], [services] and [settledAt] from concurrent readers. */
  private val factsLock = Any()
  /** When the account last gave a settled answer ([SystemClock.elapsedRealtime]). */
  private var settledAt = 0L
  /** Consecutive account reads the gateway could not answer; slows the poll down. */
  @Volatile private var accountFailures = 0
  @Volatile private var userName: String? = null
  @Volatile private var nameKnown = false
  @Volatile private var foreground = false

  private val mutable = MutableStateFlow(snapshotOf(facts()))
  val state: StateFlow<State> = mutable
  private val noticeFlow = MutableSharedFlow<Notice>(extraBufferCapacity = 4)
  /** One-off messages, each for the screen setting up its service (why a sign-in or a Jev key did not connect). */
  val notices: SharedFlow<Notice> = noticeFlow

  private var poll: Job? = null
  private var signInWatch: Job? = null
  /** Finishes a saved Jev key whose model Magpie had not listed yet ([finishJev]). */
  @Volatile private var jevWatch: Job? = null

  init {
    val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
      override fun onChange(selfChange: Boolean) { scope.launch {
        publish()
        // A completed run stops polling. Replaying it must resume real account updates.
        if (foreground && poll?.isActive != true) setForeground(true)
      } }
    }
    for (uri in listOf(
      Settings.Secure.getUriFor(OnboardingRules.SETTING),
      Settings.Secure.getUriFor(OnboardingRules.TEST_SETTING),
      Settings.Secure.getUriFor(USER_SETUP_COMPLETE),
      Settings.Global.getUriFor(Settings.Global.DEVICE_PROVISIONED),
    )) resolver.registerContentObserver(uri, false, observer)
    scope.launch { refresh() }
  }

  /**
   * Home in the foreground: re-read now, then poll while onboarding is pending, so the card
   * appears as soon as every condition is confirmed. The island pill is only for while Home is
   * away; the card itself shows progress when Home is visible.
   */
  fun setForeground(foreground: Boolean) {
    this.foreground = foreground
    if (foreground) island.clear()
    poll?.cancel()
    poll = if (!foreground) null else scope.launch {
      runCatching { refresh() }.onFailure { Log.w(TAG, "onboarding refresh failed", it) }
      while (isActive && !OnboardingRules.isTerminal(state.value.facts.stored)) {
        // An unreachable gateway (no assistant on this build, broker down) backs off to 1 min.
        delay((POLL_MS shl accountFailures.coerceAtMost(4)).coerceAtMost(MAX_POLL_MS))
        runCatching { refresh() }.onFailure { Log.w(TAG, "onboarding refresh failed", it) }
      }
    }
  }

  /**
   * While onboarding is pending, re-reads the authoritative sources, then publishes. Completed
   * costs nothing, and "all set" needs no account read (its card does not depend on AI). [full]
   * (Agents' [describe]) reads every source once, whatever the step.
   */
  fun refresh(full: Boolean = false): State {
    val stored = stored()
    val pending = !OnboardingRules.isTerminal(stored) && stored != OnboardingStep.ALL_SET
    if (full || pending) readAccount()
    if (full || stored == OnboardingStep.CONNECT_AI) {
      // A saved key still waiting for its model stays "connecting", not "not connected".
      val jev = readJev().let { if (it != Connection.CONNECTED && jevWatch?.isActive == true) Connection.CONNECTING else it }
      val fal = readFal()
      updateServices { it.copy(jev = jev, fal = fal) }
    }
    // Read the name once; re-read it only while the card shows the intro or name step (an Agent
    // may set it there). A hidden card does not call Memory on every poll.
    val shown = (OnboardingRules.decide(facts()) as? OnboardingDecision.Show)?.step
    if (!OnboardingRules.isTerminal(stored) && (!nameKnown || shown == OnboardingStep.INTRO || shown == OnboardingStep.NAME)) readName()
    return publish()
  }

  /** Runs one card action exactly as the GUI does; returns the new state. */
  fun perform(action: OnboardingAction, rawName: String? = null): State = synchronized(lock) {
    val facts = facts()
    val step = shownStep(facts)
    var next = OnboardingRules.advance(step, action)
    when (action) {
      OnboardingAction.SUBMIT_NAME -> saveName(OnboardingRules.name(rawName))
      OnboardingAction.SET_UP -> readName()
      OnboardingAction.SKIP_NAME, OnboardingAction.EXPLORE -> Unit
      OnboardingAction.FINISH -> if (!OnboardingRules.canFinish(facts))
        throw OnboardingException("AI_NOT_CONNECTED", "connect ChatGPT (or Magpie) before finishing")
    }
    next = OnboardingRules.resolve(next, nameSet(), facts.testMode)
    write(next)
    publish()
  }

  /**
   * Connects one service from the AI step, through the same operation as its settings page:
   * ChatGPT starts device-code sign-in and returns at once with the official URL and one-time
   * code in [State.signIn] (nothing opens: the card shows them, to use on another device); Jev
   * and Fal save the pasted key. Jev with no key switches on a Jev model Magpie already serves
   * (`NO_DECISION_MODEL` when there is none). A Jev key is saved even when Magpie has not listed
   * its model yet (the broker waits about 30 s): the chip then stays "connecting" while
   * [finishJev] keeps trying, rather than failing a key that will work in a moment.
   */
  fun connect(service: OnboardingService, rawKey: String? = null): State {
    if (shownStep(facts()) != OnboardingStep.CONNECT_AI)
      throw OnboardingException("WRONG_STEP", "services are connected on the connect_ai step")
    when (service) {
      OnboardingService.CHATGPT -> {
        val result = call("connectChatGpt")
        if (result.optString("phase") == "pending") {
          val challenge = OnboardingRules.deviceCode(result.optString("url"), result.optString("code"))
          // The card then shows only Cancel; the broker's URL is not one the user should be sent to.
          if (challenge == null) Log.w(TAG, "device-code challenge missing or not on an OpenAI sign-in host")
          synchronized(factsLock) {
            signIn = challenge
            services = services.copy(chatgpt = Connection.CONNECTING)
          }
          publish()
          watchSignIn()
        }
      }
      OnboardingService.JEV, OnboardingService.FAL -> connectKeyed(service, rawKey)
    }
    return refresh()
  }

  /**
   * The Jev or Fal setup page reopened after onboarding (a GUI card that Jev could not compose, or
   * voice input that needs a Fal key): the same operations as [connect], with the same validation,
   * at any step. ChatGPT is not offered here; it has its own account page.
   */
  fun setUp(service: OnboardingService, rawKey: String?): Connection {
    if (service == OnboardingService.CHATGPT) throw OnboardingException("INVALID_SERVICE", "set up jev or fal here; chatgpt has its own account page")
    connectKeyed(service, rawKey)
    return serviceStatus(service)
  }

  /**
   * The current Jev or Fal connection, read from its authority. A saved Jev key whose model is
   * still being listed is `connecting`; Fal is `unknown` when Taku Audio is not installed.
   */
  fun serviceStatus(service: OnboardingService): Connection {
    val status = when (service) {
      OnboardingService.JEV -> readJev().let { if (it != Connection.CONNECTED && jevWatch?.isActive == true) Connection.CONNECTING else it }
      OnboardingService.FAL -> readFal() ?: Connection.UNKNOWN
      OnboardingService.CHATGPT -> throw OnboardingException("INVALID_SERVICE", "set up jev or fal here; chatgpt has its own account page")
    }
    updateServices { if (service == OnboardingService.JEV) it.copy(jev = status) else if (it.fal != null) it.copy(fal = status) else it }
    return status
  }

  /**
   * Whether Jev has a decision model bound (gateway `readJev` `model`), switched on or not. A bound
   * Jev that is off was turned off by the user; only an unbound one is "not set up".
   */
  fun jevBound(): Boolean = try {
    gateway.call("readJev").optString("model").isNotEmpty()
  } catch (e: GatewayException) {
    Log.w(TAG, "jev status unavailable: ${e.code}")
    // Unknown: treat as bound, so nothing is switched on without the user asking.
    true
  }

  /** Jev: the pasted key, or with none a Jev model Magpie already serves. Fal: the pasted key. */
  private fun connectKeyed(service: OnboardingService, rawKey: String?) {
    when (service) {
      OnboardingService.JEV -> if (rawKey == null) call("enableJev") else connectJev(OnboardingRules.jevKey(rawKey))
      OnboardingService.FAL -> saveFal(OnboardingRules.key(rawKey))
      OnboardingService.CHATGPT -> Unit
    }
  }

  /**
   * Saves a Jev key and switches Jev on. The chip shows working dots for the whole wait. When the
   * key is saved but its model is not listed yet (`NO_DECISION_MODEL`, or `MAGPIE_UNAVAILABLE`
   * when Magpie's catalog did not answer), this returns normally and [finishJev] takes over.
   */
  private fun connectJev(key: String) {
    jevWatch?.cancel()
    updateServices { it.copy(jev = Connection.CONNECTING) }
    publish()
    try {
      call("connectJev", JSONObject().put("key", key))
    } catch (e: OnboardingException) {
      if (e.code != "NO_DECISION_MODEL" && e.code != "MAGPIE_UNAVAILABLE") {
        // Not connecting after all: say so at once (the gateway read stays outside factsLock).
        val jev = readJev()
        updateServices { it.copy(jev = jev) }
        publish()
        throw e
      }
      Log.i(TAG, "jev key saved; waiting for its model (${e.code})")
      finishJev()
    }
  }

  /**
   * Keeps switching Jev on with the model Magpie serves (gateway `enableJev`) until one is listed,
   * for up to [JEV_WAIT_MS]; then says why it did not connect, once.
   */
  private fun finishJev() {
    jevWatch = scope.launch {
      val deadline = SystemClock.elapsedRealtime() + JEV_WAIT_MS
      var last = "NO_DECISION_MODEL"
      while (isActive && SystemClock.elapsedRealtime() < deadline) {
        delay(JEV_POLL_MS)
        try {
          call("enableJev")
          updateServices { it.copy(jev = Connection.CONNECTED) }
          publish()
          return@launch
        } catch (e: OnboardingException) {
          last = e.code
        }
      }
      if (!isActive) return@launch
      updateServices { it.copy(jev = Connection.NOT_CONNECTED) }
      publish()
      noticeFlow.tryEmit(Notice(OnboardingService.JEV, if (last == "MAGPIE_UNAVAILABLE") JEV_NO_MAGPIE else JEV_NO_MODEL))
    }
  }

  /** Cancels a pending ChatGPT sign-in, as the account page's "Cancel this sign-in". */
  fun cancelSignIn(): State {
    call("cancelChatGpt")
    signInWatch?.cancel()
    synchronized(factsLock) { signIn = null }
    island.clear()
    return refresh()
  }

  /** Exit immediately without calling the broker, changing credentials or waiting for a key save. */
  fun dismiss(): State = synchronized(lock) {
    write(OnboardingRules.dismiss(stored()))
    poll?.cancel()
    signInWatch?.cancel()
    island.clear()
    publish()
  }

  /** Start over without clearing accounts or Memory. Test mode keeps the intro visible. */
  fun reset(): State = synchronized(lock) {
    write(OnboardingStep.INTRO)
    refresh().also { if (foreground) setForeground(true) }
  }

  /** The authoritative snapshot for Agents; dismiss can return local progress without external reads. */
  fun describe(refreshSources: Boolean = true): JSONObject {
    val s = if (refreshSources) refresh(full = true) else publish()
    val decision = s.decision
    return JSONObject()
      .put("setting", OnboardingRules.SETTING)
      .put("testModeSetting", OnboardingRules.TEST_SETTING)
      .put("testMode", s.facts.testMode)
      .put("stored", s.facts.stored?.id ?: JSONObject.NULL)
      .put("visible", decision is OnboardingDecision.Show)
      .put("step", s.step?.id ?: JSONObject.NULL)
      .put("hiddenReason", (decision as? OnboardingDecision.Hidden)?.reason ?: JSONObject.NULL)
      .put("phoneReady", s.facts.phoneReady)
      .put("ai", s.facts.ai.id)
      .put("nameSet", s.facts.nameSet ?: JSONObject.NULL)
      .put("canFinish", s.canFinish)
      .put("canDismiss", s.step == OnboardingStep.CONNECT_AI)
      .put("services", JSONObject()
        .put("chatgpt", s.services.chatgpt.id)
        .put("jev", s.services.jev.id)
        .put("fal", s.services.fal?.id ?: JSONObject.NULL))
      .put("chatgptSignIn", s.signIn?.let { JSONObject().put("url", it.url).put("code", it.code) } ?: JSONObject.NULL)
  }

  private fun shownStep(facts: OnboardingFacts): OnboardingStep = when (val decision = OnboardingRules.decide(facts)) {
    is OnboardingDecision.Show -> decision.step
    is OnboardingDecision.Hidden -> throw OnboardingException(
      when (decision.reason) {
        OnboardingRules.HIDDEN_PHONE -> "PHONE_NOT_READY"
        OnboardingRules.HIDDEN_AI_UNKNOWN -> "AI_STATUS_UNKNOWN"
        else -> "ALREADY_COMPLETED"
      },
      "onboarding is not showing (${decision.reason})",
    )
  }

  /**
   * Follows a device-code sign-in the user completes on another device: the card shows the code
   * while Home is in front; if they leave Home meanwhile, the island shows "Signing in…" and a
   * brief "Connected" when it lands, and the assistant brings Home back on success.
   */
  private fun watchSignIn() {
    signInWatch?.cancel()
    signInWatch = scope.launch {
      val deadline = SystemClock.elapsedRealtime() + SIGN_IN_MS
      try {
        while (isActive && !OnboardingRules.isTerminal(stored()) && SystemClock.elapsedRealtime() < deadline) {
          delay(SIGN_IN_POLL_MS)
          runCatching { readAccount(); publish() }
          if (!isActive || OnboardingRules.isTerminal(stored())) return@launch
          when (services.chatgpt) {
            Connection.CONNECTING, Connection.UNKNOWN -> if (foreground) island.clear() else island.signingIn()
            Connection.CONNECTED -> {
              // The pill is for while Home is away; in front, the card itself turns connected.
              if (!foreground) {
                island.connected()
                delay(CONNECTED_PILL_MS)
              }
              return@launch
            }
            Connection.NOT_CONNECTED -> return@launch
          }
        }
      } finally {
        island.clear()
      }
    }
  }

  private fun publish(): State = synchronized(lock) {
    var facts = facts()
    if (OnboardingRules.shouldComplete(facts)) {
      write(OnboardingStep.COMPLETED)
      facts = facts()
    } else {
      // Persist a skipped step so Agents and a later resume see where the card really is.
      val shown = (OnboardingRules.decide(facts) as? OnboardingDecision.Show)?.step
      if (shown != null && facts.stored != null && shown != facts.stored) { write(shown); facts = facts() }
    }
    return snapshotOf(facts).also { mutable.value = it }
  }

  private fun snapshotOf(facts: OnboardingFacts) =
    State(facts, OnboardingRules.decide(facts), userName.takeIf { OnboardingRules.isNameSet(it) }, services,
      signIn.takeIf { services.chatgpt == Connection.CONNECTING })

  private fun facts() = OnboardingFacts(stored(), phoneReady(), ai, nameSet(),
    Settings.Secure.getString(resolver, OnboardingRules.TEST_SETTING) == "1")

  private fun nameSet(): Boolean? = if (nameKnown) OnboardingRules.isNameSet(userName) else null

  private fun stored(): OnboardingStep? = OnboardingStep.parse(Settings.Secure.getString(resolver, OnboardingRules.SETTING))

  private fun write(step: OnboardingStep) {
    val saved = try {
      Settings.Secure.putString(resolver, OnboardingRules.SETTING, step.id)
    } catch (e: SecurityException) {
      Log.w(TAG, "cannot write ${OnboardingRules.SETTING}", e)
      false
    }
    if (!saved) throw OnboardingException("SETTINGS_WRITE_FAILED", "could not save ${OnboardingRules.SETTING}")
  }

  private fun phoneReady(): Boolean {
    val provisioned = Settings.Global.getInt(resolver, Settings.Global.DEVICE_PROVISIONED, 0) == 1
    // Unreadable means unconfirmed, and unconfirmed keeps the card hidden.
    val setupComplete = try {
      Settings.Secure.getInt(resolver, USER_SETUP_COMPLETE, 0) == 1
    } catch (e: SecurityException) {
      Log.w(TAG, "cannot read $USER_SETUP_COMPLETE", e)
      false
    }
    val unlocked = app.getSystemService(UserManager::class.java)?.isUserUnlocked ?: true
    return provisioned && setupComplete && unlocked
  }

  /**
   * AI availability and the ChatGPT chip from one account read; reports a failed sign-in once.
   * An unsettled or failed read keeps the last settled answer for [UNSETTLED_GRACE_MS], so one
   * busy gateway call or a broker restart does not hide a card the user is working in. Longer
   * than that, it is unknown again (and an unknown status never shows the card).
   */
  private fun readAccount() {
    val account = try {
      gateway.call("readAccount").also { accountFailures = 0 }
    } catch (e: GatewayException) {
      Log.w(TAG, "account status unavailable: ${e.code}")
      accountFailures = (accountFailures + 1).coerceAtMost(8)
      synchronized(factsLock) { if (!withinGrace()) { ai = AiAccess.UNKNOWN; services = services.copy(chatgpt = Connection.UNKNOWN); signIn = null } }
      return
    }
    val phase = account.optString("phase", "loading")
    val read = when {
      account.optBoolean("aiAvailable") -> AiAccess.AVAILABLE
      // Still reading, or a finished sign-in being confirmed: not settled either way.
      phase in UNSETTLED_PHASES -> AiAccess.UNKNOWN
      else -> AiAccess.UNAVAILABLE
    }
    val chatgpt = when {
      account.optBoolean("chatgptConnected") -> Connection.CONNECTED
      phase in SIGNING_IN_PHASES -> Connection.CONNECTING
      phase == "loading" -> Connection.UNKNOWN
      else -> Connection.NOT_CONNECTED
    }
    // Read the previous chip and write the new one atomically, so two concurrent reads of the
    // same failure report it once and neither overwrites the other's Jev or Fal update.
    val failed = synchronized(factsLock) {
      val previous = services.chatgpt
      if (read != AiAccess.UNKNOWN) { ai = read; settledAt = SystemClock.elapsedRealtime() }
      else if (!withinGrace()) ai = AiAccess.UNKNOWN
      if (chatgpt != Connection.UNKNOWN || !withinGrace()) {
        services = services.copy(chatgpt = chatgpt)
        // Only "pending" carries a code; starting, verifying and every settled phase clear it.
        signIn = if (phase == "pending") OnboardingRules.deviceCode(account.optString("url"), account.optString("code")) else null
      }
      previous == Connection.CONNECTING && phase == "failed"
    }
    if (failed) noticeFlow.tryEmit(Notice(OnboardingService.CHATGPT, account.optString("message").ifBlank { FAILED }))
  }

  /** A settled account answer is recent enough to stand in for an unsettled or failed read. Under [factsLock]. */
  private fun withinGrace(): Boolean = ai != AiAccess.UNKNOWN && SystemClock.elapsedRealtime() - settledAt < UNSETTLED_GRACE_MS

  private fun updateServices(change: (Services) -> Services) = synchronized(factsLock) { services = change(services) }

  private fun readJev(): Connection = try {
    if (gateway.call("readJev").optBoolean("ready")) Connection.CONNECTED else Connection.NOT_CONNECTED
  } catch (e: GatewayException) {
    Log.w(TAG, "jev status unavailable: ${e.code}")
    Connection.UNKNOWN
  }

  /** Null when Taku Audio is not installed (component builds): the Fal chip is hidden. */
  private fun readFal(): Connection? {
    if (app.packageManager.resolveContentProvider(AUDIO.authority!!, 0) == null) return null
    return try {
      if (audio(JSONObject().put("action", "read")).optJSONObject("fal")?.optBoolean("configured") == true) Connection.CONNECTED
      else Connection.NOT_CONNECTED
    } catch (e: OnboardingException) {
      Log.w(TAG, "fal status unavailable: ${e.code}")
      Connection.UNKNOWN
    }
  }

  private fun saveFal(key: String) {
    audio(JSONObject().put("action", "saveFalKey").put("key", key))
  }

  private fun call(action: String, params: JSONObject = JSONObject()): JSONObject = try {
    gateway.call(action, params)
  } catch (e: GatewayException) {
    throw OnboardingException(e.code, e.message ?: e.code)
  }

  private fun audio(arg: JSONObject): JSONObject {
    val bundle = try {
      resolver.call(AUDIO, "settings", arg.toString(), null)
    } catch (e: RuntimeException) {
      throw OnboardingException("AUDIO_UNAVAILABLE", e.message ?: "Taku Audio is unavailable")
    } ?: throw OnboardingException("AUDIO_UNAVAILABLE", "Taku Audio is unavailable")
    val envelope = envelope(bundle.getString("result"), "AUDIO_UNAVAILABLE", "Taku Audio")
    if (!envelope.optBoolean("ok")) {
      val code = envelope.optString("code").ifBlank { "KEY_NOT_SAVED" }
      throw OnboardingException(if (code == "INVALID_ARGUMENT") "INVALID_KEY" else code, envelope.optString("message", code))
    }
    return envelope.optJSONObject("result") ?: JSONObject()
  }

  private fun readName() {
    try {
      val status = memory("status", JSONObject())
      userName = status.optString("userName")
      nameKnown = true
    } catch (e: OnboardingException) {
      Log.w(TAG, "memory status unavailable: ${e.message}")
    }
  }

  private fun saveName(name: String) {
    val config = try {
      memory("configure", JSONObject().put("userName", name))
    } catch (e: OnboardingException) {
      throw OnboardingException("NAME_NOT_SAVED", e.message ?: "Memory did not save the name")
    }
    userName = config.optString("userName", name)
    nameKnown = true
  }

  private fun memory(method: String, arg: JSONObject): JSONObject {
    val bundle = try {
      resolver.call(MEMORY, method, arg.toString(), null)
    } catch (e: RuntimeException) {
      throw OnboardingException("MEMORY_UNAVAILABLE", e.message ?: "Memory is unavailable")
    } ?: throw OnboardingException("MEMORY_UNAVAILABLE", "Memory is unavailable")
    val envelope = envelope(bundle.getString("result"), "MEMORY_UNAVAILABLE", "Memory")
    if (!envelope.optBoolean("ok")) throw OnboardingException("MEMORY_ERROR", envelope.optString("message", "Memory rejected the request"))
    return envelope.optJSONObject("result") ?: JSONObject()
  }

  /** A provider's `{ok, result}` envelope; missing or malformed is [code], never a raw JSON error. */
  private fun envelope(raw: String?, code: String, source: String): JSONObject = try {
    JSONObject(raw ?: throw OnboardingException(code, "$source returned nothing"))
  } catch (e: org.json.JSONException) {
    throw OnboardingException(code, "$source returned an invalid result")
  }

  companion object {
    private const val TAG = "TakuOnboarding"
    /** `Settings.Secure.USER_SETUP_COMPLETE` (hidden constant; per user). */
    private const val USER_SETUP_COMPLETE = "user_setup_complete"
    private const val POLL_MS = 3_000L
    private const val MAX_POLL_MS = 60_000L
    /** How long a settled account answer outlives failed or unsettled reads (a few polls). */
    private const val UNSETTLED_GRACE_MS = 15_000L
    private const val SIGN_IN_POLL_MS = 1_500L
    private const val SIGN_IN_MS = 15 * 60_000L
    private const val CONNECTED_PILL_MS = 3_000L
    private const val FAILED = "ChatGPT sign-in didn’t finish. Try again."
    private const val JEV_POLL_MS = 5_000L
    private const val JEV_WAIT_MS = 3 * 60_000L
    private const val JEV_NO_MAGPIE = "Jev key saved, but the model service isn’t running. Try connecting Jev again."
    private const val JEV_NO_MODEL = "Jev key saved, but no Jev model is available for it yet. Try connecting Jev again."
    private val UNSETTLED_PHASES = setOf("loading", "verifying")
    private val SIGNING_IN_PHASES = setOf("starting", "pending", "verifying")
    private val MEMORY: Uri = Uri.parse("content://org.agentos.memory.open-access")
    private val AUDIO: Uri = Uri.parse("content://org.agentos.audio.open-access")

    @Volatile private var instance: OnboardingRepository? = null
    fun get(context: Context): OnboardingRepository =
      instance ?: synchronized(this) { instance ?: OnboardingRepository(context).also { instance = it } }
  }
}
