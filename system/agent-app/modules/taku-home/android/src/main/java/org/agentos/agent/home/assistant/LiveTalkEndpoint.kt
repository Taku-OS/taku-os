package org.agentos.agent.home.assistant

/** Utterance boundary from measured audio, independent of delayed cloud preview text. */
internal class LiveTalkEndpoint {
  private var lastSpeechAt: Long? = null
  fun reset() { lastSpeechAt = null }
  fun observe(level: Float, now: Long) {
    if (level.isFinite() && level > 0.18f) lastSpeechAt = now
  }
  fun shouldFinish(now: Long): Boolean = lastSpeechAt?.let { now - it >= 1_500 } ?: false
}
