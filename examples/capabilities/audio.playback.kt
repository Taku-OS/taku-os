// Capture another app's playback with public APIs · Research proposal / not yet tested
// Environment: Kotlin method snippet in an ordinary Android project; complete Manifest, permissions and lifecycle as documented; not a directly executable CLI
// Prerequisites: Same user; playback app permits capture and uses an eligible usage; create AudioRecord after user authorizes projection
// Expected result: An eligible player produces PCM; record denial/silence boundaries
// This review did not automatically execute this example; define scenario-specific success criteria first.
val config = AudioPlaybackCaptureConfiguration.Builder(projection).addMatchingUsage(AudioAttributes.USAGE_MEDIA).build()
val capture = AudioRecord.Builder().setAudioFormat(format).setAudioPlaybackCaptureConfig(config).build()
