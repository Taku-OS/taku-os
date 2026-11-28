package org.agentos.agent.home

import org.agentos.agent.home.assistant.LiveTalkSetup
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LiveTalkSetupTest {
  private fun settings(profile: String, falConfigured: Boolean) = JSONObject()
    .put("fal", JSONObject().put("configured", falConfigured))
    .put("live", JSONObject().put("profileId", profile).put("gemini", JSONObject().put("configured", false)))

  @Test fun aMissingFalKeyForGrokOpensTheFalCard() {
    assertEquals("fal", LiveTalkSetup.service("PROVIDER_NOT_CONFIGURED", null, settings("fal-grok", false)))
  }

  @Test fun aMissingGeminiKeyIsNotFixedByTheFalCard() {
    assertNull(LiveTalkSetup.service("PROVIDER_NOT_CONFIGURED", null, settings("gemini-live", false)))
    assertNull(LiveTalkSetup.service("PROVIDER_NOT_CONFIGURED", "gemini-live", null))
    assertNull(LiveTalkSetup.service("PROVIDER_REMOVED", "gemini-live", settings("gemini-live", true)))
  }

  @Test fun aMissingMossKeyIsNotFixedByTheFalCard() {
    assertNull(LiveTalkSetup.service("PROVIDER_NOT_CONFIGURED", null, settings("moss-live", false)))
    assertNull(LiveTalkSetup.service("PROVIDER_NOT_CONFIGURED", "moss-live", null))
    assertNull(LiveTalkSetup.service("PROVIDER_REMOVED", "moss-live", settings("moss-live", true)))
  }

  @Test fun anAcceptedGrokConnectionLosingItsKeyOpensTheFalCard() {
    assertEquals("fal", LiveTalkSetup.service("PROVIDER_NOT_CONFIGURED", "fal-grok", null))
    assertEquals("fal", LiveTalkSetup.service("PROVIDER_REMOVED", "fal-grok", null))
  }

  @Test fun withoutTheProviderOnlyTakuAudiosOwnAnswerCounts() {
    // A configured fal key means the missing key is someone else's; unknown settings prove nothing.
    assertNull(LiveTalkSetup.service("PROVIDER_NOT_CONFIGURED", null, settings("fal-grok", true)))
    assertNull(LiveTalkSetup.service("PROVIDER_NOT_CONFIGURED", null, null))
    assertNull(LiveTalkSetup.service("PROVIDER_NOT_CONFIGURED", null, JSONObject().put("live", JSONObject().put("profileId", "fal-grok"))))
  }

  @Test fun otherFailuresNeedNoSetupPage() {
    for (code in listOf("NETWORK_FAILED", "PROVIDER_AUTH_FAILED", "PERMISSION_DENIED", "CONNECT_TIMEOUT"))
      assertNull(code, LiveTalkSetup.service(code, "fal-grok", settings("fal-grok", false)))
    assertEquals(false, LiveTalkSetup.missingKey("NETWORK_FAILED"))
    assertEquals(true, LiveTalkSetup.missingKey("PROVIDER_NOT_CONFIGURED"))
  }
}
