# Getting started

This page is for developers who want to build Taku OS from source and run its tests. To flash a phone, run the emulator or build a plugin, go to [docs.taku.ai](https://docs.taku.ai).

Taku OS is a research preview. See [current status](current-status.md) for what works where.

## Prerequisites

| Goal | You need | You get |
| --- | --- | --- |
| Read the SDK method list and make a first change | Git, Node.js 22+. No `npm install` | A local method list and a small script to edit. No device access |
| Run the full offline checks | Node.js 22+, Python 3.11+ (3.12 recommended), JDK 17+ | Results from `npm run verify` |
| Build the React Native UI | Node.js 22.18+, npm dependencies. Android builds also need a JDK and the Android SDK | A local component gallery or the Agent APK |
| Build the native Android components | Bash, Python 3.11+ (3.12 recommended), JDK 17+, Android SDK 36, zip and unzip | APK, JAR and DEX files for each module |
| Build the full Pixel 9 image | The Docker builder and the pinned platform, device, kernel and runtime inputs set by the image project | A checked candidate image |

## Get the source

```sh
git clone https://github.com/Taku-OS/taku-os.git
cd taku-os
node --version
env -u TAKU_OS_TRANSPORT -u TAKU_OS_SERIAL node sdk/cli/taku.mjs call methods.list
```

Clearing the two environment variables applies only to this command and keeps it from inheriting a device configuration. On success the exit code is `0` and the JSON has `protocol` set to `taku/0.1` and `ok` set to `true`. `result` lists six methods: `methods.list`, `capabilities.list`, `extensions.list`, `system.info`, `displays.status` and `tasks.status`. The command reads [core/methods.json](../core/methods.json).

Next, try [your first change](first-change.md). It needs only Node.js. The call surface is described in the [protocol](protocol.md).

## Run the tests

Run these from the repository root. The root needs no `npm install`.

```sh
npm test
npm run check
npm run verify
```

`npm test` and `npm run check` need only Node.js. `npm run verify` runs the full offline suite, also needs Python 3.11+ (3.12 recommended) and JDK 17+, and stops at the first failure. [Development](development.md) explains which checks to run for each kind of change.

## Build the native Android components

The default toolchain is Android SDK `platforms;android-36`, `build-tools;36.0.0`, JDK 17+, Python 3.11+ (3.12 recommended) and zip and unzip. Component scripts resolve these paths through [android-env.sh](../scripts/android-env.sh).

```sh
export ANDROID_SDK_ROOT=/absolute/path/to/android-sdk
export JAVA_HOME=/absolute/path/to/jdk
npm run build:android
# Optional development diagnostics
npm run build:android -- --with-diagnostics
```

[build-android.sh](../scripts/build-android.sh) builds these modules in order.

| Module | Main outputs, relative to the repository root |
| --- | --- |
| Assistant and task service | `system/assistant/out/AgentOSAssistant.apk`, `system/assistant/out/task-broker.jar` |
| Display and overlay | `system/display/out/virtual-display.jar`, `system/overlay/out/AgentOSLabSettingsOverlay.apk` |
| Surfaces | `system/surfaces/out/TakuSurfaces.apk`, `system/surfaces/out/taku-surfaces-client.jar` |
| Proactivity and Memory | `system/proactivity/out/TakuProactivity.apk`, `system/memory/out/TakuMemory.apk` |
| App-kit shell | `tooling/app-kit/apk-shell/out/dex/classes.dex` |
| Optional diagnostics | `tooling/diagnostics/out/diagnostics.jar` |

This entry point does not build the React Native UI, plugins or the input method. Build the input method with `npm run build:inputmethod`. To rebuild one native component, call its own `build.sh`.

The first build downloads Markdown dependencies, the Memory vector model and ONNX dependencies, and the public AOSP test signing keys, so it needs network access. The test keys are cached in `out/development-signing/` and are not a production identity.

## Build the React Native UI

The React Native project is in `system/agent-app/`, with dependencies separate from the root SDK. Read its [AGENTS.md](../system/agent-app/AGENTS.md) before changing it.

```sh
cd system/agent-app
npm ci
npm run typecheck
npm run lint
npm test
npm run gallery
```

Open `http://localhost:8083/components` to see the real components rendered with local samples. Press Ctrl+C to stop it.

To build the APK, set `JAVA_HOME` and `ANDROID_SDK_ROOT`, then run `npm run build:android` in this directory. The output is `system/agent-app/out/TakuAgent.apk`. The APK needs a compatible assistant and task service with matching signatures, and Expo Go cannot provide this native gateway. Do not edit the generated `android/` and `ios/` directories by hand. See the [module README](../system/agent-app/README.md) for NDK, CMake and build details.

## Build the full image

### Default applications

New internal source builds include **Pixel Weather**, **YouTube Music**, and **Taku Audio**.
The Google apps retain their original signatures and are pinned in
[google-apps.lock.json](../image/google-apps.lock.json); Taku Audio includes its launcher
entry and the local ASR/TTS pack. The widget provider packages have no duplicate Clock
or Weather launcher icons. Accounts, location consent and audio API keys are configured
on the device, not included in an image.

For component development on an explicitly selected **ARM64, API 36+ Google-enabled,
root-capable emulator**, run the default-app step after installing the Taku components:

```sh
python3 scripts/install-default-apps.py --serial emulator-N
```

Replace `emulator-N` with the intended emulator serial. The command checks GMS, Google
Services Framework and Play Store, downloads only the pinned original Weather and Music
APKs, verifies their hashes/signatures, builds Taku Audio when needed, installs its local
speech models, and installs the widget providers. It preserves app data and does not
execute archive installers or npm/npx. It does not install the whole OS or add Google
services to a bare AOSP emulator. Use `--prepare-only` to prepare inputs without a device.
After installation, confirm all three entries appear in Home and open them; the generated
`out/dependencies/default-apps/.../installation-report.json` records package verification.

For a full image, prepare the Google app dependency with
`python3 scripts/google-apps.py stage --apk-dir /path/to/original-apks` (the existing Chrome
input requires `chrome.apk`; the other apps have pinned download sources). Build and seal
the current Audio app using `bash system/audio-engine/build.sh --with-tts` followed by
`python3 system/audio-engine/prepare-rom.py --write-lock` with the configured JDK/SDK.
Review the resulting lock, then use the image controller below. Missing or altered inputs
fail the build/verification instead of silently omitting apps. The existing Google-app
layer remains `internal-test-only`; this change does not publish a new OTA or emulator image.

Previously published downloads and frozen build snapshots are unchanged. These defaults
take effect in a newly built image, or through the component installation command above.

### Image controller

The full image build currently targets Pixel 9. Its controller is `python3 scripts/pixel9-image.py`. Before you start, read the [image project](../image/README.md) and [build-config.json](../image/build-config.json) to check the amd64 Linux Docker builder, resource limits, pinned inputs and output directory.

Prepare runtime assets with `python3 scripts/prepare-image-assets.py`, which reads [runtime-assets.lock.json](../image/runtime-assets.lock.json). Then run the controller through `start`, `status` and `logs`, `verify`, and `export`.

Run `python3 scripts/doctor.py --rom --network` first. The Dockerfile and pinned runtime source-build script are available, but the current Codex and Agent APK release URLs require private repository access. A complete ROM cold build from anonymous inputs has not yet passed; see [open-source readiness](open-source-readiness.md). Existing release input hashes must not be edited to bless an unbuilt component.

## Common problems

| Problem | What to do |
| --- | --- |
| `javac: command not found` or `Set JAVA_HOME to JDK 17 or newer.` | Install JDK 17+ and set `JAVA_HOME`. The SDK steps above work without a JDK |
| `Set ANDROID_SDK_ROOT to an Android SDK.` or missing platform or build tools | Install SDK platform 36 and build-tools 36.0.0, then set `ANDROID_SDK_ROOT` |
| `DEVICE_REQUIRED` | The method needs a device. Use `methods.list`, which runs without one |
