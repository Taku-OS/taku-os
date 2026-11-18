# Android scenario candidates and acceptance records

This page preserves engineering-candidate acceptance from 2026-10-10. `product_selection=pending` is the status of that experiment: Ricky/product had not yet confirmed inputs, success conditions, or first-release scope in this record. These examples provide reviewable evidence for choosing scenarios and interactions. Read devices, versions, and uncovered areas within their original scope.

## Subsequent progress (as of 2026-10-19)

- **Emulator:** On October 15, a dedicated API 36 emulator passed on-device source generation, compilation, signing/installation, same-signature upgrades retaining data, and ContentProvider business API acceptance for a pure Java native app. See [native app acceptance](agent-system-app-acceptance-2026-10-15.md). This is a separate later scenario; C2 below still used a React/WebView shell. The toolchain is not yet included in the ROM.
- **Physical device:** On October 14, a retained firmware package based on an older candidate was flashed to a physical Pixel 9 and booted normally. On October 15, assistant components passed real-model, some Android-tool, and cold-boot network/service recovery acceptance. See [installation record](../evidence/builds/2026-10-14-pixel9-install.json) and [native-client acceptance](native-codex-implementation-2026-10-15.md). C1–C3 on this page do not automatically become physical-device passes.
- **Complete ROM:** The October 11 candidate completed a build and file verification. The latest full-build record exited with failure at 00:40 Beijing time on October 16, with no new artifact from that round; the final root cause awaits confirmation from original logs. Later components do not establish inclusion in a new full package. See [image engineering](../image/README.md).

See [current progress](current-status.md) for unified version and acceptance scope. Original scenario evidence and boundaries follow.

## Experimental boundaries

The device was fixed to `emulator-5582`, AVD `AgentOS_ImageCheck`, Android 16 / API 36 / ARM64 / userdebug. Experimental adbd and phone Codex ran as UID 0; SELinux remained Enforcing. No physical device was selected or tested in this round, and this UI, SDK, and experimental tooling were not repackaged into a distributable ROM.

“Without a computer” in C2 means that, after runtime preinstallation, phone Codex wrote core source, compiled JS, packaged and signed the APK, installed it, and opened it. Engineering still used ADB to deploy test inputs, start phone processes, record screens, and collect results. A blank native shell and toolchain had been provisioned once from a computer; model inference still required a network. This stage is neither completely computer-free first-time setup nor an offline model.

## Candidates and success conditions

| ID | Candidate input and visible result | Required evidence | This round's status |
| --- | --- | --- | --- |
| C1 | Phone Codex reads Android/ABI/SELinux and actual display/task metadata | Phone commands and exits, SDK JSON, root/shell comparison, unknown-method rejection | Emulator engineering candidate passed; product selection pending |
| C2 | Generate a two-team scoreboard, preview it, install a standalone APK, add and undo scores through an external interface | Phone model events, App.jsx/api.mjs, on-device build/sign/install logs, APK hash/package/version/launcher, API before/after state, standalone-package window screenshots, original recordings | Emulator engineering candidate passed; product selection pending |
| C3 | Phone Codex opens rednote's guest image feed on a separate display, observes and swipes, while the main display remains usable | Task 1791574373107, dynamic display 11, model input commands, actual before/after screens, simultaneous main-display operations, native mirror and manual swipe, results, original recordings | Minimal emulator candidate passed; product selection pending |

Some `system.info` data in C1 is also available to ordinary apps through public APIs; success alone does not establish Root use. `tasks.status` requires root file permissions to read experimental assistant task metadata, with UID 2000 rejection as a permission-boundary example. C3's `phone-display` is an experimental root service, not an interface automatically equivalent to ordinary app permissions.

C2 validates arbitrary business code, not configuration: custom positive/negative scores, history, and undo are implemented by the generated business module and shared by UI/CLI/HTTP. Idempotent replay of the same request is limited to completion receipts in the current service; it does not claim exactly-once behavior during crashes.

A standalone APK means an independent package, launcher, signed installation, and process window. This candidate hosts generated React in a native WebView shell. UI resources are inside the APK, while the business API depends on Taku's experimental background Node service. It is not a fully self-contained offline APK and does not generate new native Java/Kotlin DEX each time. Arbitrary new native Android APIs still require shell or system-interface extensions; arbitrary JSX does not establish arbitrary hardware permissions.

## Where execution happens

| Step | Inside Android device | Engineering computer |
| --- | --- | --- |
| One-time provisioning | Codex, React/esbuild, APK shell, signing tools, experimental services | Earlier provisioning and blank-shell compilation |
| Core program generation | Phone `/system_ext/bin/codex exec` writes JSX/API | Does not generate or push app core source |
| Preview and APK | esbuild/aapt/zipalign/apksigner/pm/am | Evidence collection and APK metadata inspection only |
| API calls | Phone mini-app calls | External HTTP reaches the same service through adb forward |
| Background operations | Phone model calls `am --display`, `input -d` | Main-display observation and manual takeover tests labeled separately |
| Recording/evidence | Native `screenrecord` / real screenshots | adb pull for archiving |

## Existing prerequisites and incomplete work

Evidence for the three existing read-only SDK methods and extensions is in [foundation acceptance](acceptance.md); capability permissions are itemized in the [API guide](android-api-guide.md). Older timer and mini-app evidence in experimental directories does not replace this table's inputs and complete recordings.

Mainstream app installation/launch, guest browsing, and video decoding are maintained as separate compatibility tasks. Douyin video still has anomalies and is not described as fully compatible. A separate display is an additional Display in the same Android system, not another VM. It does not guarantee two independent instances or independent data for the same app.

Whether to show every click, when users take over, conflict scheduling, and whether takeover pauses the Agent remain product decisions or unimplemented work. Manual mirror taps after completion do not prove automatic yielding by a running Agent.

This experiment did not cover a complete ROM rebuild, clean-environment deployment and cold boot, physical-phone flashing, or conventional app/permission/scenario compatibility acceptance. Later older-candidate installation and partial physical-device validation appear at the top of this page. Releasing the current version still requires its full package and corresponding device regression; these scenario results cannot replace them.

## Original evidence from this round

`../evidence/scenarios/` holds this round's results and recordings. Collection includes only this test's task IDs, generated source, and public system information, not authentication or unrelated task bodies.

### C1: Calls and rejections actually executed by the phone model

Events from the phone model's `/system_ext/bin/codex exec` are in `phone-model-events.jsonl`; successful JSON for all three SDK methods and UID/SELinux data are in `phone-evidence/`. `scenario.unknown` returned `METHOD_NOT_FOUND`, exit 2; `su 2000 /system/bin/sh ... tasks.status` returned `PERMISSION_DENIED`, exit 4. `permissions-baseline.json` is an earlier tester baseline using ADB shell. Its executor is listed separately and does not replace phone-model evidence.

### C2: Code, standalone package, and a real API

The phone generated complete `App.jsx` (2,025 bytes) and `api.mjs` (2,161 bytes) in `phone-source/`. Positive/negative safe-integer scores for two teams, history, and undo all live in the business module; the UI calls the same API. The model actually invoked `sh build.sh scenario_score --no-open`, a shared-host preview on the main display, and `sh package-app.sh scenario_score org.agentos.scenarioscore 双队计分` (the original Chinese app title means “Two-team scoreboard”). On-device packaging/alignment/signing took 770 ms, **excluding model execution, installation, and first frame**. The complete C1+C2 run took about 150 seconds, including inspecting tool conventions, code generation, builds, API and installation verification, and results. It is not pure generation time.

The standalone APK is `phone-evidence/scenario_score.apk` (an experimental artifact, not a release ROM); SHA256 `28ab5f9f9c7fa5f14a66361ed4952dbcf495762d0bdf42dafbd1c94688f1cca5`; package `org.agentos.scenarioscore`, version `1.0` / code `1`, minSdk 30 / targetSdk 35 / compile SDK 34. **Only this API 36 device was tested.** v3 signature verification passed; actual `pm path`, launcher `org.agentos.generated.MainActivity`, window/process, and UI were read back.

Phone CLI A+3→same-key replay→B+2→undo ended at A=3/B=0; negative scores and undo were tested separately. delta=0, team=C, and fractions were rejected. An independent computer HTTP client called the same Android business service through localhost-only ADB forwarding: A3→A8, same-key replay retained A8, undo returned to A3. `04-external-api-ui-a8.png` visually confirmed that external state automatically appeared in the standalone APK. Complete UI buttons, persistence/service crashes/uninstallation/physical devices did not receive comprehensive regression testing.

Reproducible on-device call entry points:

```sh
/data/local/agentos/light-app-lab/free-app-kit/mini-app describe scenario_score
/data/local/agentos/light-app-lab/free-app-kit/mini-app call scenario_score score.state '{}'
/data/local/agentos/light-app-lab/free-app-kit/mini-app call scenario_score score.add '{"team":"A","delta":5}' --key your-new-key
/data/local/agentos/light-app-lab/free-app-kit/mini-app call scenario_score score.undo '{}'
```

These examples really change scoreboard state. Retest with a new test appId/package/idempotency key; do not mix old receipts and current state as the initial state of a new generation. Experimental script inputs and original events are preserved; app core code was not generated on a computer and pushed to the phone.

### C3: Two displays in one system, with Agent and main display usable together

The native Home widget submitted a real request to existing TaskRequests/Broker, task ID `1791574373107`; `native-submit-test.txt` reports `OK (1 test)`. In Broker `events.jsonl`, the phone model actually resolved `.index.v2.IndexActivityV2` and performed `am start --display 11`, capture→`view_image`→`input -d 11 swipe 330 1000 330 400 500`→capture→`view_image`. Dynamic IDs are obtained from SDK/phone-display first; 11 is not a protocol constant.

The model's before image showed old-town/egg-tart cards; after showed US stocks/Chinese animation/cars. Both images were manually inspected to verify real changes, rather than relying on model descriptions. A login prompt remained, but the guest feed scrolled. While the model operated, the tester tapped the focus timer on main display 0; 25 min actually became a countdown, then the native mirror was opened for observation. Evidence is in `07-main-focus-while-agent-running.png` and `08-native-mirror-observing-agent.png`. This is limited concurrent interaction, not coverage of every app and long-running task.

After Broker truly reached `completed`, the tester swiped inside the main-display mirror ImageView (input first reached display0 and was mapped through real mirror touch handling to the background display), changing cars→animation/UK cards. Compare `10-mirror-manual-before.png` / `11-mirror-manual-after.png`. Manual operations are not recorded as model actions, and post-completion touch is not described as automatic takeover or a pause policy during execution. Finally, native management restored input widget=false and focus=25min; task results were retained in `12-completed-home-widget-removed.png`.

### Recordings and retained failures

- `generation-full-01.mp4`: 199.15-second native main-display recording covering the C1+C2 model period, actual preview, and installed APK.
- `api-mobile-full-02.mp4`: 180.18-second native main-display recording covering external API score changes, native task submission, running status, concurrent main-display use, mirror, and post-completion manual swipes. Restored settings have separate screenshots; background changes also have actual model before/after captures. This records the screen; invisible model/compilation work is not presented as screen animation.
- `mobile-use-quick-2x.mp4`: 2× quick-view edit starting around 58 seconds into the second recording. The original is retained separately and the filename identifies acceleration. Edited duration is not model/operation performance.
- `mobile-use-excerpt-real-speed.mp4`: 95-second real-speed excerpt starting around 80 seconds into the second recording, including actual running state, simultaneous main-display timer use, native mirror, completed state, and post-completion manual swipes. Recommended first viewing. Tester actions are separately recorded in `manual-main-and-mirror-actions.json`, not mixed with phone-model actions.

All originals were verified with ffprobe as H264 / 720×1600; frames from the quick-view video were inspected for actual content. Retained failures include: the first native helper compared newline input with spaces displayed by a single-line EditText and failed without submitting a task, then passed after correcting test input; the phone's default PATH lacked node, resolved with an absolute path/device libraries; an old Android 16 window-query field did not match and was rechecked against actual output; the phone lacked rg, so the model used grep to verify displays/windows. `phone-result.json`, original events, and failed stdout/exit records are retained. No anomalies were hidden, original apps modified, authentication read, or accounts changed.
