# Dynamic Island on the lock screen: spec

Status: phases 1 and 2 are implemented on `vela/lock-screen`. Host tests and type checks pass. SystemUI
was not built with this change and nothing was run on an emulator or device; see
[Implementation status](#9-implementation-status). Owner decisions of 2026-11-27 are in
[section 10](#10-owner-decisions).

Related: [lock screen spec](lock-screen.md), [island v2 spec](dynamic-island-v2-spec.md),
[island architecture](dynamic-island-architecture.md).

## 1. Summary

Island activities (a timer, a recording, a delivery, music, a Taku task) show on the lock screen in
two forms:

- **Compact capsule** at the camera, in the same place and geometry as in the status bar. While the
  phone is locked the capsule's disc is the padlock and the activity uses the pill.
- **Expanded card** pinned at the top of the lock screen's card column, above the notification cards.
  It is the island's own native card, in the flow of the page. It is not a floating window over the
  camera.

Activities stay visible in full while the phone is locked, as Live Activities and Now Playing do on
an iPhone. Ordinary notifications keep the lock screen's rule: hidden until the user authenticates.

There is still one island: one engine, one store, one order and one selection. The lock screen is a
second place that draws it, with lock-screen rules for what may be shown and done before the user
authenticates.

## 2. Why the status-bar island cannot simply stay visible

- The status bar is its own window (`TYPE_STATUS_BAR`, layer 15). The keyguard is in the notification
  shade window (`TYPE_NOTIFICATION_SHADE`, layer 17). Everything in the status bar window is under the
  lock screen.
- SystemUI turns the home status bar off while the keyguard shows; the lock screen has its own status
  bar inside the shade window.
- The expanded card's window (`TYPE_STATUS_BAR_ADDITIONAL`, layer 16) is also under the shade window.

So removing `keyguard.isShowing` from `IslandPresenter` changes nothing on screen. Lock-screen island
content is drawn by views inside the keyguard.

Xiaomi's Super Island works the same way as far as its public documents show: one notification
payload, drawn separately per surface. The island capsule uses `param_island`; the lock screen and
the notification shade show a "focus notification" card from the same payload; the screen-off display
uses `aodTitle`. Its lock-screen form is a card in the notification list.

## 3. Lock screen states

"Shown" is subject to [section 4](#4-what-may-be-shown).

| State | Capsule | Card |
| --- | --- | --- |
| Before first unlock | Padlock only. Publishers cannot run yet. | None |
| Locked | Shown. Padlock in the disc, the selected activity in the pill. | Pinned for the selected activity |
| Authenticated on the lock screen | Padlock opens for 1.5 s, then the capsule is the normal island (activity glyph in the disc, pill). | Pinned for the selected activity |
| Shade or quick settings pulled over the lock screen | Hidden | Hidden. The stock notification stack shows, unchanged. |
| Bouncer (PIN, pattern, password) | Hidden | Hidden |
| Dozing / always-on display | Hidden | Hidden |
| Keyguard occluded by an app (camera, in-call screen) | Not drawn (phase 3) | None |
| Unlocked (keyguard gone) | Status-bar island, as before | Floating card window, as before |

A lock screen that needs no authentication (swipe) counts as authenticated, as it does for the
notification cards.

## 4. What may be shown

The rule is `IslandLockScreenPolicy.exposure`. Its inputs come from Android, never from the
publisher's JSON, and a publisher cannot opt in to more.

| Activity | Locked | Authenticated |
| --- | --- | --- |
| Call, alarm, screen recording, casting (`tier` 0–2) | Full | Full |
| Any other activity (`tier` 3: Taku-format, Live Updates, timer, Now Playing) | Full | Full |
| Taku Agent's assistant activity | Not shown. Taku AI is available only after authentication. | Full |
| Notification with `VISIBILITY_SECRET` | Not shown | Not shown |
| The user hides this user's notifications on the lock screen (`lock_screen_show_notifications` off) | Not shown | Not shown |
| Android requires the public version right now: the user hides sensitive content (`lock_screen_allow_private_notifications` off), a locked work profile, a one-time code | Glyph only | Glyph only for a locked profile, else full |

- "Glyph only" means the activity's glyph in the pill: no text, timer or number, and no card.
- The last three rows follow Android's own lock-screen notification settings
  (`NotificationLockscreenUserManager`). They are the equivalent of iOS's "allow access when locked".
- System-tier activities ignore those settings. Stock Android already shows calls, alarms and the
  recording indicator over the lock screen.
- The setting in [section 7](#7-settings) removes every activity from the lock screen.

### Now Playing

A playing media session is an island activity (`category` `media`): a `MediaStyle` notification
while it is ongoing.

- Capsule: the album art (or the app icon) in the disc, the track title in the pill.
- Card: track title, artist, and the notification's own transport buttons (previous, play/pause,
  next) with their labels.
- It ends when playback stops and the notification is no longer ongoing.
- It is an island activity everywhere, so it also shows in the status bar while unlocked. A dedicated
  media card (artwork, scrubber) is not part of this spec.

## 5. Compact capsule

### 5.1 Layout

Same geometry as the status bar (Figma `1379:889`): 32 dp disc, the camera, a text pill, joined by
the metaball necks, anchored to the real camera cutout.

| Situation | Disc (left of camera) | Pill (right of camera) |
| --- | --- | --- |
| Locked, no activity | Closed padlock | None |
| Locked, activity, card folded | Closed padlock | Group B (text, timer, number), or the activity's glyph when it has none |
| Locked, glyph-only activity | Closed padlock | The activity's glyph, 32 dp wide |
| Any state, card pinned | Padlock, or nothing once the padlock has left | None: the card shows the content |
| Just authenticated (1.5 s) | Open padlock | As above |
| Authenticated, card folded | Activity's group A | Group B |

- While locked the padlock always holds the disc, so the lock state is never displaced by an
  activity.
- Only the selected activity is drawn on the lock screen. The waiting circle is not used there.
- After the 1.5 s the disc changes from the open padlock to the activity's glyph in place.
- With no activity, the capsule is the padlock alone, and it leaves after 1.5 s.

### 5.2 Space

- The keyguard status bar keeps the carrier text left of the camera and the status icons right of
  it. The pill grows to the right, up to 12 dp before the status icons.
- Text that does not fit is ellipsized; if not even one character fits the pill is left out and only
  the disc shows. These are the status bar's existing rules (`IslandCapsuleView.layoutWidth`).
- The carrier text gets end padding so it stops before the disc.

### 5.3 Gestures

| Gesture | Result |
| --- | --- |
| Tap | Pins the card, or opens the app when the publisher chose `tap: open` |
| Long press | Pins the card |
| Swipe left | Next activity |
| Swipe right | Hides the activity |
| Vertical drag | Passed to the lock screen (shade down, unlock up) |

- Each gesture runs the same island operation an Agent would call, after the stock falsing check.
- An operation the island refuses with `locked` (opening an app while locked, pinning a glyph-only
  activity) makes the GUI ask for the credential and run it again after authentication.
- A capsule that only shows the padlock takes no touches.

## 6. Expanded card

### 6.1 Placement

- The card is pinned as the first item of the lock screen's card column, full column width, above
  the notification cards. It scrolls with the list.
- It uses the island's native card views (`IslandTemplateCard`, `IslandProgressCard`,
  `IslandAssistantCard`) and tokens (`IslandStyle`), so a card looks the same on the lock screen and
  when unlocked. It does not use `IslandCardWindow`: no separate window, no blur-behind, and it never
  covers the camera. `cardPosition` is ignored on the lock screen.
- While the card is shown, the capsule keeps only the disc (padlock, or nothing once the padlock has
  left). This is the lock screen's version of "an open card hides the capsule".
- A pinned card needs the room below the clock, so the clock is compact while a card is pinned, also
  when locked. With the card folded and the phone locked, the tall clock returns.

### 6.2 When it opens

| Trigger | Result |
| --- | --- |
| The lock screen shows and an activity is selected that may be shown in full | Its card is pinned. This is the lock screen's default. |
| A rising `alert` while the keyguard shows | The card is pinned for that activity, also if the user folded it before. The 5 s alert timeout does not fold it here. |
| Tap or long press on the capsule | Pins the card |
| Authentication | An activity that was hidden or glyph-only for lack of authentication gets its card |

- One card at a time: the selected activity's. Moving to the next activity moves the card.
- The user folds it with the card's handle, its Close button or by swiping it up. It then stays
  folded for that activity until the keyguard goes away. A new rising alert pins it again.
- The activity's own notification is left out of the notification cards, and out of
  `cmd statusbar lockscreen notifications`, while the island shows it (capsule or card).
- A waking alert does not change how the screen turns on. That stays with Android.

### 6.3 Actions

The rule for buttons while locked is `IslandLockScreenPolicy.actionWhileLocked`.

| Action | Locked | Authenticated |
| --- | --- | --- |
| System controller actions (stop recording, stop casting) | Run | Run |
| Call and alarm actions (answer, decline, hang up, snooze, dismiss) | Run, through the notification's own `PendingIntent` | Run |
| Now Playing transport buttons | Run | Run |
| Any other publisher button | The GUI asks for the credential, then runs it. An Agent gets `locked`. | Runs. A button that starts an activity leaves the lock screen (`ActivityStarter.startPendingIntentDismissingKeyguard`). |
| Tap on the card body (`open`) | The GUI asks for the credential, then opens. An Agent gets `locked`. | Opens, leaving the lock screen |
| Reply (RemoteInput) | Not available | Not available on the lock screen: `reply_unavailable_on_lock_screen` |

- The card is not focusable and shows no keyboard on the lock screen.
- Rich remote bodies (`SurfacePackage` from the publisher's content service) are not bound on the
  lock screen. The native template shows instead, so an app's live surface stays off the keyguard.
- Every touch passes the stock falsing check.

### 6.4 Leaving the lock screen

- Unlocking with the card pinned does not open the floating card on Home. The activity continues as
  the status-bar capsule. Taku Agent's existing reveal of a waiting reply after unlock is unchanged.
- Locking with a floating card open folds it, as before. The lock screen then applies 6.2.

## 7. Settings

| Setting | Storage | Default | GUI |
| --- | --- | --- | --- |
| Show activities on the lock screen | `Settings.Global` `taku_island_lock_screen` (`0` / `1`) | `1` | A switch on the "Activity island" page |

- It is stored beside the island's other settings (`taku_island_enabled`), which are global.
- Off: the lock screen shows only the padlock. Calls and alarms keep Android's own full-screen
  presentation.
- The island's main switch off also removes activities from the lock screen; the lock-screen switch
  is then disabled in the GUI.
- The padlock is not an activity and is unaffected by either switch.

## 8. Agent interface

The same operations serve both surfaces; none is lock-screen only.

| Read / operation | Lock screen behaviour |
| --- | --- |
| `cmd statusbar island state` → `presentation` | `surface`: `lock-screen` while the keyguard shows, else `status-bar`. `lockScreen {key, redacted, card}`: the activity the lock screen shows, `redacted` `none` or `glyph`, and the key of the pinned card. `expanded` is also true while a lock-screen card is pinned. |
| `cmd statusbar island settings` | `lockScreen` (boolean) |
| `cmd statusbar island setLockScreen 0\|1` | Trusted callers only, like `setEnabled`. Writes the same value as the GUI switch. Error `invalid_lock_screen`. |
| `expand KEY`, `tap KEY` | Pin the card. `locked` when the activity is hidden or glyph-only for lack of authentication. |
| `collapse` | Folds the pinned card |
| `select KEY`, `next`, `dismiss KEY` | As in the status bar; `next` moves among the activities the lock screen may show |
| `action KEY JSON`, `open KEY` | The table in 6.3 |
| `get KEY`, `list` | Each activity has `category` (`call`, `alarm`, `timer`, `media` or null) |
| `cmd statusbar lockscreen state` | `island {key, redacted, card, capsule, cardView}` beside `lockStatus`: the same three fields, plus the capsule and card as drawn (null when not drawn) |
| `cmd statusbar lockscreen notifications` | Does not list the notification the island shows |

Errors:

- `locked`: the operation needs authentication. An Agent can never authenticate for the user, so it
  gets this error where the GUI would ask for the credential. It applies whenever the phone is
  locked, also to a caller that did not go through the lock screen.
- `reply_unavailable_on_lock_screen`: `action` with `reply` while the keyguard shows.

Third-party callers keep their existing scope: only activities their own UID published.

## 9. Implementation status

### 9.1 Structure

- **`IslandLockScreenPolicy`** (Java, host-tested): what may be shown, and which actions run while
  locked.
- **`IslandEngine`**: `lockScreenExposure` feeds the policy from the keyguard, the notification's
  visibility and `NotificationLockscreenUserManager`. It gates `open`, `tap` and `action` while
  locked, and starts activities through the keyguard-dismissing path while the keyguard shows.
- **`IslandPresenter`** keeps the one selection and publishes `lockScreen`
  (`LockScreen(activity, redacted, card)`) for the lock screen to draw. `expand`, `collapse`, `next`
  and `expandedKey` act on the pinned card while the keyguard shows.
- **`IslandCapsuleView`** has one new arrangement: a status in the disc with an activity in the pill
  (`statusOverlay`, `redacted`).
- **`IslandStatusView`** hosts the capsule in the keyguard and reports its gestures.
  **`IslandLockScreenCard`** is the native card as an ordinary view.
- **`system/lockscreen`**: `TakuLockStatusSection` hosts the capsule, `TakuNotificationSection` pins
  the card as the list's header, and `TakuLockscreenIsland` carries their touches to the island,
  asking for the credential on `locked`.
- **Now Playing**: `IslandSystemNotifications.media` and the extractor's baseline adapter.
- No new upstream hook. Both patches were regenerated.

### 9.2 Phases

| Phase | Content | State |
| --- | --- | --- |
| 0 | Run the padlock on an emulator: position, shackle, hand-over to the status bar, TalkBack. | Not done here |
| 1 | Compact capsule with activities: policy, padlock-plus-pill arrangement, gestures, setting, Agent fields. | Implemented, not run |
| 2 | Pinned card: inline card view, triggers, actions, de-duplication with notification cards, Now Playing. | Implemented, not run |
| 3 | Occluded keyguard (status-bar capsule) and a screen-off form. Needs its own design. | Not started |

### 9.3 Verified

- Host: `IslandLockScreenPolicyTest` (27 checks) and the existing island host tests.
- Type checks: the lock-screen sources compile against doubles of the island and SystemUI APIs. The
  island sources compile with the same hidden-API errors as before this change and no new ones,
  because the framework jar the island type check needs is not available here.
- Both patches apply in order to the pinned upstream files; the patch tests pass.

### 9.4 Not verified

Nothing here was built into SystemUI or run on an emulator or device. To check there:

1. Locked, screen recording running: padlock in the disc, the card with Stop pinned under the
   compact clock. Stop works without authentication.
2. Locked, a Live Update from a third-party app: full capsule and card. A card button asks for the
   PIN, then runs. Tapping the card asks for the PIN, then opens the app.
3. Music playing: Now Playing card with working transport buttons while locked.
4. Fold the card: the capsule shows the padlock and the activity's pill, and the tall clock returns.
   Tap the capsule: the card is pinned again.
5. Face authentication on the lock screen: padlock opens, then the disc shows the activity's glyph.
6. `lock_screen_allow_private_notifications` off: glyph only, no card, while locked.
7. A `VISIBILITY_SECRET` activity never appears on the lock screen.
8. Shade pulled over the lock screen: capsule and card hidden, the notification is in the stock stack.
9. The shown notification is absent from the lock screen cards and from `lockscreen notifications`.
10. Setting off: only the padlock. `island settings` reports `lockScreen: false`.
11. Unlock with a card pinned: Home shows the status-bar capsule, no floating card.
12. An Agent's `open KEY` while locked returns `locked`.
13. The pill stops before the status icons; a long carrier text stops before the disc.
14. TalkBack reads the capsule as "Locked. <activity title>" and reaches the card's buttons.
15. Scrolling the card column, the shade pull-down and the unlock swipe still work with a card
    pinned, and swiping up on the card folds it.

`system/lockscreen/device-tests/acceptance.py` covers the setting and the new state fields
(`island_suite`). The capsule render tests cover the padlock-plus-pill arrangement.

## 10. Owner decisions

Decided on 2026-11-27.

| # | Question | Decision |
| --- | --- | --- |
| D1 | Is the card pinned by default on the lock screen, or only after a tap? | Pinned by default, like Xiaomi. |
| D2 | While locked, how much of an ordinary activity shows? | Follow the iPhone: activities and Now Playing show as full cards while locked. Ordinary notifications stay hidden until authentication. Android's own lock-screen notification settings can still hide or reduce an activity (section 4). |
| D3 | May system-tier activities show full content and run their stop/answer actions while locked? | Yes. |
| D4 | Does the padlock keep the disc while locked when an activity is present? | Yes. |
| D5 | Reply on the lock screen | Not available; reply after unlocking. |
| D6 | Screen-off (always-on) form | Later, separate design. |

Open points from the implementation, for a later decision:

- The clock becomes compact whenever a card is pinned, so with music playing the locked screen shows
  the compact clock. The alternative is to keep the tall clock and place the card at the bottom.
- Now Playing uses the generic card. A media card with artwork and a scrubber needs a design.
- A paused session leaves the island (the notification is no longer ongoing). An iPhone keeps Now
  Playing for a while after pausing.
- An Agent's `action` while the phone is locked is now refused with `locked` unless it is a
  controller, call, alarm or media action. Before, the island ran any action whatever the lock state.
