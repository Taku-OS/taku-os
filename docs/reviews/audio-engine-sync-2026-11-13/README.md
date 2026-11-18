# Audio Engine integration into new UI

Source commit: `a725e4bfced2700d01f2f69c5bceda8b38a03863`.
Target base: `Taku-OS/taku-os@a8f4186f857192cb449668e5f886920eba594841`.

The source commit adds shared Engine dictation, final-result ownership, measured
PCM amplitude, incremental fal previews, and GUI/Agent audio settings. Five files
conflicted with #55's newer hold interaction. The integration keeps Cancel / Keep
talking and the system gesture handshake, then assigns each recording a delivery
operation when that handshake activates. Release hides the temporary UI and
chooses Submit, including when the Engine already entered finalization. The Text
control keeps the same capture, waits for its complete result, and appends once
to the current draft. Cancellation and terminal failures end the gesture, while
late events cannot reopen or deliver the old operation.

The obsolete second finish timeout and external transcript injector are removed.
The waveform reads measured PCM history; the preinstalled Agent documentation is
updated accordingly. The upstream device test's settings path is corrected to
`/data/local/agentos/ai/audio-settings.json`, matching `AudioConfigBridge` and
`SystemAi.ROOT`.

Validation on 2026-11-13:

- Agent TypeScript, ESLint and 365 JavaScript tests passed.
- 105 Home JVM tests passed, including gesture and voice-delivery regressions.
- Audio runtime/concurrency/dictation policy/fal preview tests passed.
- Native system-AI host tests and repository `npm run verify` passed.
- Audio Engine, Assistant/broker, and minified Agent release APK builds passed.
- On a new `Taku_Audio_ROM_Merge` AVD (`emulator-5690`, API 37, hardware GPU),
  settings-only GUI/Agent checks passed: voice/reading/dictation round trips,
  revision atomicity, masked key input, invalid requests, and corrupt-file handling.
- BYOK settings tests passed on that AVD, without model generation.
- Dictation transport instrumentation passed: PCM encoding, cancellation, range
  bounds, native parameter parity, metadata and measured level encoding.
- Eight `VoiceSessionProbe` cases passed (`INSTRUMENTATION_CODE: -1`). This uses
  the actual AssistantSession with an in-process recording test double on an
  unminified build of the same source. Reproduction is in the Home module's
  `tests/README.md`. It submits no messages and records no audio.

The AVD has fresh userdata, host microphone/audio disabled, and no personal
credentials. Settings checks use a conspicuously synthetic offline key and clean
it up afterward. The user's `emulator-5586` installation is untouched. Raw local
test results are under ignored `out/audio-merge-a725e4b/`.

This integration does not establish real microphone/Zipformer/fal recognition,
physical-device behavior, or actual system-handle dispatch. Quickstep deployment
and real-device testing remain separate, as requested by the user.
