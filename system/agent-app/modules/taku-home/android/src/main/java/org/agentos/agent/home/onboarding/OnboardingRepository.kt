package org.agentos.agent.home.onboarding

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.UserManager
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
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
 * - ChatGPT: the broker account view through the assistant gateway (`readAccount`);
 * - name: Memory `userName` (`content://org.agentos.memory.open-access`).
 * Blocking methods run on the caller's thread: an IO dispatcher or a provider binder thread.
 */
class OnboardingRepository private constructor(context: Context) {
  data class State(val facts: OnboardingFacts, val decision: OnboardingDecision, val userName: String?) {
    val step: OnboardingStep? get() = (decision as? OnboardingDecision.Show)?.step
  }

  private val app = context.applicationContext
  private val resolver = app.contentResolver
  private val gateway = AgentGateway(resolver)
  // A failed background read or write is logged; it must never take Home down.
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e -> Log.w(TAG, "onboarding update failed", e) })
  private val lock = Any()

  @Volatile private var chatgpt = ChatGpt.UNKNOWN
  @Volatile private var userName: String? = null
  @Volatile private var nameKnown = false

  private val mutable = MutableStateFlow(snapshotOf(facts()))
  val state: StateFlow<State> = mutable

  private var poll: Job? = null

  init {
    val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
      override fun onChange(selfChange: Boolean) { scope.launch { publish() } }
    }
    for (uri in listOf(
      Settings.Secure.getUriFor(OnboardingRules.SETTING),
      Settings.Secure.getUriFor(USER_SETUP_COMPLETE),
      Settings.Global.getUriFor(Settings.Global.DEVICE_PROVISIONED),
    )) resolver.registerContentObserver(uri, false, observer)
    scope.launch { refresh() }
  }

  /** Home in the foreground: re-read now, then poll the account while the card is up. */
  fun setForeground(foreground: Boolean) {
    poll?.cancel()
    poll = if (!foreground) null else scope.launch {
      runCatching { refresh() }.onFailure { Log.w(TAG, "onboarding refresh failed", it) }
      while (isActive && state.value.decision is OnboardingDecision.Show) {
        delay(POLL_MS)
        runCatching { refresh() }.onFailure { Log.w(TAG, "onboarding refresh failed", it) }
      }
    }
  }

  /** While onboarding is pending, re-reads ChatGPT and the name, then publishes. Completed costs nothing. */
  fun refresh(): State {
    if (stored() != OnboardingStep.COMPLETED) {
      chatgpt = readChatGpt()
      readName()
    }
    return publish()
  }

  /** Runs one card action exactly as the GUI does; returns the new state. */
  fun perform(action: OnboardingAction, rawName: String? = null): State = synchronized(lock) {
    val facts = facts()
    val step = when (val decision = OnboardingRules.decide(facts)) {
      is OnboardingDecision.Show -> decision.step
      is OnboardingDecision.Hidden -> throw OnboardingException(
        when (decision.reason) {
          OnboardingRules.HIDDEN_PHONE -> "PHONE_NOT_READY"
          else -> "ALREADY_COMPLETED"
        },
        "onboarding is not showing (${decision.reason})",
      )
    }
    var next = OnboardingRules.advance(step, action)
    when (action) {
      OnboardingAction.SUBMIT_NAME -> saveName(OnboardingRules.name(rawName))
      OnboardingAction.SET_UP -> readName()
      OnboardingAction.SKIP_NAME -> Unit
    }
    next = OnboardingRules.resolve(next, nameSet())
    write(next)
    publish()
  }

  /** Agent-only: start over from the intro. A confirmed ChatGPT sign-in completes it again. */
  fun reset(): State = synchronized(lock) {
    write(OnboardingStep.INTRO)
    refresh()
  }

  /** The authoritative snapshot for Agents, freshly read. */
  fun describe(): JSONObject {
    chatgpt = readChatGpt()
    val s = refresh()
    val decision = s.decision
    return JSONObject()
      .put("setting", OnboardingRules.SETTING)
      .put("stored", s.facts.stored?.id ?: JSONObject.NULL)
      .put("visible", decision is OnboardingDecision.Show)
      .put("step", s.step?.id ?: JSONObject.NULL)
      .put("hiddenReason", (decision as? OnboardingDecision.Hidden)?.reason ?: JSONObject.NULL)
      .put("phoneReady", s.facts.phoneReady)
      .put("chatgpt", s.facts.chatgpt.id)
      .put("nameSet", s.facts.nameSet ?: JSONObject.NULL)
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

  private fun snapshotOf(facts: OnboardingFacts) = State(facts, OnboardingRules.decide(facts), userName.takeIf { OnboardingRules.isNameSet(it) })

  private fun facts() = OnboardingFacts(stored(), phoneReady(), chatgpt, nameSet())

  private fun nameSet(): Boolean? = if (nameKnown) OnboardingRules.isNameSet(userName) else null

  private fun stored(): OnboardingStep? = OnboardingStep.parse(Settings.Secure.getString(resolver, OnboardingRules.SETTING))

  private fun write(step: OnboardingStep) {
    if (!Settings.Secure.putString(resolver, OnboardingRules.SETTING, step.id))
      throw OnboardingException("SETTINGS_WRITE_FAILED", "could not save ${OnboardingRules.SETTING}")
  }

  private fun phoneReady(): Boolean {
    val provisioned = Settings.Global.getInt(resolver, Settings.Global.DEVICE_PROVISIONED, 0) == 1
    val setupComplete = try {
      Settings.Secure.getInt(resolver, USER_SETUP_COMPLETE, 0) == 1
    } catch (_: SecurityException) {
      provisioned
    }
    val unlocked = app.getSystemService(UserManager::class.java)?.isUserUnlocked ?: true
    return provisioned && setupComplete && unlocked
  }

  private fun readChatGpt(): ChatGpt = try {
    val account = gateway.call("readAccount")
    when {
      account.optBoolean("chatgptConnected") -> ChatGpt.SIGNED_IN
      account.optString("phase", "loading") == "loading" -> ChatGpt.UNKNOWN
      else -> ChatGpt.SIGNED_OUT
    }
  } catch (e: GatewayException) {
    Log.w(TAG, "account status unavailable: ${e.code}")
    ChatGpt.UNKNOWN
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
    val envelope = JSONObject(bundle.getString("result") ?: throw OnboardingException("MEMORY_UNAVAILABLE", "Memory returned nothing"))
    if (!envelope.optBoolean("ok")) throw OnboardingException("MEMORY_ERROR", envelope.optString("message", "Memory rejected the request"))
    return envelope.optJSONObject("result") ?: JSONObject()
  }

  companion object {
    private const val TAG = "TakuOnboarding"
    /** `Settings.Secure.USER_SETUP_COMPLETE` (hidden constant; per user). */
    private const val USER_SETUP_COMPLETE = "user_setup_complete"
    private const val POLL_MS = 3_000L
    private val MEMORY: Uri = Uri.parse("content://org.agentos.memory.open-access")

    @Volatile private var instance: OnboardingRepository? = null
    fun get(context: Context): OnboardingRepository =
      instance ?: synchronized(this) { instance ?: OnboardingRepository(context).also { instance = it } }
  }
}
