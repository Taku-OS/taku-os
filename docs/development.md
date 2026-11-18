# Development

Taku OS is licensed under [Apache-2.0](../LICENSE). If this is your first time in the repository, start with [getting started](getting-started.md), then make [your first change](first-change.md).

## From change to review

1. Read the root [AGENTS.md](../AGENTS.md) and the instructions for the module you are changing. Use the [project structure](project-structure.md) to find the right place for the change. When an interface changes, update the spec, the call examples and the preinstalled agent documentation.
2. Work on a topic branch.
3. Run the checks that apply from the table below. If device behavior changes, confirm real reads and writes against the GUI's source of truth on an isolated emulator, and record the environment, commands, expected result, actual result and what you did not cover.
4. Open a pull request that states what changed, how you verified it and any limits. See [CONTRIBUTING.md](../CONTRIBUTING.md).

The Agent and the GUI must use the same authoritative data and validation. Every GUI operation also needs an entry point the Agent can use. Document the actual `settings`, `content`, `cmd` or component commands, their parameters and errors, and any data that upstream genuinely cannot expose. Do not refuse Agent access on the grounds of isolating ordinary apps.

## Choose your checks

Run these from the repository root, except for the React Native row.

| Change | Command | What it covers |
| --- | --- | --- |
| Markdown entries, links, structure | `npm run check:structure` | Local Markdown targets, directories and source-only rules |
| SDK or protocol | `npm test`, `npm run check` | Root Node tests and JSON and protocol checks |
| Capability inventory or native interface list | `npm run check:inventory`, `npm run check:raw-interfaces` | Consistency as defined by the lists and check scripts |
| Whole repository, offline | `npm run verify` | The tests and checks in the root package scripts, run in sequence |
| Plugins | `node --test tests/godmode-manifest.test.mjs`. With a JDK, also `bash system/plugins/test.sh` and `bash system/plugins/test-host.sh` | The local behavior of the manifest and host. See the [plugin docs](plugins/README.md) |
| Native Android source | `npm run build:android` or the module's `build.sh` | Compile and packaging only. Check runtime behavior on an isolated emulator |
| React Native Agent UI | In `system/agent-app/`, run `npm ci`, then `npm run typecheck`, `npm run lint` and `npm test`. For an APK, run `npm run build:android` | The module's own checks and build |
| ROM integration | The build, artifact check and device steps in the [image project](../image/README.md) | Results tied to an exact input snapshot and output |

[package.json](../package.json) defines the order of `npm run verify`. It covers Node tests, JSON, capability and native interface lists, structure, images, surfaces, proactivity and OTA. It stops at the first failure. The root needs no `npm install`. The full run needs Node 22+, Python 3.11+ (3.12 recommended) and JDK 17+. CI uses Node 22 and Python 3.12, as set in the [workflow](../.github/workflows/ci.yml).

For a docs-only change, run the structure check and run the smallest set of commands from the page that make sense on your machine. If a change affects runtime behavior, add the checks and isolated emulator testing that match its scope.

## Toolchain and dependencies

Native components default to Android API 36, build-tools 36.0.0 and JDK 17+. `TAKU_ANDROID_API` and `TAKU_BUILD_TOOLS` are for explicit version experiments. React Native needs Node 22.18+, and its native build also needs Gradle, the NDK, CMake and other dependencies listed in the [module README](../system/agent-app/README.md). The plugin host scripts support macOS Bash 3.2 and Linux Bash; CI verifies both environments.

[markdown-dependencies.json](../system/assistant/markdown-dependencies.json) pins the Markdown dependencies for the assistant and Memory. Scripts verify them after download and cache them in the git-ignored `system/assistant/downloads/`. Memory also needs a vector model and ONNX assets, as described in its [README](../system/memory/README.en.md).

When you upgrade a Markdown dependency, update `Android.bp`, keep the Java resources and asset licenses inside the libraries, and rerun the Markdown device tests. The ROM preparation script fetches these dependencies and includes them in the source snapshot check. Runtime archives are prepared by [prepare-image-assets.py](../scripts/prepare-image-assets.py).

The public AOSP test signing keys are for userdebug development only. They are cached in `out/development-signing/`, with the download revision and hash pinned.

## Test output and device notes

Write exercise, check and probe output to the git-ignored `out/` directory or a module's own `out/`, and keep failure details. Root structure and inventory checks write to `out/checks/`. Before you share any output in an issue or pull request, remove accounts, private keys, credentials, chats and raw model events.

For a device change, note the source and component versions, Android version, API level and ABI, the selected serial, the caller UID and SELinux state, permission requirements, the exact input and the success condition. For a new setting or operation, report the Agent's reads and writes and the matching GUI result, with a native screenshot or recording. Read runtime identifiers such as display IDs from the current state. Do not batch-run research examples that click, install or send notifications.

## Documentation

[docs.taku.ai](https://docs.taku.ai) syncs versioned documentation from this repository. Fix interface documentation here first, then sync it to the site. When you describe a capability, keep these states separate: implemented in source, offline checks, emulator, physical device, full ROM and released.
