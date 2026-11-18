package org.agentos.agent.home

import org.agentos.agent.home.assistant.AssistantSession
import org.agentos.agent.home.assistant.DictationTranscript
import org.junit.Assert.assertEquals
import org.junit.Test

/** Engine dictation events → the transcript the hold, home field and chat show. */
class DictationTranscriptTest {
  @Test fun partialsReplaceTheOpenSegmentAndFinalsCommitIt() {
    val t = DictationTranscript()
    assertEquals("can", t.apply("can", false))
    assertEquals("can you check", t.apply("can you check", false))
    assertEquals("can you check the flight", t.apply("can you check the flight ", true))
    assertEquals("can you check the flight to", t.apply("to", false))
    assertEquals("can you check the flight to Tokyo", t.apply("to Tokyo", true))
    assertEquals("can you check the flight to Tokyo", t.text)
  }

  @Test fun chineseSegmentsRunTogether() {
    val t = DictationTranscript()
    t.apply("帮我查一下", true)
    assertEquals("帮我查一下明天的航班", t.apply("明天的航班", true))
    assertEquals("帮我查一下明天的航班OK", t.apply("OK", false))
  }

  @Test fun blankResultsAndResetLeaveNothingBehind() {
    val t = DictationTranscript()
    t.apply("hello", true)
    assertEquals("hello", t.apply("   ", false))
    t.reset()
    assertEquals("", t.text)
  }

  @Test fun engineReasonsMapToTheIslandErrors() {
    assertEquals("VOICE_DISABLED", AssistantSession.voiceError("POLICY_DISABLED"))
    assertEquals("VOICE_DISABLED", AssistantSession.voiceError("CONSUMER_NOT_AUTHORIZED"))
    assertEquals("VOICE_BUSY", AssistantSession.voiceError("RESOURCE_BUSY"))
    assertEquals("VOICE_UNAVAILABLE", AssistantSession.voiceError("ENGINE_UNAVAILABLE"))
    assertEquals("VOICE_NETWORK_FAILED", AssistantSession.voiceError("NETWORK_FAILED"))
    assertEquals("VOICE_TIMEOUT", AssistantSession.voiceError("PROVIDER_TIMEOUT"))
    assertEquals("VOICE_AUTH_FAILED", AssistantSession.voiceError("PROVIDER_AUTH_FAILED"))
  }

  @Test fun duringCapturePreviewsAreReplacedByTheWholeRecordingFinal() {
    val t = DictationTranscript()
    assertEquals("can you", t.apply("can you", false, 0, 1))
    assertEquals("a newer preview replaces the earlier one", "can you check the flight", t.apply("can you check the flight", false, 0, 2))
    assertEquals("the final covers the whole recording", "can you check the flight to Tokyo", t.apply("can you check the flight to Tokyo", true, 0, 3))
    assertEquals("can you check the flight to Tokyo", t.text)
  }

  @Test fun anEmptyFinalDropsThePreviewItNeverConfirmed() {
    val t = DictationTranscript()
    assertEquals("can you check", t.apply("can you check", false, 0, 1))
    assertEquals("", t.apply("", true, 0, 2))
    assertEquals("", t.text)
    assertEquals("a later segment still shows", "hello", t.apply("hello", false, 1, 3))
  }

  @Test fun sequenceAndSegmentsCommitTheTailOnce() {
    val t = DictationTranscript()
    assertEquals("can", t.apply("can", false, 0, 2))
    assertEquals("an older sequence is ignored", "can", t.apply("ca", false, 0, 1))
    assertEquals("can you check", t.apply("can you check", false, 0, 3))
    assertEquals("can you check the flight", t.apply("can you check the flight", true, 0, 4))
    assertEquals("a repeated final is committed once", "can you check the flight", t.apply("can you check the flight", true, 0, 5))
    assertEquals("a partial for a committed segment is ignored", "can you check the flight", t.apply("stale", false, 0, 6))
    assertEquals("can you check the flight to", t.apply("to", false, 1, 7))
    assertEquals("can you check the flight to Tokyo", t.apply("to Tokyo", true, 1, 8))
    assertEquals("and the tail after the gesture", "can you check the flight to Tokyo please", t.apply("please", true, 2, 9))
  }
}
