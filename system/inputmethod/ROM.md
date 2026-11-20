# ROM-preinstalled input method and shared AI

The input method remains at feature version 0.4.0. The assistant migration increases the ARM64 keyboard candidate to version code 10072 so Android can supersede existing code-10062 updates. Input-method settings, model choices, and source allowlists remain in each app's data directory. System model configuration and subscription sign-in remain in device-private data.

## Build inputs

`TakuInputMethod` uses `android_app_import` to preinstall into `system_ext/app`. It retains its independent signature and UID, without platform signing, shared system UID, or network permission. Assistant, Broker, Memory, and the Magpie startup wrapper still build from existing Soong product modules. IA_SETTINGS metadata supplies the system-settings home entry without a separate Android Settings modification.

```sh
TAKU_IME_ROM_PREINSTALL=1 bash system/inputmethod/build.sh
# Accept only the reviewed artifact identified by rom-input-lock.json.
python3 system/inputmethod/prepare-rom.py
python3 system/inputmethod/prepare-rom.py --verify-only
```

`prebuilt/TakuInputMethod.apk` is not committed. `rom-input-lock.json` pins APK SHA-256, signing certificate, release version, and source patch inputs. Snapshot creation explicitly includes the ignored artifact. Source changes or different rebuilt artifacts require review of a new release lock; old hashes cannot be reused.

The keyboard's **Taku AI 系统设置** button opens `org.agentos.action.AI_SETTINGS` in `org.agentos.agent`. The reply bar's account button uses the same action with `section=account`, which opens the account page of those settings. Older keyboard APKs reach Expo through the backend's forwarding activity. The ROM lock remains bound to code 10062 until the key owner signs code 10072 with the original IME signing certificate and the new artifact and source hashes are verified. The key owner can sign the aligned APK in place on their signing host. The standalone development APK uses a separate certificate.

On 2026-10-31, the ROM variant was rebuilt from main `f15eec1` patches and signed locally on the MacBook Air holding the original key. APK SHA-256 was `3722bdc2518fb5744819b481bed2f7b505bfc8b089949a213142e40b3484b1ca`, with the same certificate as the existing ROM lock. Source/submodule verification, release build/unit tests, APK v2/v3 signing, package identity, 24 JNI entries, CRC, and 16 KiB alignment passed. It was not installed on a device in that run. Release code remained 10062; whether a same-version data-partition update shadows it at runtime requires separate device acceptance.

The ROM variant uses ZIP_STORED for 24 JNI entries, 16KB alignment, and explicit `extractNativeLibs=false`. Its independent certificate, business bytes, and 0.4.0 functionality remain unchanged. Soong uses `presigned` and `preprocessed` without skipping preprocessed-APK checks. Ordinary standalone APKs retain upstream native-library extraction packaging.

Fcitx scans plugins through `applicationInfo.nativeLibraryDir`, and dictionary conversion executes `libscel2org5.so` there. Making APK JNI entries mmap-compatible is therefore insufficient for preinstallation. Twenty-three DSOs install through `cc_prebuilt_library_shared` into private `/system_ext/lib64/taku-inputmethod`; the PIE tool uses `cc_prebuilt_binary` under `/system_ext/bin/taku-inputmethod`. Two standard `install_symlink` entries reconnect the app's `lib/arm64` directory and tool path. Native modules preserve original names, enable ELF checks, and explicitly bind actual DT_NEEDED dependencies; there is no bypass switch for copied ELF files.

Staging checks signatures, manifest, offline permissions, CRC, exact JNI membership, byte hashes, ZIP/ELF 16KB alignment, and actual dynamic dependencies, deriving native inputs from the APK. Snapshot creation explicitly seals all 24 native files with the APK; none are committed. Memory's `taku-surfaces-client` compile dependency comes from client/contract/wire subpackages and needs only four Java files. It does not require the Surfaces runtime or unfinished UI in this ROM.

## Image verification and startup

`image/build.sh` runs a dedicated gate before marking the image successful:

```sh
python3 system/ai/verify-rom.py path/to/target-files.zip \
  --aapt2 /path/to/aapt2 --output /path/to/system-ai-rom.json
```

Checks cover the pinned IME bytes, the Assistant and Agent pair's package names, version codes, signing compatibility, Manifest entries and DEX definitions, and the Memory version. They also cover Broker shared-AI classes, native files and symlinks, startup files, and exclusion of private runtime state. Verify cold startup and real suggestions on the booted device.

Existing `taku_broker` and `taku_magpie` init services start at `sys.boot_completed=1`, retaining userdebug's su domain. This change only creates the root-owned 0700 AI configuration directory; it adds no production SELinux domain or elevated IME permissions. Startup wrappers restore loopback-gateway UID rules and use existing phone sign-in from the original `CODEX_HOME`. ROM contains no account tokens, provider keys, management keys, or preset personal model configuration.

## Reboot acceptance

After reboot, do not run development `deploy-ai.py` or repair mounts to supply missing services. Verify these facts before real-model and keyboard regressions:

- Active IME, Assistant, and Memory APKs come from read-only `system_ext`, with versions matching image artifacts; IME remains default.
- Broker/Magpie wrappers have no shadowing file bind mounts or overlays, match new-image receipts, and start through init independently.
- Global SELinux remains Enforcing. Configuration directories/files retain root 0700/0600, and prior system models/app switches persist.
- IME and Memory follow system image/text models. Real image requests return strict JSON in the original candidate bar; ordinary tap fills directly, and typing cancels.
- Boot gateway permission rules work. Temporary test sources, rotation, theme, and test APKs are restored/cleaned afterward.

Old temporary-mount acceptance does not establish this ROM's cold-start success. The OTA session schedules the sole installation/reboot window, then hands off to input-method acceptance.
