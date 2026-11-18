# SystemUI island: real platform build validation

On 2026-11-15, the island implementation at `cc8da40204e4c7defe843023cc84586d29564f5b`
successfully built as the real platform SystemUI on the supplied cloud builder. No island source
changes were required to pass this build. This closes the **platform compilation** gate, not the
remaining phase-0 window/input experiment or ROM/device acceptance in issue #28.

## Immutable inputs and scope

Machine-readable hashes and results are in [result.json](result.json).

- Android 17 / Lineage 24, `lineage_tokay-cp2a-userdebug`, framework commit
  `7f6de40b6429462d47ba9ba351b99943269c8067`.
- Builder image `sha256:76fffd70eba28780934535eec83791a0534e5d3bdf837e9c1270f187d59c005c`:
  the existing builder recorded by that machine's run-input receipt, not a newly pulled image.
- Reused cached build number `20261111080323`, platform build ID `CP2A.260605.016`.
  This is an incremental module validation, **not a new complete ROM release**.
- Source inputs were exported from the immutable Git commit, not the concurrently edited local
  working tree. The patch input digest was checked before applying and the installed patch was
  verified again before building.
- Exclusive `worker.lock`; copy-on-write OverlayFS over the existing source/cache; Docker network
  disabled. The original framework tracked tree remained clean. The overlay was unmounted and
  lock released after completion. The machine has no `/dev/kvm`; no device boot was attempted.
- Build container limited to 48 CPUs / 160 GiB, build parallelism `-j24`.

## Results

`m -j24 SystemUI` returned 0. The successful resumed build took 8 minutes 58 seconds and ran real
Soong/Make, resource and manifest linking, SystemUI-core/application KSP annotation processing,
Kotlin/Java compilation, R8, APK alignment/signing and ARM64 dexpreopt. These are platform build
results, independent of the earlier local SystemUI API doubles. Runtime Dagger initialization
has not been observed.

The resulting `system_ext/priv-app/SystemUI/SystemUI.apk` was downloaded and independently checked:

- SHA-256 matches the cloud receipt: `22a1500d1adc878b768fd0067e220d68fdbc18cc76505967443027a053ea5308`.
- `image/verify-island-artifacts.py`'s APK checker found the real `IslandProvider` and
  `IslandSettingsActivity` manifest entries and DEX implementations, provider authority and both
  island permissions.
- `apksigner verify --print-certs` passed. The public certificate SHA-256 is
  `c8a2e9bccf597c2fb6dc66bee293fc13f2fc47ec77bc6b2b0d52c11f51192ab8`.

The source-patch receipt `island-probe-integration.json` intentionally still says
`systemUiCompiled: false`: that tool proves only patch integration. The separate build receipt
records the subsequent successful compilation; the earlier receipt was not rewritten.

Two build-environment issues were resolved before compilation: supplying the existing external
Google-services mount required by the product, and enabling OverlayFS `redirect_dir=on` for
Soong's directory renames. Neither required modifying the original source or island code.
The first failed lunch attempted roomservice in its disposable overlay; subsequent attempts
used a clean overlay, a product preflight and `--network none`.

The successful build still emitted upstream Kotlin/deprecation warnings and R8's API-37 support
warning plus duplicate framework-library type warnings. They did not fail compilation; this
validation does not establish runtime compatibility from their absence or suppress them.

## Companion Agent release

The companion was built locally, as required by its engineering contract, in a detached worktree
at the same immutable commit. `npm run build:android` completed in 4 minutes 28 seconds (1,004
Gradle tasks executed), producing the ARM64 release APK. `npm run lint` and `npm run typecheck`
also passed in that worktree. An earlier attempt in the shared working tree was discarded after
concurrent generated-output changes; it is not used as evidence.

- APK SHA-256: `95cb75bdf8af5a93aadd3e7f53cf6f5c4a0f7beed1e8682880397c4f3227eb85`.
- Final manifest and DEX include `IslandPublisherService`, `IslandContentService` and
  `IslandActionReceiver`; `:island_content` and both island permissions are present.
- `sharedUserId` is absent: Agent retains an ordinary application UID.
- APK signature verification passed and its public certificate digest matches the cloud SystemUI
  certificate above. All development signing material stayed local.
- The APK and build log are retained alongside the downloaded SystemUI artifacts locally. This
  verifies the release packaging and signing relationship, not a running service connection.
  No new release was published and the runtime-assets lock was not changed.

## Evidence and reproduction

Cloud artifacts/logs/scripts are retained at
`/mnt/taku-build/runs/island-cc8da40-20261115-r2/results/`. The matching downloaded copy is in
the ignored local `out/island-cloud-validation-20261115/` directory. APKs and full build logs are
not committed. No SSH private keys or signing material were transferred.

With the pinned source and the existing product's required mounts available, the inner workflow
was:

```sh
python3 /island-input/image/apply-island-patches.py --source-root /src --results-dir /results
python3 /island-input/image/apply-island-patches.py --source-root /src --results-dir /results --verify-only
export SKIP_KERNEL_BUILD=true WITH_AVB=true
export BUILD_USERNAME=taku BUILD_HOSTNAME=pixel9-builder BUILD_NUMBER=20261111080323
export SOONG_NINJA=ninja TAKU_R8_SERIAL_GC=1 GOMEMLIMIT=80GiB GOMAXPROCS=24 GODEBUG=asyncpreemptoff=1
source build/envsetup.sh
source vendor/lineage/vars/aosp_target_release
TARGET_PRODUCT=lineage_tokay TARGET_RELEASE="$aosp_target_release" TARGET_BUILD_VARIANT=userdebug \
  build/soong/soong_ui.bash --dumpvar-mode TARGET_PRODUCT
lunch "lineage_tokay-${aosp_target_release}-userdebug"
m -j24 SystemUI
```

## Remaining acceptance

The full production image has not been rebuilt. Publish/pin the newly built matching Agent release,
include the current preinstalled assistant documentation, run the complete image artifact gate,
then flash the matching image. Do not install this platform-specific SystemUI over a stock AVD
as a substitute for acceptance.

The [handoff checklist](../../dynamic-island-handoff.md) remains required: real startup and
Dagger initialization, notifications/system events, cross-process surfaces and death recovery,
window input/Back/IME/rotation, Settings/Agent parity, #25 F1–F5, accessibility and frame timing.
The covering-card focus policy remains an explicitly unaccepted design refinement. Issue #28
must stay open until those gates pass.
