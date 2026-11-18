# Issue #28 cloud build and physical-device handoff

Owner decision: finish source work first; perform the real ROM build and device acceptance together
later. The SystemUI module has since passed its [real cloud platform build](reviews/island-cloud-2026-11-15/README.md);
the Pixel ROM and physical-device gates remain open. The ledger in [dynamic-island-progress.md](dynamic-island-progress.md) separates source,
local checks, and acceptance. Do not close #28 based on a source check or APK-only emulator install.

## Same-source emulator checkpoint

The full `lineage_taku_emu_arm64-cp2a-userdebug` image built and booted on a separate local ARM64
AVD on 2026-11-15. It uses all 1,239 pinned platform project revisions, Goldfish hardware, and the
Agent source frozen at `116147f`; later concurrent desktop commits are not in that image. See the
[device record and remaining failures](reviews/island-emulator-2026-11-15/README.md) and
[reproducible build/launch tools](../image/emulator/README.md).

The covering-card focus refinement has now **failed device validation**: after expansion with no
IME, an outside editor tap reaches the app but subsequent text input stays targeted at `TakuIsland`.
Opening the IME first uses the non-focusable below-bar mode and accepts input. The next window
change must keep covering cards non-focusable and route Back through the platform, then rerun both
orders, outside taps and surface input. Do not close the original no-focus gate based on Back alone.

## Inputs and build gates

Use branch `vela/TAKU-new-UI` at an agreed immutable commit and a clean build snapshot. Framework
pin is `LineageOS/android_frameworks_base@7f6de40b6429462d47ba9ba351b99943269c8067`, Android 17,
Lineage 24, target `lineage_tokay` userdebug. Preserve the repository's platform/kernel locks.

The production `image/build.sh` applies the island patch on fresh/incremental integration and
verifies it on resume/verify-only. Historical narrow snapshots without island inputs keep their
original behavior. Use a complete current snapshot for this feature; do not reuse a narrow updater
snapshot. Unknown or partly patched upstream bytes fail without modification. If an older island
patch is already applied, use a fresh isolated pinned checkout (or explicitly review/revert only
that generated patch); do not use a blanket hard reset of a colleague's source tree.

A matching Agent release from `cc8da40` has now been built and its certificate matched to cloud
SystemUI (see the receipt above). It is not yet published or pinned in the runtime-assets lock.
Before freezing a newer snapshot, **rebuild the Agent APK from those sources**, signed with the same
platform development certificate as the ROM. The currently published runtime-assets lock predates
this migration and must not be relabelled as a new APK. Run the established agent-app Android build
workflow (`npm run build:android` in `system/agent-app`, using its documented local toolchain),
place its actual signed ARM64 release at `system/agent-app/out/TakuAgent.apk`, and publish/pin its
immutable artifact using the existing `image/agent_ui.py pin --tools ANDROID_BUILD_TOOLS --release-tag TAG`
workflow. Commit the APK sources first; build/pin binds actual bytes, version, certificate and source
hashes. `image/agent_ui.py check` must pass before the image snapshot is created. The shared
`system/island/src/org/agentos/island/` wire is now included in that source binding.

The published binary refresh remains required; a locally built APK is not yet a published, locked
release. No release URLs or lock hashes are invented. The snapshot gate fails on the old APK.
The ROM builds TakuAssistant from source, including the new preinstalled island Agent documentation.

On the Linux builder, through the repository's existing image runner/mounts:

```sh
python3 /taku/image/apply-island-patches.py --check-inputs
python3 /taku/image/apply-island-patches.py --source-root /src --results-dir /results
python3 /taku/image/apply-island-patches.py --source-root /src --results-dir /results --verify-only
# Normal full build controller applies all other product policies and runs the pinned lunch/build:
bash /taku/image/build.sh --incremental
```

`--incremental` requires the existing matching platform/kernel checkpoint; on a new builder run
`bash /taku/image/build.sh` instead. Build SystemUI with the exact platform's Kotlin/Dagger/Soong
pipeline; local API doubles do not establish this. Full image verification additionally checks
SystemUI provider/settings implementations, both Agent services/action receiver, their manifest
permissions, and exact preinstalled Agent docs with `verify-island-artifacts.py`. Keep
`island-probe-integration.json`, `island-artifacts.json`, full build logs and normal ROM receipts.
The legacy `probe` filename denotes the source-patch contract, not a runtime acceptance result.

## Local source evidence

- `npm run check:island`: strict patch/source coverage; artifact verification failure cases;
  22 lifecycle/arbitration and 15 Android-source-classification checks.
- Agent native module `:taku-home:compileReleaseKotlin` and `:taku-home:testDebugUnitTest`: 129 tests.
- Agent `npm run lint` and `npm run typecheck`.
- `system/island/typecheck.py --android-framework-jar PATH`: real Android 17 framework APIs with
  explicit narrow SystemUI doubles. **No Soong or Dagger processor**.
- Parser instrumentation APK: 50 checks already passed on API 37 emulator-5586 before integration.
  It changes neither SystemUI nor the user's app and proves no window/input behavior.

## Required acceptance on the final image

Record Git commit, exact APK/image digests, build number, device, display size/cutout, orientation,
font scale, navigation mode, Lineage clock/network options and device/host load. Save screenshots,
`cmd statusbar island state/get`, `dumpsys window`, SystemUI dumps, crash logs and frame traces.

1. Verify boot/reboot/upgrade, SystemUI restart and notification reconstruction without opening Home.
   Confirm provider/cmd capabilities and actual producer connection. First frame, animations and
   callbacks must not block the main thread. Measure performance; no source-only FPS claim.
2. Verify capsule cutout, center/corner/no-cutout, centered clock/network options, large fonts and
   narrow width. Clock/battery stay accessible. Test tap, long press, horizontal selection and shade
   drag through the capsule. Secondary glyphs and stale/timer/progress state must match readback.
3. Test card corner/edge/outside taps at every animation stage. Outside taps reach the app and leave
   the card open. Handle/up-swipe/capsule tap/Back, rotation, IME, shade and keyguard transitions work.
   Cover cards currently take focus with ALT_FOCUSABLE_IM for Back; automatic/below cards do not.
   This is an explicit refinement of the issue's blanket no-focus wording, **not yet accepted**.
   Verify outside-tap focus transfer and Back afterward; if strict no-focus is required even for
   a covering card, additional platform Back routing must be implemented before closing that gate.
4. Run #25 F1–F5 in the new host, including another app beneath the card. Test voice capture,
   finalization/error/retry, stop, suggested replies, input reopening and opening the same thread.
   Kill only `:island_content`: native template/actions remain and the task continues. Close/reopen
   to rebind. Test main producer death/recovery, stale token, package update and user switching.
5. Test real incoming/ongoing/ended calls (answer/decline/hangup), full-screen call priority, clock
   alarm/timer/snooze/stop, recording and casting with Agent/JS unavailable. Check source end removal,
   five-second alert expiry, priority order and same-item chip/HUN dedup. Keep unrelated chips.
   Lock/AOD and secondary displays retain upstream behavior; v1 islands are primary-display only.
6. Use a third-party Live Update without custom extras and an ordinary notification. Test custom
   permission/allow-list accepted and rejected, malformed/oversized/duplicate JSON, invalid Icon/
   Action references, content bind denial/death/timeout. Notifications must never be lost.
7. GUI/Agent parity: change the settings switch and list in Settings, read with provider/cmd/settings;
   write via provider and verify the actual GUI. Test invalid values, list replacement, protocol
   repair, disabled-but-running tasks, every exposed action and user/profile filtering. A provider
   result alone is not GUI acceptance. Verify no secret/task data is copied to a second authority.

Keep #28 open until these gates and any discovered fixes pass. Completion of source phases 0–5
is not a claim that the high-risk phase-0 platform experiment has passed.
