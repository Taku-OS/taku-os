# Audio Engine implementation progress

Updated: 2026-11-12. Evidence is scoped to the versions and devices named in each section. The [spec](audio-engine-spec.md) remains the target baseline; the full audio-engine goal is not complete.

## S1: shared sessions and local dictation — in progress

Implemented:

- Independent platform-signed Kotlin `org.agentos.audio` APK, Binder client, shared session runtime and exclusive audio lease. The existing native realtime UI participates through an internal compatibility lease; revocation waits for its resource release acknowledgment.
- Local Chinese and English Zipformer streaming ASR using pinned sherpa-onnx 1.13.8. The same `StreamingRecognizer` is used for microphone dictation and fixed-audio verification. Audio stays on device.
- Separate model pack and development installer: host and device SHA-256 verification, staged model replacement, private application storage. Recognition weights are approximately 96.27 MB; the optional Kokoro pack is now prepared separately for S2; weights remain outside the base APK.
- Agent microphone now uses `AudioClient`, previews partial text and inserts final segments into its bound draft. Edit/send/thread change/navigation/hide cancel the session; late results are checked against draft identity. Long-press the microphone for Chinese/English selection.
- Root-owned configuration subscription verifies the engine package UID and platform signature. System AI and voice switches stop dependent sessions. The settings UI has a voice switch. Current snapshots carry the system revision; root-owned reading preferences and audio revision were added in S2; S3 added restricted fal credential delivery and change notifications.
- JS SDK/CLI persistent transport and events for capabilities/open/finish/close/status, device-verified connection ownership, bounded event queues, and cancellation on disconnect. Developer CLI microphone sessions require the visible device host. S2 added append and local TTS; S3/S4 translation purposes use the same registered session methods.

## Verification completed

- Kotlin runtime fixture: normal tail completion, ordered events, ownership, resource busy, cancellation during preparation, late callback rejection, revocation, and legacy lease release. These are small behavior fixtures, not an Android audio-stack simulation.
- Node SDK/protocol tests: 7 passed, including reading/chunk validation. Existing closed-parameter behavior for readonly methods remains covered.
- Audio Engine and assistant development APK/Broker compile successfully; APK signatures verify.
- ARM64 Android 16/API 36 emulator `emulator-5582`: actual JS SDK capability query, model presence and root configuration connection passed.
- Actual microphone lifecycle via JS SDK: `Preparing → Ready → Running → Draining → Closed`; cross-connection access rejected, competing capture rejected, resources released and a new session opened. This checks microphone operation/lifecycle, not recognition quality from silent emulator input.
- Actual native composer controls: click microphone to reach listening, finish normally, and preserve the existing draft with quiet emulator input. The first attempt found `CAPTURE_SILENCED`; binding with `BIND_INCLUDE_CAPABILITIES` fixed it. A later regression after APK update found a stale assistant binding and a toolbar that remained detached after reattachment. Rebinding the existing assistant selection and resetting the attached flag restored the test; the final native-control run passed. Preparing cancellation now clears its preview. This is not evidence of text insertion quality with live speech.
- Actual runtime policy change: disable the voice switch while recording, observe `POLICY_REVOKED` and resource release; a subsequent open is denied. The test restores the previous effective switch value.
- Two existing synthetic fixtures through the production ASR Adapter on-device: English `I WOULD LIKE A CUP OF COFFE` (3 partials, 825 ms including model setup and processing); Chinese `我想要一杯咖啡` (5 partials, 361 ms). These are batch-processing diagnostics; they are not realtime latency measurements. `qualityApproved=false`; the English output has errors.


## S2: local reading — implemented, acceptance in progress

- Kokoro local synthesis and streaming AudioTrack playback share the same session runtime and resource lease as dictation. Synthesis produces bounded conversion blocks and blocks on AudioTrack backpressure; completion waits for consumed playback frames. Focus loss, cancel and policy revocation stop output and release native resources/focus. A mediaPlayback foreground notification provides a stop action.
- SPEAK sessions accept ordered TextChunks, deduplicate repeated indices and enforce bounded input. Open request keys are scoped to the connection and a 32-session retention window; duplicate requests do not create a second synthesizer. Empty/preparing cancellation and accepted text before Ready are handled separately.
- Completed and in-progress Agent replies have a production reading/stop action; in-progress replies submit only completed sentences. Code blocks and raw Markdown markup are filtered through the existing AST; tools/plans are not submitted. Starting dictation stops active reply reading first. Leaving the reply view stops its reading.
- Taku AI exposes local reading settings; the root Broker persists per-language voice/speed, advances audioRevision and notifies the engine. Six migrated voice choices are exposed. Defaults apply to the next session; reset restores an absent preference. Voice quality has not been certified for all six choices.
- The optional combined recognition/Kokoro resource pack is 522,923,411 bytes across 387 files, separate from the base APK. Host/device hashes verify. Repeated development installation now clears only its static temporary staging directory first, avoiding adb's directory nesting behavior.
- Core runtime fixtures, seven Node tests, APK/Broker compilation and existing system-AI host regressions passed. Native reading of a synthetic completed reply passed without creating an Agent turn; code-block filtering passed and the original view was restored. Root preference save/read/revision/invalid-input checks passed, with the previous preference restored.
- Final emulator smoke: English two chunks, first written non-silent PCM 4393 ms including setup / 658 ms after Ready; Chinese two chunks, 1736 ms including setup / 424 ms after Ready. A previous round was 5234/581 ms and 1500/415 ms respectively. These are individual runs with different preparation/cache states, not P50/P95 or a language-speed ranking. The proposed 500 ms ready-state target is not demonstrated for English on this AVD.
- Repeated open/append produced exactly two playback chunks per language; cancel during playback restored resources. The final S2 build also passed the existing real microphone lifecycle/ownership/resource-conflict and runtime voice-switch revocation checks.

Physical speaker output, representative language/number/pronunciation review, Android 17/Pixel 9 and stable performance acceptance are still pending. `qualityApproved=false`. Sentence-stream reading is now implemented and device-verified; it reuses the indexed-chunk backend and also supports completed replies. The first multi-language smoke failure was a fixture lifetime bug (for-await closed its iterator after the first session); the fixture now subscribes separately for each session without loosening its assertions.

## S3: direct fal and media translation — in progress

- Added Kotlin Kotoba WebSocket adapter using pinned OkHttp 4.12.0 / Okio 3.6.0. It reads the existing device BYOK through the UID/signature-verified root configuration socket. Credentials are absent from public snapshots/events; no host `.env.local`, relay, Magpie audio route or adb reverse is used.
- BYOK fal key/model changes advance audioRevision and notify the engine. Removing the provider revokes fal profiles only; changing a key preserves an established connection. Provider-removal isolation is covered by the small shared-runtime fixture; live deletion/replacement acceptance remains pending.
- Added MEDIA_TRANSLATE to the native and JS request contract, with an explicit target package, language pair, caption/speech output and keep/replace mode. Implemented profiles are `fal-kotoba`, `fal-grok`, and `local-hybrid`; all three have passed a synthetic single-utterance device check. Kotoba voice/speed customization is explicitly rejected.
- Media capture and model transport are separate Kotlin components. Capture selects target UID + MEDIA, without an opt-out override or microphone. Speech playback has an aggregate capacity including AudioTrack, bounded cloud bursts and sustained-backlog failure. Route restoration failure is represented as `restored=false`.
- Added experimental native video setup and bounded caption overlay with manual scrollback and Latest. Taku AI links to it. UI acceptance and full video acceptance are still in progress.
- Actual on-device direct fal test passed: 1,468 ms synthetic English input → `我想喝杯咖啡。`, final event and 341,760 bytes of non-silent translated PCM. Connect 1,449 ms; Ready → first received translated PCM 663 ms; end-input commit → completion event 4,619 ms. This is one network/model test, without AudioTrack playback; it is not a video or physical-output benchmark.
- Actual production media-capture adapter passed two independent-UID tone tests in keep and replace modes: target amplitudes about 2808/2904, bystander amplitude below 1e-10. These tests used no microphone or network. Whole-session video/model/playback acceptance is separate.
- The first whole-session CLI attempt correctly ended SOURCE_UNAVAILABLE: its opaque developer host hid the VideoView, which stopped its surface/audio. Changing the host to a visible translucent control window kept the source VideoView/audio alive. The subsequent whole-session smoke passed; the failed attempt is not recorded as model success.

## Remaining work

S1 still needs native composer acceptance with real speech and edits/thread switches, representative human microphone quality/latency measurements, Android 17/Pixel 9 acceptance and the remaining configuration/permission checks. The current default profile is an experimental migration candidate, not a quality-approved universal default. Typed AudioRequest, shared native/JS parameter validation, an explicit audio request Schema and bounded request/chunk deduplication are now implemented. Complete capability/resource diagnostics and the broader permission/physical-device acceptance remain pending.

Continue S2 quality and S3 video acceptance, continue S4 real-call acceptance, S5 ROM/resource delivery, and S6 continuous conversation. fal inference has now been invoked with synthetic input through device BYOK; host `.env.local` remains untouched and is not imported. Existing `audio-translation` code and paths are preserved.

Reproduction commands and deployment requirements: [module README](../system/audio-engine/README.md).

The full repository structure gate initially stopped on a non-UTF-8 Microsoft Speech notice in the preserved old download cache. Restoring the exact `/system/call-translation/downloads/` ignore rule already present on `audio-translation` resolved this without deleting or editing the cache. The full structure gate now passes. Existing IME/System-AI host regressions also passed: 23 + 12 + 37 + 30 checks.

The latest S3 whole-session SDK smoke passed with the independent-UID synthetic video: target Chinese text, non-silent PCM consumed by AudioTrack, successful tail completion and restored route. Ready → first written non-silent PCM was 1,356 ms in this single run. After closure, source metrics reported device type 2 (speaker) and continued playback. This does not certify YouTube, physical output, all language pairs, provider deletion/replacement, or long-session behavior. The native setup/overlay UI remains under acceptance. Node tests now total 8 passing.

## Resumed implementation (2026-10-28)

- Added production sentence-stream reading. The reply button is available during an Assistant answer, only completed plain-text sentences enter one reading session, and final completion flushes its tail. The existing native device runner passed with `streamedReply=true`, `playedBeforeReplyComplete=true`, Markdown code filtering and actual Kokoro/AudioTrack output. It still does not certify physical speaker quality.
- The existing Taku AI video entry was found and successfully opened the native setup page. The earlier missing-entry check is no longer evidence that the entry itself is absent.
- Video start now brings forward the selected source app. Model connection reports its preparation status before capture; actual media-player pause/end requests tail draining and resource release. These source-lifetime changes are still undergoing native UI verification.
- Added source Soong dependency `taku-audio-client` for the assistant. Full ROM integration/build acceptance remains pending.
- Grok's device Kotlin adapter and MessagePack dependency are implemented; shared native/JS profile selection, a voice list and source transcription are wired. Its first real direct-provider attempt returned `PROVIDER_PROTOCOL_ERROR`; interop is being corrected. It is not recorded as a model pass.
- The public YouTube watch page currently requires sign-in to confirm a human, so the actual video did not load. No YouTube acceptance was claimed and no Google login was changed.

- Grok interop is now verified with the existing synthetic direct-provider runner: `我想要一杯咖啡。`, 16 kHz non-silent PCM, final transcript and completed tail. Connect 2,245 ms; Ready → first received PCM 3,715 ms; finish → completed tail 2,263 ms. The earlier failure was the incremental `session.updated` acknowledgment of the final-turn mode: that acknowledgment does not repeat audio format, which had already been verified during initial setup. These are one-run diagnostics, not a ranking.
- Added `local-hybrid`: local Zipformer → private system text translation → optional local Kokoro. The root operation reuses configured text models, does not create an Owner Agent task, and does not receive audio. It snapshots the chosen model per session. Stop closes the active IPC request and native inference releases on its owning worker. Actual single-utterance validation passed with `audioUploaded=false`, `我想要一杯咖啡。` and non-silent local output. Connect/model preparation 9,744 ms; Ready → first PCM 12,369 ms; finish → tail 11,006 ms. This run is slow and is not realtime performance acceptance.
- Native model selection now includes Kotoba, Grok and local-hybrid, with provider-specific voice choices and upload disclosure. Hybrid is independent of fal key availability; provider deletion revokes only fal profiles. Original-cloud Whisper migration, full native video acceptance, calls, ROM and continuous Agent conversation remain pending.

## S4: call routing implemented, real-app acceptance pending

- Added Kotlin `PlatformCallCapture` and `CallTranslationDriver`: UID + VOICE_COMMUNICATION downlink redirection, an independent recognition microphone, translated downlink playback and translated uplink injection. The engine does not change the call owner's global audio mode/volume. Model preparation precedes route changes; stop/provider removal/hangup share the existing cancellation and release path.
- `CALL_TRANSLATE` is available through the same native/JS methods. Incoming and outgoing profiles/voices/speeds are independent; the language pair reverses for outgoing. Source/target text and playback events carry direction. The native Taku AI entry is explicitly experimental. fal deletion checks both directions. Any fal direction marks the overall call as cloud audio.
- Reused the existing two-UID synthetic call endpoints and extended the existing route runner. Actual downlink target amplitude was 983.27; bystander amplitude was below 1e-10. Target recorder observed the injected 3 kHz tone at amplitude 2972.16 through device type 25; the bystander observed zero through its normal microphone (type 15). Route release passed. This was a route check without cloud/model translation.
- A separate short shared-runtime check connected both Kotoba sessions, reached Preparing → Ready → Running, then canceled and closed with `restored=true`. It does not certify spoken translation quality, physical output, LINE or WeChat compatibility. Both synthetic call endpoints were stopped afterward.
- Original `audio-translation` files/branch remain untouched. No real person was called.

## S6: initial half-duplex conversation implementation

- Added Kotlin `AudioConversation` client orchestration. Explicitly select Continuous conversation beside the microphone; the long-press menu also retains the experimental entry. It requires an empty composer and no active task: local ASR endpoint → existing composer/send path → matching Agent task's filtered sentence reading → listen again. Ordinary dictation still requires the user's send action.
- Existing drafts/attachments are not automatically sent by enabling the mode. Manual editing/navigation/thread changes stop it. Submission errors use the existing draft restoration. Automatic reading only uses the runtime native turn ID bound to the new task, so queued snapshots cannot read old history.
- Synthetic input has exercised ASR → automatic Agent submission → reply playback → automatic return to listening in the native UI; see the latest scoped roundtrip below. Automatic voice interruption/echo handling and migration of the existing native realtime recorder/player remain pending; the continuous mode is not claimed as S6 acceptance.

ROM integration is awaiting explicit permission after automatic approval review rejected the proposed persistent privileged-app/audio-permission configuration. That rejected command did not execute; no ROM integration files or device image were changed by it. Existing development APK audio permissions are separate from ROM packaging. Native complete-video acceptance remains pending; the short synthetic native UI check below supersedes the earlier player crash. The user has deferred YouTube work. No YouTube acceptance is claimed.

Final deployment: current engine/client/assistant APKs installed without clearing data. Existing native composer smoke passed listening, normal finish and unchanged quiet-input draft. Its first final-deployment attempt missed the completion message because conversation polling cleared voice feedback; preserving audio feedback fixed that regression, and the same check then passed. Kotlin runtime, 9 Node checks, component/test builds, metadata and repository structure checks pass. Build hashes and scoped results are in ignored `out/audio-engine-validation/resumed-summary.json`. No commit/push or device flash was performed.


## Native UI and conversation follow-up (2026-10-28)

- YouTube work is deferred at the user's request. Native Kotoba setup with the existing independent-UID synthetic video displayed Chinese floating captions. Pausing the source removed the overlay/foreground translation and the native page reported Translation complete; original audio restored. This verifies that short native flow only; resumed source playback and physical output were not demonstrated in this run. The old development player's metrics-after-release exception was repaired in its temporary build, without modifying the preserved translation branch.
- The same AVD was restarted with the official SDK's authenticated audio controls. Account/model data remained in place; the existing development Broker bind mount was restored manually. This is not ROM packaging or reboot-independent acceptance. Host microphone stayed disabled. SDK microphone injection did not produce ASR in the first attempt, so it is not recorded as a pass.
- Reusing a private test UID audio policy delivered the synthetic greeting 你好 to the engine's real AudioRecord and Zipformer. The original Agent submission completed and local reply playback was observed; one run counted 46,220 non-silent samples at 24 kHz. This is synthesized input and emulator output, not human microphone/speaker quality acceptance.
- That real flow exposed Address already in use when the bound service was recreated. Closing/shutting down the listening socket alone was insufficient: a blocked native accept still held its reference. AudioSocket now wakes and waits for its listener to exit before returning from close. An isolated on-device regression changed from failing on the second open to passing 10 consecutive close/reopen cycles. The fix is installed. A subsequent native attempt did not yield a valid complete UI/output observation; automatic return to listening remains unconfirmed.
- Added a visible Continuous conversation / Exit conversation button and accessibility state descriptions. Readiness now requires the displayed and selected conversation to match, and automatic input only reports success when the existing submission path actually starts. Existing draft/send/attachment behavior stays in the original composer path.
- Removed the failed instrumentation UI long-press/polling orchestration. The existing small private microphone injector remains separate from production; no PCM test endpoint was added to the engine.
- Engine, assistant and existing device probes compile. Latest APKs are installed without clearing data. Broader Node/Kotlin/structure checks reported above are earlier scoped checks; they were not rerun as a new matrix for this small lifecycle/UI follow-up. No commit, push, ROM permission integration or flash was performed.


## Continuous conversation roundtrip verified (2026-10-28)

- User decision: the current Codex realtime path requires a separate OpenAI API Key. Do not pursue subscription-based connection or a new realtime Adapter in this iteration. Three newly drafted, unwired migration files were removed before compilation or deployment; the existing NativeVoiceView remains on its preserved legacy path, with the API requirement clarified in its message. Authentication and model configuration were not changed.
- Added content-free `TakuAudioConversation` stage logs to the Kotlin client. They distinguish preparation from actual `Running` microphone state and avoid the earlier unreliable UI-tree polling. They contain no transcript, PCM, account or credential data.
- One native UI roundtrip passed using the same prepared synthetic greeting through actual AudioRecord and local Zipformer. Recognized input: 你好. The original Agent task completed with 你好，我是 Taku。有什么可以帮你？. Local Kokoro output contained 54,689 non-silent samples at 24 kHz in the emulator output stream.
- The recorded lifecycle was `preparing → listening → waiting → speaking → preparing → listening`. The second listening stage is emitted only after the new dictation session reaches `Running`, following release of the reading session. This closes the previously unconfirmed automatic-listening handoff. The mode was then exited normally before the private injector completed. No host microphone or Codex realtime was used.
- This is one synthetic native-flow check, not a human speech-quality/latency benchmark, physical speaker verification, barge-in/AEC acceptance, multi-round endurance result or full S6 acceptance. Automatic voice interruption and remaining supported-device work are still pending. The existing realtime resource migration is deferred until its required API authorization is available.
- Engine and assistant builds passed and were installed without clearing data. Existing broad checks were not expanded or rerun as a matrix. No ROM integration, commit, push or flash was performed.


## Whisper segmented captions (2026-10-28)

- Added Kotlin `FileSpeechSegments` and `WhisperCaptionSession`: bounded four-second phrases, explicit overlapping-boundary de-duplication, tail flush, direct fal ASR and existing restricted system text translation. The serial work queue is bounded; cancellation closes active network/text requests. No TTS/player is created for this profile.
- `fal-whisper` is available through the same native/JS/CLI contract, Schema and Taku AI first-level fal selection. It only accepts MEDIA_TRANSLATE + CAPTIONS + KEEP with no voice/speed customization; it cannot be used in either call direction. Native speech controls are disabled for this choice. Existing saved preferences remain untouched; Kotoba remains the default, and call setup falls back to Kotoba if the global media preference is caption-only Whisper. The private text translator now accepts the same Chinese/English/Japanese/Korean language choices used by the media UI; only English → Chinese was model-tested here.
- The first attempts returned HTTP 422, finally diagnosed as `Unsupported data URL`. They were failures, not model passes. The corrected adapter uses the official fal client's scoped CDN token/upload protocol before invoking Whisper, requests `expiration_duration_seconds=300`, and keeps file URLs/tokens private. That is a requested provider retention policy, not verified remote deletion. Logs contain HTTP status only; the synthetic diagnostic excludes keys and audio addresses.
- Actual device BYOK test passed: 1,468 ms synthetic English → `I would like a cup of coffee.` → `我想要一杯咖啡。`, final translation and zero output PCM. Setup 1,070 ms; Ready → first translation 8,947 ms; input-finish → complete tail 7,416 ms. These are one-run caption diagnostics and are slow; no speed improvement or model ranking is claimed.
- The reused media SDK runner also passed selected-UID video capture → Whisper → system translation → captions → complete/restored closure, with no Playback events. Ready → first translated text was 13,935 ms. Source metrics afterward reported continued playback on device type 2 (speaker). This does not certify physical sound, long videos, human quality, YouTube or realtime usability.
- After that check, the synthetic source was mistakenly left looping and the user heard it repeatedly. It and the audio engine were stopped immediately. Read-only audio inspection showed media/assistant at 5/15; volume was not changed. The existing runner now stops only its dedicated synthetic player in `finally` on success or failure. This cleanup edit passed syntax/whitespace checks; no further audible test was run after the user complaint.
- Scoped Kotlin and 5 audio SDK tests passed, including tail conservation/overlap and caption-only guards. Engine, assistant and probe builds, JSON metadata and structure checks passed. Current deployment uses the existing temporary development Broker mount, not ROM packaging. Remaining native Whisper UI/long-session acceptance and prior milestone gaps remain open. No commit/push, login change or flash was performed.

## Read-only prerequisites and cold CLI connection (2026-10-28)

- Added purpose/language-filtered `availability`, permission diagnostics and shared `resourceState` to capabilities. Reasons distinguish missing local weights, disabled policy, denied consumer, missing fal/text model configuration, platform permissions, call state, host visibility and active/Stopping leases. No session/caller identity, transcript, PCM, credential or provider URL is returned. Global compatibility metadata remains unfiltered. Source/remote authorization/network checks remain deferred to opening; previous restoration is scoped to the current service lifetime.
- Added Taku AI → Voice status, a read-only native page with purpose/language choices, local prerequisites, current lease and media volume. Its settings buttons require user action; merely opening it does not record/play or adjust volume. Device inspection rendered enabled Chinese local recognition, idle resource and media volume 5/15.
- The cold JS SDK check exposed a real background `startservice` failure. The first attempted direct Context binding also failed: app_process has no registered Android application caller, as required by Android ActiveServices. The final bridge instead triggers a DUMP-protected receiver; binding occurs inside the application and lasts only until its authenticated CLI sockets close (bounded timeout if none attaches). Socket peer UID and expected server UID checks remain. This changes no ROM permissions or session host guard.
- Rebuilt and deployed engine/bridge. The real JS SDK, with the engine force-stopped and developer host absent, successfully queried English SPEAK and Japanese MEDIA_TRANSLATE. It returned the installed Kokoro resource, HOST_REQUIRED, filtered media entries and caption-only Whisper without opening an audio session. After SDK close, `dumpsys activity services org.agentos.audio` showed no running service. Scoped SDK tests (6) and protocol/whitespace checks passed. No recorder/player/model call, host microphone, volume change, YouTube or real call was used in these checks.

## Resumed: experimental voice interruption connected (2026-10-28)

- Finished the previously paused Kotlin implementation and its Java callers. Ordinary Continuous conversation remains half-duplex; long-press its button or use the microphone menu to explicitly select Interruptible · Experimental. The native entry refuses a platform without AEC before starting that mode. No preference automatically enables it.
- SPEAK accepts an explicit boolean `interruptible`. A private BargeInCapture owns local Zipformer, VOICE_COMMUNICATION recording and actually enabled device AcousticEchoCanceler under the existing reading lease. First recognized speech pauses/flushes reading and rejects new answer chunks; the same ASR instance delivers the final interruption before the lease is released. Runtime publishes Interrupted → Draining/final source Text → Closed(interrupted, incomplete), with resource-release status. This is code behavior under lifecycle verification, not hardware echo/quality acceptance.
- AudioConversation asks to cancel only its bound old task, then requires the actual terminal snapshot and closed audio capture before the next original composer submission. A late cancellation acknowledgement does not override an already terminal task. Cancellation failure or a 5-second post-capture wait preserves finalized input in an empty original-thread draft and exits. User edits/thread changes are not overwritten; manual exit also preserves finalized, unsubmitted input. This does not pause an Agent goal.
- Engine/client/assistant compilation passed. Existing Kotlin runtime coverage now includes one interruption, duplicate interruption suppression, rejected late append, retained final text and release ordering; related JS SDK contract tests total 7 passed. Metadata/whitespace checks passed. No new audio fixture framework or test matrix was added.
- Deployed both current APKs and the CLI bridge without clearing user data. The actual JS SDK queried the installed engine, confirmed idle resources and experimental interruption metadata. **This AVD reports echoCancelerAvailable=false**; it cannot validate this AEC-backed mode. No microphone, playback, cloud-model call or volume change was used in this continuation. Actual speech/echo/false-interruption acceptance remains open on a supported device. Scoped hashes/results: ignored out/audio-engine-validation/interruption.json.
- Prepared a [concrete unapplied ROM candidate](reviews/audio-engine-rom-2026-10-28/README.md), replacing the earlier placeholder proposal with a patch that passes git apply --check and isolated temporary application/Python/XML/source-hash checks. It freezes this 14,534,240-byte component APK and 49 source inputs, adds the engine/permission module and sealed image snapshot input. Repository ROM files and device permissions remain unchanged. Applying the patch awaits the explicit approval question already presented; no full image build or flash is authorized by this candidate. Model-resource delivery and reboot acceptance remain separate S5 work.
- Full goal remains incomplete. YouTube and new Codex realtime work stay deferred as previously directed; real WeChat/LINE and physical-device quality still need their corresponding devices/accounts and acceptance.

### Home media-translation shortcut (2026-10-28)

- Added Live translation beside Taku Agent on the first home screen. A tap restores the last media setup, starts its floating window and returns to the selected app; a long-press opens setup. Missing/deleted source apps open setup instead of silently selecting another app.
- Media preferences now persist the selected app, language pair, model, voice and caption/speech/keep/replace output independently of call preferences. The previous app/language/voice preferences migrate without changing call settings. Reopening an active media session returns to its source app; the single Activity instance avoids opening duplicate sessions from repeated shortcut taps.
- Both APKs compiled and were installed/hash-verified on the existing API 36 emulator. Actual home tap opened the YouTube browser and Kotoba floating window; long-press opened setup. The saved preferences were WebView Shell, English → Chinese, Kotoba, SPEECH + REPLACE. The selected video remained paused in this short check and the session reached the existing SOURCE_UNAVAILABLE timeout; this check does not claim new end-to-end translation output or latency results. Earlier user-authorized YouTube playback did produce Chinese floating captions after sign-in. New Codex realtime/API-auth and physical-device/call acceptance remain separate pending work.

### Persistent translation entry (2026-10-29)

- Moved the translation window from `MediaTranslationActivity` to a process-wide `OverlayHost`. A new `TranslationOverlayService` is a `specialUse` foreground service started by the home app; it keeps a small **Live translation · Standby** entry visible over other apps. `MediaTranslationActivity` uses the same host for live captions and reverts to the entry when a session ends.
- The home **Live translation** button now opens settings; the previous tap-to-start/long-press-to-configure split is gone. Settings has **Hide floating window / Show floating window** (persisted as `media-translation.overlay`, default on). The entry is hidden while settings is visible so it cannot cover the controls; closing it is only possible from settings, and the notification opens settings rather than closing.
- Verified on emulator-5582 / API 36: forcing both apps to stop and starting HOME produced the entry over the launcher; it stayed over WebView Shell; tapping it opened settings; starting a Kotoba session replaced it with the caption window and stopping returned the entry; the caption window was hidden while settings was open; **Hide floating window** during that session stopped it, removed the window and persisted `overlay=false`; **Show floating window** plus HOME restored the entry. One Kotoba media session was opened and immediately stopped to exercise the active overlay; no audio was played, so no translation, latency or audio-quality result is claimed.

### Controller-owned media session (2026-10-29)

- Moved `AudioClient`, session id, event/caption state, stop timeout and cancellation out of `MediaTranslationActivity` into `MediaTranslationController`. The Activity attaches as a weak `StateListener`, renders snapshots and sends start/stop/close operations; `onDestroy()` only detaches. Finishing the page or rotating it no longer cancels an active session, and reopening the page attaches to the same session instead of opening another. Explicit `Stop translation`/`Hide floating window` and the engine `Closed`/error paths keep the existing `audio.session.close`/`AudioClient.close` flow and original-audio restoration semantics.
- `TranslationOverlayService` now keeps the foreground service while `MediaTranslationController.isActive()`, even when the idle overlay switch is off. The notification opens settings only; closing remains a settings operation.
- Unified the floating-window switch in `OverlayPreference`. The call page's legacy `MediaTranslationActivity` preference and the video page's `media-translation` preference are both read, and an explicit `false` on either side wins. Writes go to `media-translation` and remove the legacy key, so a close from the call page is not undone by the service or the home.
- Added `system/audio-engine/tests/overlay-lifecycle-device.py` (rooted emulator) covering the call/video switch round-trip, legacy `overlay=false` migration, session survival across BACK finish and rotation, no duplicate active window, and explicit close. It passed on emulator-5582 / API 36 alongside a Kotoba session. That run used the 30-second connecting window and did not play media, so it exercises session continuity and cancellation, not translation output or audio restoration after real playback. `system/audio-engine/test.sh` also passed.
- Also added navigation-bar bottom inset to the settings ScrollView so the call page's last button is not covered by the gesture/navigation bar.

### Active request restore and test cleanup (2026-10-29)

- `MediaTranslationController.Snapshot` now carries the immutable active `Request`. `MediaTranslationActivity` uses `activeRequest()` to build the page in the session's real mode (a notification reopen without a `page` extra no longer shows the video page for a call session), and restores the target app, source/target languages, profile/model, voice, outgoing profile/voice, speech/replace output and disclosure. It tracks the applied request so repeated events do not rewrite the controls; the asynchronous provider-default lookup either restores the active request or is skipped, never overriding it. Requested page changes while a session is active recreate the page in the session's mode without stopping the session.
- The device regression now snapshots both preference XML files and the rotation settings before changing anything. Its `finally` stops any test-created session, restores the exact XML files (including file absence and all unrelated business preferences), and restores rotation; it no longer unconditionally enables the overlay. An optional injected failure exercises the same cleanup path.
- Verified on emulator-5582 / API 36: a Whisper session (CAPTIONS) and a Grok session with the Ara voice and Japanese target both survived BACK/reopen and restored the actual model, voice, language, output and disclosure; a video session stayed on the video page when a `page=call` intent arrived. The full preference/legacy/session/restore run passed and restored the original enabled overlay. With the initial overlay off, a preference-only run and an injected failure both restored the exact off XML files and left no app overlay; the original enabled state was then restored. No real reboot or media playback was used, so translation output and real-playback restoration are not claimed.

### Code review #26 follow-up (2026-11-06)

Addresses [issue #26](https://github.com/Taku-OS/taku-os/issues/26) on the rebuilt branch (main + the audio commit).

1. **Whole-repository rollback:** resolved by rebuilding the branch on current main; only the audio change set and its integration remain (no files deleted relative to main).
2. **Start/stop deadlock:** `RouteGate` replaces the drivers' `routeLock`. The media and call workers start the route under the gate and report `ready()` only after releasing it; `close()` (called under the runtime monitor) never waits for the route lock: if the worker holds it, the worker sees the cancel right after and stops the route itself. `tests/ConcurrencyTest.kt` drives the real `AudioRuntime` + `RouteGate` through stop-during-route-start 20 times with `ThreadMXBean` deadlock detection; the pre-fix locking deadlocks in the same scenario (negative control, not committed).
3. **Native model release race:** `PlatformCallCapture.stopInputAndJoin()` stops capture and joins the capture threads on the driver worker. The call driver now drains in order: capture threads → model tail/finish → model release → players → route. `HybridTranslation` routes `accept()`/`finish()` through `FeedGate`; `close()` retires it, waits for an in-flight call and only then frees the recognizer (leaking it, never freeing it, if a call is stuck). Covered by `ConcurrencyTest` for `FeedGate`; no device crash reproduction.
4. **ROM integration:** still not applied. The [ROM candidate](reviews/audio-engine-rom-2026-10-28/README.md) needs explicit approval for the privileged audio permissions, model delivery and reboot acceptance. Until then the assistant checks `AudioEngine.available()` (package installed, enabled, `AudioEngineService` resolves): composer dictation falls back to the platform on-device recognizer, realtime voice connects directly, and continuous conversation, read-aloud, the home translation shortcut/overlay and the Taku AI voice rows are hidden (the voice section says the engine is not installed). Agents can check the same with `pm list packages org.agentos.audio`.

Verification: audio JVM runtime and concurrency tests pass; the full engine (with the pinned sherpa-onnx SDK and network jars), the assistant app and the broker compile. No APK/ROM build, emulator or device run was performed for this follow-up; clean-image behaviour without the engine and with-engine regression still need device acceptance.

### ROM source integration and local system-image acceptance (2026-11-06)

- User approved the five audio privileges and requested local validation first;
  the cloud Pixel ROM build host will be supplied later. The historical October
  candidate is superseded by `system/audio-engine/ROM.md` and the applied source
  integration. No physical device was flashed.
- Added platform-signed system_ext engine, engine privilege policy, CLI bridge
  and explicit delivery of the English/Chinese ASR and Kokoro model pack. Added
  the assistant's existing MODIFY_AUDIO_ROUTING request to the system_ext
  allowlist: clean boot testing caught Android rejecting startup without it.
- JNI stays uncompressed and 16 KB aligned inside the APK, with extraction
  disabled. Runtime model consumers select the read-only ROM manifest and never
  require adb to seed application data. Development-only installs retain their
  old private resource path and absent-engine fallback.
- Component build receipts bind sources to APK/bridge outputs. Snapshot, product
  and target-files checks seal the complete resource closure and reject stale
  sources, missing/corrupt/extra models, changed APK content or permission policy.
  APK product re-signing is allowed without changing its non-signature entries.
- The Kokoro archive's previous hash did not match the upstream release asset.
  The replacement matches both downloaded bytes and the official release asset
  677613419 digest. Unpacked model pack: 522,853,769 bytes; 390 sealed delivery
  assets total 559,261,025 bytes. The upstream `Mr serious` filename is preserved
  with a Soong module because make's copy list splits paths at spaces.
- Local acceptance uses a task-owned AOSP API 36 SDK image copy with an enlarged
  read-only system_ext and fresh userdata, SELinux enforcing, platform test keys
  and the production Broker/init wrapper. Host microphone/speaker are disabled.
  Original SDK images and the user's existing emulator are untouched. This is
  an SDK fixture, not a compiled Pixel ROM or OTA.
- First clean boot passed: Broker starts from init, engine is a system APK,
  private models are absent, all 390 installed asset hashes match. Real JNI
  synthesized and recognized English and Chinese synthetic speech; 387 model
  files verified. Recording lifecycle, exclusive ownership, release/reopen,
  reading playback, cancellation and request/append deduplication passed.
  Reading preference roundtrip/revision/invalid-value rejection/restoration
  passed. Agent Settings write is reflected in the native voice switch; GUI
  re-enabling updates the same Global value.
- Platform boundary: API 36 omits the feature-flagged
  BYPASS_CONCURRENT_RECORD_AUDIO_RESTRICTION permission at runtime. Four defined
  privileges are granted; the exact five-permission policy is installed, but
  `allFivePermissionsVerified` remains false. Test exceptions report absent
  platform permissions separately and still reject a defined denied permission.
  Target-ROM all-five validation, Soong/OTA gates, physical audio quality and
  real-app call/media routing remain pending the cloud/device environment.

- Integrated concurrent remote fix `d832783` before final acceptance, rebuilt both
  apps/client/Broker/tests, and refreshed the release lock. Runtime/concurrency
  tests include the new interruption-safe teardown cases. Final evidence is in
  ignored `out/audio-rom-validation/acceptance.json` and its first-boot/reboot
  reports; those tests are repeated on the refreshed SDK image.
- Final refreshed-image rerun passed both clean boot and a new kernel boot ID
  after reboot. Models/JNI, all asset hashes, init-started Broker and read-only
  system_ext passed again; GUI/Agent settings stayed synchronized and the test
  restored the initially absent Global setting. Native reply reading and
  microphone/speech lifecycle probes passed on the final binaries. Host image
  checks, structure/metadata checks, 18 JS tests, audio runtime/concurrency and
  system-AI host checks passed. Full Pixel/OTA and the unsupported fifth
  platform permission are explicitly not claimed as verified.

### Nine-finding device regression follow-up (2026-11-06)

- Confirmed d832783 is an ancestor of the local/pushed ROM integration, and its
  production APK/Broker bytes are those in the isolated image. Added repeatable
  Kotlin instrumentation plus an offline synthetic-call fixture and host runners;
  no production behavior changed in this follow-up.
- ART cancellation/retirement barriers, real AudioTrack interrupted cleanup,
  live package uninstall/reinstall on native settings/home, zero Binder IPC for
  1,000 warmed availability reads, Broker corrupt/missing configuration and
  reason codes, default profiles through actual Binder sessions, and Preparing
  cancellation/resource release were exercised. Real dynamic call-playback
  routes preserved QUEUE_BACKLOG, CAPTURE_FAILED and CALL_ENDED distinctly.
- Corrected the acceptance description: corrupt model selection reports
  MODEL_UNREADABLE and falls back to Kotoba while preserving a valid key; only
  corrupt/missing key data becomes unconfigured. No real credentials or cloud
  requests were used. Test configuration/network changes are restored.
- See [the device regression matrix](../system/audio-engine/tests/REVIEW.md) for
  per-finding evidence, reproduction and untested boundaries. Cloud translation,
  Hybrid/Whisper in-flight network teardown, silenced-capture/state-query faults,
  real calls/audio quality and target-ROM/OTA gates remain explicitly pending.

## Standalone Taku Audio entry and Memory visual alignment

- Promote the existing `org.agentos.audio` package to a launcher-visible Taku Audio app. Retain its data directory, private translation preferences, model resources, and session controller. Existing Taku Home discovers the app through Android, without a Home or Launcher3 source change.
- Collect fal key management, default model selection, voice enablement, and navigation to existing audio pages in the app. Root ByokProviders and SystemAi retain configuration ownership. The GUI and on-device Agent share the documented `org.agentos.audio.open-access` settings commands. Settings do not make paid provider requests or require OpenRouter.
- Authorized application builds completed for Audio, Assistant, their companion Broker/client artifacts, and the necessary device probes. The visual correction rebuilt Audio. No TakuOS, Home, Memory, Launcher3, or system-image build was run.
- Application acceptance used a headless copy of the existing Android 17/API 37 AVD, retaining the installed modern Home and Memory layout. Shared GUI/Agent settings, invalid requests, corrupt-file repair, APK replacement with retained preferences, and package removal/restoration were exercised offline with synthetic credentials.
- Audio uses Memory's original brand vector. The app-local `AudioUi` follows Memory's colors, typography, grouped cards, buttons, and system bars. All five pages were inspected in English and Chinese. A real filled-input check found plaintext key entry caused by a later single-line setter; removing that override restored masking, now covered by the GUI settings regression.
- Updated `Taku_Android17_20261111` (`emulator-5680`) in place. The installed final APK SHA-256 matches the host artifact. The actual desktop icon was compared with Memory, and its hub and four subpages were opened through GUI controls. Root fal/model/reading data, system AI policy, HOME role, and Audio/Memory placement were preserved.
- The focused `review-config-device.py --settings-only` run passed, including GUI key saving and public readback under UID 2000, password masking, blank-input preservation, model retention, voice/reading round trips, unknown-voice retention, and corrupt-file GUI recovery. The broader default-profile run was interrupted when the disposable emulator exited during synthetic call startup; it is not a passing call test. Cloud authorization, paid inference, physical calls, and translation quality were not exercised in this migration.
- Local evidence: `out/taku-audio/current-visual-deployment.json`, `out/taku-audio/matching-isolation/ui-config/config.json`, and `out/taku-audio/current-memory-style-*.png`. Reproduction and test scope are in the [device regression guide](../system/audio-engine/tests/REVIEW.md).

## Live fal dictation and input layout (2026-11-13)

- Dictation now publishes measured microphone RMS levels through ordered `Level` events. The home composer, hold gesture and Agent snapshots share `pcm-rms` history. Random and sine waveform substitutes are removed.
- fal Whisper recognizes non-overlapping audio ranges while capture continues. One serial worker coalesces pending audio. Previews replace the same open segment, and one whole-recording final replaces them, including an empty final. The existing capture and absolute drain bounds remain.
- The native input wraps long previews and scrolls within the space above the keyboard. A separate centered waveform row reserves the text width, with symmetric bars up to 32 dp and stronger display contrast for ordinary speech.
- Built and installed the Audio Engine, native Agent app, Assistant guide assets and transport probe on `emulator-5680`. The focused fal host regression, 26 native voice tests, chat dictation checks, TypeScript and actual ranged-WAV/Level encoding probe passed. Local Zipformer recognition and its tests were not run.
- Physical speaker-to-host-microphone input produced eight changed transcripts during playback, followed by a successful complete final result. The user personally accepted the updated layout and behavior. The raw harness rejected a further valid tail update immediately after playback ended; that raw report is preserved. No additional audible run was made after acceptance.
- Evidence is in `out/assistant-entry-voice-implementation/realtime-layout-verification.json` and `realtime-layout-zh-before-finish.png`. Installed Agent guide sections match the packaged source and preserve unrelated text. Newer user draft edits were retained during closeout. Temporary playback fixtures and the throwaway runner were removed.
