# Set up Taku OS with a coding agent

Identify the requested environment, inspect it, and follow the current installation guide. A command completing is not proof that the phone booted or the Agent works.

## Current release and scope

The current documented development preview is **TakuOS 1.4.0, Android 17, Pixel 9 (`tokay`), build 20261118070714**. The [latest release manifest](releases/latest.json) ([`tokay-20261118070714.json`](releases/tokay-20261118070714.json)) and [GitHub Release](https://github.com/Taku-OS/taku-os/releases/tag/v1.4.0-20261118070714) are the versioned sources for the downloads and checksums below.

| Artifact | Download | SHA-256 |
| --- | --- | --- |
| Full OTA | [TakuOS-1.4.0-tokay-20261118070714-full.zip](https://worker.taku.ai/os-update/v1/download/taku-os/android/tokay/releases/20261118070714-22cb53203be9da8f/fbd50dcafa988357bce12a5e4e0e57879d127f898482d69d6bac913a552fdebe/full.zip) | `9812a62734f6fe1512cc630c332b1d5338f2e166889e8fbf19a7c146b68bbed3` |
| Matching recovery | [TakuOS-1.4.0-tokay-20261118070714-recovery.zip](https://github.com/Taku-OS/taku-os/releases/download/v1.4.0-20261118070714/TakuOS-1.4.0-tokay-20261118070714-recovery.zip) | `3a04cd4054418f5fa26d6588876e5d7d074c6a3eaa617fae38adee5a162d2b5a` |
| ARM64 emulator image | [TakuOS-emulator-arm64-images.tar.gz](https://github.com/Taku-OS/taku-os/releases/download/v1.4.0-20261118070714/TakuOS-emulator-arm64-images.tar.gz) | `1f086efaa4e658079ae83366af4cecb942a11b735964454b7a637caedbf3323d` |

The [mobile installation and update guide](https://docs.taku.ai/quickstart/mobile/) and [GitHub Release notes](https://github.com/Taku-OS/taku-os/releases/tag/v1.4.0-20261118070714) contain the exact recovery, sideload and OTA steps. Follow that procedure and its exact supported device/build conditions; do not invent a second fastboot procedure. A full OTA ZIP is not a fastboot image bundle. **Never run `fastboot flashing lock` after installing a preview build** (preview builds use public AOSP development keys without custom AVB key enrollment, so re-locking the bootloader prevents booting).

Check [`verification`](releases/latest.json) in the release manifest for exact per-build verification status (`firstInstallFromStockVerified` and `nativeUpdateAndRebootVerified`). Do not label unverified paths verified or run a data-erasing step without the user's explicit approval.

## Inspect before changing anything

Read the user's existing authorization. Ask only for missing target information or a destructive step not already authorized. Use an explicit serial for every device command; never select whichever device happens to appear first.

```sh
adb devices -l
adb -s SERIAL shell getprop ro.product.device
adb -s SERIAL shell getprop ro.build.fingerprint
adb -s SERIAL shell getprop ro.taku.product.version
adb -s SERIAL shell getprop ro.build.display.id
```

For the downloads above, the device must be `tokay`. If it is another model, stop the physical-device path. Preserve data and current partitions. Before an approved install, verify the downloaded bytes against the release manifest, show the planned commands and explain any unlock or erase step. A mismatch stops installation; retain the failed download for diagnosis rather than overwriting existing evidence.

For an existing compatible Taku installation, prefer the system updater procedure in the mobile guide and verify its declared source/target builds. Do not treat a modified boot partition as an unmodified incremental OTA source.

## Emulator and source development

On an **Apple Silicon Mac (M1+)** or **ARM64 Linux host with KVM** and Android SDK (`emulator` + `platform-tools` installed; x86_64 hosts cannot hardware-accelerate this ARM64 image), download, verify, unpack, and boot the published open-source Taku OS ARM64 emulator image in an isolated AVD directory without modifying existing AVDs:

```sh
curl -fLO "https://github.com/Taku-OS/taku-os/releases/download/v1.4.0-20261118070714/TakuOS-emulator-arm64-images.tar.gz"
echo "1f086efaa4e658079ae83366af4cecb942a11b735964454b7a637caedbf3323d  TakuOS-emulator-arm64-images.tar.gz" | shasum -a 256 -c -
mkdir -p taku-emu && tar -xzf TakuOS-emulator-arm64-images.tar.gz -C taku-emu
python3 image/emulator/start.py --images taku-emu --avd-home ~/.taku-avd --name TakuOS_1_4_0 --port 5580
```

Developers building individual modules from source can also follow the component paths in [getting started](getting-started.md) on a separately created ARM64 API 36+ AVD. Preserve other AVDs and their account state.

For the optional Google-enabled default app experience on a custom development AVD, run `python3 scripts/install-default-apps.py --serial emulator-N` after the Taku components. See [default applications](getting-started.md#default-applications).

For a phone-free first step, Node.js 22+ and a local checkout can run:

```sh
env -u TAKU_OS_TRANSPORT -u TAKU_OS_SERIAL node sdk/cli/taku.mjs call methods.list
```

## Verify and hand back

After a supported installation, wait for `sys.boot_completed=1`, recheck the exact product/build, confirm the active APK paths, and test the specific user path. For the SDK, run:

```sh
node sdk/cli/taku.mjs call system.info --transport adb --serial SERIAL
```

Report which layers passed: download/hash, installation, boot, UI, actual model call and requested plugin/task. Use the device's account connection UI; do not put passwords, tokens or sign-in state in the repository or evidence. Reuse an existing authorized connection where possible. Do not claim an online model works merely because an account is configured.

If boot, checksum or identity checks fail, preserve the output and follow the documented recovery route for the exact target. Unknown results are not permission to erase data or try another device's images.
