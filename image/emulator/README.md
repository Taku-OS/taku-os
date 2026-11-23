# Same-source Lineage ARM64 development emulator

This product uses the Pixel 9 build's pinned Android 17 / Lineage 24 platform sources, with
Goldfish ARM64 kernel and hardware definitions instead of Pixel drivers. It builds the framework,
SystemUI island, home/assistant role policy, Quickstep entry routing and Taku runtime together.
It does not transplant a Pixel SystemUI APK into a stock Android SDK image.

The product is `lineage_taku_emu_arm64-cp2a-userdebug`, device `taku_emu64a`. Its local development
configuration supports adb/root and hardware graphics, keeps the real Quickstep navigation service,
and does not consume the Pixel OTA feed. It includes the same pinned Google runtime and default
apps as the internal Pixel product, including Pixel Weather, which the weather widget opens.
The resulting image retains the `internal-test-only` distribution marker. No account state, API keys or data from another AVD belong in these images.

## Prepare immutable inputs

Build the Agent release locally with `npm run build:android` in `system/agent-app`, following its
AGENTS.md. Use a clean worktree. `prepare-input.py` checks that the app source closure in that
build checkout matches the image source checkout; it verifies the APK identity and signature,
copies only tracked build sources and hash-locked dependencies, regenerates the audio/memory
product inputs and records every final file digest. It does not modify the production release lock
or publish a release. Cache roots may be previous **build snapshots**, never device data directories.

```sh
python3 image/emulator/prepare-input.py \
  --source /absolute/clean/taku-os \
  --agent-build /absolute/agent-build-checkout \
  --cache /absolute/verified-dependency-root \
  --tools "$ANDROID_SDK_ROOT/build-tools/36.0.0" \
  --output /absolute/new-emulator-input
```

Use Python 3.11+ and the project's Node/JDK toolchains. Missing or mismatched dependencies fail;
prepare them with the corresponding existing component tools instead of changing expected hashes.
The Agent APK must use the platform development certificate; its private signing inputs stay local.

## Build in isolation

Use the fixed complete platform checkout and builder, with exclusive access to the build worker
lock. The cloud validation uses OverlayFS with `redirect_dir=on` so Soong can rename cached
directories without modifying the original source. Give the container the merged checkout at
`/src`, the sealed input read-only at `/taku`, and a fresh result directory at `/results`.
Mount the dependencies selected by the **sealed snapshot's** locks read-only at
`/src/vendor/gapps` and `/src/vendor/taku-google-apps`. Prepare them with the existing
`python3 scripts/google-services.py fetch` and `python3 scripts/google-apps.py stage`
flows (see [Google app input instructions](../../docs/internal-google-apps-2026-10-28.md)). Do not substitute another
checkout's newer cache. These mounts are required: the builder validates both complete
source trees before compilation, and checks default APK signatures plus the native runtime
files before exporting `default-apps.json`. Missing weather/GMS inputs fail the build;
there is no silent Google-free fallback.
Disable container networking after all locked dependencies are staged.

```sh
export TAKU_EMULATOR_BUILD_NUMBER=YYYYMMDDHHMMSS
export TAKU_EMULATOR_JOBS=32
bash /taku/image/emulator/build.sh
```

The build applies pinned island, HOME/assistant and launcher patches, builds `droid`, checks the
actual SystemUI/Agent APK components and preinstalled Agent documentation, and exports Goldfish
images with hashes in `images.tar.gz`. It keeps the original Pixel product and its outputs intact.
Existing partly patched source must be resolved explicitly; never reset a colleague's checkout.

## Start a new local instance

Extract the archive into a fresh local directory. On an Apple Silicon Mac with an ARM64-capable
Android Emulator installed:

```sh
python3 image/emulator/start.py \
  --images /absolute/extracted-image-directory \
  --avd-home /absolute/dedicated-avds \
  --name Taku_Lineage_Island_Dev \
  --port 5590
```

The launcher verifies all nine image files, including the factory encryption metadata, before starting,
adds SDK filename aliases for the Goldfish system/vendor/ramdisk images, refuses occupied console/adb ports,
and refuses an AVD directory with a different image identity. It creates fresh userdata rather than
cloning another instance. It forces hardware GPU/acceleration and cold boot, and writes its own
`emulator.log`. Re-running with the same identity preserves that instance's data. New image bytes
require a new AVD name. Always use the reported explicit adb serial for subsequent operations;
never use `adb kill-server`, an unqualified install, or an existing AVD's data directory.
Rooted debugging is still controlled by the Lineage developer setting; it is not enabled by this
launcher. Emulator metrics/crash-upload prompts are disabled for this invocation only.

The launch behavior follows the Android Emulator's documented
[custom system directory and image options](https://developer.android.com/studio/run/emulator-commandline).
Isolation guards can be checked without a device using `python3 tests/lineage-emulator.test.py`.

## Acceptance scope

The emulator build registers the existing AOSP software AEC implementation in the
Goldfish vendor audio effects configuration and includes `libaudiopreprocessing`.
`prepare-audio.py` accepts only the pinned upstream XML or its exact registered
form, preserves all other effects, and checks the installed XML and library before
exporting `emulator-audio.json`. It runs only in the isolated emulator build; no
runtime bind mount or automatic microphone preprocessing is required. Live Talk
enables AEC on its own `VOICE_COMMUNICATION` recording session.

On a freshly booted image, use `adb -s SERIAL shell dumpsys media.audio_flinger`
to inspect the registered AEC effect, then start Live Talk in the GUI and inspect
`adb -s SERIAL logcat -d -s AudioLive` for capture/session failures. These are
the same platform effects and engine session used by the GUI; the build receipt
alone does not establish microphone capture, echo suppression, or provider access.

Check boot completion, actual HOME resolution, SystemUI stability, `cmd statusbar island state`,
the provider/Settings GUI's shared state, notification publication, touch pass-through, surface
hosting/death, IME/Back and system-event sources. Preserve screenshots and crash/frame evidence.
This image provides a platform integration environment; it does not establish Pixel hardware,
telephony, power, camera or physical-device performance acceptance.

The [first complete build and device record](../../docs/reviews/island-emulator-2026-11-15/README.md)
includes passing integration checks and the reproduced covering-card focus failure. It is not a
claim that all of issue #28 has passed.

## Centered virtual camera hole on the 1080-pixel AVD

The bundled `com.android.internal.display.cutout.emulation.hole` is a left-side hole.
For this product's 1080-pixel display, the existing Pixel 8 framework overlay supplies a
centered circular hole, matching cutout exclusion geometry, rounded corners and status-bar height.
It changes display emulation resources; it does not change the underlying hardware product.
On the dedicated instance only:

```sh
adb -s emulator-5590 shell cmd overlay disable --user 0 com.android.internal.display.cutout.emulation.hole
adb -s emulator-5590 shell cmd overlay enable --user 0 com.android.internal.emulation.pixel_8
adb -s emulator-5590 shell cmd overlay lookup android android:string/config_mainBuiltInDisplayCutout
adb -s emulator-5590 shell dumpsys display
```

Use ordinary `enable` here: this Pixel overlay has no category, so category-exclusive enable
can also disable unrelated fabricated theme overlays. Disable any other active device-emulation
geometry overlay explicitly before switching. OverlayManager persists the selected configuration.
The verified display reports `Rect(479, 0 - 601, 132)`, centered at x=540 on a width of 1080;
the circular path center is x=539.75 (subpixel rounding). These commands and SystemUI read the
same framework resources. A guest `screencap` may omit the display-decoration mask, so verify
geometry with `dumpsys display` rather than judging absence of a hole in that capture alone.

## Existing Google-free image

The first `20261115082000` image deliberately omitted Google apps/runtime. Installing
Pixel Weather alone removes the missing-package error but does **not** make that image
Google-enabled: on `emulator-5590` it opens then exits, with a log that Play Store is missing.
The locked Google runtime includes a native GMS APEX plus permission/sysconfig files;
it must be integrated through the image build. Do not claim that sideloading a weather APK
or a few framework APKs is equivalent. Later builds use the complete default dependencies.
The original image receipts and historical validation report remain unchanged.
