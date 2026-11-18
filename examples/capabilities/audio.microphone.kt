// Microphone and speech capture · Research proposal / not yet tested
// Environment: Kotlin method snippet in an ordinary Android project; complete Manifest, permissions and lifecycle as documented; not a directly executable CLI
// Prerequisites: Authorize in the foreground and configure recording, routing and lifecycle; newer Android versions restrict background starts
// Expected result: Only non-silent PCM establishes input; the example needs a complete read and stop/release lifecycle
// This review did not automatically execute this example; define scenario-specific success criteria first.
val record = AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.MIC).setAudioFormat(AudioFormat.Builder().setSampleRate(16000).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build()).setBufferSizeInBytes(4096).build()
record.startRecording()
