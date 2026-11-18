# Voice session integration probe

This probe exercises the actual `AssistantSession` on Android with an in-process
`VoiceInput` test double. It checks the integration of #55's gesture protocol with
Audio Engine's operation/delivery model: cold release, Live Talk pause intent, finalization
before release, Text tail delivery, cancellation, duplicate callbacks and errors.
It records no microphone audio and sends no messages. The original voice adapter
is restored in `finally`. It is not speech-recognition or Quickstep acceptance.

Use only a disposable emulator: the probe resets its Home conversation. The
application must be built from the same source without R8 renaming so the probe
can resolve its classes. Keep the normal minified release APK separately.

From the repository root, with the normal Android environment configured:

```sh
source scripts/android-env.sh
(cd system/agent-app/android && ./gradlew :taku-home:testDebugUnitTest :app:assembleRelease \
  -Pandroid.enableMinifyInReleaseBuilds=false -PreactNativeArchitectures=arm64-v8a --console=plain)
"$TOOLS/apksigner" sign --key out/development-signing/platform.pk8 \
  --cert out/development-signing/platform.x509.pem \
  --out system/agent-app/out/VoiceSessionTarget.apk \
  system/agent-app/android/app/build/outputs/apk/release/app-release.apk
bash system/agent-app/modules/taku-home/tests/build.sh
adb -s emulator-N install -r system/agent-app/out/VoiceSessionTarget.apk
adb -s emulator-N install -r system/agent-app/out/voice-session-probe/VoiceSessionTests.apk
adb -s emulator-N shell am instrument -w org.agentos.agent.home.tests/.VoiceSessionProbe
```

Require both `report.passed:true` (ten cases) and `INSTRUMENTATION_CODE: -1`.
The shell exit code alone does not establish success. Production voice continues
to use Audio Engine; this test adds no exported transcript injection endpoint.

## Selected PR review regressions

The same test APK also includes `ReviewRegressionProbe`:

```sh
adb -s emulator-N shell am instrument -w org.agentos.agent.home.tests/.ReviewRegressionProbe
```

Use the existing assigned isolated emulator, with an idle, empty Home conversation,
the bundled widget apps installed, and the default widget page present. This probe:

- Recreates the interrupted-placement state (committed layout plus stale pending record),
  then checks finish/resume/cancel/list preserve one live widget binding. It adds and removes
  only a temporary Date widget on a new page and asserts the original layout is unchanged.
- Performs a real cross-page widget drag and cancellation, asserting the source HostView
  remains the registered/cached view and the original layout is unchanged.
- Opens the actual assistant overlay over Settings with an in-process recording double,
  releases into finalization, verifies the input Activity stays on the listening page, injects a
  failed recognition, and checks the error remains in authoritative state. Explicit Cancel then
  closes the Activity, as required by the voice-page interaction contract.
  Finalization/error presentation now requires the patched SystemUI and separate ROM acceptance.
  No audio is recorded and no assistant message is sent.

Require `report.passed:true`, all five cases, and `INSTRUMENTATION_CODE: -1`.
Real microphone recognition and Quickstep dispatch still require their own acceptance.

## App-building capsule acceptance

`AppBuildIslandProbe` requires the same non-minified target, patched SystemUI and an idle empty
Home session. It temporarily substitutes an in-memory active-turn fixture, then exercises the
public `assistant/app-build` write/read path and checks `cmd`/provider SystemUI visibility.
It verifies normal work, listening, error, approval and finished replies stay compact-hidden,
and records ten screenshots across more than one full designer animation cycle. It restores
the original session in `finally`; no model request, audio capture or stored conversation is
created. Frames go to the target app's `files/building-island-acceptance` directory.

```sh
adb -s SERIAL shell am instrument -w org.agentos.agent.home.tests/.AppBuildIslandProbe
```

Restore the normal signed release APK after this probe. It verifies presentation and state
contract, not the success of an actual application build.

## Native SystemUI card acceptance

`NativeIslandProbe` requires an unminified Agent target, an idle session, and the installed
`org.agentos.island.fixture` APK (`bash system/island/build-device-fixture.sh`). Its unconstrained
Activity is used for rotation because Home deliberately requests portrait. It temporarily
projects an in-memory listening state and an Agent turn with a reply and optional choices;
no model call, microphone capture, stored conversation, or message submission occurs.
It verifies the first visible card is native, fresh listening has zero options, actual reply
text reaches the card, model choices appear/disappear, and long replies stay capped. It also verifies same-Expanded explicit reopening, a real new
voice gesture after a retained reply (without resetting session state), landscape dismissal,
and explicit reopening after rotating back. Swipe dismissal must also notify the Agent observer;
provider readback alone does not prove the subscription works.
The original session/draft is restored in `finally`. Restore the normal release APK afterward.

```sh
adb -s SERIAL shell am instrument -w org.agentos.agent.home.tests/org.agentos.agent.home.tests.NativeIslandProbe
```

Screenshots are written to the target's `files/native-island-acceptance` directory.

`VoiceReplyProbe` is an opt-in live gateway test. Supply the actual FAL test transcript in
`/data/local/tmp/taku-voice-check-text.txt` (a short synthetic request containing 测试).
It replays capture callbacks through the real Keep talking delivery path and sends one real
message through the configured Agent gateway. It does not fake the Agent reply. It records a
Chinese transcript screenshot, reports thread/reply, stops live capture and restores the original
voice adapter. Requires an idle session and the unminified acceptance APK. Restore the signed
release APK afterward, start MAIN/HOME explicitly, and visually verify the final desktop.

## Quickstep review regression

`RecentsReviewProbe` needs the installed island fixture, current Quickstep and an unminified
Agent target. It creates a separate fixture task, checks real center-card tapping, idempotent
show, Agent open/remove, unknown-task rejection and landscape overview, then removes only its
new task. Existing task IDs must remain present. It restores the previous rotation settings and
returns Home in `finally`. Run `.RecentsReviewProbe` with `am instrument -w`; require a true
report and instrumentation code -1. Screenshots go to `files/recents-review`. This does not
clear the user's task history and does not claim empty-overview or frame-timing acceptance.

## Voice background and screen-off regression

`VoiceBackgroundProbe` exercises actual Home/Overlay `onStop`, actual emulator screen-off/wake,
and the real 500 ms restart coroutine with an in-process recording double. It checks both
latched Listening and Continuous cancellation, next-gesture availability, contained restart
failures, and Agent-provider readback against the same GUI session. It records no microphone
and submits no model request. Run on the assigned idle isolated AVD only; it resets the Home
session, opens Settings, briefly sleeps/wakes the device and returns Home. Require six cases,
`report.passed:true` and instrumentation code -1. Restore the production APK afterward.

```sh
adb -s SERIAL shell am instrument -w org.agentos.agent.home.tests/.VoiceBackgroundProbe
```

`VoiceSessionProbe` additionally checks rotation and Continuous handoff preservation, stale
surface IDs, finalization cancellation and injected lock/start-failure races without background
Activity transitions. The JS hook tests execute `useLiveTalk` for null/empty startup, lazy thread
adoption, a mounted old page during native handoff, explicit stop and subscription lifetime.
