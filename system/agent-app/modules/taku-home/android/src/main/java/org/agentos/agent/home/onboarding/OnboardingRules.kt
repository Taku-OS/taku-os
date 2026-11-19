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
   */
  fun decide(facts: OnboardingFacts): OnboardingDecision = when {
    facts.stored == OnboardingStep.COMPLETED -> OnboardingDecision.Hidden(HIDDEN_COMPLETED)
    facts.ai == AiAccess.AVAILABLE -> OnboardingDecision.Hidden(HIDDEN_AI)
    facts.ai == AiAccess.UNKNOWN -> OnboardingDecision.Hidden(HIDDEN_AI_UNKNOWN)
    !facts.phoneReady -> OnboardingDecision.Hidden(HIDDEN_PHONE)
    else -> OnboardingDecision.Show(resolve(facts.stored ?: OnboardingStep.INTRO, facts.nameSet))
  }

  /** Confirmed available AI completes onboarding for good, even if it was never shown. */
  fun shouldComplete(facts: OnboardingFacts): Boolean =
    facts.stored != OnboardingStep.COMPLETED && facts.ai == AiAccess.AVAILABLE

  /** Skips steps that are already done: the name step when Memory already has a name. */
  fun resolve(step: OnboardingStep, nameSet: Boolean?): OnboardingStep =
    if (step == OnboardingStep.NAME && nameSet == true) OnboardingStep.CONNECT_AI else step

  /** The step after [action]; throws `WRONG_STEP` when the card is not showing that step. */
  fun advance(current: OnboardingStep, action: OnboardingAction): OnboardingStep {
    val expected = when (action) {
      OnboardingAction.SET_UP -> OnboardingStep.INTRO
      OnboardingAction.SUBMIT_NAME, OnboardingAction.SKIP_NAME -> OnboardingStep.NAME
    }
    if (current != expected)
      throw OnboardingException("WRONG_STEP", "${action.id} applies to step ${expected.id}; onboarding is at ${current.id}")
    return when (action) {
      OnboardingAction.SET_UP -> OnboardingStep.NAME
      OnboardingAction.SUBMIT_NAME, OnboardingAction.SKIP_NAME -> OnboardingStep.CONNECT_AI
    }
  }

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
