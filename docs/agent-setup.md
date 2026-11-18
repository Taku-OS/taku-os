# Set up Taku OS with a coding agent

Identify the requested environment, inspect it, and follow the current installation guide. A command completing is not proof that the phone booted or the Agent works.

## Current release and scope

The current documented development preview is **TakuOS 1.3.1, Android 17, Pixel 9 (`tokay`), build 20261111080323**. The [release manifest](releases/tokay-20261111080323.json) is the versioned source for the downloads and checksums below.

| Artifact | Download | SHA-256 |
| --- | --- | --- |
| Full OTA | [TakuOS-1.3.1-tokay-20261111080323-full.zip](https://worker.taku.ai/os-update/v1/download/taku-os/android/tokay/releases/20261111080323-1f7d7cd84956ade1/260183030430836ecb7270118421a7fd0a0a25af9ff93dc84bba7b8ac238bf4b/full.zip) | `416f958b3f10b36091dd1ffff8450aeb86b146eaa695c69cde231c40d44ac9e8` |
| Matching recovery | [TakuOS-1.3.1-tokay-20261111080323-recovery.zip](https://download.taku.ai/releases/1.3.1/TakuOS-1.3.1-tokay-20261111080323-recovery.zip) | `07f5c84cfdac7a26bdda52318c9e68119e24015076df6328046282a1a8d8ff24` |

The [mobile installation and update guide](https://docs.taku.ai/quickstart/mobile/) contains the actual recovery, sideload and OTA steps. Follow that procedure and its exact supported device/build conditions; do not invent a second fastboot procedure. A full OTA ZIP is not a fastboot image bundle.

System update and reboot from the recorded source build have passed. Installation from stock and recovery after a failed first installation have not been accepted on this exact release. Do not label them verified or run a data-erasing step without the user's explicit approval.

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

A clean, downloadable Taku OS emulator image is **not currently published**. Do not promise an image URL or create an AVD named `TakuOS` and call it a product install. Authorized developers can use the component paths in [getting started](getting-started.md), a separately created AOSP ARM64 AVD and the module's actual installation instructions. Preserve other AVDs and their account state. An AOSP emulator with selected APKs is a component test, not complete-ROM acceptance.

For the default app experience, use a Google-enabled ARM64 API 36+ development emulator
and run `python3 scripts/install-default-apps.py --serial emulator-N` after the Taku
components. This installs Pixel Weather, YouTube Music, Taku Audio with local speech
models, and the widget providers. Bare AOSP without Google services is rejected. The
source ROM product includes these apps by default; this does not change the published
release downloads above. See [default applications](getting-started.md#default-applications).

For a phone-free first step, Node.js 22+ and an authorized checkout can run:

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
