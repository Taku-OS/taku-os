# Taku lock screen: specification and implementation plan

Status: agreed direction, implementation in phases. Branch: `vela/lock-screen`.

Design: [Figma "Lock screen", node 979:428](https://www.figma.com/design/MdImIDNqePpNZ62C0GLArW/Taku-Mobile-OS?node-id=979-428).
Base: LineageOS 24.0 / Android 17 (`cp2a`) for Pixel 9 (`lineage_tokay`); frameworks/base pinned at
`7f6de40b6429462d47ba9ba351b99943269c8067` (`image/island/manifest.json`, `image/manifests/*.xml`).
Source references below are paths under `packages/SystemUI/src/com/android/systemui/` at that revision
unless stated otherwise.

## 1. Product decisions

| Topic | Decision |
| --- | --- |
| What it is | The real system lock screen, implemented in SystemUI's Keyguard. No separate lock-screen app or overlay activity. |
| Data | Real data only. Date and time follow the device clock, locale and 12/24-hour setting. |
| Locked (not authenticated) | Date, clock and the two shortcuts only. No notifications, no Taku AI button. (Like the iOS lock screen before Face ID succeeds.) |
| Authenticated, still on the lock screen | Notifications appear as a flat list of cards and the Taku AI button appears (frames 1–6). Swiping up goes to Taku Home. |
| Face recognition fails | The standard flow: the status line explains the failure, and swiping up (or tapping a card / the Taku button) shows the PIN, pattern or password bouncer. |
| Taku AI | Available only after authentication. |
| Wallpaper | The design's wallpaper is the default lock-screen wallpaper. User choice comes in a later phase. |
| Status chips / small widgets (Spotify, weather, tasks) | Later phase; user-configurable when they arrive. |
| Media controls | Later phase. |
| Notifications | Real Android notifications from the system notification pipeline, rendered natively by SystemUI in the Taku style. Flat list, no pagination. Tapping opens the app (after authentication when required). |
| Shortcuts | Flashlight (start) and Camera (end) by default; replaceable by the user with other actions or apps. |
| Dynamic Island | Not on the lock screen in this phase; the layout reserves room for it. |

## 2. Screen states

| State | Shown | Notes |
| --- | --- | --- |
| **Before first unlock** (after reboot, credential-encrypted storage locked) | Date, clock, shortcuts. | Biometrics are unavailable until the PIN/pattern/password is entered once; Taku apps cannot run yet. |
| **Locked** | Date, large clock (frame 0 clock), shortcuts. | Notifications are hidden whatever the stock lock-screen notification settings say. |
| **Authenticated on the lock screen** | Date, compact clock, notification cards, shortcuts, Taku button (frames 1–6). | Reached with face unlock while "skip lock screen" (`Settings.Secure.FACE_UNLOCK_DISMISSES_KEYGUARD`) is off. Taku ships with it off, so face unlock keeps the user on the lock screen like Face ID. |
| **Bouncer** | Stock PIN / pattern / password screen. | Shown on swipe-up when not authenticated, after face failure or lockout, and before any action that needs authentication. |
| **Fingerprint** | Stock behaviour: a successful fingerprint goes straight to Home. | The fingerprint icon stays at the sensor position. |
| **Always-on display** | Stock. | Out of scope for this design. |

Interactions:

- Tapping a notification while locked shows the bouncer, then opens the app. When already authenticated it opens directly (apps that may show over the lock screen open directly in both cases).
- The Taku button exists only in the authenticated state.
- Swipe up from the authenticated state dismisses the keyguard to Taku Home.

## 3. Architecture

Flexiglass (the scene container) is **off** in this build: no release config in the `cp2a` chain sets
`com.android.systemui/scene_container`, and its declaration (`aconfig/systemui.aconfig:1469-1474`) has no
default. The running lock screen is the legacy `KeyguardRootView` assembled from a `KeyguardBlueprint`.

### 3.1 Taku blueprint

- `DefaultKeyguardBlueprint` lists the sections that build the lock screen (clock, smartspace, notification
  stack, shortcuts, device entry icon, indication area, status bar, accessibility, AOD).
- Taku adds `TakuKeyguardBlueprint` (id `taku`) and makes it the default:
  - `keyguard/ui/view/layout/blueprints/KeyguardBlueprintModule.kt`: bind it `@IntoSet`.
  - `keyguard/domain/interactor/KeyguardBlueprintInteractor.kt:64-71`: return `taku` for single shade.
  - `keyguard/data/repository/KeyguardBlueprintRepository.kt:64`: start from `taku`.
- **Kept stock sections** (security and accessibility): `DefaultDeviceEntrySection` (fingerprint icon at
  the UDFPS location; its padlock picture is hidden, see 4.8), `DefaultIndicationAreaSection` (biometric and charging messages),
  `DefaultUdfpsAccessibilityOverlaySection`, `AccessibilityActionsSection`, `DefaultStatusBarSection`,
  `DefaultSettingsPopupMenuSection` (long-press customise), the AOD sections.
- **Taku sections**: date, clock, notification cards, Taku button, background, and the lock status in
  the island (4.8).
  Taku sections supply the constraint ids other sections expect (`smart_space_barrier_top/bottom`).
- The primary bouncer (`keyguard_bouncer_container`) and the UDFPS alternate bouncer are outside the
  blueprint and stay untouched.

### 3.2 Delivery

Same pattern as the Dynamic Island (`system/island`, `image/island`):

- Owned source in `system/lockscreen/src/com/android/systemui/taku/lockscreen/`.
- `image/lockscreen/systemui-lockscreen.patch` + `manifest.json` (before/after SHA-256 of every touched
  upstream file), applied by `image/apply-lockscreen-patches.py` from `image/build.sh` and
  `image/emulator/build.sh` after the island patch. Both patches edit the SystemUI manifest. The lock
  screen's manifest entry is layered on the island's final bytes (`layeredOn: island`, before-hash = the
  island's after-hash). The island script accepts the layered result as applied, so incremental
  re-integration and resume verification work in either script. `tests/lockscreen-island-integration.test.py`
  applies both patches in build order to the pinned upstream files, then repeats every re-run.
- `system/lockscreen/update-patch.py` regenerates the patch from owned source and pinned upstream bytes.
- `system/lockscreen/typecheck.py` compiles owned sources against API doubles on the host (as
  `system/island/typecheck.py` does); real verification needs the platform build and an emulator/device.
- SystemUI resource defaults (shortcut slots) are set through a Taku SystemUI runtime resource overlay.

## 4. Components

### 4.1 Background

- Default lock wallpaper: the design's wallpaper (`979:429` fill), shipped as the system default lock
  wallpaper and portrait-cropped for 1080×2424. Lock and home wallpapers stay independent.
- Overlay (`979:430`): blur 24 over the wallpaper and a vertical gradient `rgba(0,0,0,0.48)` →
  `rgba(0,0,0,0.08)`. Because the wallpaper is static, the blurred copy is computed once per wallpaper change
  (not a live blur).
- Bottom bar (`979:451`): 176 dp tall, blur 12, with the "Taku · Edge Refraction" shader (dispersion 0.4,
  flare 0.4). The Figma WGSL is ported line for line to AGSL and applied as a `RenderEffect` runtime shader.
- Drawn as the keyguard root's background drawable, not as a child view. SystemUI's burn-in model
  (`AodBurnInViewModel`) moves the large clock down by `topInset - minViewY` whenever a visible
  keyguard child starts above the status-bar inset. A full-screen child at y = 0, or the stock date at
  the top, would push the tall clock below its 76 dp position.

### 4.2 Date

- `979:468`: Space Grotesk Regular 24 sp, line height 1.05, tracking −1 %, `rgba(255,255,255,0.8)`, centred,
  76 dp from the top (below the status bar); 24 dp above the clock.
- Localised long date ("Tuesday, Oct 29" / "10月29日 周二"), updated on time, date, time-zone and locale
  changes.
- While the Taku clock is current, `TakuStockDateSection` keeps the stock smartspace date and weather
  views GONE, so there is only the design's centred date. It does this in the constraint set (after the
  smartspace section) and after every layout. Other clock faces keep the stock date.

### 4.3 Clock

- Both sizes use native Kotlin `Path`/`Canvas` outlines from Space Grotesk at the compact endpoint.
  The tall endpoint is 320 dp; the supplied `09:30` layout is 316 dp wide. Other times follow their
  glyph advances and fit constrained widths uniformly.
- The designer's `clock-height-morph` contours for 0, 3, 9 and the colon are preserved. Digits
  1, 2, 4, 5, 6, 7 and 8 now derive from the existing font with explicit vertical landmarks instead
  of placeholder centrelines. These additional elongated shapes need design sign-off.
- Native `TakuClockSizeTransition` morphs corresponding cubic control points, not a `scaleY` of text.
  It uses the handoff's spring (0.78, 9), 720 ms to compact, 1050 ms to tall, and opacity 0.5888 → 0.64.
  Android's animator duration scale is respected, including 0 (no motion). No JS/WebView runs on device.
- The clock is registered as `TAKU` with SystemUI's `ClockRegistry`. Selection is the stock
  `Settings.Secure.LOCK_SCREEN_CUSTOM_CLOCK_FACE` JSON, readable/writable through `settings`.
- Time and date follow device time, 12/24-hour preference and time zone. The date is localized;
  clock digits deliberately remain Latin 0–9, as in the phase-1 implementation.
- The morph hooks the existing blueprint large/small-clock transition, only for TAKU; stock clock
  transitions remain unchanged.
- Size follows authentication, not notifications (phase 2). The clock is tall while locked and compact
  once authenticated, even with no notifications. `TakuLockscreenPolicy.clockSize` is applied by the
  panel hook (`NotificationPanelViewController.computeDesiredClockSizeForSingleShade`) and by the
  notification section when authentication changes.
- Source preparation and native test harness: `system/lockscreen/motion/README.md`.

### 4.4 Shortcuts

- Buttons (`979:455`, `979:463`): 56 dp circles, `rgba(255,255,255,0.92)` with blur 16, dark 24 dp icon,
  16 dp from the sides and 40 dp from the bottom.
- Resting colours come from `TakuLockscreenPolicy` through a hook in `KeyguardQuickAffordanceViewBinder`.
  The stock binder uses framework-wide Material colours, which a SystemUI overlay cannot change. The
  overlay sets a 28 dp radius (circle) and 16 dp padding (24 dp icon). The activated state (torch on) keeps
  the stock colours, because the design does not define it.
- Defaults: `bottom_start:flashlight`, `bottom_end:camera` (`config_keyguardQuickAffordanceDefaults`,
  upstream `home` / `wallet`). LineageOS already enables custom shortcuts
  (`custom_lockscreen_shortcuts_enabled`).
- The stock shortcut configs keep their flows: Flashlight toggles `FlashlightController`; Camera calls
  `CameraGestureHelper.launchCamera`, which uses the secure camera intent over a secure keyguard.
- User choice uses the stock selection store: long-press the lock screen, then Customize and Shortcuts in
  the wallpaper picker. Taku adds an "Open app" shortcut (`taku_open_app`, `TakuOpenAppQuickAffordanceConfig`)
  to the stock config set, because stock shortcuts cannot launch an arbitrary app. It opens one chosen
  launcher activity, Taku by default.
  - The target is stored in `Settings.Secure taku_lockscreen_open_app`, per user. A missing or
    unlaunchable target falls back to Taku.
  - The icon is the app's themed (monochrome) icon, because the keyguard tints shortcut icons, or a
    generic apps glyph when there is none.
  - Triggering returns `StartActivity(canShowWhileLocked = false)`, so an app that cannot show over the
    lock screen goes through the bouncer first.
  - The picker's configure action opens `TakuShortcutAppActivity`, an app chooser in SystemUI added by
    a manifest hook and protected by `CUSTOMIZE_SYSTEM_UI`.
- Agent access: the stock `CustomizationProvider` requires `CUSTOMIZE_SYSTEM_UI` (signature|privileged),
  which the shell UID lacks. `cmd statusbar lockscreen shortcuts [list] | set SLOT SHORTCUT | clear SLOT
  | app [COMPONENT|default]` therefore calls the same `KeyguardQuickAffordanceInteractor` with the
  picker's rules: only listed, enabled shortcuts, and device policy respected.

### 4.5 Notification cards (authenticated state)

- Source: the keyguard-filtered shade list from the notification pipeline (`NotifPipeline`
  after-render list). The stock finalize filters have already applied lock-screen settings and
  `VISIBILITY_SECRET`. Groups and bundles contribute their children, so there is one card per
  notification, newest first by post time. There is no grouping and no pagination.
- Locked state: hidden entirely. Authenticated state: full content. A notification that is still
  sensitive (for example a locked work profile) shows its public version, or only the app.
- Layout (`979:574`):
  - Compact card (`979:680`): 72 dp tall, a 48 dp picture, the title and one line below it. The
    picture is the large icon, or the app icon when there is none. A large icon adds a 16 dp app
    icon to the second line.
  - Expanded card (`979:642`): used for conversations (MessagingStyle) and notifications with buttons.
    It has an app header, then a message panel with the sender picture, sender, time, conversation
    name and up to 4 lines of text, then up to two buttons. The last button is primary.
- Cards start 32 dp below the compact clock and scroll under the bottom bar. The slice under the bar
  gets the same blur and edge refraction as the wallpaper.
- Actions use the stock paths:
  - Tap goes through `NotificationActivityStarter.onNotificationClicked`. That handles the content
    intent, auto-cancel, bubbles, work challenge and click statistics.
  - Swiping sideways calls `ExpandableNotificationRow.performDismiss`, with the stock dismissibility check.
  - Buttons: activity intents go through `ActivityStarter.startPendingIntentDismissingKeyguard`; other
    intents are sent directly. Either way the click is reported with `NotificationClickNotifier`.
    Reply (RemoteInput) and contextual actions are not shown yet; the reply composer (`979:1033`)
    comes later. Pulling down the shade still offers stock inline reply.
- While the lock screen shows without the shade, the stock notification stack is made INVISIBLE,
  because it sits above the keyguard views and would draw and take touches over the cards. Pulling the
  shade down shows the stock stack, unchanged.
- Touch: on the lock screen the window root turns vertical drags into the shade (down) or unlock (up).
  The list keeps a gesture only when it starts on a card and the list can scroll that way. Empty
  space, the bar zone and drags past either end of the list stay with the stock gestures. Taps and
  buttons pass the stock falsing check.

### 4.6 Taku AI button (authenticated state)

- `979:739`: 56 dp circle, `#1B1B1B`, white "eyes": two 6 × 16 dp bars, 6 dp apart, centred 7 dp left
  of and 4 dp above the button's centre. It is centred in the shortcut row, on the shortcuts' baseline.
- Shown only in the authenticated state. A tap leaves the lock screen (no bouncer, because the user has
  already authenticated). Then it opens Taku through Taku Home's existing `ACTION_ASSIST` entry, which
  only opens once the keyguard is gone. No assistant policy is relaxed.
- Deferred: placement against the runtime UDFPS location.

### 4.7 Authentication and messages

- Stock primary bouncer, alternate (UDFPS) bouncer, face authentication triggers, lockout, Smart Lock,
  SIM PIN, emergency call and lockdown are unchanged.
- Biometric and charging messages keep using `keyguard_indication_text`, placed just above the bottom row.

### 4.8 Lock status in the island

- The lock state shows in the Dynamic Island at the camera, not as the stock padlock above the
  shortcuts. It is the island's compact geometry with only the 32 dp disc and the camera: a white
  padlock in the disc, no text pill.
- **Locked**: closed padlock, for as long as the lock screen shows and needs authentication.
- **Unlocked**: on authentication the shackle lifts open (300 ms). The open padlock stays 1.5 s, then
  leaves (it fades out on the lock screen). This is the same whether the user stays on the lock screen (face) or goes
  straight to Home (PIN, pattern, fingerprint): once the keyguard has gone, the status-bar island
  shows the open padlock for its 1.5 s, with any live activity waiting as the circle beside it. A tap
  on the open padlock in the status bar ends it early.
- A lock screen that needs no authentication (swipe) shows no padlock.
- Source: `IslandLockStatus` (in `system/island`) owns the state and timing, from
  `KeyguardStateController` (`isShowing`, `canDismissLockScreen`). On the lock screen
  `TakuLockStatusSection` hosts `IslandStatusView`, the island's own capsule view, in the keyguard
  root, because the status-bar island is hidden while the keyguard shows. It is shown with the lock
  screen (not under the shade, not while dozing) and takes no touches.
- The stock device entry icon keeps its view, accessibility label, action and long press. Only its
  lock and unlock pictures are drawn transparent; the fingerprint picture is unchanged.
- The carrier text gets end padding while the capsule is drawn, so a long carrier name does not run
  under the disc.

## 5. Agent interfaces (AGENTS.md)

| Setting / state | Interface |
| --- | --- |
| Clock face | `settings get/put secure lock_screen_custom_clock_face` |
| Face unlock stays on lock screen | `settings get/put secure face_unlock_dismisses_keyguard` |
| Lock-screen notification policy (stock) | `settings ... secure lock_screen_show_notifications`, `lock_screen_allow_private_notifications`, `lock_screen_show_silent_notifications` |
| Lock-screen state | `cmd statusbar lockscreen state` |
| Lock status in the island | `cmd statusbar lockscreen state` → `lockStatus {status, visible}`; after unlock `cmd statusbar island state` → `presentation.lockStatus`, `lockStatusInCapsule` |
| Cards, open, dismiss, buttons, Taku | `cmd statusbar lockscreen notifications`, `open KEY`, `dismiss KEY`, `action KEY INDEX`, `taku` |
| Shortcuts | `cmd statusbar lockscreen shortcuts [list]`, `set SLOT SHORTCUT`, `clear SLOT` |
| "Open app" target | `cmd statusbar lockscreen shortcuts app [COMPONENT|default]`; stored in `settings get secure taku_lockscreen_open_app` |

The command and the cards share `TakuLockscreenNotifications`: the same list, the same checks and the
same stock paths. Errors are `KEYGUARD_NOT_SHOWING`, `LOCKED`, `NOT_FOUND`, `NOT_READY`,
`NOT_CLICKABLE`, `NOT_DISMISSABLE`, `INVALID_ACTION`, `CANCELED`, `TAKU_UNAVAILABLE`, `TIMEOUT` and
`USAGE`. Shortcut commands add `DISABLED_BY_POLICY`, `INVALID_SLOT`, `INVALID_SHORTCUT`,
`SHORTCUT_UNAVAILABLE`, `INVALID_COMPONENT`, `NOT_LAUNCHABLE` and `WRITE_FAILED`.
All commands are documented in the preinstalled agent documentation and are verified on an isolated
emulator.

## 6. First unlock after reboot

What the pinned sources do (phase 4 review):

- After a reboot, biometrics stay unavailable until the first credential entry. So the first unlock is
  always the PIN, password or pattern bouncer. Bouncer success dismisses the keyguard directly; there is
  no intermediate authenticated lock screen.
- Before CE unlock, the HOME activity is Settings' `FallbackHome`, a translucent window over the
  wallpaper. It already registers for `ACTION_USER_UNLOCKED` and finishes as soon as a real HOME
  resolves. It only polls (every 500 ms) while no other HOME exists (`FallbackHome.java`,
  `maybeFinish`). Taku's home policy (`image/home`) resolves MAIN/HOME to Taku Home immediately for an
  unlocked user, so FallbackHome finishes on the unlock broadcast. Its "finishing boot" text only
  appears when it stays resumed for `config_progressTimeoutFallbackHome`, which does not happen when
  HOME resolves.
- Taku Home's `HomeRoleReceiver` already handles `USER_UNLOCKED`, so the Taku process starts at the
  same moment, and the HOME launch that follows FallbackHome's finish starts it at the latest.
- Unlock animation: `KeyguardUnlockAnimationController` plays Launcher3's in-window icon animation only
  when the activity underneath is Launcher3's registered class (`isSupportedLauncherUnderneath`).
  Otherwise it plays the standard canned or swipe-driven animation of the surface behind. Taku Home
  gets that animation like any app, on the first and every later unlock.

Result: the first unlock already lands on Taku Home, with no Taku-specific patch to Settings or
Quickstep. The earlier plan items (FallbackHome theming, finish-on-unlock, process warm-up, launcher
unlock hook) are either upstream behaviour already or unnecessary. A direct-boot-aware Taku Home shell
stays out of scope. Phase 4 therefore ships the measurement instead:
`system/lockscreen/device-tests/acceptance.py SERIAL --first-unlock` sets a temporary PIN, reboots,
checks the locked state (tall clock, no cards, Taku refused), enters the PIN, records the time until
Taku Home is the resumed activity, and removes the PIN. If that time or the hop looks wrong on the
emulator, revisit FallbackHome with that evidence.

## 7. Phases

1. **Frame**: Taku blueprint; background and bottom bar; date; clock; Flashlight / Camera defaults and
   styling; kept authentication sections; locked vs authenticated state plumbing; default lock wallpaper;
   patch tooling and host type checks.
2. **Notification cards and Taku button** in the authenticated state; compact clock on authentication;
   `cmd statusbar lockscreen` for state, cards and the Taku entry.
3. **Shortcut customisation**: Taku "Open app" shortcut with its chooser; `cmd statusbar lockscreen
   shortcuts`.
4. **First unlock and unlock animation** (section 6): reviewed against the pinned sources; already
   lands on Taku Home with the standard unlock animation. Delivered as the emulator acceptance suite.
5. Later: status chips / widgets, media, wallpaper choice, Dynamic Island on the lock screen.

## 8. Verification

Host: `system/lockscreen/typecheck.py`, patch apply/verify tests. Platform: SystemUI build at the pinned
revision; emulator (`image/emulator`) and Pixel 9 runs recorded separately. Authentication matrix:

| Method | Cases |
| --- | --- |
| PIN / pattern / password | success, wrong entry, lockout timer |
| Face | success (stays on lock screen), not recognised → bouncer, lockout → bouncer |
| Fingerprint | success → Home, failure message, lockout |
| Other | emergency call, first unlock after reboot, Smart Lock, notification tap locked / authenticated |

## 9. Open items

- Visual sign-off of the font-derived elongated digits 1, 2, 4, 5, 6, 7, 8.
- Pixel 9 UDFPS sensor position (runtime value; check with `dumpsys fingerprint`); deferred. The Taku
  button and the stock fingerprint icon are placed independently until then.
- Lock status in the island (4.8): not yet run on an emulator or device. Check the capsule position
  against the camera, the shackle animation, the hand-over to the status bar on unlock, and TalkBack
  on the hidden stock padlock.
- Launch animation on a card tap starts from the hidden stock row, not the card. Check it on the
  emulator.
- Later card features from the design: "Relevant" badge, Taku reply and "Add to tasks" buttons, rich
  cards (`979:835` map), status chips, the reply composer and the Taku suggestion sheet (`979:746`).
