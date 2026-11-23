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
    assertFalse(endpoint.shouldFinish(3_099))
    assertTrue(endpoint.shouldFinish(3_100))
  }
  @Test fun continuingSpeechExtendsTheUtteranceAndNewCaptureResetsIt() {
    val endpoint = LiveTalkEndpoint()
    endpoint.observe(0.4f, 100)
    endpoint.observe(0.5f, 1_500)
    assertFalse(endpoint.shouldFinish(2_000))
    assertTrue(endpoint.shouldFinish(4_500))
    endpoint.reset()
    assertFalse(endpoint.shouldFinish(60_000))
  }
  @Test fun aPauseAfterTheInstructionDoesNotDiscardTheRestOfTheSentence() {
    val endpoint = LiveTalkEndpoint()
    endpoint.observe(0.5f, 100)
    endpoint.observe(0.0f, 2_100)
    assertFalse(endpoint.shouldFinish(2_100))
    endpoint.observe(0.5f, 2_400)
    assertFalse(endpoint.shouldFinish(4_500))
    assertTrue(endpoint.shouldFinish(5_400))
  }
}
