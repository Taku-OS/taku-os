package org.agentos.agent.home

import org.agentos.agent.home.onboarding.AiAccess
import org.agentos.agent.home.onboarding.DeviceCodeSignIn
import org.agentos.agent.home.onboarding.OnboardingAction
import org.agentos.agent.home.onboarding.OnboardingDecision
import org.agentos.agent.home.onboarding.OnboardingException
import org.agentos.agent.home.onboarding.OnboardingFacts
import org.agentos.agent.home.onboarding.OnboardingRules
import org.agentos.agent.home.onboarding.OnboardingService
import org.agentos.agent.home.onboarding.OnboardingStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class OnboardingRulesTest {
  private fun facts(stored: OnboardingStep? = null, ready: Boolean = true, ai: AiAccess = AiAccess.UNAVAILABLE, nameSet: Boolean? = false) =
    OnboardingFacts(stored, ready, ai, nameSet)

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

  @Test fun completedNeverShowsAgainEvenWhenAiIsUnavailable() {
    for (ai in AiAccess.entries)
      assertEquals(OnboardingDecision.Hidden(OnboardingRules.HIDDEN_COMPLETED), OnboardingRules.decide(facts(stored = OnboardingStep.COMPLETED, ai = ai)))
    assertFalse(OnboardingRules.shouldComplete(facts(stored = OnboardingStep.COMPLETED, ai = AiAccess.AVAILABLE)))
  }

  @Test fun availableAiHidesAndCompletesBeforeTheAiStep() {
    // ChatGPT signed in, or Magpie running with a model: both arrive as AVAILABLE.
    for (stored in listOf(null, OnboardingStep.INTRO, OnboardingStep.NAME)) {
      val f = facts(stored = stored, ai = AiAccess.AVAILABLE)
      assertEquals(OnboardingDecision.Hidden(OnboardingRules.HIDDEN_AI), OnboardingRules.decide(f))
      assertTrue(OnboardingRules.shouldComplete(f))
    }
  }

  @Test fun theAiStepStaysUntilContinue() {
    // Mid sign-in (unknown while verifying), unavailable, or connected: the card stays so Jev
    // and Fal can still be added, and only Continue completes it.
    for (ai in AiAccess.entries) {
      val f = facts(stored = OnboardingStep.CONNECT_AI, ai = ai)
      assertEquals(OnboardingDecision.Show(OnboardingStep.CONNECT_AI), OnboardingRules.decide(f))
      assertFalse(OnboardingRules.shouldComplete(f))
    }
    assertTrue(OnboardingRules.canFinish(facts(stored = OnboardingStep.CONNECT_AI, ai = AiAccess.AVAILABLE)))
    assertFalse(OnboardingRules.canFinish(facts(stored = OnboardingStep.CONNECT_AI, ai = AiAccess.UNAVAILABLE)))
    assertFalse(OnboardingRules.canFinish(facts(stored = OnboardingStep.CONNECT_AI, ai = AiAccess.UNKNOWN)))
    assertEquals(OnboardingStep.ALL_SET, OnboardingRules.advance(OnboardingStep.CONNECT_AI, OnboardingAction.FINISH))
    assertEquals(OnboardingDecision.Hidden(OnboardingRules.HIDDEN_PHONE), OnboardingRules.decide(facts(stored = OnboardingStep.CONNECT_AI, ready = false)))
  }

  @Test fun allSetStaysOverTheFeedUntilExplore() {
    for (ai in AiAccess.entries) {
      val f = facts(stored = OnboardingStep.ALL_SET, ai = ai)
      assertEquals(OnboardingDecision.Show(OnboardingStep.ALL_SET), OnboardingRules.decide(f))
      assertFalse(OnboardingRules.shouldComplete(f))
    }
    assertEquals(OnboardingStep.COMPLETED, OnboardingRules.advance(OnboardingStep.ALL_SET, OnboardingAction.EXPLORE))
    assertFalse(OnboardingRules.takesOverHome(OnboardingStep.ALL_SET))
    for (step in listOf(OnboardingStep.INTRO, OnboardingStep.NAME, OnboardingStep.CONNECT_AI)) assertTrue(OnboardingRules.takesOverHome(step))
    assertFalse(OnboardingRules.takesOverHome(null))
    try {
      OnboardingRules.advance(OnboardingStep.CONNECT_AI, OnboardingAction.EXPLORE)
      fail("explore before all set")
    } catch (e: OnboardingException) {
      assertEquals("WRONG_STEP", e.code)
    }
  }

  @Test fun keysFollowTheKeyStoreRules() {
    assertEquals("sk-or-v1-abc", OnboardingRules.key("  sk-or-v1-abc "))
    for (bad in listOf(null, "", "  ", "two words", "a,b", "x".repeat(4097))) {
      try {
        OnboardingRules.key(bad)
        fail("accepted $bad")
      } catch (e: OnboardingException) {
        assertEquals("INVALID_KEY", e.code)
      }
    }
    assertFalse(OnboardingRules.canSaveKey(OnboardingService.FAL, ""))
    assertTrue(OnboardingRules.canSaveKey(OnboardingService.FAL, "fal-key-123"))
  }

  @Test fun jevTakesTypeSafeOrOpenRouterKeys() {
    assertEquals("apikey_21abcdefGH", OnboardingRules.jevKey(" apikey_21abcdefGH "))
    val openRouter = "sk-or-v1-" + "a".repeat(24)
    assertEquals(openRouter, OnboardingRules.jevKey(openRouter))
    for (bad in listOf("apikey_", "apikey_short", "sk-or-v1-short", "sk-proj-abcdefghijklmnopqrstuvwx", "fal-key-123")) {
      try {
        OnboardingRules.jevKey(bad)
        fail("accepted Jev key $bad")
      } catch (e: OnboardingException) {
        assertEquals("INVALID_KEY", e.code)
      }
    }
    assertTrue(OnboardingRules.canSaveKey(OnboardingService.JEV, "apikey_21abcdefGH"))
    assertFalse(OnboardingRules.canSaveKey(OnboardingService.JEV, "fal-key-123"))
  }

  @Test fun anUnknownAccountKeepsItHiddenAndNeverCompletes() {
    // An existing user whose broker is still starting must not see the card.
    for (stored in listOf(null, OnboardingStep.INTRO, OnboardingStep.NAME)) {
      val f = facts(stored = stored, ai = AiAccess.UNKNOWN)
      assertEquals(OnboardingDecision.Hidden(OnboardingRules.HIDDEN_AI_UNKNOWN), OnboardingRules.decide(f))
      assertFalse(OnboardingRules.shouldComplete(f))
    }
  }

  @Test fun itWaitsUntilThePhoneIsReady() {
    assertEquals(OnboardingDecision.Hidden(OnboardingRules.HIDDEN_PHONE), OnboardingRules.decide(facts(ready = false)))
    assertEquals(OnboardingDecision.Hidden(OnboardingRules.HIDDEN_AI_UNKNOWN), OnboardingRules.decide(facts(ready = false, ai = AiAccess.UNKNOWN)))
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
      OnboardingStep.NAME to OnboardingAction.FINISH,
    )) {
      try {
        OnboardingRules.advance(step, action)
        fail("$action at $step")
      } catch (e: OnboardingException) {
        assertEquals("WRONG_STEP", e.code)
      }
    }
  }

  @Test fun replayShowsEveryStepWithoutClearingExistingConnectionsOrName() {
    for (ai in AiAccess.entries) {
      for (step in listOf(null, OnboardingStep.INTRO, OnboardingStep.NAME, OnboardingStep.CONNECT_AI, OnboardingStep.ALL_SET)) {
        val f = facts(stored = step, ai = ai, nameSet = true).copy(testMode = true)
        assertEquals(OnboardingDecision.Show(step ?: OnboardingStep.INTRO), OnboardingRules.decide(f))
        assertFalse(OnboardingRules.shouldComplete(f))
        assertEquals(ai == AiAccess.AVAILABLE, OnboardingRules.canFinish(f))
        assertEquals(OnboardingDecision.Hidden(OnboardingRules.HIDDEN_PHONE), OnboardingRules.decide(f.copy(phoneReady = false)))
      }
    }
    assertEquals(OnboardingStep.NAME, OnboardingRules.resolve(OnboardingStep.NAME, true, testMode = true))
    assertEquals(OnboardingDecision.Hidden(OnboardingRules.HIDDEN_COMPLETED),
      OnboardingRules.decide(facts(stored = OnboardingStep.COMPLETED).copy(testMode = true)))
  }

  @Test fun turningReplayOffRestoresExistingUserRules() {
    val f = facts(stored = OnboardingStep.INTRO, ai = AiAccess.AVAILABLE).copy(testMode = true)
    assertFalse(OnboardingRules.shouldComplete(f))
    assertTrue(OnboardingRules.shouldComplete(f.copy(testMode = false)))
    assertEquals(OnboardingDecision.Hidden(OnboardingRules.HIDDEN_AI), OnboardingRules.decide(f.copy(testMode = false)))
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

  @Test fun theCardShowsTheBrokersDeviceCodeOnTheOfficialPage() {
    val shown = OnboardingRules.deviceCode("https://auth.openai.com/codex/device", "ABCD-EFGH")
    assertEquals(DeviceCodeSignIn("https://auth.openai.com/codex/device", "ABCD-EFGH"), shown)
    assertEquals("auth.openai.com/codex/device", shown!!.displayUrl)
    assertEquals("chatgpt.com", OnboardingRules.deviceCode(" https://chatgpt.com ", "1234")!!.displayUrl)
  }

  @Test fun aChallengeOffTheOfficialHostsOrWithoutACodeIsNotShown() {
    val code = "ABCD-EFGH"
    // A browser sign-in has no code: its URL only works on this phone.
    assertNull(OnboardingRules.deviceCode("https://auth.openai.com/codex/device", ""))
    assertNull(OnboardingRules.deviceCode("https://auth.openai.com/codex/device", null))
    assertNull(OnboardingRules.deviceCode("https://auth.openai.com/codex/device", "AB CD"))
    assertNull(OnboardingRules.deviceCode("https://auth.openai.com/codex/device", "A".repeat(33)))
    assertNull(OnboardingRules.deviceCode("", code))
    assertNull(OnboardingRules.deviceCode("http://auth.openai.com/codex/device", code))
    assertNull(OnboardingRules.deviceCode("https://auth.openai.com.example.com/codex/device", code))
    assertNull(OnboardingRules.deviceCode("https://auth.openai.com@example.com/", code))
    assertNull(OnboardingRules.deviceCode("https://auth.openai.com:8443/codex/device", code))
    assertNull(OnboardingRules.deviceCode("https://example.com/?next=https://auth.openai.com", code))
  }
}
