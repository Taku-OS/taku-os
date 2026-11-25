# SystemUI lock screen

Native source for the Taku lock screen. The spec and plan are in
[docs/lock-screen.md](../../docs/lock-screen.md). This is the real SystemUI keyguard on the pinned
LineageOS `frameworks/base`, not an app or an overlay window. Authentication, the bouncer, biometrics,
emergency call and accessibility all stay upstream code.

## Phase 1: frame

- `TakuKeyguardBlueprint` is the stock section list with `TakuBackgroundSection` at the bottom.
  The interactor selects it for every single-shade lock screen, and the repository starts on it.
  Split shade keeps the stock blueprint.
- `TakuLockscreenBackgroundView` draws the lock wallpaper again, blurred 24 dp under the Figma gradient,
  plus the 176 dp bottom bar with a further 12 dp blur and the "Edge Refraction" shader ported to AGSL.
  It fades with the doze amount, so AOD stays black.
- `TakuClockProvider` wraps the stock `DefaultClockProvider` and adds the `TAKU` clock as the
  registry default. The large face (locked) is the tall glyph clock, `TakuTallClockView`. The small face
  (authenticated) uses the compact Space Grotesk contours. Both faces share the native path renderer;
  `TakuClockSizeTransition` morphs between them. The date is above both faces.
- Notifications stay hidden until the user authenticates (`TakuLockscreenPolicy`, applied in
  `KeyguardNotificationVisibilityProvider`).
- Resource overlays set the Flashlight / Camera shortcut defaults and their 56 dp size and offsets,
  and turn face bypass off by default so a face match keeps the user on the lock screen.
  `image/taku.mk` ships the fonts, their OFL licences and the default lock wallpaper
  (`ro.config.lock_wallpaper`).

## Phase 2: authenticated surface

- `TakuLockscreenNotifications` is the single model and the single set of operations. The cards
  and `cmd statusbar lockscreen` both use it. It reads the keyguard-filtered pipeline list, flattens
  groups and bundles, and sorts newest first. Open, dismiss and buttons go through the stock click,
  row dismissal and click-notifier paths. The Taku entry runs after the keyguard is gone, through Taku
  Home's existing `ACTION_ASSIST` entry.
- `TakuNotificationSection` adds `TakuNotificationListView`, the flat card column below the compact
  clock. Cards are `TakuNotificationCardView`, in compact or expanded Figma layouts. The slice
  under the bottom bar is drawn through the bar's blur and refraction. While the lock screen shows
  without the shade, the section hides the stock stack, and it sets the clock size from
  authentication.
- `TakuAssistantSection` adds the Taku button, centred on the shortcut baseline.
- The cards and the button only show on the authenticated lock screen without the shade, and not
  while dozing. When hidden they are INVISIBLE, so the stock gestures keep working.
- The `NotificationPanelViewController` hook makes the stock clock-size rule follow authentication
  (`TakuLockscreenPolicy.clockSize`).

## Phase 3: shortcuts

- `TakuOpenAppQuickAffordanceConfig` joins the stock shortcut set (hook in
  `KeyguardDataQuickAffordanceModule`) as "Open app". Its target is `TakuOpenAppTarget`, stored in
  `Settings.Secure taku_lockscreen_open_app` and defaulting to Taku. `TakuShortcutAppActivity`
  (manifest hook) is the picker's configure action.
- `TakuLockscreenCommand` owns `cmd statusbar lockscreen`. It runs on a background thread, sends card
  and Taku operations to the main thread, and runs shortcut operations (`TakuLockscreenShortcuts`)
  through the picker's `KeyguardQuickAffordanceInteractor`.

## Phase 4: first unlock

No code change was needed; see section 6 of the spec. `device-tests/acceptance.py` is the emulator
acceptance suite for all phases. It needs an explicit serial on an isolated emulator, and restores
everything it changes:

```sh
python3 system/lockscreen/device-tests/acceptance.py SERIAL                  # shortcuts, cards, Taku
python3 system/lockscreen/device-tests/acceptance.py SERIAL --first-unlock   # + PIN, reboot, first unlock
```

It writes `out/lockscreen-acceptance/report.json` and screenshots (locked, authenticated, Open app
shortcut) to compare with Figma.

## Patch

`src/` goes into `frameworks/base/packages/SystemUI/src/` through `image/lockscreen/`, an exact-revision
patch with SHA-256 hashes for every file. Nine upstream hooks are anchored edits (eight sources and the SystemUI manifest). Each must match exactly once.

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
- Agent interfaces: the `taku-open-access` skill documents the clock face, face bypass, the stock
  notification settings and `cmd statusbar lockscreen` (state, notifications, open, dismiss, action,
  taku, shortcuts).

## Open items

- Design sign-off for the seven font-derived elongated digits; the handoff only supplies 0, 3, 9.
- Run `device-tests/acceptance.py` (with `--first-unlock`) on the emulator. It covers shortcut
  defaults and selection, the Open app target, cards, dismissal, the Taku button, the locked state and
  first unlock.
- Check by hand on the emulator, because a script cannot judge these:
  - the native clock morph on authentication (see [motion/README.md](motion/README.md));
  - the shade pulled over the lock screen shows the stock stack, and the stock stack is back over apps
    after unlocking;
  - scrolling the list does not fight the stock shade and unlock drags;
  - where the launch animation starts from on a card tap;
  - the picker's "Open app" configure button opens the chooser;
  - the authentication matrix in the spec, including face (stays on the lock screen) and fingerprint.
- UDFPS placement of the Taku button is deferred. On devices without UDFPS, the stock unlocked padlock
  sits directly above it.
