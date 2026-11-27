# SystemUI lock screen

Native source for the Taku lock screen. The spec and plan are in
[docs/lock-screen.md](../../docs/lock-screen.md). This is the real SystemUI keyguard on the pinned
LineageOS `frameworks/base`, not an app or an overlay window. Authentication, the bouncer, biometrics,
emergency call and accessibility all stay upstream code.

## Phase 1: frame

- `TakuKeyguardBlueprint` is the stock section list with `TakuBackgroundSection` first and
  `TakuStockDateSection` last. The stock-date section hides the smartspace date and weather while the
  Taku clock, which draws its own date, is current.
  The interactor selects it for every single-shade lock screen, and the repository starts on it.
  Split shade keeps the stock blueprint.
- `TakuLockscreenBackgroundDrawable`, the keyguard root's background drawable (a child view at y = 0
  would trigger the burn-in shift of the large clock), draws the lock wallpaper again, blurred 24 dp under the Figma gradient,
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

## Lock status in the island

- `TakuLockStatusSection` shows the lock state as a padlock in the Dynamic Island at the camera: closed
  while locked, open for 1.5 s after authenticating, then gone. The state and timing are
  `IslandLockStatus` in `system/island`; the section hosts the island's `IslandStatusView` in the
  keyguard root, because the status-bar island is hidden on the keyguard. After an unlock that leaves
  the lock screen, the status-bar island shows the open padlock. See section 4.8 of the spec.
- It replaces the stock padlock above the shortcuts. The section draws the device entry icon's lock
  and unlock pictures transparent (`imageAlpha`). The icon view, its accessibility label and action,
  its long press and the fingerprint picture are unchanged, and no upstream hook was added.
- This is the first lock-screen source that calls `system/island` code. The image builds already
  apply the island patch first.

## Island activities on the lock screen

Spec: [docs/dynamic-island-lock-screen-spec.md](../../docs/dynamic-island-lock-screen-spec.md).

- The island's selected activity shows on the lock screen, also while locked. `system/island` decides
  what may be shown (`IslandLockScreenPolicy`) and publishes it as `IslandPresenter.lockScreen`; the
  lock screen only draws it.
- `TakuLockStatusSection` draws the capsule: the padlock in the disc and the activity in the pill,
  stopping 12 dp before the status icons. While the activity's card is pinned, the capsule keeps only
  the padlock.
- `TakuNotificationSection` pins the island's card (`IslandLockScreenCard`) as the header of
  `TakuNotificationListView`. The list therefore also shows while locked, with only that card, and
  the clock is compact while a card is pinned (`TakuLockscreenPolicy.islandCardPinned`).
- `TakuLockscreenNotifications.represented` leaves the notification the island shows out of the cards
  and out of `cmd statusbar lockscreen notifications`.
- `TakuLockscreenIsland` carries touches to the island as ordinary island operations after the
  falsing check. When the island answers `locked`, it asks for the credential
  (`ActivityStarter.dismissKeyguardThenExecute`) and runs the operation again.
- `cmd statusbar lockscreen state` gains `island {key, redacted, card, capsule, cardView}`.
- Not built into SystemUI or run on an emulator; section 9.4 of the spec lists what to check.

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
patch with SHA-256 hashes for every file. Eleven upstream hooks are anchored edits (ten sources and the SystemUI manifest). Each must match exactly once.
The manifest edit is layered on the island patch's final bytes (`layeredOn: island`). Both apply scripts
accept the combined result, and `tests/lockscreen-island-integration.test.py` checks the build order and
every re-run against the pinned upstream files.

```sh
python3 system/lockscreen/update-patch.py --fetch          # or --upstream-root <frameworks/base>
python3 image/apply-lockscreen-patches.py --check-inputs
python3 tests/lockscreen-patches.test.py
python3 tests/lockscreen-island-integration.test.py   # needs network or TAKU_FRAMEWORKS_BASE
python3 system/lockscreen/typecheck.py --kotlinc KOTLINC --android-jar ANDROID_JAR \
    --coroutines-jar KOTLINX_COROUTINES_CORE_JVM_JAR
```

`image/build.sh` and `image/emulator/build.sh` apply the patch after the island patch, then verify it.

## Verification status

- Host: the type check compiles all owned source against `android.jar`, with narrow doubles of the
  SystemUI APIs and the three island classes it uses. The patch applies cleanly (`git apply --check`) to the pinned upstream files.
- Clock/date/wallpaper fixes were verified on 2026-11-26 in a full ARM64 SystemUI module build
  installed on `emulator-5596`, before integration with the island lock-status commit.
  Targeted regressions pass for the single top date, 76 dp date position, 320 dp tall clock,
  stock/Taku clock switching, PIN lock/unlock, 12 wallpaper changes and 10 sleep/wake cycles.
  The native digit harness passes 36,000 path checks, 430 raster topology checks, and forward,
  reverse and interrupted transitions. The latest digit revision adds actual stroke-width and zero-padded-hour regressions.
- The combined island lock-status revision still needs a full SystemUI build and device acceptance.
- Still separate: a full ROM build, Pixel 9 hardware and the complete biometric/authentication matrix
  in the spec. The patch application tool's `lockscreen-integration.json` only describes patch
  integration; it does not certify a module build or device acceptance.
- Agent interfaces: the `taku-open-access` skill documents the clock face, face bypass, the stock
  notification settings and `cmd statusbar lockscreen` (state including `lockStatus`, notifications,
  open, dismiss, action, taku, shortcuts).

## Open items

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
- UDFPS placement of the Taku button is deferred.
- The lock status in the island has not run on an emulator or device. `device-tests/acceptance.py`
  checks its state (no padlock on a swipe lock, closed while locked, open then gone after the PIN).
  Check by hand: the capsule sits on the camera, the shackle opens, the open padlock carries over to
  the status bar after a PIN unlock, the stock padlock is gone but TalkBack still finds the device
  entry icon, and a long carrier text stops before the disc.
