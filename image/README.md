# Pixel 9 development image

**Current release: TakuOS 1.3.1 / Android 17 / 20261111080323.** [Public installation and updates](https://docs.taku.ai/zh/quickstart/mobile/) and the [release manifest](../docs/releases/tokay-20261111080323.json) provide current downloads and validation scope. Dated entries below preserve historical states.

For first-time builder setup, see [clean-environment preparation](builder/README.md). Runtime inputs may still require private-repository access; `python3 scripts/doctor.py --rom --network` anonymously checks reachability of pinned inputs and reports gaps without modifying anything.

2026-10-28 Google integration candidate: new builds default to **LineageOS 24 / Android 17 + MindTheGapps ARM64**, targeting **TakuOS 1.2.0 / Updater 7**. Taku Assistant and input method remain defaults; dependencies, permissions, APEX, and original Google signatures are included in immutable-image checks. Pixel 9 currently remains on accepted 1.1.0; the first data-preserving migration to 1.2.0 and Google login await real acceptance. See [default Google capabilities](../docs/google-services.md).

2026-10-26 product-version support update: product name fixed to **TakuOS**, with the next repair target configured as **1.0.1**; Updater source displays name and version using independent component version 4. Old `1.0` archives and v3 upgrade evidence remain readable. The exact applied v3 source layer can migrate under controlled conditions to v4 while retaining build caches. The new target has not been built or installed; the phone remains recorded under J's actual `1.0` identity. [Versioning](../docs/versioning.md) and [source verification](../docs/reviews/versioning-2026-10-26/README.md) explain implementation and release boundaries separately.

2026-10-27 integration candidate: target **TakuOS 1.1.0 / Updater 5**, derived from accepted 1.0.1 L snapshot, pinning IME 10062, Assistant 73, Memory 8, and accompanying background-display and Surfaces sources. Source, kernel, and build caches are reused; L and earlier attempts are preserved. New ROM and incremental upgrades receive separate acceptance. See [v73 ROM integration](../docs/runtime-v73-rom-2026-10-27.md).

The target is `tokay`, Pixel 9 12GB/256GB. The source baseline has moved to **LineageOS 24.0 / Android 17 / API 37 / userdebug**, based on the official 2026-10-19 tokay build. Platform, device, vendor, and kernel commits are pinned in [source-lock.json](source-lock.json), [kernel-source-lock.json](kernel-source-lock.json), and [manifests/](manifests/). Complete artifacts, vendor binaries, and signing caches remain in ignored out / Docker volumes.

2026-10-22 update: initial candidate A is retained but excluded as an upgrade source because boot measurements are missing; attempt B was preserved and stopped. Corrected C passed actual OTA/payload/AVB signature verification and booted on Pixel 9. A 263 KiB C→D incremental update also ran; the device is now D `20261021174811`. Exact boot measurements, slot switching, probe data, original apps/input method/proxy/persistent drafts, and snapshot merging were verified. The host installation tail record was lost; complete installation acceptance was neither fabricated nor gates relaxed. Nineteen C/D and incremental objects, about 13.12 GB, were archived under a separate Taku OS prefix in TakuOS R2 `releases`, with full per-object SHA readback passing. Owned staging objects and incomplete multipart uploads both total 0. Status remains an unpromoted candidate; automatic updates and the stable channel are disabled, and automation remains paused. The device AI account is not configured, so model-chat regression cannot be claimed. See [Android 17 baseline](../docs/android17-baseline-2026-10-20.md) for identities, measured evidence, R2 layout, and acceptance boundaries.

## Building on Mac mini

Run these commands in the Taku workspace on Mac mini. MacBook remains for source development and later artifact downloads. Build parameters come from [build-config.json](build-config.json).

Preparing the main reply component catalog requires Node.js 22.18+ and pinned npm dependencies in `system/agent-app` (initially run `npm --prefix system/agent-app ci`). Full asset preparation and the immutable source snapshot created by `start` generate `system/assistant/out/reply-assets/native/reply-contract.json` from the current directory. The snapshot explicitly seals this ignored file's SHA; Soong packages it with the Assistant APK, without depending on Node in the build container or later workspace changes.

```sh
python3 scripts/prepare-image-assets.py
python3 scripts/google-services.py fetch
python3 scripts/pixel9-image.py start
python3 scripts/pixel9-image.py status
python3 scripts/pixel9-image.py logs
# After source/kernel integration, resume an interrupted build from verified caches:
python3 scripts/pixel9-image.py resume
# When a complete build has finished and only artifact-verification rules need repair:
python3 scripts/pixel9-image.py verify
# Only after build and artifact verification succeed:
python3 scripts/pixel9-image.py export
```

When an old reference volume is genuinely empty, the full build records the absence of reference caches and cold-syncs from the pinned manifest. A volume with residual files but no `.repo` is still rejected to avoid treating a damaged cache as empty. If a rebuilt local builder has a new Docker image ID, set `TAKU_BUILDER_IMAGE=sha256:<full-digest>` when running the controller. The actual ID is recorded in `run-input.json`; the default remains the pinned image in configuration. A new image requires separate validation and must not be described as byte-identical to the old one.

The builder uses an amd64 Linux Docker image pinned by digest on an ARM Mac. Android 17 uses a separate container `taku-pixel9-android17-build` and source volume `taku-pixel9-lineage24-source`. Old `taku-pixel9-lineage23-source` is mounted read-only at `/previous` for repo object references; original `agentos-lineage23-source` remains read-only at `/reference` to satisfy existing absolute paths in old Git objects/alternates. Neither old volume is modified or used as writable Android 17 source or a resume checkpoint. Output is `out/pixel9-android17/`; old `out/pixel9/` and Android 16 artifacts remain. Actual initial full-build duration follows the logs; cross-architecture execution still adds overhead.

Current configuration is 8 CPU / 36 GiB RAM / 40 GiB memory+swap, Ninja concurrency 8. Soong graph construction has a separate Go soft memory limit of 24 GiB and runtime concurrency 4, managed by the same configuration. The soft limit is not a hard RSS limit for the entire process and still requires actual build validation. Historically, the 2026-10-11 build used 8 CPU / 36GB RAM, and the October 15 incremental retry used 10 CPU / 38GiB RAM. Actual quotas follow each round's container and snapshot records.

Soong's `env -i` launcher does not automatically inherit exported outer Go parameters. [apply-build-workarounds.py](apply-build-workarounds.py) verifies the pinned upstream exactly, then passes through `GOMEMLIMIT` / `GOMAXPROCS` and lets Blueprint retain explicit concurrency limits; original defaults remain when unset. After filtering, primary `soong_build` and main Ninja also receive only the exact value `GODEBUG=asyncpreemptoff=1`; other GODEBUG settings remain filtered. The patch covers currently directly invoked Go tools, not necessarily every additional sandbox environment. All four patches are validated before writing, with before/after SHAs in `build-workarounds.json`. Original v1 Go environment patches allow only an exact-match controlled upgrade. After kernel artifacts install successfully, `tools/bazel shutdown` runs through the same upstream wrapper to release the idle JVM while retaining sources and Bazel caches.

[build.sh](build.sh) uses official `SOONG_NINJA=ninja` to select the source-prebuilt C++ Ninja for both bootstrap and system compilation, retaining `m -j` concurrency. Siso previously triggered Rosetta's `unexpectedly got a signal during sigreturn`; its execution environment filters outer `GODEBUG`. Switching executors preserves upstream environment isolation and existing source/build caches without enabling `ALLOW_NINJA_ENV`. C++ Ninja still runs through Rosetta, so this does not promise elimination of translation-layer failures throughout the toolchain.

`start` creates an immutable snapshot of this round's Taku source and SHA-verified public runtime archives, mounted read-only in the container. Later workspace edits do not change active build inputs. `run-input.json`, `resolved-manifest.xml`, `build.log`, and `status.json` go to configured `out/pixel9-android17/`. An existing same-name container is not overwritten; check status first. Preserve logs and input snapshots before repairing/retrying failures. `resume` checks SHAs of integrated sources and the new snapshot, plus boot/module checkpoints. It permits only build-control script/documentation changes and goes directly into incremental system compilation with the original build number. Source or kernel input changes require full `retry`; new builds, ordinary `retry`, and next-candidate `rebuild` allocate new build numbers.

Pixel 9 continues to use the **6.1 kernel** on this baseline. Kernel sources are independently pinned across 18 repositories in [kernel-source-lock.json](kernel-source-lock.json), using the upstream caimito Bazel build. Signed GKI (build `16160610`), FIPS modules (build `11690398`), and source-built device modules form `out/caimito/dist`; `caimito-kernels` provides only module lists. New upstream GKI source lives at `common/gki`; build links and wrapper follow the pinned kernel manifest. Before packaging, check boot.img and declared modules, recording actual artifact SHA-256 and resolved kernel manifest.

An October 20 reinspection of original old-build logs confirmed that the earlier exit occurred in CellBroadcastApp's R8 JVM, with a crash stack involving G1 GC. New builds use [apply-build-workarounds.py](apply-build-workarounds.py) to add R8-specific SerialGC parameters controlled by `TAKU_R8_SERIAL_GC` to the generated Soong checkout, recording before/after SHAs. D8 and other Java tools retain their configurations. This workaround still requires actual build validation; see [migration record](../docs/android17-baseline-2026-10-20.md).

Generate and verify a full Android 17 image and signed target-files first, then create incremental OTAs on the same baseline. Incremental compilation using source caches and incremental OTA distribution to phones are separate mechanisms. Old Android 16 checkpoints cannot directly serve as `resume` inputs for the new baseline.

## Integrated components

### New Home / Agent application

The new launcher belongs to `org.agentos.agent`, built through Expo/Gradle in `system/agent-app`; Soong's `TakuAssistant` does not compile it. The ROM's ordinary-UID `TakuAgent` prebuilt module preserves the validated APK's original bytes and signature and installs it at `system_ext/app/TakuAgent`. First initialization prefers the new Home. On devices using old Taku Home, the app's HomeRole migration handles the change; other launchers chosen by users remain unchanged.

Build inputs use the private GitHub release asset mechanism in `runtime-assets.lock.json`. Agent APK package, version, signature, HOME entry, source hash, and download object are pinned together. Commit app source, build, and validate device behavior first, then run:

```sh
python3 image/agent_ui.py pin --tools "$ANDROID_SDK_ROOT/build-tools/37.0.0" \
  --release-tag <immutable-component-input-tag>
python3 image/agent_ui.py check
```

Upload that same APK to the private input tag under the generated `githubRelease.asset` name; do not overwrite different existing bytes. Other builders use `prepare-image-assets.py` to obtain and verify the exact asset. The snapshot explicitly seals the ignored APK. Whole-device acceptance checks target-files bytes, actual APK identity, HOME implementation, and a signature matching the platform certificate, recorded under `agentUiIntegration`; missing or old packages fail.

To bind only an unpublished local candidate, replace `--release-tag` with `--local`. This still verifies committed source, actual APK identity, and byte hashes. The lock records only `publication: unpublished`, with no download URL. It depends on the exact locally available artifact and is suitable only for local validation. Before committing a lock for others to build, publish its artifact and bind the actual download object. `tests/runtime-assets-fetch.test.py` rejects repository locks lacking a download source or still marked unpublished, preventing local caches from hiding clean-environment failures.

```sh
python3 image/agent_ui.py pin --tools "$ANDROID_SDK_ROOT/build-tools/37.0.0" --local
python3 image/agent_ui.py check
```

Check installed `/data/app` versions before OTA. Agent increases from code 7 to code 8, and Assistant from 76 to 77, so new system packages can replace matching-signature copies while retaining data. Ultimately verify the active path, APK hash, and default launcher on the target device. Version numbers or a frontend emulator preview cannot replace whole-device upgrade acceptance. A component-input tag is not a system stable release.

The following describes image integration in current source. Added or updated components still need verification in a successful complete build; configuration alone does not prove inclusion in the October 11 candidate or installation on Pixel 9.

- Compile and platform-sign Taku Home / VoiceInteractionService in the ROM.
- Reuse TaskBroker and VirtualDisplayHost, installed in system_ext/framework.
- Package the official Magpie Android ARM64 executable in system_ext at a pinned SHA. Independent init service `taku_magpie` provides only a local model gateway. Configuration, provider keys, and usage stay in the device data directory and are excluded from source snapshots.
- Integrate `phone-display`, Codex 0.161.0 ARM64 program archives, result schema, and public CA bundle. Include programs only, without login state, accounts, or task data.
- init starts services after boot completed; services wait for user 0 unlock before accessing CE task directories. First boot selects Taku HOME/ASSISTANT; later boots retain user role choices.
- Android init manages services; the background-display wrapper invokes the same init service inside the image. A fresh emulator validated reboot autostart, remaining stopped after a stop, and restarting.

The selected Lineage product does not introduce Pixel Launcher or Gemini. The upstream Recents provider remains temporarily for system gestures/Recents. Taku Quickstep contracts are not implemented, so replacing HOME does not mean Recents has also been replaced.

Default navigation is gesture navigation (the single horizontal gesture bar in Figma 1-6): `taku.mk` sets `ro.boot.vendor.overlay.theme=com.android.internal.systemui.navbar.gestural`. OverlayManager enables it at boot only when the navigation-bar category has no enabled overlay. Fresh flashes and OTA devices without a manually selected mode therefore switch; three-button navigation selected in Settings is preserved. Development devices that previously selected three buttons can switch with `adb shell cmd overlay enable-exclusive --category com.android.internal.systemui.navbar.gestural`. [verify-artifacts.py](verify-artifacts.py) requires gesture navigation in product properties and both `NavigationBarModeGesturalOverlay` and `TakuGestureOverlay` in the image. This proves artifact contents only; actual navigation mode still needs device verification with the next complete build.

Assistant gestures: double-tap the system navigation handle to open Taku's text composer and focus the keyboard; long-press for temporary voice, release to send, slide to Cancel and release to cancel, or slide to Keep talking and release to continue the same recording. Patches in [launcher/](launcher/) for pinned Launcher3 `818f72b7` replace the `NavHandleLongPressInputConsumer` ContextualSearch branch only when Taku is the default assistant. Directional movement beyond slop, multiple pointers, lock screen, default-assistant changes, and consumer changes cancel it. If long press is not recognized, input continues to the original navigation consumer. Double-tap remains passive observation and does not consume navigation events.

Long press calls the Home Assistant provider through ordered `prepare/move/heartbeat/end/cancel`, then opens a cross-app transparent composer through `SystemUiProxy.startAssistant` → Taku `VoiceInteractionSession.startAssistantActivity`. A UI-ready handshake prevents cold startup from opening the microphone after release. Details and Agent operations are in the [preinstalled interface documentation](../system/assistant/assets/skills/taku-open-access/SKILL.md). Google apps are not disabled, and explicitly choosing another assistant retains upstream behavior. Corner swipe remains disabled by default through `TakuGestureOverlay`; restore it with `settings put secure assist_touch_gesture_enabled 1`.

`apply-launcher-patches.py` checks pinned HEAD, patch SHA, and every target-file SHA, supporting upgrades from the exact old double-tap patch; other or partial modifications are rejected. Each revision uses a new results directory to preserve old audit records. **Source-patch validation and app APK validation do not establish system-navigation acceptance**: build Launcher3/a complete ROM containing the patch, then test long press, double-tap, Home, Recents, and quick app switching on the target device. The current host has no complete Android build tree/build container, and an ordinary AOSP emulator cannot prove Pixel Quickstep routing works.

## Explicit development boundaries

Current init uses `u:r:su:s0`. In upstream userdebug, **the su domain is permissive**; even if global `getenforce` returns Enforcing, this is not complete enforcing Agent isolation. Build entry points require userdebug, and components appear only in PRODUCT_PACKAGES_DEBUG, not user images. A production version needs dedicated service domains, authorized IPC, and policy acceptance.

Current Android 17 device configuration declares vendor baseline `CP2A.260805.005`, vendor SPL `2026-08-05`; the official reference build's OS SPL is `2026-10-01`, recorded separately. Old Android 16 baseline was `BP4A.260205.002` / `2026-02-05`. The October 14 installation used a special package retaining existing bootloader/modem; firmware versions were identical before and after, as recorded in [installation evidence](../evidence/builds/2026-10-14-pixel9-install.json). That outcome applies only to the recorded device and candidate. Every installation still needs target-phone firmware and anti-rollback checks; do not directly flash old boot firmware to a device with a higher rollback index.

Linux musl programs use Android platform DNS through loopback ResolverBridge instead of hard-coded emulator DNS. The bridge, background-display rendering/targeted swipes, main-display activity preservation, and Home/Assistant roles were validated on a fresh Android 16 emulator. October 15 Pixel 9 component acceptance also covered independent proxy networking, real model replies, Android read-only tools, and Wi-Fi/proxy/assistant recovery after cold boot. It did not cover every IME, video, sleep, or hardware behavior and does not replace physical-device regression for current new components.

## Artifact verification

[verify-artifacts.py](verify-artifacts.py) checks target-files ZIP CRC, product/vendor tokay properties, development build type, OTA pre-device, key partition images, Taku packages/JARs/scripts, and absence of Gemini/Pixel Launcher, recording SHA-256 and firmware entries. It performs file checks only. Thus `physicalDeviceVerified=false` and `coldBootVerified=false` in build results mean this artifact check did not run device tests; they do not negate separately recorded older-candidate installation or component tests. Every new candidate still needs independent device acceptance.

Public vendor components retain their original licenses; this project's source license grants no redistribution rights for vendor binaries. This round's complete package was for development acceptance and user-authorized private organizational candidate archiving, without public release or a stable update channel.

## Completed candidate (2026-10-11)

File SHAs and source/kernel provenance for the first complete development candidate are in the [versioned candidate record](../evidence/builds/2026-10-11-pixel9-image-candidate.json). The original delivery manifest is `out/pixel9/artifacts/delivery-manifest.json` on the build host. OTA size is 1,718,080,173 bytes; target-files size is 4,209,216,925 bytes; four recovery images from the same batch were extracted. Excluded-package checks across 247 APKs, public runtime input SHAs, and Taku/system-framework platform certificate checks passed. Actual platform SPL was 2026-09-01; vendor baseline remained the device-tree declaration 2026-02-05. This records October 11 build/file acceptance; later physical-device results are separate below.

The generic system partition's ro.product.system.device=generic alone cannot determine the flashing target. Verification also requires product/vendor tokay, OTA pre-device tokay, and userdebug. Ten regression checks include rejecting vendor mismatches, similar names, and user builds.

## Physical-device installation and component acceptance (2026-10-14–15)

- October 14: the firmware-preserving package based on the October 11 candidate installed successfully; Android booted normally and Taku Home/background root services were confirmed. That day's record did not cover power-off cold boot or a real model task. See [installation record](../evidence/builds/2026-10-14-pixel9-install.json).
- October 15: v43 Assistant and broker validated independent phone networking, real models and same-session follow-ups, and Android read-only tools, recording cold-boot recovery for Wi-Fi, VPN, and assistant invocation. v44 validated real dynamic tools, same-turn additions, question cards, goals/queues/file operations; v45 completed UI and directory-navigation acceptance. See [independent operation](../evidence/builds/2026-10-15-pixel9-independent-agent.json), [native-client acceptance](../docs/native-codex-implementation-2026-10-15.md), and [v45 UI record](../evidence/builds/2026-10-15-native-codex-ui.json).

These used APK/broker updates without rebuilding the complete ROM or publishing a signed OTA channel. Partial physical-device cold-boot recovery does not establish acceptance of all hardware, every app, long-term standby, or the latest source version.

## Updating Taku inputs in existing caches (2026-10-15)

Use `python3 scripts/pixel9-image.py rebuild` when a complete checkpoint exists. The controller preserves prior artifacts and logs first. Incremental checks require unchanged platform/kernel locks, no drift in integrated files, and passing boot/module checks. Only vendor/taku updates; all build caches remain. Failed retries also preserve the original container and immutable inputs. New exports use snapshot-specific directories to avoid overwriting old files.

To upgrade branded versions from a frozen Updater 3 / product 1.0 baseline with runtime integration, use `rebuild --from-snapshot <SHA-256> --integration taku-branding-version-v2`. This accepts only the fixed old v3 branding contract and derives nine inputs required for the three-part product version and Updater 4, including the shared APK byte/signature verifier. It retains the parent snapshot's build entry point, product modules, runtime lock, and complete system AI acceptance gates, replacing only the exact Updater-version expression in the parent artifact verifier. It does not overwrite old-baseline runtime evidence with the development workspace's general verifier, import new components, or clear build caches. Inspect the derived snapshot first with `derive` and the same parameters. Actual signing, export, and data-preserving device upgrades remain required per version.

Current Agent resources and broker feature classes are also included in artifact checks. The Ninja FIFO open safeguard under Rosetta handles pipe connections only; it does not generate build progress. See [cancellation repair and incremental build](../docs/agent-cancellation-2026-10-15.md). New component acceptance does not mean these changes entered a complete image or were installed on a physical device.

For build-configuration repairs, use `rebuild --from-snapshot <SHA-256> --overlay Android.bp --overlay image/build.sh` to derive a new snapshot from an existing one, overlaying only explicit build-control files while retaining other frozen contents. This does not collect unrelated current UI changes. It records the parent snapshot, overlay paths, and dirty baseline. Retries inherit the previous container's CPU, memory, and swap quotas.

Minimal Updater canary integration uses separate `derive` / `updater-preview` / `rebuild --from-snapshot <D-SHA> --integration updater-canary` entry points. See [Pixel 9 test updates](../docs/pixel9-test-updates.md) for the six allowed input overlays, fixed upstream patch, platform-signed APK checks, and native installation observer. It imports no other workspace WIP and does not expand build-repair `--overlay` scope. Preview records APK build results only in an independent directory; D's state, inputs, and immutable archives remain unchanged. Passing source tests does not establish acceptance of a new APK, complete package, or physical-device upgrade.

The 2026-10-15 preinstalled FlClash build added the APK's three optional uses-library declarations. The original strict manifest_check passed; library-consistency checks were not disabled. See [repair record](../evidence/builds/2026-10-15-pixel9-proxy-build-repair.json) for retry and scope.

## Latest complete-build record (as of 2026-10-19)

The [latest versioned status](../docs/research/pixel9-image-build-state.json), committed October 18, records completion at **2026-10-16 00:40 Beijing time** (original UTC: October 15 16:40): `phase=failed-exit-1`, `running=false`, `romBuilt=false`, `oomKilled=false`, `artifactCount=0`. This retry produced no new complete-package export.

FIFO blocking and missing optional uses-library declarations have their own repair evidence, but the final status file does not record this exit's specific error. **The final failure's root cause still requires the original build logs**; it cannot simply be attributed to already repaired issues. Current source and emulator-component progress must be assessed separately from full-build status.

## Source repairs for frozen components

`derive-runtime-repair` / `rebuild-runtime-repair` reuse an existing runtime snapshot and original component packet, reading only individually listed UTF-8 sources, public locks, and version inputs. Repair packets use a `taku.system-ai-source-repair/1` manifest: parent snapshot/manifest SHAs, original component commit, new source commit, three component versions, declarations of no credentials/no physical acceptance, and per-file before SHA, after SHA, size, and mode. Deletions, unlisted payloads, private paths, APK/native binaries, and arbitrary build-control files are rejected. This is not a general extension of `--overlay` permissions.

`taku.system-ai-source-repair/2` adds per-file `contentType` to the same source binding. Only seven explicitly listed Assistant fonts and two launcher icon types across five densities may be public binary resources. TTF checks cover directory/table bounds; PNG checks cover signature, dimensions, chunk CRCs, pixel chunks, and file ending. Both have size limits and must be non-executable. Other inputs remain NUL-free UTF-8 source. Unknown resource paths, APK/JAR/SO, credentials, and arbitrary build controls remain rejected. v1 does not automatically gain resource permission. New resources must bind to the candidate Git commit and original resource hashes; old snapshots and archives are not rewritten.

Callers provide an independently reviewed packet through the original four `--runtime-handoff` / manifest SHA / packet / packet SHA arguments and explicitly set JSON minimum versions for `ime`, `assistant`, and `memory` through `--minimum-runtime-versions`. Versions must also not be below parent versions and must exactly match component source manifests/IME locks. Original `5fc006a` handoff remains. The repair manifest and complete parent-input manifest are enclosed in `image/source-repair.json`, included in the content digest. `runtimeIntegration.sourceRepair` binds new source origins and versions to run-input, actual component acceptance, receipts, exports, and `load_baseline`. The shared verifier is archived with SHA verification. Old archives without repair fields retain their format. Preparing entry points and passing source tests does not mean derivation, build, release, or physical-device acceptance has occurred.

When rebinding reviewed components to a new branding parent snapshot, generate a new handoff and packet; do not rewrite the old packet's parent identity. `derive-runtime` / `rebuild-runtime` may additionally accept paired `--runtime-contract` and `--runtime-contract-sha256`. Contract schema `taku.reviewed-runtime-handoff/1` explicitly lists `baseSnapshotDigest`, `baseManifestSha256`, `handoffManifestSha256`, `packetSha256`, `sourceCommit`, three component `versions`, `replacementCount`, `unchangedCount`, and `cachePolicy=checksum-changed-inputs-fresh-mtime`. The original four handoff arguments remain required and must match exactly; all parent files, replacement preimages, payloads, and JNI locks remain verified. Original contract JSON and SHA travel with the runtime descriptor into run-input, artifact reports, and archive acceptance. Without a contract, original fixed parent-snapshot/component-packet constraints remain.

Source integration uses `rsync -a --checksum --no-times --delete`: compare contents byte for byte, assign current timestamps only to changed inputs, and preserve caches for unchanged inputs. Otherwise, restoring source from an earlier frozen snapshot can give old mtimes that cause Ninja to reuse generated manifests from a later version. The new contract entry strictly derives this replacement from frozen parent build.sh, without deleting Soong/kernel caches or rewriting old snapshots.

### System privileged permission packaging

TakuMemory uses the native Soong `privapp_allowlist` property. Its reviewed policy is installed as `SYSTEM_EXT/etc/permissions/TakuMemory.xml` beside the privileged app and grants its declared `DUMP` permission. The image verifier checks the actual policy bytes, package, grants, ownership/mode and actual APK permission requests. It also audits every `Taku*` privileged APK against permission protection flags from the actual framework-res APK: every requested privileged permission must be allowed in the same partition, and denies or omissions stop artifact verification. Export revalidates the policy and the bound permission-closure report.

The `taku-memory-privapp-v1` integration derives only the four explicit policy/module/validator files from the immutable H snapshot; it preserves application source, pinned IME inputs, branding, runtime input contracts and platform/kernel locks. H failed real startup because the previous artifact gate omitted this policy; its logs and signed archive remain preserved. The new image still requires actual boot, stable system_server, granted DUMP, existing data/configuration checks and UI/AI acceptance. No enforcement switch, data wipe or temporary runtime policy is used.

The standard Codex ARM64 archive includes the Taku plugin transaction patch, with source, size, and SHA-256 pinned in `runtime-assets.lock.json`. Taku release assets are in a private repository. Asset preparation requires GitHub CLI login or `GH_TOKEN` with repository read access; credentials are not included in the image. See [Codex runtime notes](../adapters/codex-runtime/README.md).
