# Taku Proactivity

An independent native Android app that stores observer authorization and policies, combines events, and publishes hints through Taku Surfaces after changes settle. Community extensions run in their own processes, with independent signatures and updates, and connect through the [public protocol](API.md) or optional SDK.

Version 0.1 implements one rule, `settled_event`: plugins produce their own namespaced events, and the host applies a settling delay, hint interval, and latest-N context selection. The host does not collect event bodies; the example reports metadata about its own page revision. This validates the community development loop. Global screen observation, Jev decisions, and background Agent execution are not connected yet.

## Interface

The interface follows the Taku Memory app's warm white background, orange actions, native cards, icons, and brand resources. The overview presents existing data through an activity heatmap, hint trend chart, observer status ring, and active-source bars. Observer details explain the source, trigger, hint preview, frequency, and version. Activity records explain maintenance and hint outcomes. Package names, event IDs, and signatures appear in expandable integration details.

The heatmap shows 24 consecutive hours, including the current hour, in the local time zone. Hint trends use two-hour buckets. Tap a chart or the previous/next-hour controls to inspect counts. Each observer retains only its latest 16 activity records. Charts show records still retained within that scope; they cannot establish complete daily usage or acceptance rates. Empty cells mean no retained records. The status ring reflects authorization configuration, not whether a plugin is online.

In details, users can allow or disable an observer, adjust four policy parameters, or roll back. New policies and plugin version changes require confirmation. Edits check the expected revision and save a new revision. Removing an observer revokes authorization and deletes its history. Refresh preserves scroll position. The full-screen policy editor keeps Save above the keyboard, preserves unsaved input across rotation, and supports large system text. See [design and emulator screenshots](../../docs/reviews/proactivity-2026-10-24/README.md).

Observers may optionally declare a `PromptSpec`, such as `Try on` / `Not now`. After approval, it uses the existing initially expanded Surfaces top choice card and returns fixed `accept` / `cancel` action IDs. Legacy nine-field declarations keep the single `Viewed` hint. Receipts distinguish `promptType=choice` from `hint`; a choice consumer checks `accepted` and `actionId=accept` together. The host does not execute any service or invoke a model. The management preview includes both labels, and timing edits preserve the approved prompt.

## Build

```sh
npm run build:proactivity
bash examples/proactivity-observer/build.sh 1
bash examples/proactivity-observer/build.sh 2
npm run check:proactivity
```

Dependencies match the other components: Android SDK 36, build-tools 36.0.0, and JDK 17. Outputs are in ignored `out/` directories: host `TakuProactivity.apk`, public `taku-proactivity-client.jar`, and plugin `Observer-v1.apk` / `Observer-v2.apk`. These commands only compile.

`check:proactivity` runs offline scheduling, overview, prompt wire/renderer-input and registry approval/edit/rollback checks. The prompt tests use filesystem substitutes for Android Context and AtomicFile; they make no model calls and do not install or operate any device. Their results and the APK build do not establish emulator UI acceptance or whole-ROM integration.

The host uses the platform development test signature to call the existing Surfaces signature bridge. It does not share the system UID. Ordinary observers need no platform signature, privileged permissions, or host source. The example's development key is in its own ignored `out/signing/` directory and differs from the host key. Production signing and system-image integration are separate work.

## Module boundaries

| Directory / class | Responsibility |
| --- | --- |
| `contract/`, `client/` | Exportable data contract and optional Messenger client, without the host implementation |
| `RegistryStore` | Atomic persistence of authorization, policy revisions, activity records, and last successful publication time |
| `SettledObserver` | Bounded metadata records, settling timers, deduplication, cooldown, and deterministic latest-N selection |
| `HostRuntime` | Binder identity, protocol validation, authorization gates, candidate invalidation, and Surfaces bridge |
| `ProactivityActivity`, `ProactivityUi` | User operations inside the host and native UI |
| `OverviewStats`, `OverviewCharts` | Read-only aggregation and charts from the existing registry/trace, without additional collection or analytics libraries |
| [Independent observer](../../examples/proactivity-observer/README.md) | Depends only on the exported SDK; the event source, page, and debugging features belong to the plugin |
| [Request schema](../../spec/proactivity-v1.schema.json) | Public v1 format and bounds; describes the protocol together with the API documentation |

A bound client or management page keeps the host alive. The example disconnects when it leaves the foreground, invalidating pending context, and does not poll screenshots in the background. Authorization, policies, history, and the cooldown from successful publication persist. Process recovery does not replay events or external actions. The official host package is currently fixed; alternative-host selection and migration remain unimplemented.

## Device acceptance

`tests/verify-emulator.py` accepts only an explicitly named disposable AVD and an `emulator-*` serial. It checks both the qemu property and AVD name at runtime. Installation and cleanup run only with `--install --reset-fixtures` and affect the example's three packages. Use your own empty test emulator.

```sh
source scripts/android-env.sh
python3 system/proactivity/tests/verify-emulator.py \
  --serial emulator-5590 --expected-avd Taku_Proactivity_20261024 \
  --adb "$ADB" --apksigner "$TOOLS/apksigner" \
  --out out/proactivity-acceptance --install --reset-fixtures
```

Acceptance covers real Binder registration and rejection, user approval, merged page changes, system hint publication ACK and tap callbacks, disabling, independent APK v1→v2 upgrade, policy rollback, debug APK v2→v1 downgrade, and data retention. APK downgrade is a development test of the debuggable example; it does not establish that Android allows arbitrary production APK downgrades.

Next priorities are independently authorized global event sources and image capability adapters, public Jev and Agent bridges, AI policy tools, conflict previews, and import/export. The [design document](../../docs/proactivity-trigger-design.md) contains the full plan and source discussion references.
