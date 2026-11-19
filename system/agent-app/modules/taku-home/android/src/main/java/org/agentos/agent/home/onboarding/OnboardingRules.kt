package org.agentos.agent.home.onboarding

/**
 * First-run onboarding progress, stored per Android user in `Settings.Secure`
 * [OnboardingRules.SETTING]. A factory reset clears it; a missing or unknown value means "not
 * started" ([INTRO]).
 */
enum class OnboardingStep(val id: String) {
  INTRO("intro"),
  NAME("name"),
  CONNECT_AI("connect_ai"),
  /** "You’re all set": Home shows the Feed and the expanded AI entry under the card (Figma 1341:782). */
  ALL_SET("all_set"),
  COMPLETED("completed");

  companion object {
    fun parse(raw: String?): OnboardingStep? = entries.firstOrNull { it.id == raw }
  }
}

/**
 * Whether the assistant's AI is usable, from the broker's account view (gateway `readAccount`
 * `aiAvailable`): a ChatGPT sign-in, or Magpie mode running with at least one model.
 */
enum class AiAccess(val id: String) {
  /** The broker is still reading or verifying the account, or it cannot be reached. */
  UNKNOWN("unknown"),
  UNAVAILABLE("unavailable"),
  AVAILABLE("available"),
}

/** One service's connection on the AI step (chips: not connected, animated dots, checkmark). */
enum class Connection(val id: String) {
  UNKNOWN("unknown"),
  NOT_CONNECTED("not_connected"),
  CONNECTING("connecting"),
  CONNECTED("connected"),
}

/** The three services on the AI step. [fal] is null when Taku Audio is not installed. */
data class Services(val chatgpt: Connection, val jev: Connection, val fal: Connection?) {
  companion object { val UNKNOWN = Services(Connection.UNKNOWN, Connection.UNKNOWN, Connection.UNKNOWN) }
}

enum class OnboardingService(val id: String) {
  CHATGPT("chatgpt"),
  JEV("jev"),
  FAL("fal");

  companion object { fun parse(raw: String?) = entries.firstOrNull { it.id == raw } }
}

/** The authoritative inputs; GUI and Agents both decide from these. */
data class OnboardingFacts(
  val stored: OnboardingStep?,
  /** Setup wizard finished, device provisioned and this user unlocked, all confirmed. */
  val phoneReady: Boolean,
  val ai: AiAccess,
  /** Memory `userName` is set; null when Memory could not be read. */
  val nameSet: Boolean?,
)

sealed interface OnboardingDecision {
  data class Hidden(val reason: String) : OnboardingDecision
  data class Show(val step: OnboardingStep) : OnboardingDecision
}

class OnboardingException(val code: String, message: String) : Exception(message)

/** The user's choices on the card. Agents send the same actions through the provider. */
enum class OnboardingAction(val id: String) {
  SET_UP("set-up"),
  SUBMIT_NAME("submit-name"),
  SKIP_NAME("skip-name"),
  /** "Continue" on the AI step, once AI is available: on to "You’re all set". */
  FINISH("finish"),
  /** "I’ll explore" on "You’re all set": onboarding is complete. */
  EXPLORE("explore"),
}

object OnboardingRules {
  const val SETTING = "taku_onboarding_step"
  /** Memory's `userName` until the user picks one (`MemoryStore.userName()`). */
  const val MEMORY_UNSET_NAME = "我"
  /** Memory's own limit (`MemoryStore.configure`); Memory remains the authority. */
  const val NAME_MAX = 20

  const val HIDDEN_COMPLETED = "completed"
  const val HIDDEN_AI = "ai_available"
  const val HIDDEN_AI_UNKNOWN = "ai_unknown"
  const val HIDDEN_PHONE = "phone_not_ready"

  /**
   * Shown only when all three are confirmed: onboarding is not completed, AI is confirmed
   * unavailable, and the phone is ready. Anything uncertain keeps it hidden, so an existing user
   * never sees it while the broker is still starting.
   *
   * A user who has reached the AI step is mid-setup: the card stays there, through sign-in and
   * verification, until they tap Continue (which needs available AI), so Jev and Fal can still be
   * added after ChatGPT.
   */
  fun decide(facts: OnboardingFacts): OnboardingDecision = when {
    facts.stored == OnboardingStep.COMPLETED -> OnboardingDecision.Hidden(HIDDEN_COMPLETED)
    facts.stored == OnboardingStep.CONNECT_AI || facts.stored == OnboardingStep.ALL_SET ->
      if (facts.phoneReady) OnboardingDecision.Show(facts.stored) else OnboardingDecision.Hidden(HIDDEN_PHONE)
    facts.ai == AiAccess.AVAILABLE -> OnboardingDecision.Hidden(HIDDEN_AI)
    facts.ai == AiAccess.UNKNOWN -> OnboardingDecision.Hidden(HIDDEN_AI_UNKNOWN)
    !facts.phoneReady -> OnboardingDecision.Hidden(HIDDEN_PHONE)
    else -> OnboardingDecision.Show(resolve(facts.stored ?: OnboardingStep.INTRO, facts.nameSet))
  }

  /**
   * Confirmed available AI completes onboarding for good when the user has not reached the AI
   * step (existing users, or AI set up elsewhere); there it waits for Continue.
   */
  fun shouldComplete(facts: OnboardingFacts): Boolean =
    facts.stored != OnboardingStep.COMPLETED && facts.stored != OnboardingStep.CONNECT_AI &&
      facts.stored != OnboardingStep.ALL_SET && facts.ai == AiAccess.AVAILABLE

  /** The step takes over the whole Home (wallpaper, empty Home, card); "all set" sits over the real Home. */
  fun takesOverHome(step: OnboardingStep?): Boolean = step != null && step != OnboardingStep.ALL_SET && step != OnboardingStep.COMPLETED

  /** Continue needs a working AI: a ChatGPT sign-in or Magpie with a model. */
  fun canFinish(facts: OnboardingFacts): Boolean = facts.ai == AiAccess.AVAILABLE

  /** Skips steps that are already done: the name step when Memory already has a name. */
  fun resolve(step: OnboardingStep, nameSet: Boolean?): OnboardingStep =
    if (step == OnboardingStep.NAME && nameSet == true) OnboardingStep.CONNECT_AI else step

  /** The step after [action]; throws `WRONG_STEP` when the card is not showing that step. */
  fun advance(current: OnboardingStep, action: OnboardingAction): OnboardingStep {
    val expected = when (action) {
      OnboardingAction.SET_UP -> OnboardingStep.INTRO
      OnboardingAction.SUBMIT_NAME, OnboardingAction.SKIP_NAME -> OnboardingStep.NAME
      OnboardingAction.FINISH -> OnboardingStep.CONNECT_AI
      OnboardingAction.EXPLORE -> OnboardingStep.ALL_SET
    }
    if (current != expected)
      throw OnboardingException("WRONG_STEP", "${action.id} applies to step ${expected.id}; onboarding is at ${current.id}")
    return when (action) {
      OnboardingAction.SET_UP -> OnboardingStep.NAME
      OnboardingAction.SUBMIT_NAME, OnboardingAction.SKIP_NAME -> OnboardingStep.CONNECT_AI
      OnboardingAction.FINISH -> OnboardingStep.ALL_SET
      OnboardingAction.EXPLORE -> OnboardingStep.COMPLETED
    }
  }

  /** An API key as the key stores accept it (Taku Audio and the OpenRouter BYOK path). */
  fun key(raw: String?): String {
    val key = raw?.trim().orEmpty()
    if (key.isEmpty() || key.length > 4096 || key.any { it.isWhitespace() || it == ',' })
      throw OnboardingException("INVALID_KEY", "key must be one complete API key")
    return key
  }

  /**
   * A Jev key: an OpenRouter key (`sk-or-v1-…`) or a TypeSafe Jev API key (`apikey_…`), the two
   * Magpie providers that serve Jev (the broker's `MagpieDecisions.jevPreset`).
   */
  fun jevKey(raw: String?): String {
    val key = key(raw)
    if (!key.matches(OPENROUTER_KEY) && !key.matches(TYPESAFE_KEY))
      throw OnboardingException("INVALID_KEY", "use a TypeSafe (apikey_…) or OpenRouter (sk-or-v1-…) key")
    return key
  }

  fun keyFor(service: OnboardingService, raw: String?): String = if (service == OnboardingService.JEV) jevKey(raw) else key(raw)

  fun canSaveKey(service: OnboardingService, draft: String): Boolean = runCatching { keyFor(service, draft) }.isSuccess

  private val OPENROUTER_KEY = Regex("sk-or-v1-[A-Za-z0-9_-]{20,}")
  private val TYPESAFE_KEY = Regex("apikey_[A-Za-z0-9_-]{8,}")

  /** Trimmed name, or `INVALID_NAME` (the same rule Memory enforces). */
  fun name(raw: String?): String {
    val name = raw?.trim().orEmpty()
    if (name.isEmpty() || name.length > NAME_MAX)
      throw OnboardingException("INVALID_NAME", "name must be 1–$NAME_MAX characters")
    return name
  }

  fun canContinue(draft: String): Boolean = draft.trim().let { it.isNotEmpty() && it.length <= NAME_MAX }

  fun isNameSet(userName: String?): Boolean =
    !userName.isNullOrBlank() && userName.trim() != MEMORY_UNSET_NAME
}
