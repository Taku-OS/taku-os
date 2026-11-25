# SystemUI lock screen

Native source for the Taku lock screen. The spec and plan are in
[docs/lock-screen.md](../../docs/lock-screen.md). This is the real SystemUI keyguard on the pinned
LineageOS `frameworks/base`, not an app or an overlay window. Authentication, the bouncer, biometrics,
emergency call and accessibility all stay upstream code.

## Phase 1: frame (this change)

- `TakuKeyguardBlueprint` is the stock section list with `TakuBackgroundSection` at the bottom.
  The interactor selects it for every single-shade lock screen, and the repository starts on it.
  Split shade keeps the stock blueprint.
- `TakuLockscreenBackgroundView` draws the lock wallpaper again, blurred 24 dp under the Figma gradient,
  plus the 176 dp bottom bar with a further 12 dp blur and the "Edge Refraction" shader ported to AGSL.
  It fades with the doze amount, so AOD stays black.
- `TakuClockProvider` wraps the stock `DefaultClockProvider` and adds the `TAKU` clock as the
  registry default. The large face (locked) is the tall glyph clock, `TakuTallClockView`. The small face
  (authenticated, when notifications appear) uses the compact Space Grotesk contours. Both faces share the native path renderer;
  `TakuClockSizeTransition` morphs between them. The date is above both faces.
- Notifications stay hidden until the user authenticates (`TakuLockscreenPolicy`, applied in
  `KeyguardNotificationVisibilityProvider`).
- Resource overlays set the Flashlight / Camera shortcut defaults and their 56 dp size and offsets,
  and turn face bypass off by default so a face match keeps the user on the lock screen.
  `image/taku.mk` ships the fonts, their OFL licences and the default lock wallpaper
  (`ro.config.lock_wallpaper`).

## Patch

`src/` goes into `frameworks/base/packages/SystemUI/src/` through `image/lockscreen/`, an exact-revision
patch with SHA-256 hashes for every file. Six upstream hooks are anchored edits. Each must match exactly once.

```sh
python3 system/lockscreen/update-patch.py --fetch          # or --upstream-root <frameworks/base>
python3 image/apply-lockscreen-patches.py --check-inputs
python3 tests/lockscreen-patches.test.py
python3 system/lockscreen/typecheck.py --kotlinc KOTLINC --android-jar ANDROID_JAR \
    --coroutines-jar KOTLINX_COROUTINES_CORE_JVM_JAR
```

`image/build.sh` and `image/emulator/build.sh` apply the patch after the island patch, then verify it.

## Verification status

- Host: the type check compiles all owned source against `android.jar`, with narrow doubles of the
  SystemUI APIs it uses. The patch applies cleanly (`git apply --check`) to the pinned upstream files.
- Not yet done: the SystemUI module build, a ROM build, the emulator run and the Pixel 9 run.
  `lockscreen-integration.json` reports these as `false` until they are recorded. The authentication
  matrix in the spec is the acceptance list.
- Agent interfaces: the clock face, face bypass and stock notification settings are documented in the
  preinstalled `taku-open-access` skill. Shortcuts and lock-screen state get a shell command in phase 3.

## Open items

- Design sign-off for the seven font-derived elongated digits; the handoff only supplies 0, 3, 9.
- Full Keyguard verification of the new native morph, including authentication with no notifications.
  See [motion/README.md](motion/README.md) for source, timing and the standalone native test harness.
- The Pixel 9 UDFPS position needs checking on a device before the phase 2 Taku AI button goes in.
