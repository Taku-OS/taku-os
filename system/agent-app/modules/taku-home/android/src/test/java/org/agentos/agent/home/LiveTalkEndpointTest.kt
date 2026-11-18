package org.agentos.agent.home

import org.agentos.agent.home.assistant.LiveTalkEndpoint
import org.junit.Assert.*
import org.junit.Test

class LiveTalkEndpointTest {
  @Test fun silenceDoesNotSubmitAnEmptyRecording() {
    val endpoint = LiveTalkEndpoint()
    endpoint.observe(0.01f, 100)
    endpoint.observe(Float.NaN, 200)
    assertFalse(endpoint.shouldFinish(60_000))
  }
  @Test fun speechThenSilenceFinishesWithoutWaitingForCloudPreview() {
    val endpoint = LiveTalkEndpoint()
    endpoint.observe(0.6f, 100)
    endpoint.observe(0.02f, 900)
    assertFalse(endpoint.shouldFinish(1_599))
    assertTrue(endpoint.shouldFinish(1_600))
  }
  @Test fun continuingSpeechExtendsTheUtteranceAndNewCaptureResetsIt() {
    val endpoint = LiveTalkEndpoint()
    endpoint.observe(0.4f, 100)
    endpoint.observe(0.5f, 1_500)
    assertFalse(endpoint.shouldFinish(2_000))
    assertTrue(endpoint.shouldFinish(3_000))
    endpoint.reset()
    assertFalse(endpoint.shouldFinish(60_000))
  }
}
