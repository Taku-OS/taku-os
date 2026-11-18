# Default Google capabilities

The new TakuOS image baseline is Android 17 / LineageOS 24 + MindTheGapps ARM64. The product name remains TakuOS; adding Google system capabilities is a minor release, version **1.2.0** (Updater 7). The first incremental OTA adding Google components to Pixel 9 and selected data-retention acceptance are complete; Google-account login, store downloads, and channel publication still require separate acceptance.

Defaults include Play services, Play Store, Google Services Framework, account/restore, and contact/calendar synchronization components, retaining upstream permissions, APEX, and overlays. User apps such as Gmail and Google Maps can be installed later from Play Store. Original Google APKs/APEX retain their signatures and are not resigned as Taku platform packages; accounts, tokens, and device data are not build inputs.

## Sole dependency and build entry point

The [dependency lock](../image/google-services.lock.json) pins the upstream repository, commit, SHA-256/size/executable bits for 97 native inputs, and files to install. Google binaries reside only in ignored `out/dependencies/google-services/`, outside Git. Upstream upgrades require renewed review of input locks, permissions, APEX, and installation output, rather than changing only a commit or following a floating branch.

```sh
python3 scripts/google-services.py fetch
python3 scripts/google-services.py verify
```

The builder binds dependencies matching the frozen snapshot read-only to `/src/vendor/gapps`, verifying both before startup and inside the Linux build. Missing, modified, or extra files cause failure rather than silently generating a new image without Google. [Product inheritance](../image/google-services.mk) uses upstream native `inherit-product`. It does not copy loose “three Google packages,” install a Recovery ZIP, or generate `30-gapps.sh`.

Controlled integration starts from the frozen branding/runtime parent snapshot: use `taku-branding-version-v2` to update the version, then `taku-google-services-v1` to add explicitly allowed Google files, and finally reapply the accepted v73 source-repair package. Preserve the original strict runtime verifier, IME lock, platform/kernel locks, and component bytes; do not import the entire current development tree.

Historical non-Google snapshots and 1.0/two-part version archives retain read-only compatibility. Receipts, run-input, native input locks, and target-files for the new Google baseline must bind to one another; subsequent OTAs must not silently remove Google integration.

## Taku defaults

Upstream GmsOverlay selects Google Assistant by default. Taku uses a separate static, platform-signed `system_ext` RRO directing `config_defaultAssistant` and `config_forceVoiceInteractionServicePackage` to the new desktop `org.agentos.agent`; other Google resources are unchanged. Android partition priority overrides the Google RRO in product. If a new `overlay/config/config.xml` appears, stop and review explicit enablement/ordering first so static manifests are not ignored.

The current product's [system-entry policy](../image/home/README.md) unifies HOME, ASSISTANT, and voice service on Taku Agent at first boot, upgrade, and loss of binding. Default/enabled input methods, `device_provisioned`, and `user_setup_complete` are unaffected; first-run setup is not forcibly repeated. Check actual final defaults separately for fresh installations and upgrades retaining existing data. Historical acceptance below applies only to that frozen image and does not establish ROM acceptance of the new entry policy.

## Image and physical-device acceptance

Image gates verify bytes of native Google APKs/APEX and permission files, APK/APEX PRESIGNED metadata, supporting resources, and the compiled Taku default-assistant RRO. Actual outputs of Soong-processed libraries/JARs are recorded separately without claiming byte-for-byte equality with original inputs. Evidence is `google-services-artifacts.json`, archived with immutable full OTA/target-files; `load_baseline` rechecks actual archive bytes. This is host-side image evidence, not proof that Google works on the phone.

For the existing Google-free Pixel 9, first save selected data/configuration, source version, and slot, then verify this non-wiping upgrade:

1. Check that no old externally installed GApps addon restore scripts interfere with target partitions; pin exact source/target images and the incremental package.
2. Do not wipe/reset or alter provisioning flags. Restart after installation, then check APEX activation, actual GMS/store/framework packages, and crash logs.
3. Confirm retention of Taku assistant, input method, AI backend configuration, apps, and selected data; validate the Google-account entry, login, and store download. The user must personally complete credentials and authorization during login.
4. Use a second Google-enabled version to verify another data-preserving OTA, APEX, and account retention. Connect the test channel after all checks pass; stable-channel acceptance is separate.

Lineage's official instructions explicitly require a factory reset when installing GApps through Recovery after the system has booted. Native build integration does not automatically erase data, but this does not prove that the first nondestructive migration of an existing system will succeed. If migration fails, retain failure information and data without clearing the phone unilaterally.

Google capabilities do not mean Google Wallet NFC payment certification. Custom ROMs and unlocked bootloaders remain subject to Google's device-integrity requirements. MindTheGapps development/test integration does not automatically grant permission to bundle and distribute GMS in a formal product; confirm that separately before public product distribution.

## Pixel 9 acceptance record, 2026-10-28

Frozen image source was `83fde2b62af18ef7ae41ea02f952139426a3ea3b`, target build `20261027185641`, product version TakuOS 1.2.0. These results apply only to that image and do not replace physical-device acceptance of later merged conversation/floating-window changes.

- The native system updater verified and installed a real signed incremental package; the engine explicitly returned `kSuccess` and `UPDATED_NEED_REBOOT`. Installed version, AVB measurements, and physical-device identity matched the target. Two normal restarts passed, snapshot merging completed, and the boot slot was marked successful.
- No data was wiped. An independent probe's preferences, SQLite, and private files survived; so did UIDs for 12 selected packages, APKs of 8 existing apps, checksums of 3 private AI/account/proxy configurations, and 3 workspace references. This does not cover all user data.
- GMS APEX, Play Store, Google Services Framework, account authentication entry, and the Google login page were verified on the physical device. The Taku default assistant, default input method, and original input-method subtype were retained; Google's voice service added only one optional input method. Original settings-equivalence diff reports and verification of exact additions are retained; phone settings were not rewritten to eliminate differences.
- Although the first automatic Recovery restart attempt had an ADB transfer-success record, it actually booted the old system and was not counted as upgrade success. Later native-updater installation and restart evidence is recorded independently.

The private acceptance summary is in ignored `out/reviews/gapps-default-20261028/installation-P/assembled-verification.json`, SHA-256 `e6491456c5eba651612c1ea8d04c55ecc253530d95581ad0e0dc93a6d2da7768`; its 21 referenced evidence items were verified on the MacBook and synchronized to the Mac mini. Backup, account, and configuration contents do not enter Git.

Actual Google-account login, store downloads, Wallet certification, account-preserving upgrades between two Google-enabled versions, and public-channel publication are outside this accepted scope. Android 17 automation remains paused.

## Upstream basis

- [LineageOS Google Apps instructions and Android 17 ARM64 downloads](https://github.com/LineageOS/lineage_wiki/blob/main/pages/gapps.md)
- [Pinned MindTheGapps native inputs](https://github.com/MindTheGapps/vendor_gapps/tree/822ff6a805533792ae43a9c3f2a159222da22a6f)
- [Android RRO configuration and priority](https://source.android.com/docs/core/runtime/rros)
- [Google Wallet device requirements](https://support.google.com/wallet/answer/12200245)
- [Google Mobile Services](https://www.android.com/gms/)
