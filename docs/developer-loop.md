# A developer’s first real call and contribution loop

Updated 2026-10-19. These commands are current executable entry points; they do not install a new ROM or grant privileges by default. Node.js 20+ is required; device calls also need Android SDK adb, an explicit device, and authorized debugging. The SDK extensions below execute as trusted local source; Skills and on-phone app development follow their separately verified paths.

## Distinguish tasks from capability artifacts

The goal is for people and Agents to keep extending the phone: execute directly when existing commands suffice; use temporary scripts for one-off combinations; turn repeated workflows into skills; create an extension when context, triggers, connections, tools, or UI are missing. A completed Case does not require first registering a plugin with the same name.

Context, observers, tools, runtimes, memory implementations, and interfaces are all in extension-design scope. Developer-written and Agent-generated work follows the same execution, validation, and version standards; the goal is a shared contract for default components and external extensions. Limited real tests cover public community Skills installation, enable/disable, conversation loading and model use, plus on-phone native app creation, upgrading, and API loops. Unified plugin installation/hot loading/rollback and autonomous loops across all extension types remain unaccepted.

Target loop: express a need → check existing capabilities → use or generate → test in practice → load and verify → retain a working version / correct or restore. See [extensible system](extensibility.md).

## Discover what you can call

Run at the repository root; npm install is not required:

```sh
npm test
npm run check
node sdk/cli/taku.mjs call methods.list
node sdk/cli/taku.mjs call extensions.list
```

`methods.list` returns implemented methods. `capabilities.list` returns a capability catalog including research items; a catalog title does not make an unimplemented method callable.

## Query one explicit device

List your own devices first, then explicitly select a serial. The example serial is a development emulator; replace it with the device you actually prepared.

```sh
adb devices -l
node sdk/cli/taku.mjs call system.info --transport adb --serial emulator-5582
node sdk/cli/taku.mjs extension device-report --transport adb --serial emulator-5582
```

If adb is not on PATH, add `--adb /path/to/android-sdk/platform-tools/adb`. Device information, display IDs, and task state come from actual Android; a disconnected device or missing permission should produce an error and nonzero exit. `tasks.status` requires Root/system UID and corresponding file permissions to query existing assistant metadata. If it fails, it reports successful values from other probes and its own errors, without treating the overall result as full success; it neither switches USB devices nor fabricates example data.

Success of `system.info` on a stock device does not make task metadata readable; the SDK does not automatically root the phone. API36/userdebug is the currently tested scope; other devices/APIs require evidence through capability and compatibility reports.

This path already has [real device-report evidence](acceptance.md). Setup within 5–10 minutes on an arbitrary clean computer is not yet demonstrated; that is a goal to measure with external developers.

## Write your own business logic

Start directly from `run(client)` in `examples/extensions/device-report/index.mjs`, without changing Android Home or compiling a complete ROM. This small standalone module combines real interfaces and prints diagnostics:

```js
// my-device-summary.mjs: save in your local development directory; no registration is needed to experiment.
import { TakuClient } from './sdk/client.mjs';

const serial = process.argv[2];
if (!serial) throw new Error('Pass an explicit Android serial');
const client = new TakuClient({ transport: 'adb', serial, adb: process.argv[3] });
const system = await client.call('system.info');
const displays = await client.call('displays.status');
console.log(JSON.stringify({
  observedAt: new Date().toISOString(),
  apiLevel: system.apiLevel,
  callerUid: system.callerUid,
  selinux: system.selinux,
  activeDisplayIds: displays.displayIds,
  backgroundDisplay: displays.background,
}, null, 2));
```

Save the file at the repository root and run `node my-device-summary.mjs emulator-5582`; if adb is not on PATH, append `/path/to/android-sdk/platform-tools/adb` after the serial. This is a standalone development example: failed calls throw and exit, without faking success. It changes no registry, reads no accounts, and submits no new model tasks.

To contribute an extension, place business code at `examples/extensions/<id>/index.mjs` exporting `run(client)`; add id/title/version/entrypoint/capabilities/permissions/status to the sole `spec/extensions.json`, following the existing device-report field format. The CLI supplies a scoped client based on declarations. Document author, actual usage, device/API, permissions, success criteria, failure boundaries, and licensing in the README; do not create a parallel manifest.

After registration, run `node sdk/cli/taku.mjs extension <id> --transport adb --serial <device>`. The SDK provides only existing methods; new OS methods such as camera, tapping, or installation do not become available by adding names to the registry. Extensions can implement arbitrary core business logic, but currently run in the same process without a security sandbox.

## Call from the device

The on-device shell needs no Node. Deployment and invocation follow; development deployment still uses host ADB, so this does not claim fully computer-free initial setup.

```sh
adb -s emulator-5582 shell mkdir -p /data/local/tmp/taku-os
adb -s emulator-5582 push core/device-probe.sh core/taku-device.sh /data/local/tmp/taku-os/
adb -s emulator-5582 shell sh /data/local/tmp/taku-os/taku-device.sh system.info
```

After preparation, the phone Android shell / Owner Agent can run directly:

```sh
sh /data/local/tmp/taku-os/taku-device.sh system.info
sh /data/local/tmp/taku-os/taku-device.sh displays.status
sh /data/local/tmp/taku-os/taku-device.sh tasks.status
```

All three ran in the experimental emulator Root shell. Task-state calls from ordinary shell UID2000 were actually rejected; see foundation acceptance. Node in a Linux chroot is not Android shell; do not confuse it with the native-Android-command prerequisites of `--transport device`. Use an explicit device CLI/bridge.

Two app-generation paths are verified on the phone:

- [Early C2](scenario-acceptance.md) uses a preinstalled WebView shell/toolchain, with newly written JSX/API business code and on-phone APK building, signing, and installation; this candidate’s business API depends on a background Node service.
- The [2026-10-15 native app](agent-system-app-acceptance-2026-10-15.md) has Java and Manifest written by the Agent on a dedicated emulator, then compiled, signed, and installed on-device with a prepared ECJ/D8/aapt toolchain; the same package is updated while retaining business data. Native ContentProvider and UI share state, with a separate probe for actual permission rejection; this path has no precompiled business shell or web assets.

Neither path provides completely computer-free initial setup or an offline model; toolchain deployment, ordinary app permissions, physical-device compatibility, and full-ROM integration are verified separately. See [app-kit](../tooling/app-kit/README.md) for scripts.

## Install and use Skills

The Skills entry in native settings supports public GitHub repositories, pinning browsing and installation to the same commit and retaining origin/SHA. Native `skills/list` confirms recognition after installation; skills can be enabled/disabled, with up to four explicitly selected per conversation. Emulator acceptance on 2026-10-15 covered actual model use, not merely saving `SKILL.md`. See [Harness and Skills](agent-harness.md). Downloading skills does not automatically configure external-service credentials, MCP, or binary dependencies; skill removal, private-repository authentication, and third-party sandboxing remain undelivered.

## Use the corresponding build loop for system-component changes

```sh
bash system/assistant/build.sh
bash system/display/build.sh
bash system/overlay/build.sh
bash tooling/app-kit/build-shell.sh
```

Requires Android SDK android-36/build-tools36.0.0, JDK17+, Python3, and zip, configured with ANDROID_SDK_ROOT / JAVA_HOME. Each system component has its own out artifacts; test signing is not production signing. Build scripts do not install or complete the ROM for you.

For extension changes, run the extension and verify business output. For assistant/display-host changes, build the corresponding component, install on an explicit experimental device, and verify UID/SELinux, UI, and background displays. Framework, kernel, or device-integration changes use the corresponding full-system build/flashing path. The older Pixel 9 candidate was flashed and booted normally, and v44 components have device model/command/recovery evidence; latest v54 emulator acceptance cannot replace acceptance of the latest complete ROM, hardware matrix, or release. See [project status](current-status.md) and [image engineering](../image/README.md).

## Submit a contribution others can maintain

- Problem and result: give an input and clear success criteria, explaining what actually changed.
- Environment and permissions: device, API/ABI, build type, app version/login state, actual UID, and authorization source; distinguish Root, system signatures, and ROM modification.
- Validation: separately mark offline, emulator, and physical-device checks passed/failed/untested; preserve actual commands, output, screenshots/original recordings, and failures. Building cannot replace actual installation/execution.
- Contract and maintenance: declarations, errors, event/version changes, licensing origins, authors, and future maintenance scope; research capabilities must remain labeled research.
- Materials: submit only necessary public/redacted test evidence, excluding account login state, production private keys, app user data, and personal runtimes.

Use the [capability proposal](../.github/ISSUE_TEMPLATE/capability-proposal.yml), [compatibility report](../.github/ISSUE_TEMPLATE/compatibility-report.yml), and [PR template](../.github/pull_request_template.md).

The composed JS example in this documentation was actually run on 2026-10-10 Shanghai time, with an explicit development emulator and adb path: it read API36, UID0, and SELinux Enforcing and confirmed both primary and actual background displays existed. This check did not install, restart, create tasks, or replace ROM/physical-device acceptance; display IDs vary at runtime, so the example does not hardcode a background ID.
