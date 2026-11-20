package org.agentos.agent.home.assistant

/** Utterance boundary from measured audio, independent of delayed cloud preview text. */
internal class LiveTalkEndpoint {
  private var lastSpeechAt: Long? = null
  fun reset() { lastSpeechAt = null }
  fun observe(level: Float, now: Long) {
    if (level.isFinite() && level > 0.18f) lastSpeechAt = now
  }
  // A short pause inside a sentence (e.g. "please only reply: ...") is not submission.
  // Keep the microphone open through a two-second pause; cloud preview latency is irrelevant.
  fun shouldFinish(now: Long): Boolean = lastSpeechAt?.let { now - it >= 3_000 } ?: false
}
