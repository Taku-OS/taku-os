package org.agentos.agent.home.assistant

import org.junit.Assert.*
import org.junit.Test

class HomeComposerScreenshotPolicyTest {
  private val draft = HomeComposer.Draft("thread", "revision", "poll", emptyList(), null)
  @Test fun ordinaryChatKeepsConfiguredScreenshotPolicy() {
    val request = HomeComposer.Request.new(draft, null)
    assertFalse(request.submission().has("includeInvocationScreenshot"))
    assertNull(HomeComposer.Request.parse(request.submission().put("text", draft.text)).pollSendIntentId)
  }
  @Test fun backgroundPollOptOutSurvivesSavedRequestAndRetry() {
    val request = HomeComposer.Request.new(draft, null, pollSendIntentId = "poll-intent")
    assertEquals(false, request.submission().get("includeInvocationScreenshot"))
    val restored = HomeComposer.Request.parse(request.submission().put("text", draft.text))
    assertEquals("poll-intent", restored.pollSendIntentId)
    assertEquals(request.id, restored.id)
    assertEquals(false, restored.submission().get("includeInvocationScreenshot"))
  }
}
