package org.agentos.agent.home

import org.agentos.agent.home.onboarding.ChatGpt
import org.agentos.agent.home.onboarding.OnboardingAction
import org.agentos.agent.home.onboarding.OnboardingDecision
import org.agentos.agent.home.onboarding.OnboardingException
import org.agentos.agent.home.onboarding.OnboardingFacts
import org.agentos.agent.home.onboarding.OnboardingRules
import org.agentos.agent.home.onboarding.OnboardingStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class OnboardingRulesTest {
  private fun facts(stored: OnboardingStep? = null, ready: Boolean = true, chatgpt: ChatGpt = ChatGpt.SIGNED_OUT, nameSet: Boolean? = false) =
    OnboardingFacts(stored, ready, chatgpt, nameSet)

  @Test fun aFreshUserSeesTheIntro() {
    // No stored value: first boot, or after a factory reset cleared Settings.Secure.
    assertEquals(OnboardingDecision.Show(OnboardingStep.INTRO), OnboardingRules.decide(facts()))
  }

  @Test fun anUnknownStoredValueStartsOver() {
    assertNull(OnboardingStep.parse("garbage"))
    assertEquals(OnboardingDecision.Show(OnboardingStep.INTRO), OnboardingRules.decide(facts(stored = OnboardingStep.parse("garbage"))))
  }

  @Test fun itResumesFromTheSavedStep() {
    assertEquals(OnboardingDecision.Show(OnboardingStep.NAME), OnboardingRules.decide(facts(stored = OnboardingStep.NAME)))
    assertEquals(OnboardingDecision.Show(OnboardingStep.CONNECT_AI), OnboardingRules.decide(facts(stored = OnboardingStep.CONNECT_AI)))
  }

  @Test fun completedNeverShowsAgainEvenWhenChatGptIsSignedOut() {
    for (chatgpt in ChatGpt.entries)
      assertEquals(OnboardingDecision.Hidden(OnboardingRules.HIDDEN_COMPLETED), OnboardingRules.decide(facts(stored = OnboardingStep.COMPLETED, chatgpt = chatgpt)))
    assertFalse(OnboardingRules.shouldComplete(facts(stored = OnboardingStep.COMPLETED, chatgpt = ChatGpt.SIGNED_IN)))
  }

  @Test fun aConfirmedChatGptSignInHidesAndCompletesFromAnyStep() {
    for (stored in listOf(null, OnboardingStep.INTRO, OnboardingStep.NAME, OnboardingStep.CONNECT_AI)) {
      val f = facts(stored = stored, chatgpt = ChatGpt.SIGNED_IN)
      assertEquals(OnboardingDecision.Hidden(OnboardingRules.HIDDEN_CHATGPT), OnboardingRules.decide(f))
      assertTrue(OnboardingRules.shouldComplete(f))
    }
  }

  @Test fun anUnknownAccountStillShowsAndNeverCompletes() {
    val f = facts(chatgpt = ChatGpt.UNKNOWN)
    assertEquals(OnboardingDecision.Show(OnboardingStep.INTRO), OnboardingRules.decide(f))
    assertFalse(OnboardingRules.shouldComplete(f))
  }

  @Test fun itWaitsUntilThePhoneIsReady() {
    assertEquals(OnboardingDecision.Hidden(OnboardingRules.HIDDEN_PHONE), OnboardingRules.decide(facts(ready = false)))
  }

  @Test fun anExistingNameSkipsTheNameStep() {
    assertEquals(OnboardingDecision.Show(OnboardingStep.CONNECT_AI), OnboardingRules.decide(facts(stored = OnboardingStep.NAME, nameSet = true)))
    // Memory unreadable: never skip on a guess.
    assertEquals(OnboardingDecision.Show(OnboardingStep.NAME), OnboardingRules.decide(facts(stored = OnboardingStep.NAME, nameSet = null)))
    assertEquals(OnboardingStep.INTRO, OnboardingRules.resolve(OnboardingStep.INTRO, true))
  }

  @Test fun actionsMoveForwardOnlyFromTheirOwnStep() {
    assertEquals(OnboardingStep.NAME, OnboardingRules.advance(OnboardingStep.INTRO, OnboardingAction.SET_UP))
    assertEquals(OnboardingStep.CONNECT_AI, OnboardingRules.advance(OnboardingStep.NAME, OnboardingAction.SUBMIT_NAME))
    // "Later" skips only the name; onboarding goes on to the AI connection.
    assertEquals(OnboardingStep.CONNECT_AI, OnboardingRules.advance(OnboardingStep.NAME, OnboardingAction.SKIP_NAME))
    for ((step, action) in listOf(
      OnboardingStep.NAME to OnboardingAction.SET_UP,
      OnboardingStep.INTRO to OnboardingAction.SKIP_NAME,
      OnboardingStep.CONNECT_AI to OnboardingAction.SUBMIT_NAME,
    )) {
      try {
        OnboardingRules.advance(step, action)
        fail("$action at $step")
      } catch (e: OnboardingException) {
        assertEquals("WRONG_STEP", e.code)
      }
    }
  }

  @Test fun namesFollowMemoryRules() {
    assertEquals("Deki", OnboardingRules.name("  Deki "))
    for (bad in listOf(null, "", "   ", "x".repeat(21))) {
      try {
        OnboardingRules.name(bad)
        fail("accepted $bad")
      } catch (e: OnboardingException) {
        assertEquals("INVALID_NAME", e.code)
      }
    }
    assertFalse(OnboardingRules.canContinue("  "))
    assertTrue(OnboardingRules.canContinue("Dekidiscool"))
    assertFalse(OnboardingRules.isNameSet(OnboardingRules.MEMORY_UNSET_NAME))
    assertFalse(OnboardingRules.isNameSet(""))
    assertTrue(OnboardingRules.isNameSet("Dekidiscool"))
  }
}
