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
- **Kept stock sections** (security and accessibility): `DefaultDeviceEntrySection` (fingerprint / lock icon
  at the UDFPS location), `DefaultIndicationAreaSection` (biometric and charging messages),
  `DefaultUdfpsAccessibilityOverlaySection`, `AccessibilityActionsSection`, `DefaultStatusBarSection`,
  `DefaultSettingsPopupMenuSection` (long-press customise), the AOD sections.
- **Taku sections**: date, clock, notification cards, Taku button, background, and a reserved island slot.
  Taku sections supply the constraint ids other sections expect (`smart_space_barrier_top/bottom`).
- The primary bouncer (`keyguard_bouncer_container`) and the UDFPS alternate bouncer are outside the
  blueprint and stay untouched.

### 3.2 Delivery

Same pattern as the Dynamic Island (`system/island`, `image/island`):

- Owned source in `system/lockscreen/src/com/android/systemui/taku/lockscreen/`.
- `image/lockscreen/systemui-lockscreen.patch` + `manifest.json` (before/after SHA-256 of every touched
  upstream file), applied by `image/apply-lockscreen-patches.py` from `image/build.sh` and
  `image/emulator/build.sh` after the island patch. The two patches touch disjoint upstream files.
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

### 4.2 Date

- `979:468`: Space Grotesk Regular 24 sp, line height 1.05, tracking −1 %, `rgba(255,255,255,0.8)`, centred,
  76 dp from the top (below the status bar); 24 dp above the clock.
- Localised long date ("Tuesday, Oct 29" / "10月29日 周二"), updated on time, date, time-zone and locale
  changes.

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
  transitions remain unchanged. Phase 1 still relies on notification-driven size selection, so
  authentication with no notifications is not yet proof of the compact-state requirement.
- Source preparation and native test harness: `system/lockscreen/motion/README.md`.

### 4.4 Shortcuts

- Buttons (`979:455`, `979:463`): 56 dp circles, `rgba(255,255,255,0.92)` with blur 16, dark 24 dp icon,
  16 dp from the sides and 40 dp from the bottom.
- Defaults: `bottom_start:flashlight`, `bottom_end:camera` (`config_keyguardQuickAffordanceDefaults`,
  upstream `home` / `wallet`). LineageOS already enables custom shortcuts
  (`custom_lockscreen_shortcuts_enabled`).
- The stock shortcut configs keep their flows: Flashlight toggles `FlashlightController`; Camera calls
  `CameraGestureHelper.launchCamera`, which uses the secure camera intent over a secure keyguard.
- User choice uses the stock selection store (long-press → Customize, wallpaper picker) and adds a Taku
  "Open app" shortcut config (stock shortcuts cannot launch an arbitrary app); an app that cannot show over
  the lock screen goes through the bouncer first.
- Agent access: the stock `CustomizationProvider` requires `CUSTOMIZE_SYSTEM_UI` (signature|privileged),
  which the shell UID lacks, so a SystemUI shell command (`cmd statusbar lockscreen shortcuts ...`) exposes
  the same selections to agents and ADB.

### 4.5 Notification cards (authenticated state)

- Source: the keyguard-filtered notification list (the finalize filter in `KeyguardCoordinator` /
  `KeyguardNotificationVisibilityProvider` already applies lock-screen settings and `VISIBILITY_SECRET`).
- Locked state: hidden entirely. Authenticated state: shown with full content.
- Rendering: native SystemUI cards in the Figma style, one card per notification, flat list, newest first,
  scrolling under the bottom bar. No pagination dots.
- Actions use the stock paths so system behaviour is unchanged:
  - tap → `StatusBarNotificationActivityStarter` (opens the app; bouncer first when needed; click
    statistics and auto-cancel as usual);
  - swipe to dismiss → `NotifCollection` dismissal with user statistics;
  - inline reply → `NotificationRemoteInputManager` (always requires authentication on a secure keyguard).
- Pulling down the shade keeps the stock notification shade.

### 4.6 Taku AI button (authenticated state)

- `979:459`: 56 dp circle, `#1B1B1B`, white "eyes" (two 6 × 16 dp bars, 6 dp apart).
- Opens Taku through the existing assistant session. Android allows the assistant window
  (`TYPE_VOICE_INTERACTION`) above the lock screen; Taku's current "refuse while locked" checks are policy
  and are relaxed only for the authenticated state.
- Its position is resolved against the runtime UDFPS location so it never overlaps the fingerprint icon.

### 4.7 Authentication and messages

- Stock primary bouncer, alternate (UDFPS) bouncer, face authentication triggers, lockout, Smart Lock,
  SIM PIN, emergency call and lockdown are unchanged.
- Biometric and charging messages keep using `keyguard_indication_text`, placed just above the bottom row.

## 5. Agent interfaces (AGENTS.md)

| Setting / state | Interface |
| --- | --- |
| Clock face | `settings get/put secure lock_screen_custom_clock_face` |
| Face unlock stays on lock screen | `settings get/put secure face_unlock_dismisses_keyguard` |
| Lock-screen notification policy (stock) | `settings ... secure lock_screen_show_notifications`, `lock_screen_allow_private_notifications`, `lock_screen_show_silent_notifications` |
| Shortcuts | `cmd statusbar lockscreen shortcuts list|get|set|clear` (new) |
| Lock-screen state (locked / authenticated / bouncer) | `cmd statusbar lockscreen state` (new) |

All new commands are documented in the preinstalled agent documentation and verified on an isolated
emulator.

## 6. First unlock after reboot

Today: before the first unlock only direct-boot-aware HOME activities resolve, so Settings' `FallbackHome`
runs (transparent window, "finishing boot" after 2 s). After unlock it polls every 500 ms until Taku Home
resolves, then Taku Home starts cold. SystemUI's launcher unlock animation only applies to Launcher3.

Plan:

1. Theme `FallbackHome` with the Taku wallpaper and finish on `ACTION_USER_UNLOCKED` instead of polling.
2. Warm the Taku Agent process as soon as the user is unlocked.
3. Give Taku Home the launcher unlock-animation hook (via the Quickstep bridge used by
   `TakuNavHandleBridge`), which also smooths every later unlock.
4. Only if still needed: a direct-boot-aware Taku Home shell.

## 7. Phases

1. **Frame**: Taku blueprint; background and bottom bar; date; clock; Flashlight / Camera defaults and
   styling; kept authentication sections; locked vs authenticated state plumbing; default lock wallpaper;
   patch tooling and host type checks.
2. **Notification cards and Taku button** in the authenticated state.
3. **Shortcut customisation**: Taku "Open app" shortcut, agent shell command.
4. **First unlock and unlock animation** (section 6).
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
- Compact clock and card specs from frames 1–6 (to be taken from `979:574` / `979:835` in phase 2).
- Pixel 9 UDFPS sensor position (runtime value; check with `dumpsys fingerprint`).
