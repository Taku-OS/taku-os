# Common Google apps internal-test candidate

This variant is for internal testing only; build artifacts and links are not distributed externally. The user explicitly defined this scope; no Google public-preinstallation or redistribution permission is claimed here. Existing MindTheGapps Android 17 / ARM64 core, Taku default assistant, IME, AI backend, and user configuration retain their original inputs.

## Dependencies and original signatures

| App | Package | Pinned version |
| --- | --- | --- |
| Gmail | `com.google.android.gm` | `2026.02.23.876771157.Release` |
| Google Maps | `com.google.android.apps.maps` | `26.16.12.898594237` |
| YouTube | `com.google.android.youtube` | `21.25.523` |
| Chrome | `com.android.chrome` | `156.0.8078.47` |
| Google Photos | `com.google.android.apps.photos` | `7.67.0.882706237` |

Gmail, Maps, YouTube, and Photos are individually pinned original APKs extracted from the official NikGapps Android 17 stable registry; its installation/removal scripts are not run and PhotosOverlay is excluded. The catalog is pinned to `a53197efbacbc099a051874621c31a98964b1156`. Chrome uses the complete standalone ARM64 APK corresponding to the current official stable release, downloaded through a normal browser and checked against its pinned SHA; older Chrome/Trichrome pairs are not mixed, and splits are neither merged nor resigned.

APKs, transfer ZIPs, and SDK caches remain in ignored `out/dependencies/`; Git contains only code, package identities, SHAs, versions, and public origins. Each package is checked with actual `apksigner`, package name, minimum/target SDK, ARM64 ELF, uncompressed JNI, ZIP alignment, complete standalone variant, and manifest dependency order. Assetlinks declarations on official Google domains establish the approved publisher certificate; Source Stamp certificates are not APK publisher certificates.

Modules install under `/product/app/TakuInternal*/`, using `presigned: true` and `preprocessed: true`, without resigning, recompressing, or skipping preprocessed-APK checks. Ordinary apps receive no new privileged allowlist. The original core `image/google-services.lock.json` must retain SHA `1bd7811e2ebae2d87b27e2322ae785d1cb9c07bbfb86544b2af2a4cf43598e00`.

## Controlled entry points

1. Use `scripts/google-apps.py stage --apk-dir <internal-original-apk-directory>` to verify and prepare separate dependencies, locating the cache by the entire lock-file SHA.
2. Derive `taku-google-apps-internal-v1` from verified branding parent `a7421afe…`. Add only separate read-only dependencies, product inheritance, and acceptance hooks; do not append files directly to a final candidate containing an old source-repair capsule.
3. Rebind the existing 7b source closure to the new parent and execute existing `derive-runtime-repair`, without relaxing old source-repair, version, platform, signing, or OTA gates.
4. Run `pixel9-image.py google-apps-preview --from-snapshot <final-SHA>` on the final snapshot, first building/checking these five modules in an independent container. Retain controller source-operation and container source locks; mount both original GMS and extra-app dependencies read-only. If this entry fails, stop without starting a complete ROM build.
5. Only after entry success, reuse Mac mini caches for a complete candidate and verify actual OTA, payload, AVB, and target-files. Export a separate app report and recheck archives against original APK bytes and PRESIGNED records.

Default broad source snapshots exclude this proprietary dependency layer; it requires the explicit internal-integration entry point. Locks, run-input, receipts, and archives carry `internal-test-only`, with target property `ro.taku.image.distribution=internal-test-only`. Artifacts retaining `TakuInternal` APKs without this scope declaration are rejected. This round does not update public R2, Worker, CloudFront, or public update channels.

## Acceptance scope and current status

Offline image acceptance passed in this round; UI acceptance remains blocked by the device environment, so delivery is not complete.

| Check | Result |
| --- | --- |
| MacBook original-package identity, ARM64, Google signatures, integrity, and 16 KiB ZIP alignment | PASS, 5 packages |
| `npm run verify`, 8 new boundary tests, private-branch `source-check` CI | PASS |
| Real apps-only entry | PASS, exact container exited 0; five installed artifacts byte-identical to original APKs |
| Pixel 9 / tokay / Android 17 complete candidate | PASS, `TakuOS 1.3.0 / 20261028112718` |
| Five packages, PRESIGNED metadata, and internal-purpose property in complete target-files | PASS |
| Native OTA, payload, and AVB signature verification | PASS, five-node AVB chain; eight native verification commands exited 0 |
| Immutable export and independent read-only `load_baseline` review | PASS, 2026-10-28 20:08 Beijing time |
| Desktop icons and launch screenshots for all five apps | BLOCKED, no five-package screenshots yet |
| Pixel 9 ROM boot, account features, data-preserving upgrade | Not performed |

Candidate input commit is `778ce07a9e7d095124c0a5f204ff8eb4c816c472`, on private branch `codex/takuos-google-apps-internal-20261028`. Later acceptance-document commits are not ROM compiler inputs. The source closure retains 453 compiler files; IME `10062`, Assistant `74`, Memory `8`, and original AI backend inputs remain. Previous complete build `20261028042638`, its signed archives, containers, caches, and snapshots are retained.

The final immutable snapshot is `8b77b5c8829ae9d968de8d95c689828869ca6b95bcbc0ec37643e95b062a7565`. The real entry report is on the local build host at `out/pixel9-android17/google-apps-previews/20261028T105945301274Z-8b77b5c8829ae9d9/`; the complete build started only after the same snapshot's entry-success receipt, report hash, and exact container exit status passed.

## Internal artifacts

The following directory is on the dedicated local build host, accessed through authorized the authorized local build host; nothing was uploaded to public R2, Worker, CloudFront, or update channels.

```text
<workspace>/taku-os/out/worktrees/branding-release-20261024/out/pixel9-android17/artifacts/20261028112718-8b77b5c8829ae9d9
```

| File | Bytes | SHA256 |
| --- | ---: | --- |
| `TakuOS-1.3.0-tokay-20261028112718-full.zip` | 2,572,613,890 | `1ed35db503a1a7bed26e8944635b97c114505c00f76a27791915d89fb2887ee9` |
| `lineage_tokay-target_files.zip` | 6,365,833,880 | `c412d0af0b5d89e8d6b81ba1712a422ad8e7d7be92b466d2f2014df1e978c548` |
| `baseline-receipt.json` | 10,559 | `adefd917483eae45dffb823c8c1db6f49ea35ee1903ab87912f540a6b5e6e626` |
| `google-apps-artifacts.json` | 1,854,857 | `08e170b33e36a13a26589f686ec526d0cdb2cac92fc980aea5f984915834d203` |

Archives also retain `run-input.json`, original-package locks, verification helper, platform/kernel locks, Google-core and AI-input evidence, and `signature-verification/`. This round's signature-report SHA256 is `55b51d266feb121503210972a71395d46e32d7b49b190dfbd7ec11efe8f5c5cc`, binding run-input `73a8ab681f9da2b2e4f509a76b04cf16cf8d566a1805c710f4a2d156056de6e9`. The five actual APK paths are `PRODUCT/app/TakuInternal{Chrome,Gmail,Maps,Photos,YouTube}/TakuInternal*.apk`; each full path, version, original SHA, and native-tool output is in the app report.

## Changed files

| File | Purpose |
| --- | --- |
| `image/google-apps.lock.json`, `image/google-apps.mk`, `image/google_apps.py` | Five-package provenance/original-signature lock, ordinary system-app product integration, input/artifact validation |
| `scripts/google-apps.py` | Reviewable cache preparation/checking for pinned original packages |
| `scripts/pixel9-image.py`, `image/build.sh` | Explicit internal integration, dual read-only mounts, separate module preflight, controlled cached rebuild |
| `image/verify-artifacts.py`, `scripts/ota_release.py` | target-files, export, and old-archive compatibility; reject lost internal scope or original-package binding |
| `tests/google-apps.test.py`, `package.json`, this page | Boundary regression, check entry points, and real acceptance record |

Eleven files in total; no APKs, caches, account data, or keys were committed to Git.

## UI blockers and next steps

Independent AVD `Taku_InternalGoogleApps_API37_20261028` / `emulator-5680` had only 592 MiB remaining in its system overlay, while the five packages needed 895.39 MiB. Standard 1536 MiB userdata scratch expansion returned `ImageManagerBinder::MapImageDevice Failed to map`, leaving insufficient space after rollback. No five-package installation or preinstalled-app screenshots were produced on this path.

A subsequent standard `/data` update was attempted only for UI compatibility: Chrome 156 installed with the active APK matching its original SHA. Gmail actually returned `INSTALL_FAILED_UPDATE_INCOMPATIBLE` because SDK-preinstalled Gmail and the pinned original package had incompatible signatures, so installation of the remaining three stopped. No resigning, system-app deletion, or PackageManager bypass occurred; five icons and five launch screenshots were not obtained.

Both tests shut down their own AVD normally and retained disks, data, and logs. Hashes, sizes, and mtimes of four original SDK images were unchanged; the Case's `emulator-5590` was untouched. Evidence is in ignored `out/reviews/google-apps-internal-20261028/emulator/` and `emulator-ui-only/`. At the offline-candidate acceptance cutoff, ADB did not detect Pixel 9; this round performed no phone installation, restart, wipe, or configuration change.

Merging source into `main` retained later workspace-receipt recovery fix `815cbac` and history/new-conversation entry `be4824c`. Neither is among the archived ROM's compiler inputs; merging source does not rewrite old images or substitute for new-version ROM/device acceptance.

Next, connect/unlock Pixel 9 and verify device identity and availability before checking all five icons, app launches, and crashes. ROM installation must retain existing firmware, source, and data-preserving OTA gates. Offline image signatures, emulator installation, Pixel full-ROM boot, account features, and data retention each require separate evidence. The complete candidate remains internal-test-only; scheduled automation remains paused.
