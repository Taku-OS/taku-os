# Shared environment reference

[Back to pitfalls](README.md)

This page describes the environment behind these pitfalls. On it, an agent on an AOSP emulator wrote Java source, compiled, packaged, signed, installed and opened an app on a background screen.

The test app is a native counter. One tap takes it from 0 to 1, and the value is still 1 after force-closing and reopening. This is an emulator result and does not apply to physical devices or IoT devices.

Device names, serials and directories below are examples. Replace them with your own.

## Verified environment

| Item | Verified version |
|-|-|
| Host | Mac M5, 32 GB, standalone SDK and JDK 21 |
| Android base | Google AOSP Android 16 / API 36 / ARM64, `userdebug`, `system-images;android-36;default;arm64-v8a` revision 2 |
| Taku components | APK, task service, display, init and assets built from one commit |
| Agent | Codex App Server, with ChatGPT login completed separately on the device |
| Compile and packaging | ECJ 3.33.0, D8 and apksigner from SDK build-tools 36.0.0, Termux Android ARM64 aapt, aapt2, zipalign and shared libraries |
| Test app | `org.taku.explore.compilercheck20261016`, an ordinary app UID with no extra permissions requested |

The components were installed on an existing AOSP emulator. No Pixel was flashed and no full ROM was rebuilt.

The scope is Android framework and small native APKs on Java 16 or lower. React light app hosts, Node or API services, and arbitrary Gradle or AndroidX dependencies were not set up in this environment.

## Reproducing on a prepared environment

A clean checkout cannot do this directly. See the next section for first-time setup.

1. Start the emulator and wait for Android to finish booting, with the task service running and Codex installed. Background screens are created on demand, so seeing only the main screen after boot is normal.
2. Send the request below in the Taku agent. Model generation needs network access and uses the Codex quota of the account signed in on the device.

   > Using the installed app development skill, build a native counter in the emulator. Show a number and a "+1" button, keep the number after closing and reopening, and request no extra permissions. Write the manifest and Java yourself, then compile, sign and install with the existing build-native.sh, and open it only on a separate background screen. When finished, look at the actual screen and report the result.
3. Open the background screen to view the app. Tapping should increase the number, and exiting and reopening should keep it. Go by the actual interface, package and data, not only the agent's completion message.

To rebuild an existing project without a model request, open a device shell:

```bash
adb -s <emulator-serial> shell
```

Then run this in the device shell:

```bash
phone-display start
TAKU_APP_DISPLAY="$(phone-display id)" sh /data/local/agentos/light-app-lab/build-native.sh /data/local/agentos/light-app-lab/generated/compilercheck20261016
```

The builder reinstalls over the app and opens it on the current background screen, and the display ID is not hard-coded. Success means compile and signature checks pass, install returns `Success`, the real Activity is interactive and existing data is kept.

## First-time setup

| Item | Notes |
|-|-|
| A clean checkout cannot run this directly | SDK, JDK, base image and device toolchain need separate setup. Check the device name and serial in each component install script before using it |
| Install and upgrade need matching signatures and components | Home runs with a system identity. Confirm the AOSP platform signature matches, and update APK, task service, display, init, schema and startup scripts from the same commit |

The compiler must first be converted to DEX that Android can run. Keep parser resources when converting ECJ. Keep all DEX output when converting D8, which here means `classes.dex` and `classes2.dex`.

aapt and similar programs must be Android ARM64 builds. Mac ARM64 programs do not work.

Create the development signing key locally, and use the same key for updates.

D8's warning about a missing optional API database did not block this APK build.

The order is: get the source → set up SDK, JDK and an ARM64 AOSP `userdebug` AVD → check the platform signature and install matching system components → log in on the device → prepare native tools and a development signing key → push them to the device → compile a small project to confirm.

The [Android environment entry](../../scripts/android-env.sh), [build guide](../getting-started.md) and [native builder](../../tooling/app-kit/build-native.sh) can be reused.

Each person logs in and generates their own development signing key. User data from a logged-in AVD and local debug keys should not be handed over with a directory.

## Sources

ECJ packaging follows the [official Termux build](https://github.com/termux/termux-packages/blob/master/packages/ecj/build.sh). The added class is the [original SourceVersion at a pinned OpenJDK 12 commit](https://github.com/openjdk/jdk12u/blob/89c4c727007ec579d4183221954ed33f23b46bd5/src/java.compiler/share/classes/javax/lang/model/SourceVersion.java).
