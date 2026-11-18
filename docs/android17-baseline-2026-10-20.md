# Pixel 9 Android 17 baseline migration (2026-10-20)

**Latest checkpoint (UTC+8, 2026-10-25): Pixel 9 downloaded and installed the native system update from TakuOS 1.0 I `20261024154635`, then rebooted into J `20261024174448`. The formal incremental-install receipt, exact AVB measurements, A→B slot switch, natural merging of six snapshots, selected data/settings, Chinese drafts and real-session restoration, actual model response, and 167.29-second stability sample all passed. Full-byte readback of seven R2 objects and canary routing for exact source I passed; the stable channel remains closed and automation remains paused.** See [I→J physical-device acceptance](reviews/takuos-1.0-ota-2026-10-25/README.md). This chronological record retains earlier checkpoints: “current/pending” in earlier sections means the state at that time. Selected data checks do not establish preservation of all user data.

Earlier C→D summary, retaining the original evidence gaps:

The user explicitly requested Android 17, compilation on Mac mini, downloads on MacBook, and Pixel 9 acceptance over USB. **Full candidate A remains archived but is not an upgrade source because root vbmeta has flags=3; attempt B was preserved and stopped. Corrected candidate C passed actual signature verification and booted on the phone. The actual C→D delta is 269,439 bytes; the phone booted D (20261021174811 / Android 17 / API 37). Exact boot measurements, slot switching, three classes of private probe data, existing apps/input method/proxy/persisted drafts, and snapshot merging were checked. The host sideload tail was lost and recovery left no retrievable completion log, so an installation-success receipt cannot be reconstructed or the release process called fully verified. C/D and the delta were archived as unpromoted candidates in TakuOS R2 `releases`: 19 objects, 13,120,713,267 bytes, with full-byte SHA readback passed; the stable channel remained unready.** Automation stays paused; manual progress does not resume it.

## Baseline and sources

Google [released Android 17 on 2026-06-16](https://developer.android.com/blog/posts/android-17-is-here), with API 37. The [official LineageOS tokay build API](https://download.lineageos.org/api/v2/devices/tokay/builds) was read to verify API 37 and OS SPL `2026-10-01` for `lineage-24.0-20261019-nightly-tokay-signed.zip`. HTTP Range reads of its OTA metadata confirmed `pre-device=tokay`, `ota-type=AB`, and an Android 17 fingerprint.

| Input | Pinned value / basis |
| --- | --- |
| Product | `lineage_tokay` / `userdebug` / LineageOS 24.0 / Android 17 |
| AOSP tag | `android-17.0.0_r1` |
| Platform manifest commit | `d4c6331bc603e5038a9e48f5e526e7f10c8b96d9` |
| Official build manifest SHA-256 | `da3b20112cb1fc416b2120f106fc5471a68b383a0bea5ec78979607f1126c958` |
| Vendor | `CP2A.260805.005` / vendor SPL `2026-08-05` |
| Kernel | 6.1；manifest `f23d585a01fed93f9bfea5f95a0135cb88f42fbf` |
| GKI source tag | `android14-6.1-2025-12_r30` |
| Upstream binary inputs | signed GKI build `16160610`; FIPS140 build `11690398` |

The [official resolved build manifest](https://mirrorbits.lineageos.org/full/tokay/20261019/build-manifest.xml) was downloaded and matched the SHA-256 published by the official API. This establishes source-file integrity, not completion of a new Taku image. Full pins and provenance are authoritative in [source-lock.json](../image/source-lock.json) and [kernel-source-lock.json](../image/kernel-source-lock.json).

- All 1,243 official project names and revisions are retained: 1,239 in the platform manifest and four device projects moved to the devices local manifest. `TheMuppets/proprietary_vendor_google_tokay` is added separately at a pinned SHA; this community vendor repository is absent from the official resolved manifest. The new upstream removed the old `gs-common` dependency.
- `caimito-kernels` contains only module lists, not a complete prebuilt kernel. Device modules are still compiled using the upstream-pinned signed GKI/FIPS inputs. The new kernel manifest uses `common/gki`, `external/bionic`, and new WORKSPACE/wrapper links. The entry point remains `./build_caimito.sh`, producing `out/caimito/dist`.
- The [Taku Assistant manifest](../system/assistant/AndroidManifest.xml) temporarily retains `minSdkVersion=36` / `targetSdkVersion=36`. Android 17 can run APKs targeting that version; the ROM platform version and APK targetSdk are managed separately. This ROM migration does not simultaneously broaden application behavior changes. Android 17 changes affecting all apps still require regression checks.

## Build isolation

[build-config.json](../image/build-config.json) is the authoritative configuration for build locations and resources, consumed by the [controller](../scripts/pixel9-image.py) and [build script](../image/build.sh).

| Item | Android 17 configuration |
| --- | --- |
| Output | `out/pixel9-android17/` |
| Container | `taku-pixel9-android17-build` |
| Writable source volume | `taku-pixel9-lineage24-source` |
| Previous read-only cache | `taku-pixel9-lineage23-source` → `/previous`, for repo references |
| Original read-only cache | `agentos-lineage23-source` → `/reference`, preserving old Git alternates paths |
| Resources | 8 CPUs, 36 GiB RAM, 40 GiB memory+swap, Ninja concurrency 8 |
| Soong Go runtime | 24 GiB soft memory limit, runtime concurrency 4; explicitly forwarded to the isolated graph process |
| Status report | `docs/research/pixel9-android17-build-state.json`, generated by the post-run publisher |

The old Android 16 source volume is used only to reuse downloaded objects. Its Git objects/alternates already use absolute `/reference` paths, so both read-only cache mounts are retained to avoid rewriting Git metadata in the old volume. Old `out/pixel9/`, container logs, candidates, and input snapshots remain. The new build has an independent input snapshot recording platform, resources, and build number. A source-lock change requires full synchronization and kernel/system builds; an old checkpoint cannot simply be resumed. The pre-migration workspace backup is in ignored `out/migrations/android17-20261020/`; existing user-modified documents were untouched.

The local migration branch is `codex/android17-baseline-20261020`, created from `bc21fe297001e579e3cf1d6a29962fe92e8b11ca`. Source, dirty files, and old outputs in the Mac mini's original `codex/pixel9-image` workspace remain. The new build uses an isolated worktree in its ignored `out/worktrees/android17-20261020/`, on `codex/android17-builder-20261020`, receiving the same Taku source and pinned public runtime assets.

The first container, `917b4817b540`, exited 1 at `2026-10-19T18:47:24Z`, with `OOMKilled=false`. Diagnosis showed that `repo --reference` imported old shallow-clone refs without their shallow boundaries, causing `Failed to traverse parents of commit eabe683...`; the Android 17 pin was present. Manifest initialization without references obtained the exact locked SHA. The fix fetches the small manifest independently, handles project-cache shallow boundaries separately, and permits retry with a residual `.repo`. Failed containers, snapshots, and logs go to this run's `attempts/`, with diagnostics in `diagnostics/`.

The second container, `84fb597b73c3`, started at `2026-10-19T18:57:27Z` and entered `source-sync` at `18:59:43Z`. Among the combined 1,244 projects, 1,073 object caches were prepared and 1,133 projects inherited old shallow boundaries. Cache content was unchanged. Git initialization and the first actual fetch in the generated checkout passed the previous failure point.

The conversation's `taku-os-android-17` background follow-up originally checked every 15 minutes, but was paused at the user's request; `status=PAUSED` was reconfirmed at 12:07 UTC+8 on 2026-10-21. Manual inspection or optimization does not authorize resuming automation; the current build container continued running. The original next step remained one cached B build after complete verification and archival of A, recording the selection to avoid duplicate builds. No premature delta generation or rollout was permitted. Earlier host checks passed 101 image tests, 18 Linux recovery tests, and 47 export/signature-orchestration tests. Added OTA coverage passed 79 host tests, 31 Linux delta-tool tests, and 13 real Repo-initialization tests. These are not actual ROM or OTA acceptance.

After platform synchronization, `84fb597b73c3` exited at `2026-10-19T19:58:34Z` (1, no OOM). Repo's launcher anchors a workspace at `.repo/repo/main.py`; that file was absent from the kernel subdirectory, so `repo init` found `/src` and replaced the platform manifest URL/HEAD/entry point with the kernel manifest. Kernel sync did not run; eight sampled core-project HEADs still matched the saved 1,244-project resolved manifest. The fix creates an independent copy of the same Repo version inside the kernel directory; real launcher/init checks verified that parent metadata stays unchanged. The failed container/log/resolved manifest remain in `attempts/84fb597b73c3/`, with contaminated manifest Git metadata separately in `diagnostics/nested-repo-84fb597b73c3/`. New container `fee60246187f` started a full retry at `2026-10-19T20:04:57Z`, rechecking the platform manifest and reusing downloads/checkouts without deleting the source volume.

The running snapshot stayed unchanged. Updated host export, independent verification, and recovery helpers were synchronized to the isolated worktree. Before any later continuation, compare the input manifest referenced by `run-input.json` and obey `resume-check.py` source/kernel checkpoint restrictions; do not restart an old container and overwrite its logs. Progress counts only `*.git/HEAD`: counting all files named HEAD would also count `logs/HEAD` twice.

At 04:31 UTC+8 on October 20, `fee60246187f` was healthy with `status.phase=kernel-build`, begun at `2026-10-19T20:12:09Z`. Platform/kernel resolved manifests were saved at 04:12/04:14. Bazel was executing caimito kernel targets at 541/599; this is not overall package completion. Kernel sync/initialization had passed earlier failures, but full-package output count was still zero; A verification/export and B building had not started.

At 06:22 UTC+8 on October 20, no new remote state could be obtained: SSH to the private build host repeatedly timed out, and the existing status-page port was unreachable. Independent read-only checks found local internet and Tailscale healthy, with an earlier successful Mac mini ping; that does not establish that the remote build stopped or failed. The last confirmed fact remained the 04:31 kernel build. Containers, network services, and run inputs were unchanged; follow-up remained configured, pending real logs after connectivity returned.

After reconnection, actual logs showed `fee60246187f` exited at `2026-10-19T21:09:30Z` (1, no OOM). All 599 kernel Bazel actions had succeeded; `kernel-artifacts.json` recorded 460 artifacts and the installer verified 339 declared modules. System Soong reported `flag provided but not defined: -j8`: pinned Soong translated `m -j` into Siso's `--local_jobs`, while old `SOONG_UI_NINJA_ARGS` appended the incompatible argument unchanged. The fix explicitly clears only that variable in [build.sh](../image/build.sh), retaining shared concurrency configuration and `m -j`. No `out/combined-lineage_tokay.ninja` existed, so existing resume/rebuild gates were unsatisfied. A full retry had to retain the source volume and kernel cache; checks were not relaxed. Failure logs were also downloaded to the MacBook's ignored `out/pixel9-android17/diagnostics/siso-fee60246187f/`.

The argument fix passed `bash -n`, 14 build-controller tests, and a minimal offline Ninja action using the actual pinned Siso binary: old `-j8` exited 2; replacing it with `--local_jobs 8` succeeded (0). Evidence is in the isolated worktree's `out/pixel9-android17/diagnostics/siso-fee60246187f/relative-config-smoke/`, not ROM acceptance. Controller `retry` retained the failed container and `attempts/fee60246187f/`. New container `e748bba17f62` started at `2026-10-19T22:45:46Z`, build `20261019224545`, snapshot `db8b750c6cf40c319dc7c05eb361d7d00fefd6eca820e8d1f5e75d140a9a2c69`. File-by-file snapshot comparison showed only `image/build.sh` changed; platform/kernel locks and application code matched. This remained recovery of first full candidate A; A was not selected and B had not started.

At 07:02 UTC+8 on October 20, `e748bba17f62` was healthy and had entered `compile` at `2026-10-19T22:57:22Z`. Siso was compiling the Soong toolchain, reaching 295/296 (copying `soong_build`) past the old `-j8` failure. That percentage covers only bootstrap, not the complete system. There was no full image, signature-verification or archival result, and B had not started.

Container `e748bba17f62` exited at `2026-10-19T23:28:12Z` (1, `OOMKilled=true`). `soong_build` failed while analyzing Android.bp and generating the graph; its child exited 137. Source sync, kernel compilation, and the old argument failure had been passed. Read-only source inspection showed that outer `GOMEMLIMIT=28GiB` / `GOMAXPROCS=8` were recorded in available-env JSON but not forwarded through `env -i` to the Go runtime; Blueprint also forced concurrency back to NumCPU. The container had a 36 GiB hard limit and 40 GiB total, on a Docker VM with about 39.1 GiB RAM/4 GiB swap. VM memory was not increased and user applications were not closed.

The fix configures Soong with `GOMEMLIMIT=24GiB` / `GOMAXPROCS=4`. A patch guarded by exact upstream-block checks forwards only these two variables to the graph subprocess and preserves explicit concurrency. After successful kernel installation, the same wrapper stops the idle Bazel JVM. `env -i`, source/kernel locks, and application code remain. Of 118 image checks, 114 passed and four were skipped on macOS. The Go runtime tests also ran in the pinned Linux builder: all 20 passed, including applying/reapplying patches and checking Go formatting against actual upstream source. These are tooling checks, not complete ROM acceptance. Failure evidence and Linux validation remain in ignored `out/pixel9-android17/diagnostics/oom-e748bba17f62/`.

The OOM fix started container `3e6719b3d817` through `retry` at `2026-10-19T23:38:46Z`, build `20261019233845`, snapshot `cecc12b15e12949f2feaf389741ec93dd21a7fd00941a2b86fc0fb5960cfc159`. Failed container `e748bba17f62` and `attempts/e748bba17f62/` were retained. Only `image/build.sh`, `build-kernel.sh`, `build-config.json`, `apply-build-workarounds.py`, and the image README changed from failed inputs; platform/kernel locks and product behavior did not. This still recovered A; B had not started. After entering compile, inspect the actual `soong_build` command/process environment for `GOMEMLIMIT=24GiB` and `GOMAXPROCS=4`, audit all three patches, and confirm no kernel Bazel JVM remains. Outer configuration and passing tests alone do not establish that the memory failure is resolved.

At 08:06 UTC+8 on October 20, direct allowlisted reads of the current `soong_build` environment confirmed `GOMEMLIMIT=24GiB` / `GOMAXPROCS=4`. Audit SHAs for all three patches matched and no kernel Bazel JVM was found. Graph generation continued with RSS about 23.2 GiB. Cgroup `max` reclaim events had accumulated, while `oom` / `oom_kill` stayed zero; a soft limit alone did not establish that memory issues were fully resolved. Read-only observations are in `out/pixel9-android17/diagnostics/runtime-3e6719b3d817/20261020T000623Z.json`; compilation and full-package acceptance remained pending.

At 09:07 UTC+8 on October 20, `3e6719b3d817` logged successful completion of `analyzing Android.bp files and generating ninja file`. Actual `out/soong/build.lineage_tokay.ninja` was 366580561 bytes, modified at `2026-10-20T00:55:38Z`. Active `ckati` was generating Make rules; `out/combined-lineage_tokay.ninja` was absent, so the resume checkpoint could not yet be declared satisfied. Cgroup `oom` / `oom_kill` remained zero; the container was running without a complete image. Evidence is in `out/pixel9-android17/diagnostics/runtime-3e6719b3d817/20261020T0106-graph-complete.json`. Passing the prior graph failure does not establish compilation, signature verification, or candidate A completion.

At 09:40 UTC+8 on October 20, the same container passed Make-rule generation and reached Ninja 1224/198010, compiling host tools such as protobuf with Clang. Action counts do not reliably predict remaining time. It was healthy at about 20.73/36 GiB memory, `OOMKilled=false`; status publishing continued and full-package output count was zero. Evidence is `out/pixel9-android17/diagnostics/runtime-3e6719b3d817/20261020T014017Z-system-compile.json`. The first SSH attempt timed out but a retry restored access; Tailscale and SSH were reachable without network changes or build restart.

Container `3e6719b3d817` exited at `2026-10-20T04:47:19Z` (1, `OOMKilled=false`) after 22556/197217 system actions. The first error was `rosetta error: unexpectedly got a signal during sigreturn`, followed by Soong's `ninja failed with: signal: trace/breakpoint trap`. Later Kotlin/Java/C++/Rust failures were `action cancelled when ninja exited`, not evidence of source errors in those modules. The failed container, snapshot, and logs remain; diagnostic copies are in `diagnostics/compile-3e6719b3d817/` on the MacBook and isolated worktree.

Read-only source/environment inspection confirmed outer `GODEBUG=asyncpreemptoff=1` was absent from the main Ninja environment allowlist, so Siso did not receive it. This proves missing compatibility propagation, not the sole cause of the Rosetta crash. Pinned upstream `SOONG_NINJA=ninja` selects C++ Ninja for both bootstrap and main compilation, without product-source changes or relaxing `ALLOW_NINJA_ENV`. With the same pinned builder and read-only source volume, real Ninja 1.9.0.git passed a single-action/FIFO-guard test, including progress handoff and EOF; evidence is `siso-environment/ninja-fifo-smoke.log`. Existing `.ninja_log` remains, while `.ninja_deps` was absent. Switching executors may repeat actions to regenerate dependency information; compilation caches are not deleted.

The continuation controller also fixed `resume` accidentally assigning a new build number: continuing A preserves `20261019233845`; normal `retry`, new builds, and B `rebuild` still allocate new numbers. Of 121 local image checks, 117 passed and four were skipped under macOS conditions; 18 remote controller checks passed. Snapshot comparison showed only `image/build.sh` and `image/README.md` changed; source/kernel locks, application code, and original `resume-check.py` gates remained. After preserving `attempts/3e6719b3d817/`, `resume` started `852274b7c68a` at `2026-10-20T05:06:19Z`, snapshot `20cf91e421074493bce5b88aac42ef605d0315ca2d15e574170486b2605e0991`. This still recovered A, not B. At `2026-10-20T05:06:28Z`, actual `resume-check.json` confirmed identical integration inputs and all checked boot/kernel-module hashes, no source resync/kernel rebuild, and preserved compilation cache. Subsequent results still require actual run logs.

Container `852274b7c68a` exited at `2026-10-20T17:03:36Z` (1, `OOMKilled=false`; 01:03 UTC+8 on October 21), last action count 73441/194458. The sole failed action was the `android.app.privatecompute.flags-aconfig-java-export` srcjar; log line 82073 directly invoked `out/host/linux-x86/bin/soong_zip`. Its Go stack contained `runtime.systemstack`, compression, and `android/soong/zip`, with another goroutine in `runtime.asyncPreempt`. Java/Kotlin warnings did not cause this failure. `ninja.environment` lacked `GODEBUG`, although available-env contained `asyncpreemptoff=1`; bootstrap.ninja's primary builder also carried only the two resource variables. The missing setting was established, but SIGSEGV causality still required an actual continuation. [Go runtime source](https://go.dev/src/runtime/preempt.go) and [Go's Rosetta issue](https://github.com/golang/go/issues/68485) support disabling asynchronous preemption; the issue's platform differs and cannot replace this run's validation.

The fix accepts only exact `GODEBUG=asyncpreemptoff=1` after filtering in the primary builder and main Ninja; it neither enables `ALLOW_NINJA_ENV` nor forwards arbitrary GODEBUG values. The compatibility value is exported early for application auditing. The v1 resource-environment patch upgrades to v2 only after an exact match. The new Ninja patch shares prevalidation and hash auditing. This failed action had no additional `sbox` filtering; other sandbox environment restrictions remain, so this fix does not cover every tool. Failed container/inputs/logs remain, with diagnostic copies on both machines in ignored `out/pixel9-android17/diagnostics/soong-zip-852274b7c68a/`. A was not selected and B had not started.

Of 125 local image checks, 121 passed and four were skipped under macOS conditions. All 24 workaround regressions passed in the pinned Linux builder, including compiled/executed Go subprocess environment checks. Read-only upstream copies passed v1→v2 upgrade, reapplication of four patches, and Go formatting checks. The failed action's five Java inputs were replayed 20 times through the same `soong_zip` with the pinned compatibility environment; ZIP CRC and per-file SHA matched throughout, with archive SHA `5755c43aead35f262f3296c971d91bf1de28957a31065144ccaf2c6eb64b614a`. This short action does not establish acceptance of the whole translated toolchain or ROM.

`resume-check.py` does not allow the workaround helper; that gate remains. Platform/kernel locks and application code are unchanged, so the existing failed-checkpoint branch of `pixel9-image.py rebuild` can reintegrate the fix: preserve failed container/logs, run `check-incremental.py` against old integration inputs, the new immutable snapshot, source locks, and kernel hashes, reuse compilation cache, and skip source sync/kernel compilation. Here `incrementalIntegration=true` describes only the build method; it still recovers first candidate A, not OTA candidate B. Actual container/input identity must be recorded after recovery starts.

Recovery container `9c565a08220c` started at `2026-10-20T21:25:10Z` (05:25 UTC+8 on October 21), build `20261020212510`, snapshot `a6fc88b42a796ae47e14787717e3c61a8d1ccf67d231cf1b1a4a4a5cbce53b56`. Comparisons with both the prior controller snapshot and original integration inputs showed only three differences: `image/build.sh`, `image/apply-build-workarounds.py`, and `image/README.md`. Platform/kernel locks and application sources were unchanged. The failed container was renamed and preserved; `attempts/failed-852274b7c68a/` retains failed inputs/logs, and `diagnostics/soong-zip-852274b7c68a/recovery-start.json` records recovery identity. Actual `incremental-check.json` verified 341 boot/kernel file hashes at `2026-10-20T21:25:23Z`, confirming no source resync or kernel rebuild. Four workaround audits were written; compatibility values in this run's primary environment and new main Ninja environment still required checks at their actual stages. There was no successful A archive and B had not started.

At 05:27 UTC+8 on October 21, allowlisted environment reads from the actual recovery `soong_build` process (container PID 1083) confirmed propagation of `GODEBUG=asyncpreemptoff=1`, `GOMEMLIMIT=24GiB`, and `GOMAXPROCS=4`. The new bootstrap.ninja primary node also contained all three. Graph generation was ongoing. Old `out/soong/ninja.environment` predated this run and could not serve as new main-Ninja evidence; the new file/tool process still needed inspection after main compilation began. Observations are in `out/pixel9-android17/diagnostics/runtime-9c565a08220c/20261020T212709Z-go-environment.json`. Full outputs, signature verification, and A/B acceptance remained pending.

## FlClash presigned APK fix and cached rebuild (2026-10-21)

Container `9c565a08220c` exited at 14:17:37 UTC+8 (1, `OOMKilled=false`), last action count 117053/121534. The failing target was `TakuPersonalProxy/validated-prebuilt/check.stamp`: Android 17 Soong requires explicit `preprocessed: true` for presigned APKs targeting SDK ≥30, to avoid rewriting APKs and invalidating signatures. The failed container remains as `taku-pixel9-android17-build-failed-9c565a08220c`; logs, run inputs, resource adjustments, and source/kernel checkpoints are in `out/pixel9-android17/attempts/failed-9c565a08220c/`. Source volumes and build outputs were not cleaned.

Inspection of the original FlClash 0.9.0 package confirmed targetSdk 37, `extractNativeLibs=true`, and all 14 JNI libraries compressed. Adding only `preprocessed` would therefore still fail upstream layout checks. `Android.bp` adds `preprocessed: true` and `skip_preprocessed_apk_checks: true` only to this module, explaining why the original compressed-JNI package is preserved. Other modules and uses-library consistency checks were not relaxed; the APK was not unpacked, resigned, or replaced.

With source mounted read-only in the same pinned Linux builder, actual `zipalign -c -p 4`, `apksigner verify --verbose --print-certs`, and upstream `check_prebuilt_presigned_apk.py --preprocessed --skip-preprocessed-apk-checks` all returned 0. APK SHA-256 before/after validation was `ed06fb1ccf73156dbea07cf906b0ab2f2bececca29a510b288a5621868ff2bcf`; v2 signing was valid, with certificate SHA-256 `2859e236b6c1c6073773752678c52e8e902a9503ae9beed6729ca999c376841c`. Logs/commands are in `diagnostics/presigned-apk-9c565a08220c/validated.{json,log}`; all 24 existing local cache-gate/controller tests passed. These verify the preinstalled APK and recovery tools, not the complete ROM.

This used existing `rebuild --from-snapshot a6fc88b42a796ae47e14787717e3c61a8d1ccf67d231cf1b1a4a4a5cbce53b56 --overlay Android.bp`, without relaxing strict `resume` input limits. New immutable snapshot `dc6bc1cd45e13e577e103f11bf072bd666219e06033ded30346dde1e76e140d5` differed only in `Android.bp`; it did not collect other Agent/Jev changes on Mac mini or feature changes from the MacBook workspace. Platform/kernel locks and signing inputs were unchanged. New container `195faefafe04` started at 19:08:58, build `20261021110858`, still repairing first candidate A; B had not started.

At 19:09:01, actual `incremental-check.json` confirmed unchanged platform/kernel locks, 341 matching boot/module hashes, `compileCachePreserved=true`, `sourceSyncedAgain=false`, and `kernelRebuilt=false`; only `Android.bp` changed. It continued using `taku-pixel9-lineage24-source`, inheriting runtime settings of 10 CPUs, 36 GiB RAM, 40 GiB RAM+swap, and Ninja concurrency 8. `rebuild` allocates a new build number, regenerating identity and affected targets while preserving other caches and the old snapshot. The user explicitly kept automation paused; heartbeat was not resumed. Completion still depends on actual build, verification, and export results.

## Build-performance diagnosis and live adjustment (2026-10-21)

The bottleneck varied by stage: around 06:06, `ckati` used about one core, with Make-rule generation mainly serial; main Ninja began after 06:07. Pinned [Soong stage orchestration](https://github.com/LineageOS/android_build_soong/blob/183e59578606687d66096cbce86cb50c27f4e9d7/ui/build/build.go#L358) runs Soong/Kati before Ninja, so increasing Ninja concurrency cannot accelerate earlier Kati work. Repeated compatibility interruptions also repeated dependency-graph checks, but object and kernel caches remained. This run had 121534 pending actions versus 194458 previously; actions differ in duration, so subtracting or accumulating counts cannot yield reliable ROM completion percentages.

The Mac mini is an M4 Pro / 14 cores / 64 GiB; Docker runs an ARM64 VM with about 39.1 GiB RAM, while build tools use amd64/Rosetta and the container has a 36 GiB hard limit. [Official AOSP requirements](https://source.android.com/docs/setup/start/requirements) call for x86-64 Linux and at least 64 GB RAM. Translation overhead makes official timing estimates inapplicable directly. Host memory compression was frequent, but sampled container memory PSI and disk I/O wait were near zero, with no OOM. Historical swap allocation alone does not establish an active paging bottleneck.

The old background emulator `Taku_ImageIntegration_20261010` (`emulator-5634`, PID 36513) was confirmed to belong to the earlier Taku integration worktree and use SwiftShader. At 06:13, emulator-console `avd pause` preserved running state, userdata, and files; a five-second sample fell from about 357% CPU to 0.79%. It remained paused. Resume it on Mac mini with `"$ANDROID_SDK_ROOT/platform-tools/adb" -s emulator-5634 emu avd resume`, with `ANDROID_SDK_ROOT` set to that machine's SDK. Do not launch the same AVD again or wipe its data.

After pausing the emulator, Ninja saturated its eight-core quota and cgroup CPU throttling increased while memory/disk PSI stayed near zero. At 06:14, live `docker update --cpus 10 taku-pixel9-android17-build` raised only the CPU quota; Ninja stayed at `-j8`, with RAM/swap and Go parameters unchanged. The build was not restarted; container ID, `run-input.json`, and immutable snapshot-manifest SHA remained identical. Original startup resources stay in run-input; live adjustments are separately recorded in `out/pixel9-android17/resource-adjustments.json` and archived by the controller with failed/completed attempts. Revert with `docker update --cpus 8 taku-pixel9-android17-build`.

The latest 12:07 UTC+8 sample showed the same healthy container, `OOMKilled=false`, Ninja 72788/121534 (about 59% of this run's actions, not image acceptance), roughly 1022% CPU, Docker working set about 27.16/36 GiB, container swap about 43 MiB, and zero memory/io PSI avg10. The new main Ninja environment also confirmed `GODEBUG=asyncpreemptoff=1`, extending evidence beyond bootstrap alone. The larger CPU quota was used, but differing tasks/times prevent a fixed speedup claim or completion forecast. Full images and actual signature verification remained unfinished.

Original observations, reversible emulator operations, and resource adjustments remain on both machines in ignored `out/pixel9-android17/diagnostics/performance-20261020T221206Z/`. Source/object/kernel caches and old failures were retained; the running snapshot and existing product changes were untouched. Concurrency/VM resources were not increased further. After the first baseline, routine Assistant work should prefer module/APK incremental builds; complete packages are for system/OTA acceptance. A native x86-64 Linux builder can be evaluated later through a separate migration preserving current caches.

## Earlier failure and this run's build workarounds

The [old full-package status record](research/pixel9-image-build-state.json), through October 19, reported failure without a root cause. Re-reading the Mac mini's original `out/pixel9/build.log` on October 20 found CellBroadcastApp R8 around line 21873 and an OpenJDK 21.0.4 amd64 `SIGSEGV` near line 21878, with `libjvm.so` frames `G1AdjustClosure::do_oop` / `G1FullGCAdjustTask`. That run exited at `2026-10-15T16:40:59Z`, `exit=1`, `OOMKilled=false`. This is newly checked evidence of an old failure, not an Android 17 build result; original logs remain in old output storage.

New [apply-build-workarounds.py](../image/apply-build-workarounds.py) adds switchable `-JXX:+UseSerialGC` only to R8 in the generated Soong checkout, controlled by `TAKU_R8_SERIAL_GC` and enabled in `build-config.json`. It validates exact upstream blocks, rejects drift, and writes before/after SHAs to this run's `build-workarounds.json`. It sets no global Java GC option; D8 and other Java tools are unchanged. **An actual build must still confirm whether this avoids the old crash.**

## Acceptance and OTA sequence

1. Complete synchronization, kernel-distribution verification, and full Android 17 compilation in the new checkout; retain input snapshots, resolved manifests, logs, and build-workaround records.
2. Verify Taku full OTA and target-files: tokay, API 37, userdebug, firmware declarations, critical partitions, APK/JAR/runtimes, and checksums.
3. Independently test the new candidate on Pixel 9: boot, networking, Assistant/dynamic tools, background displays, Chinese input, and reboot recovery. Do not record physical-device success before flashing.
4. Archive the actually released, consistently signed Android 17 target-files as the OTA baseline, then generate the next-version delta and retain full-OTA fallback downloads. Android 16→17 cross-version upgrades require separate validation; successful delta generation does not establish usability.

The builder stores every full candidate, inputs, source locks, and acceptance receipts under `artifacts/<buildNumber>-<first16OfSnapshotDigest>/`; separate builds of the same source do not overwrite one another. Reverification alone preserves the original build number. Artifact validation requires target-files and each OTA to share build identity, producing `baseline-receipt.json`; certificate presence is not recorded as OTA signature verification or physical-device acceptance.

The October 11 Android 16 full candidate, October 14 installation, and October 15 APK/broker acceptance remain valid only for their documented versions. See the [image README timeline](../image/README.md) for the full history. Historical evidence does not establish completion of current Android 17 work.

## Full-package signature verification

**The following procedures were pending, not verification results from this run.** Execute them after a successful full build and identity/file-hash acceptance in `baseline-receipt.json`. Verify the full candidate first, then wait for the user to connect Pixel 9. These procedures generate no delta or signing keys and do not modify the running build snapshot.

### Tool sources and default build dependencies

The Linux command `m target-files-package bacon` builds verification tools by default, based on pinned source dependencies, not merely their presence in an old binary cache:

- Pinned [vendor/lineage bacon.mk](https://github.com/LineageOS/android_vendor_lineage/blob/87daf527b0288b60c9cc07c05a5f5aad1660c234/build/tasks/bacon.mk#L24) connects `bacon` to `INTERNAL_OTA_PACKAGE_TARGET`.
- The OTA target in pinned [build/make/core/Makefile](https://github.com/LineageOS/android_build/blob/90d006387398ae6899145e6531a304500cfa0f70/core/Makefile#L7347) depends on `INTERNAL_OTATOOLS_FILES`; [its module list](https://github.com/LineageOS/android_build/blob/90d006387398ae6899145e6531a304500cfa0f70/core/Makefile#L5908) includes `check_ota_package_signature`, `delta_generator`, and `avbtool`. Linux enables that list by default; this invocation did not set `TARGET_SKIP_OTATOOLS_PACKAGE=true`.
- The [check_ota_package_signature Soong module](https://github.com/LineageOS/android_build/blob/90d006387398ae6899145e6531a304500cfa0f70/tools/releasetools/Android.bp#L392) additionally depends on `delta_generator` and disables Darwin targets. Package verification therefore runs in this amd64 Linux builder, not by executing Linux binaries directly on macOS.

After a successful build, the minimum tools are `out/host/linux-x86/bin/check_ota_package_signature`, sibling `delta_generator` and `avbtool`, their build dependencies, and system `openssl`. This avbtool version also needs `avb_openssl` and `fec`; copying one script does not supply all dependencies. If tools are missing, inspect this build's targets/configuration first. If necessary, run `m check_ota_package_signature avbtool` only after the main build ends, with identical source and lunch configuration. Do not fall back to old AVB tools under `/previous`.

### Prepare an independent verification directory

Run these commands in a Linux verification shell using the same builder image, working in `/src` with this run's source volume and `/results` output. The main container exits after building, so do not assume `docker exec` remains available. A temporary verification container can use the same pinned image with this run's `/src` mounted read-only, saving logs to `/results`. Never verify packages concurrently with compilation still writing them.

```bash
set -euo pipefail
unset PYTHONOPTIMIZE
cd /src
export PATH="/src/out/host/linux-x86/bin:$PATH"

TF=/src/out/target/product/tokay/obj/PACKAGING/target_files_intermediates/lineage_tokay-target_files.zip
OTA=/src/out/target/product/tokay/lineage_tokay-ota.zip
test -f "$TF"
test -f "$OTA"
test -x out/host/linux-x86/bin/check_ota_package_signature
test -x out/host/linux-x86/bin/delta_generator
test -x out/host/linux-x86/bin/avbtool

BUILD_NUMBER=$(python3 -c 'import json; print(json.load(open("/results/run-input.json"))["buildNumber"])')
V=$(mktemp -d "/results/crypto-${BUILD_NUMBER}-XXXXXXXX")

python3 - "$TF" "$V" <<'PY'
import pathlib, sys, zipfile
target, root = pathlib.Path(sys.argv[1]), pathlib.Path(sys.argv[2]).resolve()
with zipfile.ZipFile(target) as archive:
    for name in archive.namelist():
        if name == "META/misc_info.txt" or name.startswith(("IMAGES/", "RADIO/")):
            archive.extract(name, root)
# Upstream validate_target_files likewise adds sibling links for AVB-referenced RADIO partitions.
radio = root / "RADIO"
if radio.exists():
    for source in radio.iterdir():
        destination = root / "IMAGES" / source.name
        if source.is_file() and not destination.exists():
            destination.symlink_to(source)
PY

CERT_REL=$(sed -n 's/^default_system_dev_certificate=//p' "$V/META/misc_info.txt")
ROOT_KEY_REL=$(sed -n 's/^avb_vbmeta_key_path=//p' "$V/META/misc_info.txt")
test -n "$CERT_REL"
test -n "$ROOT_KEY_REL"
CERT="/src/${CERT_REL}.x509.pem"
test -f "$CERT"
test -f "/src/$ROOT_KEY_REL"
openssl x509 -in "$CERT" -outform DER | sha256sum | tee "$V/ota-certificate-der.sha256"
```

Match the certificate's DER SHA-256 to baseline receipt `signingEvidence.trustedOtaCertificates`, retaining its source path. This unsigned-for-release development package usually uses `build/make/target/product/security/testkey.x509.pem`; actual META determines the command. Future release signing must explicitly supply the corresponding established public certificate and record provenance, rather than assuming the old testkey. These commands read existing key files to export public keys; they generate no new private key.

### Verify whole-OTA and payload signatures

The [pinned checker](https://github.com/LineageOS/android_build/blob/90d006387398ae6899145e6531a304500cfa0f70/tools/releasetools/check_ota_package_signature.py#L52) verifies the ZIP footer's whole-file RSA signature and covered-region digest, then extracts the payload and invokes `delta_generator --in_file=… --public_key=…` to verify payload/metadata signatures. It does more than display a certificate. It uses Python `assert`; do not run with `-O` or `PYTHONOPTIMIZE` enabled.

The [pinned PayloadSigner](https://github.com/LineageOS/android_system_update_engine/blob/b12fb307bb2f96c06370d143e38c3630f14044e0/payload_generator/payload_signer.cc#L232) skips metadata signature verification when its size is zero. First confirm this v2 payload carries that signature, then verify the package:

```bash
python3 - "$OTA" <<'PY' | tee "$V/payload-header.txt"
import struct, sys, zipfile
with zipfile.ZipFile(sys.argv[1]) as archive:
    with archive.open("payload.bin") as payload:
        magic, version, metadata_size, signature_size = struct.unpack(">4sQQI", payload.read(24))
if magic != b"CrAU" or version != 2 or metadata_size <= 0 or signature_size <= 0:
    raise SystemExit("Missing expected signed v2 payload metadata")
print("Signed v2 payload metadata present:", signature_size)
PY

check_ota_package_signature "$CERT" "$OTA" 2>&1 | tee "$V/ota-payload-signatures.log"
```

Record these two results only if the command returns 0 and the log contains both `Whole package signature VERIFIED` and `Payload signatures VERIFIED`. `set -o pipefail` prevents `tee` from hiding verification failure. On signature mismatch, retain failure logs and check candidate/certificate provenance. Do not repeatedly substitute credentials or treat that candidate as an accepted baseline.

### Verify AVB root identity, chain signatures, and partition content

Use pinned [avbtool.py cb5eae91…](https://github.com/LineageOS/android_external_avb/blob/cb5eae91a6b83e1394882decbf6de133239107e1/avbtool.py#L2937). This version verifies child-image public keys against parent chain descriptors; old `/previous` lacks that `expected_key` check. It also recursively forwards `--key` to children. When root and children use RSA4096/RSA2048 respectively, combining `--key ROOT --follow_chain_partitions` incorrectly demands the root key for children. Verify the root public key independently, then verify the chain from that checked root image:

```bash
avbtool extract_public_key \
  --key "/src/$ROOT_KEY_REL" \
  --output "$V/expected-root.avbpubkey"
avbtool info_image \
  --image "$V/IMAGES/vbmeta.img" \
  --output_pubkey "$V/image-root.avbpubkey" \
  --output "$V/vbmeta-info.txt"
test -s "$V/expected-root.avbpubkey"
cmp "$V/expected-root.avbpubkey" "$V/image-root.avbpubkey"
sha256sum "$V/expected-root.avbpubkey" "$V/image-root.avbpubkey" | tee "$V/avb-root-keys.sha256"

avbtool verify_image \
  --image "$V/IMAGES/vbmeta.img" \
  --follow_chain_partitions 2>&1 | tee "$V/avb-verification.log"
avbtool calculate_vbmeta_digest \
  --image "$V/IMAGES/vbmeta.img" \
  --hash_algorithm sha256 | tee "$V/vbmeta-digest.txt"
```

`Algorithm: NONE` is not signature success. The root public key must be nonempty and equal to the expected key, and every actual chain partition must pass signing and parent-key matching. Stop on missing partitions/keys or hash/hashtree failure; do not relax checks with `--accept_zeroed_hashtree`. Chain traversal also checks referenced partition content hashes/hashtrees. `calculate_vbmeta_digest` produces a comparison digest; alone it is not signature verification.

Read and retain algorithm, flags, and rollback index from `vbmeta-info.txt`. Old Android 16 outputs used `--flags 3`; do not assume this run does too. Even if offline cryptographic checks pass, flags disabling device-side AVB must be accurately recorded. Offline signature validity, whether current phone firmware permits installation, and boot-time enforcement are separate acceptance results.

### Verification evidence and baseline receipts

Retain pinned tool-source commits, binary SHA-256s, commands/exit codes, log SHA-256s, matching target-files/OTA SHA-256s, certificate/root-public-key fingerprints, and vbmeta digest. Confirm before/after verification that candidates still match the receipt; do not mix verification with another rebuild. Archive successes as independent supplemental records. Updating `baseline-receipt.json` also requires refreshing `status.baselineReceipt.sha256` and re-exporting; never leave a stale receipt hash.

Set `otaPackageSignatureVerified`, `payloadSignatureVerified`, and `avbSignatureVerified` true separately only after actual command success. Before these commands run, all three remain false. Valid signatures also do not change `physicalDeviceVerified=false`, `coldBootVerified=false`, or `incrementalOtaReady=false`. Complete this full candidate's Pixel 9 acceptance before separately generating and testing incremental OTA.

### Reusable verification entry point (pending at this checkpoint)

[verify-signatures.py](../image/verify-signatures.py) implements these boundaries; invoke it in an independent Linux verification container after the full build:

```bash
python3 /verification/verify-signatures.py \
  --source-root /src \
  --results-dir /results \
  --verification-dir "/results/crypto/${BUILD_NUMBER}-${SNAPSHOT_DIGEST:0:16}"
```

`BUILD_NUMBER` and `SNAPSHOT_DIGEST` come from this run's `run-input.json`. The controller pins a verification-script copy, launches an offline container using the run's pinned image, and mounts `/src` read-only. The script verifies original status → receipt SHA-256 → run-input SHA-256 → every artifact SHA-256, checks each unique OTA byte sequence, and rejects missing metadata signatures and empty AVB keys. Mismatched parent and child keys or tool failures also fail verification. To cover every chain level, it parses headers/footers and chain descriptors, invoking upstream `avbtool verify_image` for each signed node rather than implementing cryptography itself.

Only success produces `<verification-dir>/signature-verification.json` (`taku.image-signatures/1`), with three explicit verification booleans in `checks`. It records candidate identity, tool/source/script SHA-256s, commands and exit codes, relative log paths/hashes, certificate/root/chain-public-key fingerprints, flags/rollback index, and vbmeta digest. The script does not rewrite original baseline receipt/status; the controller supplements acceptance with this independent report and archives it. Extracted images and upstream-tool temporary payloads are cleaned on temporary-directory exit; no private keys enter evidence storage. An existing successful report is reused unchanged, including timestamps, only if bindings match and evidence is complete. Mismatched or modified reports are rejected.

`tests/image-signatures.test.py` uses explicit host-tool substitutes to cover these acceptance/rejection conditions; fixtures are not valid ROM signatures. Real old Android 16 vbmeta, boot, init_boot, vbmeta_system, and vbmeta_vendor metadata were also inspected read-only to check header/footer and four-chain parsing compatibility. That is not Android 17 signature acceptance.

## Two-version incremental OTA and data-preservation acceptance

The user authorized establishing Android 17 A→B delta/data-preserving upgrades before integrating automated builds, cloud publication, and phone updates. The entry point is [pixel9-ota.py](../scripts/pixel9-ota.py), with shared archive validation in [ota_release.py](../scripts/ota_release.py). Linux packaging uses [generate-incremental-ota.py](../image/generate-incremental-ota.py) to call this Android version's official releasetools; device evidence uses [ota_device.py](../scripts/ota_device.py). **At this checkpoint the entry points were ready, but no actual Android 17 delta or successful physical-device upgrade record existed.**

**Connection update: at 05:29 UTC+8 on 2026-10-21, the user connected Pixel 9 to MacBook over USB; ADB reported authorized `device`. Subsequent work could no longer assume it was disconnected.** Read-only observation confirmed `tokay`, Android 16/API 36, LineageOS `23.2-20261011-UNOFFICIAL-tokay`, current slot `_a`, Virtual A/B enabled, `ro.boot.flash.locked=0` / verified boot `orange`, user 0 `RUNNING_UNLOCKED`, and about 98 GiB free data storage. Vendor SPL was `2026-02-05`, bootloader `ripcurrentpro-16.4-14791556`; compatibility with target Android 17 firmware remained unchecked. Installed Assistant update was `org.agentos.assistant` v46 / `20261015-mobile-composer`, with an older v36 in the image. Current `ro.boot.vbmeta.digest` was empty; it could not be invented or used to bypass the later Android 17 before-stage AVB gate. Observations remained only in ignored `out/pixel9-android17/ota/device-inspections/20261020T212901Z-connected.json` and `20261020T213007Z-packages-user.json`. No test APK was installed and no system/device changes occurred. Full A/B packages remained pending; recheck ADB and actual firmware before each device operation.

The current full build and later B both run in the Mac mini's isolated worktree. MacBook needs only the frozen plan, full A OTA, later delta, and acceptance material, not two large target-files archives. Both versions retain the same platform/kernel locks, signing trust, and partition layout. B initially changes only BUILD_NUMBER, producing a genuinely different system identity without feature changes confounding the upgrade experiment. Existing source/kernel caches can be reused; the published A archive cannot be overwritten.

### Sequence and gates

1. After full A build, real verification, and export succeed, revalidate its archive with `load_baseline`, recording its absolute directory and receipt/target-files SHAs. Pin A's selection in ignored `out/pixel9-android17/ota/lab-state.json` before starting B. This state records facts; it does not relabel pending work as verified.
2. Generate B with `python3 scripts/pixel9-image.py rebuild` in the same controlled source volume. Export A and retain old container/logs first, then reuse caches. Do not create another complete source volume or change platform/kernel locks. Build the A/B plan after B's verification and archival.
3. Check current Pixel 9 firmware, bootloader, rollback restrictions, and A's RADIO/partition requirements; retain actual-device and upstream-version evidence. A firmware assessment requires these real checks, not matching Android versions alone. Any initial installation requiring unlock or data erasure needs separate explicit handling; these scripts perform neither.
4. `baseline-sideload` records the actual full A package SHA, same-device ADB-identifier digest, firmware assessment, and installation result. The phone must already be in recovery ADB sideload mode; the script does not reboot, unlock, format, or flash partitions automatically.
5. Once A boots and is unlocked, `device-before` installs an independent probe, records randomized SharedPreferences/SQLite/private-file hashes, and verifies A's version, build number, timestamp, SPL, and AVB digest. A getprop fingerprint alone does not establish installation provenance; retain step 4's actual package record too.
6. Return lightweight before/full-install evidence to Mac mini. `generate` revalidates A/B archives, plan, and device evidence before producing the delta. The build container must have stopped successfully; source mounts are read-only. The task shares the controller's source-operation lock and rejects concurrent compilation writes.
7. Download the entire delta delivery directory to MacBook; `sideload` records installation. After the phone reboots into B, `device-after` binds the successful install record, same physical device, new AVB measurements, slot switch, and probe data. Do not reinstall/reinitialize the probe after upgrading and hide data loss.
8. Then verify Virtual A/B snapshot merging, Taku functionality, Chinese input/background displays, networking, reboot, and failure recovery. The combined report retains unexecuted items as false. Sample preservation does not verify every account/app/database. Only after the complete sequence passes should cloud channels and automatic phone updates proceed.

For the first full installation, `bind-baseline` on the builder can create a lightweight SHA-pinned binding from one verified archive. MacBook consumes it with `baseline-sideload --baseline-binding … --baseline-sha256 …`, without waiting for the next candidate or downloading large target-files. It preserves package SHA, firmware assessment, same-device identity, recovery-sideload checks, and install-record format. Later `plan/device-before/generate/sideload/device-after` still require a complete two-version plan and actual source-install evidence; one baseline binding cannot replace delta gates. Partial Android 16 install records, partial payloads, or different firmware conditions cannot substitute for this source baseline.

### Command entry points

First create a plan in the Mac mini's isolated worktree, using actual export directories:

```bash
python3 scripts/pixel9-ota.py plan \
  --from-baseline "$BASELINE_A" --to-baseline "$BASELINE_B" \
  --output out/pixel9-android17/ota/plan.json
```

Deliver the returned plan SHA with the file. Every subsequent command requires explicit `--plan-sha256` and must not silently accept a replaced plan. `--help` lists all required parameters:

```bash
python3 scripts/pixel9-ota.py baseline-sideload --help
python3 scripts/pixel9-ota.py device-before --help
python3 scripts/pixel9-ota.py generate --help
python3 scripts/pixel9-ota.py sideload --help
python3 scripts/pixel9-ota.py device-after --help
```

The `baseline-sideload` assessment schema is `taku.ota-firmware-assessment/1`, containing `result=compatible`, current `baselineReceiptSha256`, `adbSerialSha256`, `fullOtaSha256`, actual checks `deviceModelVerified/firmwarePathCompatible/bootloaderAcceptsPackage`, and an `evidence` list of relative paths/hashes. Create it only after reading the phone and checking firmware; no prefilled successful template is provided. The command retains the assessment and referenced evidence together; `generate` revalidates actual A installation provenance.

Probe source is in [tests/fixtures/ota-probe](../tests/fixtures/ota-probe/README.md). It reuses the existing SDK/JDK and public development test signing; outputs and signature logs stay in ignored `out/pixel9-android17/ota/probe/`, outside the production ROM. Offline APK compilation/verification on MacBook does not mean installation on the phone.

Successful packaging produces `incremental-ota.zip`, `incremental-ota.json`, command logs, a generator copy, frozen plan, before evidence, and A installation provenance; `delivery.json` binds all core hashes. Deltas target only the exact previous baseline, with strict partition, non-partial, old/new identity, no-wipe/no-downgrade, OTA-signature, and payload-signature checks. Verified B supplies target AVB content; actual device boot still requires separate acceptance.

Development check: `npm run check:ota`. Fake crypto/ADB verify acceptance/rejection behavior, not physical-device results. Actual full-package, delta, and device acceptance depend on their immutable archives and installation records. Automatic publication, cloud storage, channel indexes, and Updater service URLs were not yet configured, preventing premature rollout of unverified candidates.


## 2026-10-22: physical-device installation preparation and boot-measurement fix

First A had build number `20261021110858`, snapshot `dc6bc1cd45e13e577e103f11bf072bd666219e06033ded30346dde1e76e140d5`, and immutable Mac mini directory `out/pixel9-android17/artifacts/20261021110858-dc6bc1cd45e13e57/`. Full OTA was 1,914,604,356 bytes, SHA-256 `b0f6300228d7d441a0478539e7c8979c2d8ef780e46f1ab2d3dd5996af90d545`; target-files was 4,645,590,778 bytes. `load_baseline` revalidated the complete archive, input locks, and actual signature report, passing all three cryptographic checks. The independent `signature-verification` report supplements signature fields still false in the original receipt; historical files are not rewritten.

After pinning A in ignored `out/pixel9-android17/ota/lab-state.json`, B started at 00:27:58 from the same snapshot (`20261021162758`, build number only). Before phone installation, inspection of current phone vbmeta, A's actual root header, and pinned `external/avb/libavb/avb_slot_verify.c` confirmed that `flags=3` enters VERIFICATION_DISABLED and skips generating boot-measurement options. This matched the phone's missing `ro.boot.vbmeta.digest`. Offline signature validity does not ensure device boot measurements; original `device-before` requirements cannot be skipped.

Pinned `device/google/zumapro/BoardConfig-common.mk` adds `--flags 3` when `WITH_AVB!=true`. GNU Make checks against the actual pinned block confirmed the flag with false and its absence with true. The fix exports upstream `WITH_AVB=true` only in `image/build.sh`, leaving platform/kernel locks, keys, and userdebug/root configuration unchanged. `verify-artifacts.py` reads flags from the actual AVB0 root header, rejects disabled verification/unknown bits, and keeps cryptographic verification independent. `ota_release.create_plan` also rejects these source/target candidates early; original Android 17 physical-device, install-provenance, actual AVB-digest, sample, and slot-switch gates remain intact.

After a controlled stop, rebuild archived B's container/logs/inputs under `attempts/failed-eff6192bb76f/`; B did not overwrite A. Corrected C is build `20261021163801`, snapshot `bc7f8c6af00db83b07d8a928fdc7283f194f1529f36e4d0d72bb27db8e8d9363`, overlaying only `image/build.sh` and `image/verify-artifacts.py` on A. Actual `incremental-check.json` confirmed 341 unchanged kernel checkpoints, unchanged platform/kernel locks, no source resync/kernel rebuild, and retained compilation cache. C later passed actual verification/archival with root flags=0; device boot still awaited measurement. Next candidate D uses the identical snapshot with a higher build number. A and stopped B remain historical records; old A's missing-measurement evidence cannot authorize a delta.

MacBook rechecked USB Pixel 9: tokay / Android 16 API 36 / LineageOS 23.2, FBE, unlocked bootloader, slot `_a`, vendor SPL 2026-02-05, current root vbmeta flags=3, and empty boot measurement. The trusted OTA testkey fingerprint matched the candidate; no Google Apps add-on was present. Official Lineage Wiki revision `504b1dbb59d2b8496002dfb7bbb5f1445aec5d82` describes tokay as A/B, Android 17, with firmware in the package, and does not mandate formatting for upgrades. This supports further checking of a data-preserving recovery-sideload path, not installation/firmware acceptance itself. Source copies and SHAs remain in ignored `ota/firmware-references/`.

For the first Android 16→17 migration, independent `org.taku.otaprobe` was installed and randomized samples generated. `ota/initial-migration/before.json` contains only three storage hashes plus APK/device identity digests. This is initial-migration observation, not Android 17 `device-before`, complete A installation provenance, or evidence preserving all user data. Verify it after the corrected version actually boots; do not reinstall/reinitialize the sample and conceal loss.

A was downloaded only as a local transfer basis through six resumable fixed-size SSH/Tailscale chunks, followed by complete SHA-256 verification after merging. It was neither published to the cloud nor flashed, because it failed the measurement gate. Corrected downloads must bind to their new receipt/SHA rather than reuse A's identity. At this checkpoint the phone had not been flashed, rebooted to recovery, or wiped.

Local checks passed 24 package-acceptance, 27 OTA archive/plan, and 18 build-isolation cases, plus a real Make test on pinned source. These test configuration/gates, not C's full build, ROM signatures, physical device, or data preservation. Original outputs are in ignored `out/pixel9-android17/diagnostics/boot-measurement-20261022/`.


The first-full-install entry point no longer unnecessarily depends on a future target version. On Mac mini, run `python3 scripts/pixel9-ota.py bind-baseline --baseline <actual-verified-archive-directory> --output <new-baseline-binding-file>`. It emits `taku.ota-baseline-binding/1` only for archives revalidated by `load_baseline` with usable boot measurement; it does not mark physical-device acceptance. After downloading the full OTA, binding, and returned SHA to MacBook, `baseline-sideload` accepts `--baseline-binding` / `--baseline-sha256`, still requiring a same-device firmware assessment/SHA and an already-sideloading phone. It never automatically reboots, unlocks, or wipes. The old `--plan` path remains. Subsequent two-version plans revalidate the same installation record, baseline receipt, and OTA SHA; a single binding cannot bypass before, actual vbmeta, or probe gates.

Three selected applications' data and shared files were saved in an approximately 718 MB private local backup and passed gzip integrity checks. It remains only in ignored `ota/initial-migration/private-backup/`; accounts, application data, and the backup itself were not committed. A file backup does not guarantee whole-device rollback, key recovery, or acceptance of every user's data.

### C archival and actual C→D execution

C's immutable builder archive is `out/pixel9-android17/artifacts/20261021163801-bc7f8c6af00db83b/`. OTA: 1,914,603,776 bytes, SHA-256 `0965a785e308e124dfb0daaf36faeaa66592f471014f0e1150231c5d3b4e1287`; target-files: 4,645,590,812 bytes, SHA-256 `e26d990df6974b44dfdf805e1b83e132bf4f32f3dc8e992ef45b1a884126cddb`. Receipt SHA: `8721f73c4f9ae600b014bb6174975695307c61723065ad3f62d7d47c0af4ae1c`; actual signature-report SHA: `39a50378e7342a992102ef3cd431123a62f43354aa40b16bb64e6eab7baf8a3e`. All five AVB signed nodes have flags=0; root measurement is `9d81daaa9fcc19edd8618df7a624ef2f0a3456b4bd327c296ed81a45a02a70fd`.

`bind-baseline` revalidated the archive, producing binding SHA `1358c397cc71a0f1634563942c0c7d381ab370e3e351b57bf9ac0b4dcdce401b`. MacBook retained A through an independent APFS copy as the transfer basis; rsync transferred about 95 MB in 52.81 seconds and verified the complete C-package SHA. This is network transfer volume, not an Android incremental OTA. Original A was unchanged. The binding, full OTA, and download-check records are in local ignored `ota/baselines/20261021163801-C/`.

D's build number is `20261021174811`, container `44898d29605fb6058c4edc8c4581876aaeaa88ed23b44cd2ac7b8c870340d9e1`. Controlled rebuild first revalidated/preserved C. D's `incremental-check` has old=new C snapshot, `changedInputs=[]`, 341 kernel checks, and unchanged platform/kernel locks. C's container remains as `taku-pixel9-android17-build-completed-2a3f0879973f`. `lab-state.json` separately records historical A/B, candidateC, and candidateDStart; neither D nor old A overwrites C.

Before the first installation, the same phone was rechecked: primary user unlocked, FBE, bootloader still unlocked, and probe APK/UID/three private-data hashes identical to Android 16 before evidence. C's RADIO firmware metadata was extracted again from actual target-files; bootloader/modem bytes matched the reviewed pinned baseline, old/new AVB root keys matched, and root rollback index did not decrease. `ota/initial-migration/firmware-assessment-C/` retains exact C-package binding, current-phone evidence, and pinned official upgrade-path references. Its bootloader condition establishes only preinstallation compatibility review. Actual recovery verification, secure-hardware acceptance, boot, and data preservation require later observations; assessment compatibility cannot substitute for them.

While backing up newer data such as the added input method, the first direct USB exec-out transfer returned ADB 0 but failed gzip due to truncation. The failed file/record were retained and not treated as valid backup; the previously verified backup stayed unchanged. The new backup was instead created/verified on the phone before pulling. Upgrade continued only after matching both endpoint SHAs and passing MacBook gzip checks. Backup, recovery, boot, or data-check failures do not authorize wiping, relocking, forcing an old slot, or downgrading.

The latest phone-staged/pulled backup passed both endpoint SHA and gzip checks: 718,436,917 bytes, SHA-256 `4e8b6c5ad777389a69ad4e27ce038fae544e0e3af1cbfb8064f7f50226a0981a`. The authoritative record is ignored `ota/initial-migration/private-backup/20261021T174951Z-staged/backup-record.json`. C's actual `baseline-sideload` then returned 0 with unchanged package bytes; install-record SHA was `4e8faa3699b78489035d123350964a1257a72230cbd74daf58a303a8b667530c`. This establishes command success only, not boot/data-preservation success. Afterwards ADB disconnected temporarily and Mac USB inventory could not find the phone. The user was asked to keep it connected, reboot from the completion page, and unlock it. No forced reboot, slot switch, or wipe was performed.

After the user rebooted/unlocked it, the same Pixel 9 returned to ADB. `ota/initial-migration/after-C.json` (SHA `0227153b038f30b64908329385c6177a3d84a69d725746f854aeca181ad89e8a`) records Android 17/API 37, build `20261021163801`, and an actual AVB digest exactly matching C. Active slot was `_b`; bootloader/modem matched C firmware, boot completed, and primary user was unlocked. Independent probe APK, UID, preferences, SQLite, and private-file hashes all matched preinstallation Android 16; default/enabled input methods were unchanged.

Original `priorUserPackagesPresent=false` resulted from comparing `pm list packages -3`: C includes a system version of `com.follow.clash`, so its surviving `/data/app` update was classified as an updated system app. Original evidence remains. Supplemental `after-C-supplement.json` (SHA `aab132f0f2871d2aaf1f07983c087b7e6747c8443f644201cf5db10adc367d0a`) checks the full installed-package set and confirms all former user apps remain installed. Actual `adb root` succeeded, four `taku_*` services were running, `taku-device system.info` and `displays.status` succeeded, and an independent background display existed. The active slot was marked successful and snapshot merge was `none`. These checks cover listed items only, not all user data or hardware.

D completed automatic export and actual signature verification; receipt SHA is `0723f7c07c90a1824e277506b1670f51c47af854a8858bd812dd83731d1c8e83`, root AVB flags=0, digest `3afe85102bc5e2eabfac91accdb902d150fb75d2d25de7f9e68be615637f1306`. Re-running `load_baseline` for C/D confirmed matching input snapshot, source/kernel locks, trust, and 26 partitions. The plan is pinned in `ota/C-to-D-20261021163801-20261021174811/plan.json` (SHA `f0edfdf6fa74eb37916a9d33099168b62426ab895be591ede708a99ccbd11dcd`). Physical C's `device/pre-upgrade.json` SHA is `869db9246cc28ccd0ebe4d0646fc610a0dcf2d04aaab6767f6faa1a2b0533550`; the independent probe was reinitialized for C→D only after preserving initial-migration comparisons. Delta generation must consume this before evidence and actual C installation provenance, not historical A/B or unit tests alone.

At the user's request, a private local configuration containing only the active VLESS/REALITY proxy node was copied from Mac Clash Verge, imported, and selected in phone FlClash. The old Personal profile remained. Actual runtime configuration had one node and no provider, with connection parameters matching the computer. Google/Cloudflare requests through the phone's local proxy returned 204/200. Configuration and connection credentials stay outside Git. C→D retains only selected profile ID/file SHA as extra preservation samples, never credential values.

### Authorized direction for later release infrastructure

The user confirmed reuse of Desktop's R2, Worker, and CloudFront. The sequence remained C installation and C→D delta/data-preservation acceptance before release channels; automation stayed paused. Existing Desktop production storage is R2 bucket `releases`, gateway `worker.taku.ai`, and CloudFront domain `download.taku.ai`. Current routes/metadata are limited to desktop platforms/Electron; do not disguise a ROM as a desktop ZIP or rewrite Desktop's active pointer.

Android uses independent device/build/channel namespaces and a JSON API, reusing large-file streaming, Range, ETag, and immutable-object mechanisms, with signed-URL refresh resuming the same object. Actual full/delta OTAs are distributed; target-files and acceptance material are privately archived. Deduplicated actual outputs were about 6.56 GB/version, or 197 GB for 30 versions. At the then-documented R2 Standard rate of $0.015/GB-month, storage was about $3/month before shared free allowances, excluding deltas, requests, Worker, and CloudFront. Deployment documentation listed CloudFront Pro at $15/month and 50 TB/month; this was not a live bill or remaining-quota check. Sources: [R2 pricing](https://developers.cloudflare.com/r2/pricing/) and [CloudFront plans](https://docs.aws.amazon.com/PricingPlanManager/latest/UserGuide/plans.html). At this checkpoint no candidates were uploaded, Desktop channels changed, or Android cloud publication activated.

Team members need not separately compile the same ROM. The release side builds/signs/tests/archives once per supported device; members with that device install the common full baseline, then receive a delta for their exact source version or a compatible full OTA on mismatch. Daily application work can install individual APKs; platform, kernel, or system-integration changes enter image releases. Other devices cannot reuse packages accepted only for tokay. Full OTA and initial cross-system installation are distinct; the latter's data-migration requirements need individual verification. See [AOSP package generation and exact-source rules](https://source.android.com/docs/core/ota/tools).

### Existing system Updater reuse boundaries (read-only review; not yet integrated)

C/D already include `org.lineageos.updater`, pinned at [Updater 6d50e0b](https://github.com/LineageOS/android_packages_apps_Updater/tree/6d50e0bf1b16fe7bca818375a3e734f4959e2e57). Its A/B installer can pass delta payloads to platform `update_engine`; a separate system-update app is unnecessary. Feed contracts, selection, and download gates must match that pinned version:

- `lineage.updater.uri` supports `{device}/{type}/{incr}`. The feed must use HTTPS and directly return a top-level JSON array; each item contains `datetime/type/version/files`, and the client reads only `files[0]`. The server must choose one applicable package, explicitly setting `os_sdk_level=37`.
- C/D release type is `UNOFFICIAL`, which does not prevent requests to the default official feed. Taku must explicitly select its own URI. Development `setprop` does not persist across reboot. Later products can pin the URI in `image/taku.mk` or independently update Updater resources/logic with an APK using the same platform signature and a higher version. Updater platform signing and OTA package signing are distinct requirements.
- Manual download resume sends Range, retains it through redirects, and rebuilds each request from the original download URL. The first version should use a stable URL identifying one immutable object, with the server re-signing CloudFront links each time; an expiring final URL is not permanent. The current client does not automatically refresh HTTP 403 or validate ordinary resume Content-Range/If-Range.
- Initially omit `ota_property_files`, allowing the existing APK to download the complete delta ZIP, verify its OTA signature, and install locally. This avoids expiring-link recovery during direct streaming and does not require downloading a full ROM.
- The original client filters only by time, SDK, and release type, without exact source fingerprint/vbmeta or independent ZIP SHA-256 checks; completion checks only build-time change. Later clients should add exact-source identity, ZIP SHA, and actual-result checks. Until then, the server maps exact verified source builds, with existing physical-device acceptance retained. An Updater “complete” message cannot replace evidence.

The real C→D delta was generated, passed OTA/payload signature verification, and returned to MacBook: 269,439 bytes (about 263 KiB), SHA `4402e88c5a12d8e930edb904936169b23b67bf2ca224a324fc54fab7cfbf3a46`, delivery SHA `0da5dc560d05bd71e87fb1d764455c73d9a922e179592ab642fb8d20f53135ae`. Delivery, actual source-installation/before evidence, and command logs remain in `ota/C-to-D-20261021163801-20261021174811/delta-attempt-1/`; full-chain `validate_delivery` passed. This changes only build number; its 0.01407% full-package ratio does not generalize to feature updates. The phone entered recovery for its first delta sideload; post-upgrade verification was still incomplete.

When manual follow-up resumed at 13:23 UTC+8 on October 22, the phone still enumerated as `sideload`, but no host `adb sideload`/controller process survived, the original command session was unavailable, and no `installation.json` existed. The old log ended at host serving `~47%`, which is not installation completion and cannot justify inventing success or declaring phone-side failure. Observation is in `device/interruption-observed-20261022T052347Z.json` (SHA `7fd50a12a991d9cca3f3329de903792c279013ab3967677b157b0f46cecfbdc6`). The user was asked to confirm the recovery screen; no repeat sideload, reboot, cancellation, slot switch, or wipe occurred. Original delivery, before/source-install evidence, and incomplete logs remain. Device upgrade status was unknown and cloud channels stayed inactive.

### Actual D boot and data-preservation observations (from 13:44 on October 22)

After the user confirmed installation completion, the same Pixel 9 returned to ADB. Read-only `post-upgrade.json`, SHA `16ab4dc9d8234c79c8c35c258a0b2e5ac924d34e88380b4b15be531feef68bdd`, records build `20261021174811`, Android 17/API 37, an AVB digest exactly matching archived D, active slot switching `_b`→`_a`, and unlocked primary user. Probe APK, UID, preferences, SQLite, and private-file hashes all matched C before evidence; the probe was neither reinstalled nor reset.

The current slot was marked successful and `bootctl get-snapshot-merge-status` returned `none`. This D boot's update_engine log directly recorded `MergeCompleted` for product/system/system_dlkm/vendor_dlkm/system_ext/vendor and cleanup `ErrorCode::kSuccess`, with merging taking about 14.8 seconds. The log remains private; the sanitized review records its SHA. Four `taku_*` services were running, native `system.info/displays.status/tasks.status` succeeded, and the Assistant UI/composer opened.

FlClash retained the same selected proxy profile and private-file SHA; Google/Cloudflare through its local proxy returned 204/200. Original user apps and default/enabled input methods remained. The current composer was empty with another prompt, but its differing UI-text SHA did not establish draft loss: the original Chinese draft SHA still existed exactly in persistent composer storage. Supplemental evidence is `draft-retention-supplement.json`; no user draft was sent or rewritten.

Sanitized `candidate-device-review.json`, SHA `ba124c1c140703ea318346ec50332a3659a3faa30ec4b7d83ae93096c9517490`, summarizes 13 actual checks. Status is `observed-upgrade-passed-with-publication-evidence-gap`, `candidateArchiveEligible=true`, `stablePromotionEligible=false`. Missing tail evidence remains explicit. The complete-install-evidence gate in `pixel9-ota.py device-after` was not relaxed and no fake `incremental-acceptance.json` was created. The phone lacked an AI account; model conversations, physical-keyboard Chinese input, and all user data were not verified.

### Actual R2 organization inventory and permanent storage

At this stage the user explicitly requested review of the Taku organization's R2 structure and upload if device checks found no issue. Account API checks confirmed **TakuOS** matched Desktop's pinned production identity; complete pagination found 22 buckets. Only directory structure, public-access configuration, service bindings, and aggregate statistics were read, not other object contents. Actual Worker settings confirmed production `RELEASES→releases` and staging `RELEASES→releases-staging`. Managed r2.dev access was disabled for every bucket, `releases` had no direct public R2 domain, and existing products downloaded through restricted Worker/CloudFront routes.

At that checkpoint `releases` primarily contained `apps/taku/`, with no Taku OS prefix, while `releases-dev` was exactly empty. The latest releases sample counted 1570 objects and 243,810,861,280 bytes. Latest samples across 22 buckets totaled 16,341 objects and 251,882,322,667 bytes, but were not simultaneous live enumeration. Complete bucket purposes, per-level completeness, and sampling times remain in the private inventory under ignored `ota/cloud-publication/r2-audit-20261022/`.

This reused `releases`, adding a permanent namespace consistent with existing products. Development/prerelease use the same key layout in `releases-dev` / `releases-staging`; environments and release channels remain distinct:

```text
apps/taku-os/android/tokay/releases/<buildId>-<snapshot16>/
  ota/full.zip
  manifest.json
  checksums.sha256
apps/taku-os/android/tokay/deltas/<sourceBuildId>--<targetBuildId>/<deltaSha256>/
  ota/incremental.zip
  manifest.json
private/taku-os/android/tokay/builds/<buildId>-<snapshot16>/
  target-files.zip
  baseline-receipt.json
  signature-verification.json
  provenance.json
private/taku-os/android/tokay/acceptance/<sourceBuildId>--<targetBuildId>/<evidenceId>/
  report.json
  incremental-ota-report.json
  manifest.json
apps/taku-os/android/tokay/channels/<canary|beta|stable>/current.json
```

Archival uses a fixed allowlist, conditional creation, and complete streamed readback SHA for each object: only deduplicated C/D full OTAs, target-files, C→D delta, necessary receipts/signature reports, and sanitized review. It excludes A/stopped B, source volumes/caches, private signing keys, phone accounts/data, and raw private logs. This is `candidate/unpromoted`; channel pointers remain planned and are neither created nor changed. A `private/` prefix is not access control. There is currently no public bucket entry point; future Android viewers/APIs must serve only manifest-allowlisted OTAs, never arbitrary R2 keys. Upload completion and automatic phone updates are separate stages.

The pinned upload plan is ignored `ota/cloud-publication/archive-C-to-D-20261022/upload-plan.json`, SHA `75fc26d3f4543eb05232d7a22edeb9ba28581cf3b6877bbe1911266232f112ef`: 19 final objects, 13,120,713,267 bytes. Each C/D release includes full OTA, target-files, receipt, signature report, provenance, manifest, and checksums. Delta includes package/manifest; acceptance includes sanitized review, generator report, and manifest. Public manifests contain no absolute host paths, phone serials, private storage content, or credentials. Provenance retains source commit, `sourceTreeDirty=true`, and exact input snapshot; these candidates must not be described as reproducible production releases from clean Git commits.

Large files first use task-UUID staging keys, then R2 conditional copy `cf-copy-destination-if-none-match:*` creates permanent objects; small files use `If-None-Match:*`. ETag-conditioned Range reads cover every byte and compute SHA-256; multipart ETag and self-reported object metadata do not prove integrity. Manifests are written last. Only this task's staging objects are deleted, after final-object readback succeeds. Failure evidence, multipart state, and journal remain; recovery must bind to the same plan and never unconditionally overwrite objects.

Independent read-only review was pinned to `Taku-OS/taku-workers@294d956b097caa6c67eb486b49a16b327a320145`. Parsers/callers for `/assets/*`, `/releases/*`, and `/app-update/v1/download/*` did not expose top-level `private/` as arbitrary anonymous keys; new Android prefixes did not automatically gain download routes. Production traffic was 100% on version `ca7a4f49-fed1-4ed0-b899-6351ffc80549`, whose metadata lacked a Git SHA. Thus source conclusions apply only to reviewed main, not proof that production matches. Future Android routes/feeds use existing repository review, Git, and Cloudflare Workers Builds. This R2 archival changed no Worker deployment, Desktop channel, or Android update pointer.

Uploading D's full package hit one `ERR_SSL_DECRYPTION_FAILED_OR_BAD_RECORD_MAC`. Failed process/state/journal/original helper were retained in ignored `ota/cloud-publication/archive-C-to-D-20261022/recovery-20261022T064640Z/`. After confirming the old writer stopped, recovery reused the same plan/session; `ListParts` reused 603,979,776 confirmed bytes without a second multipart set. The recovery helper only added lock release after all concurrent tasks settled and actual ETag/LastModified checkpoints. TLS verification, system networking, and concurrency were not weakened/changed. Old C records lacked ETag, so identity was not fabricated to skip checks: both C objects were fully reread in about 615 seconds and still matched SHA, retaining earlier verification history. Only later checkpoints with unchanged identity could reuse existing complete SHA results.

### R2 candidate archival complete (15:35 on October 22)

All 19 permanent objects completed at `2026-10-22 15:35:43` UTC+8, totaling 13,120,713,267 bytes (about 13.12 GB / 12.22 GiB). Every object was fully read back; SHA, size, and key matched the pinned plan, beyond metadata/ETag/upload exit-code checks. C/D full packages are `ota/full.zip` beneath `apps/taku-os/android/tokay/releases/20261021163801-bc7f8c6af00db83b/` and `20261021174811-bc7f8c6af00db83b/` in bucket `releases`. The delta is `apps/taku-os/android/tokay/deltas/20261021163801--20261021174811/4402e88c5a12d8e930edb904936169b23b67bf2ca224a324fc54fab7cfbf3a46/ota/incremental.zip`. Build inputs and sanitized acceptance are in the preceding top-level `private/taku-os/` namespace.

Final `ota/cloud-publication/archive-C-to-D-20261022/upload-receipt.json` SHA is `06c406a2040be5f4403d0442f4504176fb916b4aeb2933bc7d299340384956c0`; sibling `cleanup-verification.json` SHA is `03c99d27ede15e6f639229aad613511e136a7d41db74b459fa0292821408e1e6`. Cleanup verification completed at 15:36:13, restricted to this session prefix; complete pagination returned zero temporary objects and incomplete multiparts. Receipts, pinned plan, small metadata, state/journal, and recovery evidence were delivered to the corresponding ignored MacBook directory. Independent checks verified plan binding, 19 keys, sizes, SHAs, and cleanup.

Status remains explicitly `candidate/unpromoted`, `stablePromotionEligible=false`, and `channelPublished=false`. No channel pointer, Worker/CloudFront deployment, bucket exposure, or resumed automation occurred. Cloud archival does not mean the phone has automatic updates. The missing host-install completion tail remains documented; upload success cannot relax later release gates. Original Mac mini build, cache, existing changes, and acceptance materials were not deleted.

## 2026-10-23: E full package and D→E delta delivery

This checkpoint describes actual runs on UTC `2026-10-22`. D later regained source-installation evidence through an independent full installation. This run's `device-before` confirmed actual D build, exact AVB, slot `_b`, unlocked state, and three new independent probe storage samples. Existing validators rechecked the new D install record, original sideload log, and firmware assessment. This new chain does not reconstruct the lost C→D host tail or change unverified fields in earlier reports.

E derives only six Updater integration files from frozen D, excluding other workspace changes. The source volume had received approved `af844…` preview integration. E's incremental-build checks confirmed unchanged platform/kernel locks and 341 matching kernel outputs; source/compilation caches were retained with no resync/kernel rebuild. The full-package container started at UTC `16:06:57.981978261` and exited 0 at `16:39:37.651997126`; the log's `m` duration was `30:59`, a different timing scope. Existing publishing then performed actual verification and immutable export, without creating another candidate.

| E item | Verified fact |
| --- | --- |
| Identity | `20261022160657` / Android 17 / API 37 / `tokay` / `userdebug`; SPL `2026-10-01`, build timestamp `1792685272` |
| Input snapshot | `5fac91c7cac6009e736faf6e583ee070c8a2b23464aed5d6f368db2edd06f7d7`; manifest SHA `51f74390831aa6f85935df2c6269add746cfbbc3491aa5e16e7279b8afdbce72` |
| Mac mini archive | `out/pixel9-android17/artifacts/20261022160657-5fac91c7cac6009e/` beneath the isolated worktree |
| Canonical full OTA | `lineage_tokay-ota.zip`, 1,914,605,310 bytes; SHA `5d0e258b54661943f50251ecf79edab2f41e9fca1c3dc18718fee3e25653a312` |
| target-files | 4,645,593,105 bytes; SHA `06e22389abb6941e8f8645cb1c8bcc887a0ff49f909fb92dc3205329c70a37a3` |
| Receipt / signature report | SHAs `12204d99b75aa3d13646c5d5f722339f85770306c61ca9e8a15e8a96ef16a76b` / `ff9baa4cadb4a1a519cd68f32358ad269ec3ffe1ba351fabb30b441a0070cf2e` |
| AVB | Root flags `0`; digest `380057709f76660c8def1a09f7f17e47a9a78fcda717bfbdf0f37c5a83de52af` |

Verification binds actual OTA, payload, and AVB chain, not unit fixtures. D/E platform/kernel locks, signing trust, 26 A/B partitions, and AVB signing configuration passed the same `create_plan` gates, without decreased rollback index. The production canary URI persisted in E target-files, Updater v2 platform signature, and actual APK were also checked. APK size is 5,717,588 bytes, SHA `ef5fc8f1de9594bd4572c2668ea5c3794b49790bce8d3a9dcb932c270fc30ba1`, byte-identical to the preview installed on D; APK proof SHA is `4343e527d7456501ad5bbd68985dbc7ce143dc3beadfb942076efcbe173bf078`. After E boots, actual enabled APK path/SHA must still be read; version alone cannot establish that the system copy is active. Publication selects the canonical full package; stale date aliases do not establish candidate identity.

The following evidence is in local ignored `out/`; large target-files and full OTA were not copied to MacBook:

| Evidence | SHA-256 |
| --- | --- |
| New D full-install record (ignored `out/pixel9-android17/ota/test-channel-20261022/baseline-D-installation/installation.json`) | `b0f69b727b038fcfc6362d18618f2c8151733e88d0a92834dd9fb8fa58362f6e` |
| D→E plan (ignored `out/pixel9-android17/ota/test-channel-20261022/candidate-E-20261022160657/plan.json`) | `58b77f3c2c07c714a04a27645e97294edf658e6d0b1cf9d93b76146853e0f5a1` |
| This run's pre-upgrade evidence (ignored `out/pixel9-android17/ota/test-channel-20261022/D-to-E-20261021174811-20261022160657/device/pre-upgrade.json`) | `1b2c9e512cb33f77680cbffa060656e6211fea6f1f4c11be2b1c18c9b86e8dd6` |
| delivery.json（ignored `out/pixel9-android17/ota/test-channel-20261022/D-to-E-20261021174811-20261022160657/delta-attempt-1/delivery.json`） | `83184b9c9b2f447490afaa0a2b0ac0b3a01148833af5a71d9088179fc45b417b` |
| Actual generation/signature report (ignored `out/pixel9-android17/ota/test-channel-20261022/D-to-E-20261021174811-20261022160657/delta-attempt-1/incremental-ota.json`) | `64a080526a86a4ab4d6af006b3e08dc7729654841785e591ed70b1da34edeb0a` |

`delta-attempt-1/incremental-ota.zip` is **6,964,579 bytes (about 6.96 MB)**, SHA `3b9d3a6a22bbb78a699fba3ebdd395d0773d5bad86a3e5cc9a4a98243249f6b4`, about **0.364%** of E's 1.915 GB full package. This applies only to D→E's minimal Updater change. Future source/target content, partitions, and compression vary; neither 6.96 MB deltas nor 1.915 GB full packages are promised.

The generator actually ran `ota_from_target_files -i` and `check_ota_package_signature`; both commands, outer CLI, and durable driver exited 0. Source/reference volumes, both baselines, and frozen helpers were mounted read-only with networking disabled. The plan, D/E archives, new before evidence, and new D installation chain were revalidated before/after generation. Complete portable delivery contains 16 files, 7,050,302 bytes, mirrored by individual SHA to the corresponding MacBook directory and accepted by local `validate_delivery`. Twenty-two immutable D archive hashes plus original attempt run-input/status remained identical (24 total). Original container, failed preview, logs, and caches remain.

### Release and physical-device gates unfinished at this checkpoint

1. An independent publisher was uploading 12 E/D→E archive objects; completion receipts/full readback had not yet established publication. This is separate from D's verified permanent download. Archive receipt, exact-source D canary feed, and immutable download responses still required verification; stable promotion remained prohibited.
2. Updater v2's native page and refresh on D were observed, but showed no update then; native HTTP status was not captured and the stream-toggle change was unconfirmed. Native Updater still needed to obtain this ZIP, verify exact size/SHA, and establish complete-ZIP download/resume behavior from actual requests/files.
3. Start a read-only observer after initial IDLE in the same boot, then tap Install in native UI. Bind the unique actual `ApplyPayload` file URL, payload offset, and properties; obtain `UPDATED_NEED_REBOOT`, `kSuccess(0)`, and actual native-follow exit 0. Another same-target package or UI prompt cannot substitute for this delta's installation evidence; do not invent sideload-completion fields.
4. After reboot/unlock, check E identity, exact AVB, slot switch/success marker, and the same probe APK/UID/three data types. Independently verify snapshot merging, Taku services/Assistant/Chinese input/proxy networking/reboot/failure recovery. Probe samples do not cover all user data.

Original generator/delivery `physicalDeviceVerified=false`, `dataPreservationVerified=false`, and before `allUserDataVerified=false` remain; package generation does not make them true. E installation, reboot, and feature outcomes require later independent evidence. Automation remains paused and stable channels closed. See [Pixel 9 test publication and system updates](pixel9-test-updates.md) for native-update evidence procedures.

## 2026-10-23: actual native D→E upgrade and independent physical review

The preceding unfinished list describes the build/delivery checkpoint. These later results supplement it without rewriting earlier records. The phone downloaded the exact 6,964,579-byte delta through system-update UI. Native follow exited 0; 182 callbacks included initial IDLE, actual applying, unique `kSuccess(0)`, then final `UPDATED_NEED_REBOOT(6)`. The old observer incorrectly required status 6 before completion, so the host wrapper actually exited 1 and the original record says `commandCompleted=false`; these failure fields/frozen tools remain. Offline replay corrected both legal orderings while still rejecting failures, duplicate completion, and backwards stage transitions.

The complete actual engine log contains one `ApplyPayload`, with file URL, payload offset 5911, size=0, and four properties exactly matching verified delta bytes. Independent same-boot checks showed D/ZIP/APK/probe unchanged from initial state. However, engine prefix/identity at initial IDLE was not persisted in time; the normal install receipt remained ineligible. Later logs cannot fabricate a preinstallation seal. Independent revalidation retains `preInstallEnginePrefixSealed=false`, `normalObserverReceiptEligible=false`, and `binderCallerVerified=false`; it does not claim formal channel acceptance. The new observer fsync-seals prefix/identity before declaring ready and requires those files when consuming receipts.

The native Reboot button then performed a normal reboot. Independent `capture_after` obtained actual E `20261022160657`, exact AVB `380057709f76660c8def1a09f7f17e47a9a78fcda717bfbdf0f37c5a83de52af`, new slot `_a`, and unchanged probe APK/UID/three private data types. Each of six target snapshots recorded `MergeCompleted`, with global merge completion and cleanup `kSuccess`. Enabled Updater v2 APK bytes exactly matched E, production URI persisted, and the page showed E/no next update. This run's bounded checks passed four Taku services, three native operations, background display, proxy HTTPS, and actual Chinese candidate-selection input.

- post-upgrade.json（ignored `out/pixel9-android17/ota/test-channel-20261022/D-to-E-20261021174811-20261022160657/device/post-upgrade.json`）：SHA `f22ac20440d9cfc63cc07fa18a146568e535ff854075d7b033becbb8f70e6f64`。
- scoped-physical-review.json（ignored `out/pixel9-android17/ota/test-channel-20261022/D-to-E-20261021174811-20261022160657/scoped-physical-review.json`）：SHA `aaecb06c2d4ab69aa57e3976441584843746d5f5717190ddeeb290aa637c88ae`。
- Independent supplement to the original failed record (ignored `out/pixel9-android17/ota/test-channel-20261022/D-to-E-20261021174811-20261022160657/native-observer-attempt-1/revalidation-supplement-1.json`): SHA `8198e67d1c1d7a4c93f17bb8e8ecea4484df9c52336dc1a30238cb7604cb1475`; the failed record is unchanged.

The controlled draft's private data/revision survived, but empty-session fallback did not automatically restore it after reboot, so UI restoration did not pass. Other authorized development also changed Assistant/IME versions; identical UID alone cannot establish that version differences occurred within OTA. `allUserDataVerified=false`, `functionalRegressionVerified=false`, `formalPublicationAcceptanceVerified=false`, and `stablePromotionEligible=false` remain. Canary still awaits formal device acceptance; automation stays paused. These are bounded physical observations, not unit tests substituting for native installation or complete feature acceptance.


## 2026-10-23: actual TakuOS 1.0 and system-AI integration candidate checkpoint

New candidate `20261023063753` uses frozen snapshot `e3609930bb21ca25b9f222bd61d4fe359a796394f65cefee675eec3ee94fec75`, retaining Android 17/API 37, pinned platform/kernel locks, source, and compilation cache. Four branding properties now come from `PRODUCT_SYSTEM_EXT_PROPERTIES`. Actual `gen_build_prop` checks exited 0; the later full build also passed generic_system partition artifact gates without disabling artifact-path checks.

Container `5113ca4aef95…` started at UTC `06:37:54` and exited 1 at `07:06:59`, without OOM. Ninja's parallel output reached `258/580`; TakuMemory failed platform javac because its existing `taku-markdown` static library declaration was missing and hidden platform `android.view.InputFilter` conflicted with the text-input-filter import. Failed container, original run-input/logs, `e360`, and earlier snapshots remain. No full image was verified or exported.

Component fix `c8ee214423f4e8cece96212ef3ff70d726e0ba66` adds only `taku-markdown` to Memory's `Android.bp` and explicitly imports `android.text.InputFilter`. It changes neither the locked IME APK, separate signing, nor native libraries. The fix entered source integration, but actual platform-classpath javac, next full ROM, installation, data-preserving reboot, and cold boot without bind mounts still required acceptance. Old E observations apply only to E, not this candidate or the corrected image. Automation remains paused.

## 2026-10-25: actual installation of corrected TakuOS 1.0 I

I's input-control commit is `caefbf89efdcab20464189468b284205452dfddf`, build `20261024154635`, frozen snapshot `16fb163782b9e2d0f8224f3143fdae97c54b693c955dd178cfbecf6e8c64926e`. Reusing platform/kernel/compilation caches, its full build exited 0; whole-OTA, payload, and AVB verification plus immutable export passed. Archive is in the build worktree's ignored `out/pixel9-android17/artifacts/20261024154635-16fb163782b9e2d0/`; target-files SHA is `f80507c35c02296566bbadc5261804bb022594cc6589ecc6d82262a83085af56`, baseline receipt SHA `df604f959c6fe5d1dc35c45681890026e9b2b74d69bf638eaca11f8ce3bb6566`.

Earlier H signed/flashed successfully but failed boot because Memory's `android.permission.DUMP` lacked a same-partition privileged allowlist. Failed artifacts/logs remained; usable E was restored first, confirming unchanged original probe/configuration/APKs/UIDs. I derives only four policy/module/validator inputs from H. Actual APK permissions are audited against actual framework-res protection levels; requests of every Taku privileged APK must be covered. Memory's native Soong `privapp_allowlist` installs the 203-byte policy to `/system_ext/etc/permissions/TakuMemory.xml`; source and actual-image SHA both equal `d95554c2ddf45f91ea2431edd100c232d774ac330b35fc6f4331e5d7deb2e3c0`. Permission enforcement and AVB were not disabled.

Independent Linux tools unpacked generated super: raw bytes of six A partitions exactly matched authenticated target-files/payload, and six B logical partitions were empty. Eight companion boot images also matched individually. MacBook recomputed complete SHAs for all nine files after receipt. Following fresh preinstallation observations and a private local backup, only `super` and eight fixed `_a` boot partitions were written on the same Pixel 9; all nine actual flash commands succeeded. Userdata, physical metadata, and firmware were untouched; no wipe, slot switch, or disabled verification occurred. Boot completed about 40 seconds after reboot.

Actual postboot-2 passed 20 bounded checks: Android 17/API 37, I build/AVB, same phone/slot, all five APKs active directly from ROM system_ext (IME 10062, Assistant 67, Memory 8, Updater 3, Settings 37), unchanged original three-app UIDs/default IME/probe/three selected configuration hashes, four running services, and actual Memory policy SHA, root:root/0644 ownership/mode, and DUMP grant. System process PID/startTicks/boot ID stayed constant over 11 samples in 34.47 seconds. Report SHA is `830c011833f7049e16a4eb8377427f3cec88115b3903fc042b25d96bfaf56dc3`, in ignored `out/reviews/I-install-20261024/postboot-2/report.json` under the build worktree. This is not a 120-second continuous sample.

Original postboot-1 stopped because it strictly compared APK-declared and runtime-requested permissions; that record remains. Android 17's actual platform XML splits INTERNET into ACCESS_LOCAL_NETWORK for targetSdk <37. The new read-only observer binds XML extracted from exact I target-files, actual APK targetSdk, phone XML hash, and actual aflags enabled/default/read-only/system rows before accepting the precisely derived request set. It still rejects unknown extra permissions or missing original declarations. This changes only the acceptance observer, without rebuilding or changing phone policy.

Actual About phone showed TakuOS 1.0; system-update UI showed TakuOS1.0 and Android 17, matching four effective properties. Real-model checks, 30 IME cases, five shared-setting cases, seven model-configuration GUI round trips, and actual Memory collection/queue/Luna organization/query/result display passed. Memory's original 20 records remained; this run's synthetic records/new tools were cleaned up. Original Taku defaults, Pinyin, theme, accessibility registration, and effective model selection were restored.

After the independent session returned the phone, the main session sampled 41 times across 135.33 actual seconds, retaining identical boot/PID/startTicks. Boot status, four services, Enforcing, and user state were healthy before/after. Stability report SHA is `c5298084e9ff7447b7c31c55c27891ad503ceb7454c1eb8c22ab23ced10101e8`. Final probe/account/IME configuration readback bytes still matched. Independent comparison of preflash private backup with actual phone AI settings found only test-generated revision 48→55; all other keys/values were unchanged. Readback-report SHA is `6bc9d2c9c2f47c24fc6a38132d0dc7a21d2481ca2bacb9fe172bcdee538d7766`.

Original screenshots, cold-boot outcomes, QA fixes, and host acceptance receipts are in the [complete acceptance record](reviews/inputmethod-rom-2026-10-25/README.md). Signed I artifacts remain bound to original build inputs; document/QA-script updates do not rebuild them. This was direct full-image installation with bounded real feature/data checks, not formal incremental OTA or exhaustive acceptance of all user data. Automation stayed paused; cloud/stable channels were not opened.
