# Taku Audio

Experimental media translation has Kotlin direct Kotoba/Grok paths and a local-ASR/cloud-text/local-TTS path. Open Taku Audio → Video translation · Experimental, select the playing application, language direction, captions/speech and original-audio mode. Kotoba/Grok upload audio to fal using device BYOK. `local-hybrid` keeps audio local and sends only finalized text to the configured system text model. The scope is the whole app UID (including all browser media). `fal-whisper` adds experimental segmented captions: short WAV files go to fal temporary storage and Whisper, then recognized text goes to the configured system text model. It only supports CAPTIONS + KEEP, creates no TTS/player and is not a realtime speech profile. The native UI and fal settings expose it; Kotoba remains the default. This is not yet a completed S3 acceptance.

Open **Taku Audio** from a Home page to manage fal and open translation settings. The existing **Realtime translation · Ready** floating entry opens the media settings directly. Starting a session replaces the entry with the live caption window; stopping returns the idle entry. Closing the floating window is done from **Close overlay** in settings and persists until **Open overlay** is used. The session is owned by `MediaTranslationController` in the audio process: finishing or recreating the settings page does not stop it, and reopening settings attaches to the same session and restores its real mode, target app, languages, model, voice and output/disclosure. The provider-default lookup never overwrites an active request. The call and video pages share one overlay switch through `OverlayPreference`. Media preferences keep the app, languages, model, voice and output mode independently of call settings.

`audio.session.open` also accepts `purpose: "MEDIA_TRANSLATE"`, `profileId: "fal-kotoba" | "fal-grok" | "local-hybrid" | "fal-whisper"`, `language`, `targetLanguage`, `targetPackage`, `output: "CAPTIONS" | "SPEECH"`, and `originalAudio: "KEEP" | "REPLACE"`. Caption-only sessions require KEEP. Kotoba uses its model voice/speed. Grok exposes five voice IDs; local-hybrid uses per-language Kokoro voices and reading defaults. Hybrid currently supports English/Chinese. Text events are marked `textKind: "target"`; PCM and keys stay inside the engine. Finish commits the input tail and waits for the provider completion event and consumed playback, with a finite deadline; close cancels.

Paid direct-model smoke: `adb -s emulator-5582 shell am instrument -w org.agentos.audio.tests/.FalDirectProbe`. It requires the existing synthetic PCM fixture and device BYOK, and does not certify quality or speaker output. `tests/media-smoke.mjs` additionally requires the separate synthetic media fixture playing behind the visible developer host; it exercises capture, model, playback and release. Use `-e model whisper` on the same direct probe for Whisper captions, or `TAKU_MEDIA_PROFILE=fal-whisper` for the same media runner. That runner now stops its synthetic player in `finally`; do not leave a looping fixture audible. These are explicit network tests, outside routine unit tests.

Kotlin audio host shared by the Agent composer, completed-reply reading, and the JS SDK/CLI. The independent, platform-signed `org.agentos.audio` APK owns local ASR/TTS, microphone/playback resources, connection-owned sessions and a root-owned policy subscription. Local dictation/reading keep audio and inference on device; the experimental Kotoba profile uploads selected app audio to fal.

S1/S2 are under acceptance. Emulator microphone lifecycle, local inference, reading/playback, native reply controls and preference roundtrips have passed. Representative human quality, physical speaker checks, Android 17/Pixel 9 and ROM integration remain pending. S3 direct fal/media is under acceptance; call routing is implemented and under real-app acceptance; ROM integration and complete continuous-conversation acceptance remain pending. The fal adapters consume device BYOK through the private configuration socket; hybrid requires a configured system text generation model.

See the [spec](../../docs/audio-engine-spec.md), [implementation progress](../../docs/audio-engine-progress.md) and [public request Schema](../../spec/audio.schema.json).

ROM source integration and local acceptance are documented in [ROM.md](ROM.md). Full Pixel ROM/OTA and physical-device acceptance remain separate gates.

## App settings and on-device Agent access

The existing `org.agentos.audio` package has the **Taku Audio** launcher entry. Its home page contains fal key management, the translation default model, voice enablement, and links to desktop voice input, video translation, call translation, local reading, and audio status. Adding the app entry uses Android launcher discovery. Existing Home placement and audio preferences remain in place.

The icon uses Memory's original Taku brand vector. The hub, reading, translation, and status pages follow `MemoryUi` and Memory's settings layout through the app-local `AudioUi`: the same colors, typography, cards, actions, and system-bar treatment. Labels use English defaults and Chinese resources selected by Android's app locale.

The GUI and the standard Agent endpoint use the same settings commands and root configuration owner:

```sh
content call --uri content://org.agentos.audio.open-access --method settings --arg '{"action":"read"}'
content call --uri content://org.agentos.audio.open-access --method settings --arg '{"action":"selectFalModel","model":"grok"}'
content call --uri content://org.agentos.audio.open-access --method settings --arg '{"action":"setAudioEnabled","enabled":true}'
am start -n org.agentos.audio/.AudioHomeActivity
```

`Bundle.result` contains `{ok:true,result:...}` or `{ok:false,code,message}`. The snapshot includes voice policy, revisions, reading profiles, and the fal configured state, masked key, model catalog, and configuration error. Saving a key does not authenticate it with fal. Settings reads and saves make no provider inference requests and do not depend on OpenRouter. The full command and error contract is in the preinstalled [system AI settings instructions](../assistant/assets/skills/taku-system-ai-settings/SKILL.md).

Desktop voice input saves `primaryProfileId` (`zipformer` or `fal-whisper`), `fallbackProfileId` (null or the other profile), and `language` (`system`, `zh-CN`, or `en-US`) under `assistant-entry`. Use `configureDictation` with all three fields and `scope:"assistant-entry"`, or `resetDictation` with that scope. The GUI and Agent use the same root file and validator. Settings reads expose resolved system language, default provenance, errors and backend prerequisites without starting recognition. Corrupt preferences are reported as unreadable instead of displayed as defaults.

The desktop opens `DICTATION` with `settingsScope:"assistant-entry"`; explicit language/profile and other purposes cannot be combined with that scope. Defaults remain local Zipformer, system language and no fallback. Explicit fal Whisper dictation transcribes the source audio without translation or a text model. Cloud capture is bounded to 60 seconds and finalization to 100 seconds. Local finalization is bounded to 5 seconds. Backend metadata exposes the absolute drain deadline. A saved fallback is selected once before capture for missing models, unsupported language or a missing fal key; runtime failures do not replay audio to another backend. Full chat and unscoped SDK requests retain their existing defaults.

Dictation `Level` events report microphone PCM RMS loudness normalized from -60 dBFS to 0 dBFS into 0..1, measured over 100 ms windows. The desktop and Agent share the same bounded history with `levelSource:"pcm-rms"`; text arrivals do not drive the waveform. fal Whisper transcribes successive non-overlapping audio ranges during capture and publishes their accumulated text as replaceable previews. After capture, one whole-recording transcription replaces the preview, including an empty final result. One serial worker coalesces pending audio, so application-submitted preview audio is bounded to one recording duration plus the final recording. This is incremental file transcription, with updates dependent on provider completion.

The key and selected model stay in the existing root credential store. Public settings cannot retrieve the runtime key; its private engine channel and session access remain separate. The launcher and settings passed offline GUI/Agent checks on an isolated copy of the existing Android 17/API 37 AVD, including invalid-request preservation, corrupt-file recovery, and retained key/model/reading data across APK replacement. These checks do not certify cloud authorization, translation quality, physical calls, or a new ROM build.

## Build and checks

Use JDK 17+, Android SDK platform/build-tools 36, and a Kotlin JVM compiler (`TAKU_KOTLINC` selects it). Existing local compiler/dependency caches are reusable.

```sh
bash system/audio-engine/test.sh
python3 system/audio-engine/tests/overlay-lifecycle-device.py --serial emulator-5582
bash system/audio-engine/build.sh --with-tts
bash system/assistant/build.sh
npm test
```

`--with-tts` prepares the optional Kokoro pack; a clean build without it prepares recognition only. An already prepared TTS pack is preserved. Preparation verifies pinned archives/notices and writes a separate manifest with per-file hashes. Kotlin/sherpa native runtime and notices are packaged in the APK; weights stay in `out/model-pack`. ASR-only resources are about 96.27 MB; the combined ASR/Kokoro pack is 522,853,769 bytes. These are unpacked development-resource sizes, not APK sizes or RAM use.

For an authorized rooted development emulator:

```sh
python3 system/audio-engine/install.py --serial emulator-5582
```

The installer checks source/device hashes, installs the module/CLI bridge, stages and replaces only its model directory, and keeps one previous model directory for development rollback. It preserves user accounts/preferences. The updated assistant APK and root Broker must also be deployed through the existing development workflow; the installer does not change the running Broker or its mounts.

## Native and JS access

`out/audio-client.jar` provides `AudioClient`, typed `AudioRequest`, and the higher-level `AudioReader`. Connect/callbacks run on the main thread; inference, recording and playback use engine workers. Kotlin/Java callers can use typed requests or JSON. JSON fields omitted from SPEAK requests inherit saved voice/speed preferences; concrete typed request values are per-request overrides.

Taku Audio → Audio status opens a read-only page showing local model resources, permission/feature prerequisites, current resource use and media volume. It never opens an audio session or changes volume. `audio.capabilities` accepts optional `purpose` and `language` filters; these select `availability` entries, while existing compatibility fields (voices, mediaProfiles, defaults and permission summary) remain global. Each entry reports `available`, `unavailableReasons`, required/missing permissions and model readiness. These are local prerequisites; source capture, remote authorization and network connectivity are checked when starting. `resourceState` shows busy/purpose/profile/phase, whether the lease belongs to the caller, and the previous release result within the current service lifetime. It contains no session ID, other caller identity, transcript or PCM. A Stopping lease remains busy until resource release.

Only capabilities/open/append/finish/close/status are registered. DICTATION supports Zipformer and fal Whisper; SPEAK uses Kokoro. Text chunks are numbered from zero, limited to 500 characters and accepted in order. Native reply reading supports both completed replies and live sentence input. It sends completed sentences through one reading session and waits for chunk playback before advancing. Code/markup filtering remains in the native reply view; the incomplete final sentence waits for reply completion. Pending client input is bounded to 12,000 characters. No arbitrary PCM, paths, endpoint URLs or credentials are public inputs.

An open `requestKey` deduplicates within the caller connection and the retained 32-session window. Identical replay returns the same handle (including a completed one); conflicting content is rejected. Append retains the last 32 accepted indices; identical replay does not synthesize twice. No automatic reconnect/replay is performed.

Open/status expose the selected profile/language/voice/speed. Events carry device monotonic timestamps captured when the event is created, not when a UI later renders it. Closed confirms resource release; accepted input is not the same as completed playback. The native realtime entry uses a separate internal compatibility lease and is not advertised as a local model.

Native composer behavior: partial text previews, final segments enter the bound draft, edit/send/thread/navigation/hide cancel, and long-press microphone selects Chinese/English. Completed assistant replies have reading/stop controls; tools, plans, code blocks and raw Markdown markup are not submitted as speech. Starting dictation stops active reading first. Long-press reading or use Taku Audio → Local reading for voice/speed settings. Preferences are held in the root-owned `ai/audio-settings.json`, updated through the shared GUI and Agent settings operation, and apply to the next session.

Media/call sessions opened without `profileId` use the fal model selected in Taku Audio (`AudioProfiles.defaultMedia`; calls fall back from captions-only Whisper to Kotoba). Parameter validation checks the same resolved profile, so an unsupported combination fails as `INVALID_PARAMS` before opening.

`Closed.reason` for call translation reports the actual cause: `CALL_ENDED` (target stopped call capture or the audio mode changed), `CAPTURE_SILENCED`, `CALL_STATE_UNAVAILABLE`, `CAPTURE_FAILED` (capture path broke), or the model's own code such as `QUEUE_BACKLOG`. Configuration failures from the Broker keep their reason: `PROVIDER_NOT_CONFIGURED` (missing/unreadable fal key), `POLICY_REVOKED` (system or voice AI paused), `TEXT_TRANSLATION_FAILED`, `CONFIG_UNAVAILABLE`. The fal settings object (`fal` in the config snapshot, `falSpeech` in the Taku AI read) carries `error`: `null`, `KEY_UNREADABLE` or `MODEL_UNREADABLE`; an unreadable file reads as not configured instead of failing the read.

Capabilities can be queried with the engine stopped and no visible developer host:

```sh
node sdk/cli/taku.mjs call audio.capabilities --transport adb --serial emulator-5582 --params '{"purpose":"SPEAK","language":"en-US"}'
```

The shell/root bridge uses a DUMP-protected bootstrap receiver to bind inside the application. The temporary binding is released after the last authenticated socket disconnects, or if startup receives no socket. This does not open recording/playback. `app_process` cannot directly bind as an Android application process. CLI **audio sessions** still need an explicitly visible device host:

```sh
adb -s emulator-5582 shell am start -n org.agentos.audio/.AudioHostActivity
node sdk/cli/taku.mjs call audio.capabilities --transport adb --serial emulator-5582
node sdk/cli/taku.mjs audio dictation --transport adb --serial emulator-5582 --params '{"language":"en-US"}'
node sdk/cli/taku.mjs audio speak --transport adb --serial emulator-5582 --params '{"language":"zh-CN","text":"你好，这是本地朗读测试。"}'
```

Ctrl-C finishes a dictation tail or cancels reading. Leaving the host cancels developer sessions. ROMs deliver the bridge at `/system_ext/etc/taku-audio/taku-audio-bridge.jar`; development installs use `/data/local/tmp/taku-audio-bridge.jar`. Native business callers use Binder; CLI uses a persistent root/shell-only socket into the same runtime. Unix peer/Binder identities and connection ownership determine access; a session ID alone is insufficient.

```js
const client = new TakuClient({transport: 'adb', serial: 'emulator-5582'});
const events = client.audioEvents(); // Subscribe before opening.
try {
  const session = await client.call('audio.session.open', {purpose: 'SPEAK', language: 'en-US'});
  await client.call('audio.session.append', {sessionId: session.sessionId, index: 0, text: 'Hello.'});
  await client.call('audio.session.finish', {sessionId: session.sessionId});
  for await (const event of events) {
    if (event.data.sessionId !== session.sessionId) continue;
    if (event.data.kind === 'Closed') break;
  }
} finally {
  await events.return();
  client.close();
}
```

Create a new event iterator for a later session after breaking a for-await loop; JavaScript closes that iterator on break. One transport can remain connected across sessions. Slow event consumers close with an explicit error; audio/transcripts are not persisted by the engine.

## Verification

```sh
TAKU_OS_SERIAL=emulator-5582 node system/audio-engine/tests/device-smoke.mjs
TAKU_OS_SERIAL=emulator-5582 node system/audio-engine/tests/speech-smoke.mjs
python3 system/audio-engine/tests/composer-smoke.py --serial emulator-5582
bash system/audio-engine/tests/build.sh
adb -s emulator-5582 install -r system/audio-engine/out/AudioEngineTests.apk
adb -s emulator-5582 shell am instrument -w org.agentos.audio.tests/.ReadingSettingsProbe
adb -s emulator-5582 shell am instrument -w org.agentos.audio.tests/org.agentos.assistant.ReadingUiProbe
```

The microphone/speech smokes require AudioHostActivity visible. They check actual recording/synthesis/playback lifecycle, ownership, conflicts, deduplication and cancellation. `ReadingUiProbe` renders a synthetic completed reply with the production view, filters Markdown/code, and restores the original page without creating an Agent turn. `ReadingSettingsProbe` verifies actual root preference save/read/revision/error behavior and restores an absent preference as absent.

`RecognitionProbe` separately exercises the production StreamingRecognizer using prepared synthetic PCM16 mono/16kHz files `/data/local/tmp/taku-audio-incoming.pcm` and `taku-audio-outgoing.pcm`. These fixture checks do not certify human ASR/TTS quality. `policy-smoke.mjs` requires the freshly compiled NativeSocketProbe at `/data/local/tmp/taku-audio-native-probe.jar`; it disables the voice switch during recording, checks revocation, then restores its previous effective value.

The mediaPlayback foreground service and focus lifecycle follow the [Android audio-focus requirements](https://developer.android.com/media/optimize/audio-focus). Model configuration follows the [official sherpa Kokoro example](https://github.com/k2-fsa/sherpa-onnx/blob/master/java-api-examples/NonStreamingTtsKokoroZhEn.java); actual supported quality/voices require the project's separate acceptance.

## Experimental call and continuous modes

Taku AI → Call translation · Experimental opens the same native setup with independent incoming/outgoing models and voices. Establish the selected app's call first. `CALL_TRANSLATE` uses `language` for the remote language and `targetLanguage` for the local language; outgoing reverses them. Set `output: "SPEECH"` and `originalAudio: "REPLACE"`. Optional `outgoingProfileId`, `outgoingVoiceId`, and `outgoingSpeed` control that direction. Both directions share one audio lease; a fal direction requires device BYOK. The two-UID route and a short connect/cancel check passed; WeChat/LINE spoken quality has not been verified.

Tap Continuous conversation beside the Agent microphone for the experimental half-duplex mode; the microphone long-press menu also retains this entry. Enable only with an empty draft and no active task. Speech endpoints automatically use the original Agent submission, filtered reply sentences are read locally, and the next listening turn begins after playback release. Tap Leave conversation or the microphone to exit. Synthetic speech has reached local ASR, the real Agent submission and local reply playback. One synthetic native roundtrip has now passed including automatic return to listening. Human/device acceptance remains pending. The preserved Codex realtime entry separately requires an OpenAI API Key and is outside this local ASR/text Agent/local TTS path.

Long-press Continuous conversation (or the microphone menu) to explicitly choose **Interruptible · Experimental**. The default click still uses half-duplex. This option uses `audio.session.open` with `purpose: "SPEAK", interruptible: true`; normal reading leaves it false. The engine owns ASR, microphone, enabled device AEC and TTS under one resource lease. The first recognized speech stops queued/output reading; the same recognizer keeps the entire interruption through its final phrase. Events are `Interrupted` → final source `Text` → `Closed` with `reason: "interrupted"`, `completion: "incomplete"` for the interrupted reading and a separate release result. No replacement listening session is opened while the old capture remains active. The original Agent task is asked to stop; its terminal snapshot, rather than cancellation acceptance, gates the next automatic submission. Failure or a 5-second task-settlement timeout preserves the final phrase in an empty original-thread draft and exits the mode.

This option requires local ASR/TTS resources, microphone permission and AEC that can actually be created/enabled. Missing AEC is explicitly unavailable; it does not silently fall back to simultaneous unprocessed recording. Capabilities expose experimental `speechInterruption` metadata; `echoCancelerAvailable` is a platform declaration, not proof of echo suppression or recognition quality. Real speaker echo, false interruptions and interruption latency have not passed acceptance. No recording/playback test was run for this addition; related lifecycle/contract checks and compilation are separate evidence.

### Whisper silent-input boundary

Before any file upload/transcription, `WhisperSignalGate` checks each 16 kHz PCM range.
It removes the DC component from 20 ms windows and requires at least 100 ms above the
180 RMS floor, including a 60 ms run. Empty PCM, near-silence, DC offsets and isolated
startup clicks return an empty transcript without contacting FAL. Previews and final
recognition use this same gate; words are never blacklisted. This is an energy floor,
not a general speech/noise classifier. Audible speech still uses `fal-ai/whisper`.
`WhisperSilenceProbe` exercises the actual transcriber with silent inputs and an invalid
credential, confirming it returns before network authentication. The authoritative
`assistant/get.voice` state and ordinary audio events continue to expose the result.

Whisper transport interruptions receive at most one retry per HTTP request, within the original
final drain deadline. Authentication/protocol failures are not retried. A transient preview
failure retains the unprocessed PCM and waits for additional audio; it does not terminate capture
or busy-loop. Final recognition must still succeed before any transcript is submitted. Safe logs
include only the provider host and exception type, never credentials, audio addresses or text.
`WhisperNetworkProbe` checks timeout recovery and non-retryable authentication offline; its
explicit `-e mode live` mode uploads `/data/local/tmp/taku-voice-check.pcm` using the device's saved
FAL credential. Supply synthetic test audio only. It reports the real transcript and elapsed time.
