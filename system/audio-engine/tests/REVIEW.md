# Device regression checks for d832783

These supplement the original JVM checks. Run on an explicitly disposable
`Taku_Audio_ROM_*` AVD, never a personal device. The November 6 run used the
API 36 system-image fixture and production binaries sealed by f68da48.
Only the test APK and synthetic call fixture are installed by adb.

| Review item | Local device evidence | Boundary |
| --- | --- | --- |
| 1. Cancellation during cleanup | 20 RouteGate cancel/start cases and 20 interrupted FeedGate retirement cases on ART; interrupted cleanup of a real AudioTrack waits for its worker and restores the interrupt flag | Controlled barriers exercise production classes, not a long-running cloud translation. Hybrid/Whisper network-worker interruption and physical concurrent capture remain separate acceptance. |
| 2. Unreadable BYOK files | Real Broker returns KEY_UNREADABLE without breaking its snapshot; native Taku AI displays the recovery message. MODEL_UNREADABLE also preserves snapshot reads | A malformed model selection falls back to Kotoba and keeps an existing key configured; it does **not** mean the key is absent. No real key is used. |
| 3. Default model | Actual SDK/Binder session opens without profileId select Kotoba/Grok/Whisper; Grok voices and Whisper speech restrictions are validated against the selected default; calls fall back from Whisper to Kotoba | Networking is disabled; the probe checks selection, validation and startup cancellation, not successful cloud synthesis. |
| 4. Call failure reason | A real Android dynamic playback route preserves an injected model QUEUE_BACKLOG and ordinary CAPTURE_FAILED; terminating the synthetic call owner produces CALL_ENDED | CAPTURE_SILENCED and CALL_STATE_UNAVAILABLE have not been induced on this fixture. No real phone/WeChat/LINE call is made. |
| 5. Configuration reason | Actual engine-to-Broker socket preserves PROVIDER_NOT_CONFIGURED and POLICY_REVOKED; missing-key SDK requests mention BYOK | In-flight cloud authorization failures still require provider-backed acceptance. |
| 6. Availability cache | 1,000 warmed production helper reads on the UI thread issue zero Binder transactions | Does not measure whole-chat rendering latency. |
| 7. Package refresh | Per-user uninstall/install-existing updates the live native settings and home screens without navigation or assistant process restart | Does not validate Play Store updates or a new APK with a changed service definition. |
| 8. Shared method names | Existing JS contract tests and real session open/close/capabilities calls pass | This is an internal refactor, not a new Android feature. |
| 9. Single availability lookup/UI listeners | Code uses one resolveService probe; warmed reads and both screen listeners are exercised above | The first cold lookup count is established by source inspection; zero warm IPC is measured on device. |

The three selected media profiles and their call variants were canceled while
Preparing, followed by Closed(canceled, restored=true) and an idle resource
state. This validates startup cancellation with the production drivers and a
synthetic call owner, not interruption of an established provider connection.

## Reproduce

First build the engine and assistant as described in ../ROM.md. Start the
isolated fixture with host audio input/output disabled and root adb. Do not
seed real credentials. The config script refuses existing fal files, disables
Wi-Fi/mobile data before writing a conspicuously fake key, and restores network
and voice-switch values and removes its files in finally.

```sh
bash system/audio-engine/tests/build.sh
bash system/audio-engine/tests/fixture/build.sh
adb -s emulator-N install -r system/audio-engine/out/AudioEngineTests.apk
adb -s emulator-N install -r system/audio-engine/out/call-fixture/CallFixture.apk
adb -s emulator-N shell pm grant org.taku.callfixture.target android.permission.RECORD_AUDIO
adb -s emulator-N shell pm grant org.agentos.audio android.permission.RECORD_AUDIO
adb -s emulator-N shell am instrument -w org.agentos.audio.tests/.ReviewRegressionProbe
adb -s emulator-N shell am instrument -w -e mode playbackCleanup org.agentos.audio.tests/.ReviewRegressionProbe
adb -s emulator-N shell am instrument -w org.agentos.audio.tests/org.agentos.assistant.AvailabilityProbe
python3 system/audio-engine/tests/review-package-device.py --serial emulator-N --output out/review/packages
python3 system/audio-engine/tests/review-config-device.py --serial emulator-N --output out/review/config
python3 system/audio-engine/tests/review-capture-device.py --serial emulator-N --output out/review/capture
```

Instrumentation must report `passed:true` and `INSTRUMENTATION_CODE: -1`; adb's
shell exit status alone is not a test result. Package/config/capture scripts
assert reports and exit nonzero on failure. Raw local reports from this run are
in ignored `out/audio-nine-fixes/`. The synthetic fixture has no INTERNET
permission and discards recorded frames without saving them. Stop it after use.

Remaining acceptance: real cloud sessions and translations, interruption while
Hybrid/Whisper cloud work is in progress, silenced-capture/platform-state fault
paths, physical audio quality, all-five privileges on the target ROM, and the
full Pixel ROM/OTA build. These local regressions do not close those gates.

## Standalone Audio app and Memory visual alignment

The Android 17/API 37 app acceptance uses a headless copy of the existing running
TakuOS AVD, retaining its installed Home APK and Memory layout. The Audio icon
uses Memory's original brand vector; all five Audio pages were inspected in
English and Chinese. This is application acceptance, not a new system build.

`review-config-device.py` also exercises a real GUI key replacement: an empty
submission keeps the dialog and settings, entered synthetic text stays masked,
and saving is read back through the standard shell settings provider without
changing the model. Checking the filled input catches the password transformation
being overwritten by a later single-line setter. The unknown-voice regression
checks the saved voice after a GUI speed change rather than pinning button text.

`review-package-device.py` supports the data-installed Audio APK used by this
AVD. `--apk` selects the built APK restored after per-user removal; its default is
`system/audio-engine/out/TakuAudioEngine.apk`. Uninstall keeps application data. The test checks Audio availability in the live Expo settings page, the System AI provider, and the Home app catalog without restarting either Assistant process.

Run settings and GUI acceptance independently of synthetic audio sessions:

```sh
python3 system/audio-engine/tests/review-config-device.py --settings-only \
  --serial emulator-5682 --output out/taku-audio/matching-isolation/ui-config
```

This mode reports `defaultProfilesVerified: false`. Omit `--settings-only` for
the existing media/call default-profile probes. The full API 37 attempt was
interrupted by the emulator process exiting; it is not a passing call test.
Launcher tests use Android's resolved explicit component, and expose a partially
visible control before tapping it.

The package runner's settings/home checks target the native fallback UI. A signed
frontend version 3 or newer normally redirects system AI settings to
`org.agentos.agent/.MainActivity`. Exercise the native fallback only with that
frontend temporarily disabled in the disposable copy, then restore its original
enabled state and HOME role. The modern Home icon and normal frontend handoff
are checked with the frontend enabled. Never disable it on the user's original
device for this test.

The synthetic call fixture needs the RECORD_AUDIO grant in the recipe above.
Default-profile startup/cancellation is separate from a completed call or cloud
translation; no real credentials or paid inference are used in this acceptance.
