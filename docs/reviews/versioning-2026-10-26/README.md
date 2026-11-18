# Three-part product versions and historical-archive compatibility

2026-10-26: **TakuOS 1.0.1 / Updater 4 passed actual full USB OTA, reboot, version UI, and selected-data/config retention on Pixel 9**. Name/version display separately; this is not J→L incremental acceptance or channel promotion. Source, historical-contract, build, and device stages remain recorded. [Versioning](../../versioning.md); [original I/J acceptance](../takuos-1.0-ota-2026-10-25/README.md).

## Separate name and version

`productName=TakuOS` and `productVersion=1.0.1` are independent. New `taku.product-branding/2` writes strict three-part versions; historical two-part values are read only through old receipt contracts. `ro.taku.product.name` / `ro.taku.product.version` generate separately; Android 17/API 37/Lineage compatibility fields retain their own meanings.

Updater title is `TakuOS`, with separate `Version 1.0.1` (or its Chinese localization) and Android version. Target/confirmation text handles old/new labels and combines `TakuOS 1.0.1` when needed. Existing Settings already separates name heading/version property and is reused.

Machine `displayVersion` remains spaceless `TakuOS1.0.1` for properties/receipts/feed binding, no longer the large title. Old v3 eligibility still uses Android/Lineage; unrecognized labels fall back to product name without blocking existing OTA checks. Updated components display old two-part/new three-part labels.

## Old archives and new targets

- Original `taku.product-branding/1` validates original two-part version, Updater 3, derived fields, without receipt migration.
- New v2 binds three-part product version and Updater integer version to exact APK/signature report/manifest/source evidence. Component versions may rise independently; APK number is not product version.
- Compare old `1.0` as `(1, 0, 0)` while preserving original strings; `1.10.0 > 1.9.0`; reject leading zeros, extra parts, prerelease suffixes, nonstrings in new versions.
- OTA plans/device binding add product anti-downgrade; retain build-time/number, rollback, model, partition, signature, provenance, AVB gates. Separate test builds of one product version may still form plans.

Mixed archive/device/native-observer tests explicitly use stub crypto/fake ADB, proving contracts rather than real `1.0 → 1.0.1` installation.

## Cached-source migration

Generator retains accepted v3 complete patch/pinned manifest, SHA-256 `47a7c2bebbc46c5ad002a0bdf8d2054fe86f12e6a0fba46fb4377d8064894d30`. For v3 volumes, verify every listed Updater/Settings file/pinned HEAD, rehearse on temporary source, reverse exact old layer, apply new layer, verify per-file hashes. Reject unknown/partial drift before writes and preserve unrelated source.

Old upstream SVG trailing spaces remain in exact reverse preimages; new forward patch uses `--whitespace=error`. Git whitespace warnings are disabled only for branding patches storing literal diffs; patch/result hashes remain checked.

Migration changes source only, deleting no source volumes/builds/old images. `product-branding-migration.json` is a preserved, optionally archived source-migration record, not device upgrade. Old v3/new v4 share update-database schema 5, with no new destructive runtime migration.

## Results

| Check | Result/scope |
| --- | --- |
| `npm run verify` | Full source checks passed: branding/patches, archives/OTA, provenance, device evidence, display, existing checks |
| Generated Kotlin helper | Actually compiled/executed using local Kotlin 2.3.21; property doubles cover old/new labels, separate name, fallback, invalid inputs; no compiler-missing skip |
| Android resources | AAPT2 36.0.0 compiled added English/Chinese resources; not full-APK/system-UI screenshot acceptance |
| Source patches | v1→v2→v4, exact v3→v4, repeated application, unknown-drift rejection, existing SQLite preservation passed |
| Old/new targets | 1.0/1.0.1 archives/plans/device bindings/Updater 3/4 exact evidence passed; wrong contracts/downgrades rejected |
| Actual I/J archives | Mac mini `load_baseline`, native v3 evidence, I→J plan/binding recheck passed; original receipt/metadata hashes unchanged |

Actual archive bindings:

| Build | Original product version | baseline receipt SHA-256 | target-files SHA-256 |
| --- | --- | --- | --- |
| I `20261024154635` | `1.0` | `df604f959c6fe5d1dc35c45681890026e9b2b74d69bf638eaca11f8ce3bb6566` | `f80507c35c02296566bbadc5261804bb022594cc6589ecc6d82262a83085af56` |
| J `20261024174448` | `1.0` | `ade8815554ae76473b04c149d3df02a1b2e54a66bf814d5ab85092977c56c600` | `e355e9bb1c861185dd1a7dd15255fcc380ff36757bd06557b7f23144633097a6` |

Raw logs/receipts with validator hashes remain on both hosts under ignored `out/reviews/product-version-20261026/`, without private directories/account config/images committed.

## Validator compatibility issue found in later actual build

First 1.0.1 build `20261025172855` compiled/packaged full OTA, but shared APK-byte validator accepted only Updater 2/3 and rejected valid 4. Failed container, inputs, logs, unaccepted image retained; artifact is not a signature-accepted/installable archive.

Fix explicitly permits integer 2/3/4 while exactly checking package/version/API 37/unique platform certificate/APK hash/verified source contract; unknown versions, floats, wrong versions/certificates remain rejected. Restricted profiles inherit the fix and frozen inputs/system-AI gates. Regression directly runs the shared validator with labeled host-output/certificate doubles; real rebuild/APK/full-package signatures/export still require separate evidence.

## Corrected L full-package checkpoint

L `20261025180734`, control `8d405b6`, frozen inputs `39b0c2fe6dcdfdc099964f72b57bf6aad9dcd89430ec8c894665755283a9abd4`, changes only shared APK validator from failed K. Cached continuation, real Updater 4 platform signing, system-AI gates, full OTA/payload/AVB signatures, immutable export passed without platform resync/kernel rebuild. Candidate/main CI `40219390396` / `40219668388` and full local `npm run verify` passed.

| Artifact | Bytes | SHA-256 |
| --- | ---: | --- |
| L full OTA | 1,961,595,782 | `45b3d8f528665cb8fe93531336ae12440b308639034e01ad29d8e74c9c4f9e17` |
| L target-files | 4,749,159,608 | `511b31e54f719bf9f2d86b219a7f93167f9cc98447df8058f0b28288e36a16e8` |

Mac mini `load_baseline` reread actual J/L full archives, generated single-version USB binding and J `1.0`→L `1.0.1` plan successfully. Three-part numeric comparison retains contracts/trust/platform-kernel locks/partition/rollback gates; all 11 RADIO files byte-identical. At that point only static upgrade conditions passed; later full-device results follow, with no delta generated for this pair. [Candidate results/receipt/report SHAs](image-candidate.json).

L retains J frozen IME/Assistant/Memory `10062 / 67 / 8`, Updater 4. Independent cross-app Case APKs, temporary Broker/display overlays, later repairs were not automatically integrated. Before upgrade, inspect actual current components/tasks/firmware/mounts, save latest data/config, preserve matching development overlays; old records must not roll back newer data.

## Actual L full USB OTA acceptance

After explicit device release by cross-app Case, reread J boot measurements/components/all loaded turns/requests and saved approximately 1.62 GB of selected app/account/conversation backups. Archive was readable but is a live-data copy, not a transactional database snapshot/full-device backup. Reconfirmed no active turns before installation; no data clearing, old-account restoration, unlocking, or AVB-policy changes.

After full-byte SHA recheck on MacBook, complete L installed via `adb-sideload`, exit 0, retaining full tail logs/formal receipt. Device rebooted J `20261024174448` slot b→L `20261025180734` slot a, AVB `1f9330fed9523ff590beedb15563dad3a727c72d0324075ee906b7d9d7a84a57`. Snapshots naturally merged, slot successful, no additional reboot in 124.8-second sampling.

All 18 final checks passed: original probe preferences/SQLite/private files, selected UIDs/APK bytes, IME/accessibility/assistant settings, private AI/auth/proxy hashes, actual Updater 4 matching archive. Updater shows `TakuOS` and separate `Version 1.0.1`; About shows `Android version 17` and `TakuOS / 1.0.1`; Home opens. Read-only `model/list` returned 7 models without tasks/generation. Initial `state.ready=false` preceded lazy initialization; original observation remains, with later initialization/recheck separate, not rewritten as first-pass success.

Existing Assistant 71 / Surfaces 4 data APKs and matching Broker/VirtualDisplayHost overlays remain; independent Case candidates not silently merged. Both JAR mounts are **temporary overlays for this boot**, not L-image content, with no persistent boot hooks. Next reboot needs matching overlays or an authorized integrated image. Real reservation was not duplicated; no WeChat message sent; this version acceptance does not establish complete cross-app Case success.

[Device results/raw evidence digests](device-acceptance.json). [Physical updater screenshot](updater-1.0.1.png) contains version UI only; account data/backups/raw runs/device-identifying images remain ignored/private.

## Later incremental/channel acceptance

1. Independently generate/deliver/accept an actual delta for this pair; full USB success does not replace delta evidence or bypass provenance gates.
2. Check new cloud metadata/feed/native download-install, then use authorized channels after their gates pass.

Original I/J canary unchanged, stable unopened, Android 17 automation paused.
