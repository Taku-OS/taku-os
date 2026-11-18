# v73 ROM integration candidate

This is a build and acceptance record, not a published release.

The accepted source is `4c04499fe21d2a5e1b377b69f074bf0a314916f5` (Assistant 73). Its Pixel installation and UI/control checks are recorded in [the workspace acceptance](background-workspaces-design-acceptance-2026-10-27.md). Live-model cancellation and the cross-app business case retain their separate acceptance boundaries.

The new target is **TakuOS 1.1.0**, because it includes background-workspace capabilities. Updater advances to component 5; product name remains TakuOS. The accepted L source remains **1.0.1 / 20261025180734**, snapshot `39b0c2fe6dcdfdc099964f72b57bf6aad9dcd89430ec8c894665755283a9abd4`. Do not rewrite its archive or installation receipt.

## Frozen component scope

- Keep the independently signed IME **10062**, its exact APK, 24 native libraries, two symlinks, and signing identity.
- Integrate Assistant **73**, its broker, matching multi-display implementation and `phone-display`, and the Surfaces **4** source dependencies used by the accepted build.
- Keep Memory **8**, the Codex/Magpie executable locks, shared AI implementation, trust roots, platform/kernel locks and firmware unchanged.
- Keep credential-encrypted task storage waiting for user 0 unlock. Do not include device accounts, model credentials, proxy profiles, runtime tasks or private backups in the image.

## Build and verification path

1. Derive product branding from L with `pixel9-image.py derive --integration taku-branding-version-v2`. The exact accepted v4 patch is retained and migrated to v5 only after all predecessor bytes pass checks; unknown source edits are rejected.
2. Freeze a source-only packet against that derived parent. Bind every replacement/addition to its preimage, output hash, source commit, packet hash and version floors. Include the Assistant build dependency and matching display sources; do not import the entire developer checkout.
3. Use `rebuild-runtime-repair` with explicit hashes and minimum versions `{"ime":10062,"assistant":73,"memory":8}`. Reuse the Mac mini source/kernel checkpoint and compile cache. Preserve the completed container, immutable L archive, failed attempts and logs.
4. Require real full OTA/payload/AVB verification and export. The baked-component check must find the workspace broker classes, display classes, Surfaces APK and exact phone-display script, in addition to IME/shared AI checks.
5. Arrange exclusive Pixel access with the Case owner before installation or reboot. Record fresh backups and exact source installation/device-before evidence. Verify data/config retention, reboot without developer mounts, native AI, input method, workspace functionality, slot success and snapshot merge.
6. Generate an incremental OTA only after the exact source and target meet the existing gates. Retain the real installation/delivery receipt and device-after evidence. Do not promote a channel before the complete acceptance closes.

### Activating the baked apps without losing data

An existing `/data/app` update with the same version can continue to shadow its new ROM counterpart. The pinned Android package-manager source uses a strict greater-than comparison when automatically replacing an updated system app. Therefore a successful boot alone does not prove that the baked Assistant or Surfaces is active.

Do **not** remove current development APKs while the phone still runs L: its Assistant 67 base is below the installed 73 update. The pinned `DeletePackageHelper` explicitly clears `DELETE_KEEP_DATA` for a downgrade or appId change, even when the caller requested it.

Only after the new ROM has booted and its baked APK hashes match the accepted target, inspect the active and disabled-system package versions/appIds. If an update shadows the ROM, activation may remove only that system-app update, with data retention, **provided the baked version is at least the installed version and the appId is unchanged**. Keep a verified private backup and before hashes. Refuse the operation if any condition differs; never use uninstall, clear-data or downgrade as an unchecked workaround. Recheck private data/config hashes, UID, selected IME/assistant, actual system APK paths and cold-runtime checks afterward. This procedure remains pending physical-device acceptance.

Automation remains paused. Local progress, immutable inputs, build logs and private device evidence live under ignored `out/reviews/runtime-v73-rom-20261027/` and `out/pixel9-android17/`; they are not public distribution contents.

## Build checkpoint

The manual cached build started on October 27 at 22:00 (Asia/Shanghai), build **20261027140035**, container `9b7930d475cf`. The immutable runtime snapshot is `ea955f9745e5650e43113d56a0dad309bcdc75cea5ea07dac732b177c6fbc364`; the branding parent is `1a0d192f790a7580819a60112f19287d1bfe8a40e7bce3667d8f672c7fe16e7d`.

The source packet binds 69 changed/new public source files, manifest SHA-256 `ae29a08ff00cf94370102e5511ebb4e9aef62766570cd237fad0ba1cac07ce75`, packet SHA-256 `c34b1b5ffe8ac9ffc1f0455adc3171dbb3b8290107650456b6899eee12c0101c`. Runtime integration source is `ec3f1f25b70ee8af9631b1df026edb615390762e`; candidate CI [40516565376](https://github.com/Taku-OS/taku-os/actions/runs/40516565376), the full local repository verification and standalone Assistant/display builds passed. The rebuilt APK payload entries and broker/display DEX match the accepted Case builds; archive-container timestamps/signatures need not have identical file hashes.

The live incremental checkpoint verified **341** boot/kernel files, unchanged platform/kernel locks, preserved compile cache, no source resynchronization and no kernel rebuild. At this checkpoint the container is running in `compile`/Soong rule generation. This is not complete-image, cold-boot or incremental-OTA acceptance. Pixel has not been operated in this build task; the Case owner retains device access.

### Product-selection correction

Read-only inspection of the first attempt's installed APKs found IME 10062, Assistant 73, Memory 8 and Updater 5, but no baked Surfaces APK. Its inherited L product list did not select `TakuSurfaces`; copying component sources and linking its client library did not select the application for installation. The artifact gate requires the missing component and must reject that candidate. Preserve the first attempt and derive a corrected source packet that adds only `TakuSurfaces` to the inherited product list, with an exact product-file preimage and output hash. Reuse the checkpoint through controlled `rebuild-runtime-repair`, not a mutable snapshot or a reduced verification gate.

### Corrected full candidate

The corrected build **20261027143926** started at 22:39 (Asia/Shanghai) and completed compilation/packaging in **30 minutes 20 seconds**. Container `a15362caf7e1` exited 0. The mandatory baked-component gate, actual full OTA/payload/AVB signature verification and immutable export all passed. Candidate source `3adea2a2a2bad0480a556d780f7d723f3f9dd695` passed [CI 40522441432](https://github.com/Taku-OS/taku-os/actions/runs/40522441432). The failed first container and inputs remain preserved.

The corrected snapshot is `0264202b91a907422aead356e699abf0da8e37bf7e389be0bd9ad9bb68a6ed52`. Its 70-file source packet binds manifest SHA-256 `870cc3dda934a395cae3d32795e01a622b142bb381c1a1bd5f668742ed675d03` and packet SHA-256 `239713f76a5a74a984def7958015ee9d99b785c1bff0169fac41dc3252348ad8`. The checkpoint again verified all 341 boot/kernel files with no source resync or kernel rebuild.

The Mac mini archive is `out/pixel9-android17/artifacts/20261027143926-0264202b91a90742/` in the owned release worktree. The full recovery/distribution package is **TakuOS-1.1.0-tokay-20261027143926-full.zip**, **1,961,632,268 bytes**, SHA-256 `706fb0e8ead9a609ffa02fabca4c78b0343a2f13cce757b10a6cf9931ce29fc6`. Its target-files SHA-256 is `8978fe95bf0820d781260c66e24dd42cc216ccd3c257fcf9d4403e5630da81f4`; receipt SHA-256 is `2a19f4f792bd56651f2d8cf314e2defb23efd96ab0ccc5002800f720321b7b80`; signature report SHA-256 is `1fcf00c8d0b6809dc3f40bc98ee1a85f16244bfcd3613961bb96821cc7d172b9`.

The actual target-files report verifies 12 baked components, including Assistant 73, Surfaces 4, IME 10062 and Memory 8, matching display/broker code and the locked AI backends. This is complete-image acceptance, **not** physical-device or incremental-OTA acceptance. The new Chat UI candidate remains outside this immutable build, even if its development versionCode also reads 73; identify accepted source and APK bytes, not versionCode alone.

After the Case owner handed off the phone, a fresh read-only observation matched its exact L build/AVB measurement, the accepted v73 APK hash and broker hash, and three idle workspaces. Plan `252aca0a968fc30840677d8337c8b5c2bcd9f3e8f2d2657ce202043529bd5350` binds the actual installed L baseline to this corrected candidate. Private backups, fresh probe evidence, delta generation and device-after verification remain separate gates; none are implied by creating the plan.

## Pixel and incremental acceptance

The exact **1.0.1 / L → 1.1.0 / 20261027143926** incremental package passed generation, OTA-package and payload-signature validation. It is **7,540,917 bytes**, SHA-256 `ef23a032587fc58d8d8e8f92e811da7dba1974a50da8b88b8033d826b0096596`; delivery receipt SHA-256 is `e6bb86b80eeccc3f4beec0f8f012ffac7c6f8eacdebb0bf3bee2a5e539794961`. All **11 firmware files** were byte-identical to accepted L. The source installation receipt and a fresh, independently initialized probe were validated before generation. A private selected-data safety copy of 2,502,006,540 bytes was verified readable; this live copy is not an atomic database snapshot.

Real Pixel USB incremental sideload completed successfully (`Total xfer: 1.00x`, exit 0). Installation receipt SHA-256 is `0ab677a9199bebbdff1149147df7c8a845ec585f362dc79dcac8b4b278e6194f`. The phone booted target slot **b** with AVB digest `bd9d0afc51aacd0d2b6f67be9acd9eaecbe20bca5bd7d25d5d4034d02b11411f`. The independent probe's preferences, SQLite database and private file, selected package UIDs, default IME/assistant/accessibility settings, and private AI/account/proxy configuration hashes were preserved.

Existing Assistant 73 and Surfaces 4 updates initially shadowed the new system APKs. Their baked and updated versions/appIds/signers were verified equal, target APK hashes authenticated, and only those system-app updates removed with keep-data semantics. No clear-data, version downgrade, user removal or runtime bind restoration was performed. A **second normal reboot** then verified all 12 baked components, the 24 IME native libraries/two symlinks, four init services, read-only backing mounts and preserved AI/IME/Memory policies. Snapshot merge completed, the target slot was marked successful, and the same boot remained stable through a 123-second observation.

The first postboot comparator incorrectly required Memory 8's rebuilt system APK to keep its old APK hash. All 22 Memory source files are unchanged, while its statically linked, accepted Surfaces contract changed. The original failed observation is retained. The explicit reconciliation instead requires Memory's actual baked bytes to match the authenticated target, while unchanged selected app APK bytes must still match the source; selected data and policy checks remain intact.

Actual postboot checks passed for the shared AI settings UI, a live text-model request using the existing account, real Chinese pinyin/candidate input, and a live image-model response reaching the IME and inserting into a synthetic editor without sending a message. Pending-row cancellation, secure-window rejection and password-field suppression passed. System update and About phone both rendered **TakuOS / 1.1.0**; the baked workspace management page opened. Screenshots and exact evidence remain private under `out/reviews/runtime-v73-rom-20261027/installation-N/`, with the combined result in `assembled-verification.json`. Newly installed test helpers were removed; existing fixtures and the OTA probe remain.

This closes the exact incremental USB OTA and **selected-data** acceptance for this device. It does not prove all userdata, a new cross-app business case, live-agent cancellation, cloud/Updater delivery, stable-channel publication or production readiness. Workspace references survive reboot but need explicit rebind; tasks are not automatically replayed. Automation remains paused, and phone ownership is returned to the Case owner separately.
