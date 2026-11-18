# Pixel 9 test releases and system updates

Currently for internal development testing on `tokay` (Pixel 9) only. Build archiving, cloud uploading, channel opening, and physical-device upgrade acceptance are four separate states. See [Android 17 baseline](android17-baseline-2026-10-20.md) for candidate facts. Historical C/D installation records remain unchanged; missing installation-tail evidence is not rewritten as success.

**Latest result, 2026-10-25:** the 275,720-byte incremental I `20261024154635` → J `20261024174448` passed native check/download/install/reboot, formal installation evidence, slot switching, merging, and selected-data retention acceptance. The test endpoint for exact source I is connected using existing R2/Worker/CloudFront. The original D endpoint remains; stable is closed and automation paused. [Actual screenshots, scope, and evidence](reviews/takuos-1.0-ota-2026-10-25/README.md). Historical candidate states follow.

See [versioning and releases](versioning.md) for responsibilities of product versions, build numbers, and channels, and when to release `1.0.1 / 1.1.0 / 2.0.0`. I/J are actual `1.0` test builds; incremental packages do not automatically increment patch versions, and historical archive/acceptance identities are not retroactively changed.

## How developers and users obtain versions

| Current phone state | Obtaining and installing | Current boundary |
| --- | --- | --- |
| Supported Taku Android 17 baseline installed | Open the system updater in Settings, check, download, install, and reboot when prompted | Only validated increments for exactly matching source builds; real acceptance/deployment of the corresponding test channel must finish first |
| Taku installed without a matching increment | Publisher evaluates/provides a full OTA compatible with model, signature, and firmware | Full OTAs do not require one delta's exact source version, but arbitrary cross-system upgrades do not necessarily preserve data |
| Stock OS, another ROM, or no completed initial installation | Install a full baseline using independently accepted instructions for that model | Requires boot/Recovery images for the same candidate and firmware/bootloader checks; newcomer initial-flash acceptance was not completed this round |

Team members need not build the same system individually. A publisher builds and accepts one candidate, sharing its full OTA, SHA-256, version/model information, and instructions. Existing Taku users obtain everyday updates through the system updater. Ordinary app development can install APKs independently without reflashing the ROM.

**Candidate D's permanent full-OTA link is deployed and verified:** [Download Pixel 9 / tokay full OTA](https://worker.taku.ai/os-update/v1/download/taku-os/android/tokay/releases/20261021174811-bc7f8c6af00db83b/5ac2a11086b33dc4b7a224fd3e54f5e7a8ffaae734281208e0277aa8c77cfbea/full.zip). Build `20261021174811`, Android 17 / API 37, size **1,914,604,070 bytes (about 1.915 GB)**, SHA-256:

```text
e8f8e4cd418101eb14fedca3c0855c42b2d642fa91bc768a76094fc2c2e5f2cb
```

The gateway URL binds to immutable D and redirects on each visit to a currently valid CloudFront signed URL. Share the permanent address, not temporary signed query parameters. After Git Build deployed the production route, 11 HTTP checks passed; complete readback across 229 consecutive Range requests matched size and SHA. Original evidence is in local ignored `out/`: HTTP report (ignored `out/pixel9-android17/ota/cloud-publication/production-canary-router-20261022/production-http-verification.json`), SHA `580cbf5ca65c5bfa7b020625e7a52b6df794b08c83124ee6871fd61997caaaf7`; full-readback report (ignored `out/pixel9-android17/ota/cloud-publication/production-canary-router-20261022/production-parallel-readback.json`), SHA `fac70c17d4fe1ed2d744f48e094b5942344eb9d8e52620ada19128ac896b5b9b`.

D remains an unpromoted test candidate. Full-package download verification does not establish D→E native update acceptance. No production canary channel pointer has been written and stable is closed.

A full OTA is not a universal factory installer for locked stock phones. First bootloader unlock wipes user data and requires separate backup, confirmation, and execution. This update flow must not implicitly unlock, wipe, relock, downgrade, or switch slots. No initial-flashing commands unvalidated for this candidate are provided here. See [AOSP initial unlock behavior](https://source.android.com/docs/core/architecture/bootloader/locking_unlocking) and [OTA exact-source rules](https://source.android.com/docs/core/ota/tools).

## Actual D→E status this round (2026-10-23)

| Stage | Evidence obtained / current boundary |
| --- | --- |
| Full E candidate | Build `20261022160657`, frozen snapshot `5fac91c7cac6009e736faf6e583ee070c8a2b23464aed5d6f368db2edd06f7d7`; real full build exited 0; OTA/payload/AVB signatures, immutable export, and `load_baseline` recheck passed |
| D source and before | Independent full D installation record, firmware assessment, and original logs reviewed; actual `device-before` checked D identity, AVB, slot `_b`, and a new independent probe, without reconstructing missing old C→D tail evidence |
| D→E increment | Actual generation, signature checks, outer CLI, and driver for `delta-attempt-1` exited 0; complete delivery reached MacBook with per-SHA and portable `validate_delivery` passing |
| Cloud archive/channel | Independent publisher uploaded 12 E / D→E objects; cloud publication/channel acceptance not yet recorded complete; stable closed |
| Native update and physical E | v2 UI/refresh results observed on D; native ZIP download, installation, post-reboot E boot, merge, and functional regression not yet complete |

Increment size: **6,964,579 bytes (about 6.96 MB)**, SHA `3b9d3a6a22bbb78a699fba3ebdd395d0773d5bad86a3e5cc9a4a98243249f6b4`. Corresponding full E: **1,914,605,310 bytes (about 1.915 GB)**; increment about **0.364%**. These are measurements for one minimal Updater change. Future change scope/compression differs; neither 6.96 MB nor 1.915 GB is a fixed release size.

Lightweight plans and baseline/signature/input metadata are in local `out/pixel9-android17/ota/test-channel-20261022/candidate-E-20261022160657/`; complete portable delta delivery is in sibling `D-to-E-20261021174811-20261022160657/delta-attempt-1/`. Large target-files or a second full OTA were not downloaded to MacBook. Fixed bindings:

- plan.json (ignored `out/pixel9-android17/ota/test-channel-20261022/candidate-E-20261022160657/plan.json`): SHA `58b77f3c2c07c714a04a27645e97294edf658e6d0b1cf9d93b76146853e0f5a1`.
- pre-upgrade.json (ignored `out/pixel9-android17/ota/test-channel-20261022/D-to-E-20261021174811-20261022160657/device/pre-upgrade.json`): SHA `1b2c9e512cb33f77680cbffa060656e6211fea6f1f4c11be2b1c18c9b86e8dd6`.
- delivery.json (ignored `out/pixel9-android17/ota/test-channel-20261022/D-to-E-20261021174811-20261022160657/delta-attempt-1/delivery.json`): SHA `83184b9c9b2f447490afaa0a2b0ac0b3a01148833af5a71d9088179fc45b417b`.

Next complete, in order: archive/exact-source canary-feed readback, native-downloaded ZIP byte verification, observer/native-UI installation below, post-reboot E identity/AVB/slot/probe, snapshot merge, and Taku functionality/recovery acceptance. D's earlier no-update page does not prove E downloaded; whether the stream switch changed then was also unconfirmed. Judge actual requests, files, and installation evidence. Original generator/delivery `physicalDeviceVerified=false` / `dataPreservationVerified=false` remain unchanged; packaging/unit tests cannot override them. Automation remains paused. See [latest baseline checkpoint](android17-baseline-2026-10-20.md) for full-package, APK, and history-preservation details.

## Updater integration scope

Reuse pinned [Lineage Updater 6d50e0b](https://github.com/LineageOS/android_packages_apps_Updater/tree/6d50e0bf1b16fe7bca818375a3e734f4959e2e57) UI, HTTPS, OTA signature checks, and platform `update_engine`. Controlled patches change only download-integrity checks and version numbers:

- Full downloads and resumed downloads must pass feed ZIP SHA-256 and exact size checks before existing RecoverySystem signature verification.
- Resume accepts only `Content-Range` matching the requested offset and total object length; rejects truncation, excess data, full-body responses, and wrong ranges. If the initial network failure created no file, Continue can restart a complete download.
- APK uses the ROM platform certificate, `versionCode=2`, actual min/target SDK 37. Build `Updater` with Soong, not a Gradle development substitute.
- E persists `lineage.updater.uri=https://worker.taku.ai/os-update/v1/canary/{device}/{type}/{incr}`; this value was verified in E target-files. The production route is deployed and returns an empty array without a published exact-source match. E build, channel pointer, and physical-device update have separate acceptance. Temporary `setprop` and same-platform-signed APK on D serve this test only; verify actual values before/after reboot.

Feed must return a top-level JSON array directly. The pinned client reads only `files[0]`, requiring `sha256`, `size`, `url`, and `os_sdk_level=37`; `datetime` must be later than the source build, and `type` match `ro.lineage.releasetype` (`unofficial` on D). `canary` is a release channel, not release type. Server selects the one applicable delta by exact source build in the URI, never serving “latest” to every source. Initial version omits `ota_property_files`, making clients download the complete incremental ZIP, verify, and install locally.

## Building minimal E from D caches

E was already executed and completed this round. These describe the controlled entry points used; do not start another full build for later download/device acceptance.

Original D snapshot: `bc7f8c6af00db83b07d8a928fdc7283f194f1529f36e4d0d72bb27db8e8d9363`. With unrelated workspace changes, use restricted `updater-canary` derivation, not the whole current workspace or expanded `repair --overlay` scope. Only these paths are overlaid:

```text
image/build.sh
image/taku.mk
image/verify-artifacts.py
image/apply-updater-patches.py
image/updater/manifest.json
image/updater/download-integrity.patch
```

`image/taku.mk` is generated by inserting one fixed URI line into frozen D, without new workspace modules/product settings; `parentDerivedPaths` records this rule. Other D input bytes, platform/kernel locks, source volumes, and build caches remain. Patches first verify fixed Updater HEAD, every old/new file SHA, complete patch SHA, and exactly five target paths. Partial application, unrelated edits, or source drift are rejected. Reapplying the same fully applied patch is idempotent, with audits in separate output.

Run in an isolated build-host worktree. `derive` freezes inputs only; `updater-preview` modifies reviewed Updater/vendor integration in the generated source volume and builds the APK; only `rebuild` creates a full candidate. Review inputs manually before separately executing preview/full build; they must not occupy the source volume concurrently.

```sh
python3 scripts/pixel9-image.py derive \
  --from-snapshot bc7f8c6af00db83b07d8a928fdc7283f194f1529f36e4d0d72bb27db8e8d9363 \
  --integration updater-canary

python3 scripts/pixel9-image.py updater-preview \
  --from-snapshot bc7f8c6af00db83b07d8a928fdc7283f194f1529f36e4d0d72bb27db8e8d9363 \
  --integration updater-canary

python3 scripts/pixel9-image.py rebuild \
  --from-snapshot bc7f8c6af00db83b07d8a928fdc7283f194f1529f36e4d0d72bb27db8e8d9363 \
  --integration updater-canary
```

Preview independently retains container, logs, platform-signed APK, and actual signature evidence without changing D's `run-input.json`, `status.json`, or immutable archive. Failures preserve volumes/logs. Complete E verified actual target-files URI, APK bytes, platform signature, and patch audit consistency. APK SHA `ef5fc8f1de9594bd4572c2668ea5c3794b49790bce8d3a9dcb932c270fc30ba1` matches preview on D. `romBuilt` follows real full build/signature checks; `physicalDeviceVerified` remains false.

After E boots, verify active Updater APK path and full SHA against E receipt/target-files. The same-version preview installed on D may still override the system package from `/data/app`; `versionCode=2` alone does not prove E's system APK is active.

## Obtaining installation evidence from the native updater

D/E `plan`, D physical `device-before`, actual source-installation proof, and delta generation/signature verification are complete. Next, download and verify this round's ZIP in Updater. The observer **does not download, call applyPayload, initiate installation, or reboot**. Its sole sustained command is `/system/bin/update_engine_client --follow`.

```sh
python3 scripts/pixel9-ota.py observe-updater \
  --plan PLAN_JSON --plan-sha256 PLAN_SHA256 \
  --before PRE_UPGRADE_JSON --before-sha256 BEFORE_SHA256 \
  --delivery DELIVERY_JSON --delivery-sha256 DELIVERY_SHA256 \
  --serial PIXEL9_SERIAL \
  --engine-log /data/misc/update_engine_log/update_engine.CURRENT_TIMESTAMP \
  --downloaded-package /data/lineageos_updates/EXACT_FILENAME.zip \
  --updater-apk VERIFIED_UPDATER_APK \
  --updater-verification UPDATER_APK_VERIFICATION_JSON \
  --updater-verification-sha256 UPDATER_VERIFICATION_SHA256 \
  --output NEW_OBSERVER_ATTEMPT_DIRECTORY
```

Replace placeholders with this round's verified paths/hashes, not historical C/D evidence. `--engine-log` must be the current `update_engine` process's uniquely open actual regular log file, matching exact path and inode. Observer enumerates `/proc/<pid>/fd/` and records the actual fd. Do not supply old logs/rotating symlinks or treat this round's fd number as a platform guarantee. At startup, observer reads exact D identity, AVB, boot ID, probe, installed Updater SHA/version, and complete phone ZIP SHA/size. Wait for `observer-ready` with initial IDLE before tapping Install in native UI. Do not reboot until observation finishes.

Success requires actual payload application, `UPDATE_STATUS_UPDATED_NEED_REBOOT (6)`, `ErrorCode::kSuccess (0)`, and actual native-process exit 0, with unchanged boot ID/source identity/APK/ZIP/probe before and after. `kUpdatedButNotActive`, `kUpdateAlreadyInstalled`, timeout, lost connection, or exit 0 alone do not pass. Preserve independent failure directories/original logs; never manually fabricate success records.

Initial IDLE also freezes engine PID, process start time, boot ID, open-log inode, and prefix SHA. Only appended log content is then accepted, requiring exactly one actual `ApplyPayload`: real `file://` path, `payload.bin` ZIP offset, call size=0, and ordered payload properties must match the verified delta. Actual payload bytes must also match FILE_HASH/FILE_SIZE and METADATA_HASH/METADATA_SIZE. Installing a full package to the same E cannot masquerade as delta installation. Save the log suffix with evidence; existence of the expected ZIP does not prove installation source. This proves the engine's payload, not Binder caller identity, which logs lack. Provide separate actual native-Updater tap evidence; `binderCallerVerified` remains false.

Successful method is `lineage-updater`, not `adb-sideload`, and does not write `incrementalSideloadCompleted=true`. Manually reboot/unlock afterward and use the same `device-after` gates for actual E AVB, slot switch, and probe retention. Snapshot merge and Taku functionality need independent real acceptance. Unit tests, successful downloads, and an installation-complete UI do not replace these checks. Keep stable closed and build automation paused until completion.

## Later physical checkpoint, 2026-10-23

The D→E table/instructions preserve initial-delivery status. Later, actual native ZIP download, installation, ordinary reboot, E identity/AVB, `_b→_a` switch, three classes of private probe-data retention, and six snapshot merges completed. E's actual Updater v2 APK SHA matched the receipt; persistent URI and no-next-update UI were observed. See independent physical review (ignored `out/pixel9-android17/ota/test-channel-20261022/D-to-E-20261021174811-20261022160657/scoped-physical-review.json`), SHA `aaecb06c2d4ab69aa57e3976441584843746d5f5717190ddeeb290aa637c88ae`.

Actual callback order was `kSuccess(0)` then `UPDATED_NEED_REBOOT(6)`. The old observer wrongly rejected it and the real wrapper exited 1; original failure remains. Later independent review bound the unique actual delta payload, but initial engine prefix was not sealed, so a normal success receipt cannot be fabricated. Control-draft private data remained; empty-session automatic UI recovery failed. Formal canary acceptance and complete functional regression remain pending; stable closed. New observer accepts both legal terminal callback orders and writes/fsyncs prefix and identity before ready. Normal receipts must contain revalidatable sealed files. Automation remains paused.

## Next candidate: TakuOS1.0 product branding (in preparation)

The next candidate derives narrowly from sealed E inputs, unifying system product version and updater display without renaming/rebuilding old E. `image/product-branding.json` is the single source: `TakuOS` / `1.0`, deriving product properties, `ro.build.display.id=TakuOS1.0-<buildNumber>`, About phone TakuOS / 1.0, Updater header/list/install confirmation, and public full-OTA filename `TakuOS-1.0-tokay-<buildNumber>-full.zip`. receipt / baseline binding `productBranding` retains configuration SHA and exact build number. Original canonical build ZIP remains; renaming E cannot impersonate a new product candidate.

Actual Android 17 / API 37, `ro.lineage.build.version=24.0`, release type, package names, and platform-signature trust retain compatible values. Updater becomes version 3, preserving download integrity and adding a strictly pinned-source branding layer. Device header reads local `ro.taku.display.version`; update cards/confirmation use optional target-feed `displayVersion`, falling back to neutral TakuOS without guessing from the current APK. This field survives runtime/Builder, JSON, controller's existing ID merge, and Room entity. Room 4→5 only adds nullable `display_version`, preserving downloads, states, and history. 5.json is actually generated by the same Room processor and SHA-checked in the pinned-source patch. Nonblocking update copy becomes neutral “System update,” no longer claiming every package is a full package installable across arbitrary versions or fabricating client delta detection from copy.

Restricted profile: `taku-branding-v1`. `image/taku.mk` inherits frozen E and adds only the product-branding inherit line, excluding other workspace app changes. Platform/kernel locks and caches are reused. Exact ROM overlays:

```text
image/build.sh
image/taku.mk
image/verify-artifacts.py
image/apply-updater-patches.py
image/apply-product-branding.py
image/product_branding.py
image/product-branding.json
image/product-branding.mk
image/branding/manifest.json
image/branding/updater-v3.patch
image/branding/settings.patch
```

Original five-target download-integrity patch/manifest remain. Branding additionally restricts Updater to 15 targets and Settings to 3, checking full patch/before-after file SHAs and fixed HEADs. `derive --from-snapshot <E_DIGEST> --integration taku-branding-v1` freezes inputs only; preview/full rebuild require separate review before launch. Prepared code, offline patch checks, and unit tests do not establish APK build/platform signing or new-ROM acceptance. No next full candidate or branding APK installation has begun.

Suggested cloud-publisher display contract derives `product_name`, `product_version`, feed `displayVersion`, and filename from verified receipt `productBranding`; `version=24.0`, `os_sdk_level=37`, and `type=unofficial` retain compatibility roles. This work does not change Worker/feed or describe unpublished candidates as live. Initial flashing and stable gates remain separately accepted above.

## Incremental-source qualification for directly flashed baselines (I→later candidate)

I was actually installed using nine controlled fastboot images, not `adb-sideload`. New `taku.direct-image-source-installation/1` records only a current review of the existing installation plan, nine real commands/original logs, super generation/validation, and actual I postboot evidence, also requiring fresh exact-source partition contents. Historical records remain byte-identical. This path does not flash, wipe, switch slots, enable root, reset snapshots, or initialize probes.

Generate references on Mini holding the complete immutable I archive, then create the existing `plan` after complete target archives such as J pass. Uppercase placeholders require reviewed paths/hashes and fresh directories each time. Metadata-only image directories are not complete archives.

```sh
python3 scripts/pixel9-ota.py source-reference \
  --baseline I_ARCHIVE --avbtool SIGNATURE_BOUND_AVBTOOL_PY \
  --payload-proto PINNED_UPDATE_METADATA_PROTO --output NEW_REFERENCE_DIR

python3 scripts/pixel9-ota.py device-before \
  --plan PLAN_JSON --plan-sha256 PLAN_SHA256 --serial PIXEL9_SERIAL \
  --reuse-probe-anchor ORIGINAL_I_POSTBOOT_REPORT --probe-anchor-sha256 ORIGINAL_REPORT_SHA256 \
  --evidence-dir NEW_DEVICE_DIR

python3 scripts/pixel9-ota.py source-partitions \
  --plan PLAN_JSON --plan-sha256 PLAN_SHA256 --serial PIXEL9_SERIAL \
  --before NEW_DEVICE_DIR/pre-upgrade.json --before-sha256 BEFORE_SHA256 \
  --reference NEW_REFERENCE_DIR/reference.json --reference-sha256 REFERENCE_SHA256 \
  --output NEW_PARTITION_OBSERVATION_DIR

python3 scripts/pixel9-ota.py bind-direct-source \
  --plan PLAN_JSON --plan-sha256 PLAN_SHA256 \
  --before NEW_DEVICE_DIR/pre-upgrade.json --before-sha256 BEFORE_SHA256 \
  --reference NEW_REFERENCE_DIR/reference.json --reference-sha256 REFERENCE_SHA256 \
  --qualification NEW_PARTITION_OBSERVATION_DIR/qualification.json --qualification-sha256 QUALIFICATION_SHA256 \
  --direct-evidence REVIEWED_DIRECT_INPUT_JSON --direct-evidence-sha256 DIRECT_INPUT_SHA256 \
  --output NEW_SOURCE_INSTALLATION_DIR
```

`REVIEWED_DIRECT_INPUT_JSON` uses `taku.direct-image-source-input/1`. `files` contains exactly `installationPlan`, `flashResult`, `flashCommands`, and `postbootReport`, each with original absolute `path` and `sha256`. The adapter follows the original plan to review eight classes of small proofs, nine actual flash logs, and original postboot siblings, generating portable copies without rewriting history. It specifically covers reviewed I installation format, not arbitrary direct-flash log imports.

Reference first runs full `load_baseline`, checking/carrying pinned-source `update_metadata.proto` (operations field 8, REPLACE_ZSTD=14; unknown types rejected). It reads all 26 `new_partition_info` entries from the actual signed full payload, then uses `avbtool.ImageHandler(read_only=True)` bound by the same signature proof to read each original target-files image. Sparse-container SHA and expanded raw SHA are separate. Temporary space retains only the current image; successful derived copies are cleaned while failure evidence remains.

`before/2` explicitly reuses original I probe APK, UID, and three storage hashes with original three-file postboot evidence, without installation/reseeding. Partition observation reads only canonical block paths for the exact slot, from offset 0 for the signed metadata's byte count, recording actual commands, counts, SHA, and stderr without retaining raw partition contents. Firmware physical blocks may exceed images; physical tails are outside scope. Block-device type uses hexadecimal mode bits from `stat -L -c %f` (S_IFMT/S_IFBLK), not Toybox/GNU type wording. All six dynamic partitions' original mappers must be contiguous linear with exact sizes; mounted verity/COW/user maps cannot substitute for raw partitions. Device, boot ID, slot, probe, and snapshot status must match before/after. Existing opposite-slot `CREATED` records are recorded, not deleted; status must be none, without active COW mappings, and later deltas must cover the corresponding partitions.

Pass `NEW_SOURCE_INSTALLATION_DIR/installation.json` and SHA to existing `generate --source-installation ...`. Generation and portable `validate_delivery` reparse the actual incremental manifest; every `old_partition_info` and source-reading operation must match the qualified source. This does not establish acceptance of offline payload reconstruction. Delivery includes before/2 sidecars and complete private source proof, not one copied JSON. Publish only necessary version information and digests, not device evidence.

Continue through `observe-updater` above: retain the same qualified source boot, observe real initial IDLE and fsynced engine prefix, then install in native UI. Success receipts, post-reboot exact-target AVB/slot/probe, merge, and actual functionality remain independent gates. `allUserDataVerified=false` remains; source/synthetic tests do not establish I→J physical-device acceptance.
