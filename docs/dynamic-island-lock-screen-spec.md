# Dynamic Island on the lock screen: spec

Status: proposal, not implemented. Only the lock status (the padlock capsule,
[lock-screen.md §4.8](lock-screen.md)) exists today. Recommendations that need the owner's decision are
listed in [Owner decisions](#10-owner-decisions).

Related: [lock screen spec](lock-screen.md), [island v2 spec](dynamic-island-v2-spec.md),
[island architecture](dynamic-island-architecture.md).

## 1. Summary

Island activities (a timer, a recording, a delivery, a Taku task) are invisible on the lock screen
today. This spec puts them there in two forms:

- **Compact capsule** at the camera, in the same place and geometry as in the status bar. While the
  phone is locked the capsule's disc is the padlock and the activity uses the pill.
- **Expanded card** pinned at the top of the lock screen's card column, above the notification cards.
  It is the island's own native card, in the flow of the page. It is not a floating window over the
  camera.

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
content has to be drawn by views inside the keyguard, as the padlock already is
(`TakuLockStatusSection` hosting `IslandStatusView`).

Xiaomi's Super Island works the same way as far as its public documents show: one notification
payload, drawn separately per surface. The island capsule uses `param_island`; the lock screen and
the notification shade show a "focus notification" card from the same payload; the screen-off display
uses `aodTitle`. Its lock-screen form is a card in the notification list (reference screenshot: a
recording card with a timer, Pause and Stop above the notifications).

## 3. Lock screen states

"Permitted" is defined in [§4](#4-what-may-be-shown-while-locked).

| State | Capsule | Card |
| --- | --- | --- |
| Before first unlock | Padlock only. Publishers cannot run yet. | None |
| Locked | Padlock in the disc. The selected activity in the pill, as far as permitted. | Only for system-tier activities |
| Authenticated on the lock screen | Padlock opens for 1.5 s, then the capsule is the normal island (activity glyph in the disc, pill, waiting circle). | Any activity |
| Shade or quick settings pulled over the lock screen | Hidden | Hidden. The stock notification stack shows, unchanged. |
| Bouncer (PIN, pattern, password) | Hidden | Hidden |
| Dozing / always-on display | Hidden (phase 3 may add a screen-off form) | Hidden |
| Keyguard occluded by an app (camera, in-call screen) | Phase 3: the status-bar capsule, system-tier activities only | None while locked |
| Unlocked (keyguard gone) | Status-bar island, as today | Floating card window, as today |

A lock screen that needs no authentication (swipe) counts as authenticated, as it does for the
notification cards.

## 4. What may be shown while locked

The lock screen spec's rule is that a locked phone shows no notification content. Island activities
follow it, with one exception for things the user must be able to see and stop.

| Activity | Locked | Authenticated |
| --- | --- | --- |
| System tier: call, alarm, screen recording, casting (`tier` 0–2) | Full capsule and card. These already show over the lock screen in stock Android. | Full |
| Any other activity (`tier` 3: Taku-format, Live Updates, timer, media) | Glyph only: the activity's group A glyph in the pill. No text, timer or number, and no card. | Full |
| Taku Agent's assistant activity | Not shown. Taku AI is available only after authentication. | Full |
| Notification with `VISIBILITY_SECRET`, or hidden by the stock lock-screen notification settings | Not shown | Not shown on the lock screen at all |
| Activity of a locked work profile, or otherwise still sensitive after authentication | Not shown | Glyph only |

A publisher cannot opt in to more than this. There is no new payload field.

## 5. Compact capsule

### 5.1 Layout

Same geometry as the status bar (Figma `1379:889`): 32 dp disc, the camera, a text pill, joined by
the metaball necks, anchored to the real camera cutout.

| Situation | Disc (left of camera) | Pill (right of camera) | Waiting circle |
| --- | --- | --- | --- |
| Locked, no activity | Closed padlock | None | None |
| Locked, permitted activity | Closed padlock | Group B (text, timer, number) | None |
| Locked, glyph-only activity | Closed padlock | The activity's glyph, 32 dp wide | None |
| Just authenticated (1.5 s) | Open padlock | As above, now with full content | None |
| Authenticated | Activity's group A | Group B | Next activity, where there is room |

- While locked the padlock always holds the disc, so the lock state is never displaced by an
  activity. Only the selected activity is drawn; others wait until authentication.
- After the 1.5 s the disc changes from the open padlock to the activity's glyph in place. The pill
  does not move.
- With no activity, the capsule is the padlock capsule that exists today, and it leaves after 1.5 s.

### 5.2 Space

- The keyguard status bar keeps the carrier text left of the camera and the status icons right of
  it. The pill grows to the right, so it competes with the status icons.
- The pill gets the width between the camera and the first status icon, less 12 dp. Text that does
  not fit is ellipsized; if not even one character fits the pill is left out and only the disc
  shows. These are the status bar's existing rules (`IslandCapsuleView.layoutWidth`).
- The carrier text keeps the end padding it gets today so it stops before the disc.

### 5.3 Gestures

| Gesture | Locked | Authenticated |
| --- | --- | --- |
| Tap | System-tier: opens its card. Other: shows the bouncer; the card opens after authentication. | Opens the card (or the app, when the publisher chose `tap: open`) |
| Long press | Same as tap | Opens the card |
| Swipe left | Nothing | Next activity |
| Swipe right | Nothing | Hides the activity |
| Vertical drag | Passed to the lock screen (shade down, unlock up) | Same |

Taps pass the stock falsing check, like the cards. A capsule that only shows the padlock takes no
touches, as today.

## 6. Expanded card

### 6.1 Placement

- The card is pinned as the first item of the lock screen's card column, 32 dp below the clock,
  full column width, above the notification cards. It scrolls with the list.
- It uses the island's native card views (`IslandTemplateCard`, `IslandProgressCard`,
  `IslandAssistantCard`) and tokens (`IslandStyle`), so a card looks the same on the lock screen and
  when unlocked. It does not use `IslandCardWindow`: no separate window, no blur-behind, and it never
  covers the camera. `cardPosition` is ignored on the lock screen.
- While the card is shown, the capsule keeps only the disc (padlock, or nothing once the padlock has
  left). The pill is not drawn, so the same content does not appear twice. This is the lock screen's
  version of "an open card hides the capsule".
- While locked, the tall clock stays. A system-tier card sits below it; if the two do not fit, the
  clock switches to the compact size, as it does on authentication.

### 6.2 When it opens

| Trigger | Result |
| --- | --- |
| The lock screen appears and a permitted activity is selected | The card is pinned, without animation. This is the lock screen's default: there is room for it and the user is here to glance. |
| A rising `alert` while the lock screen is visible | The card is pinned for that activity and stays. The 5 s alert timeout does not fold it here. |
| Tap on the capsule | Pins the card |
| Authentication | Activities that were glyph-only get their card |

- One card at a time: the selected activity's. Swiping the capsule left moves to the next activity
  and the card follows.
- The user folds it with the card's handle or by swiping it up. It then stays folded for that
  activity until the screen next turns off. A new rising alert pins it again.
- The activity's own notification is left out of the notification cards while its island card or
  capsule represents it, in the same way the status bar's chip is suppressed
  (`IslandPresentationOwnership`).
- A waking alert does not change how the screen turns on. Whether the notification wakes the screen
  stays with Android.

### 6.3 Actions

| Action | Locked | Authenticated |
| --- | --- | --- |
| System controller actions (stop recording, stop casting) | Run | Run |
| Call and alarm actions (answer, decline, hang up, snooze, dismiss) | Run, through the notification's own `PendingIntent`, as stock does | Run |
| A publisher's button that sends a broadcast or starts a service | Not reachable: no card while locked | Run |
| A publisher's button that starts an activity | Not reachable | Runs through `ActivityStarter.startPendingIntentDismissingKeyguard`, leaving the lock screen |
| Tap on the card body (`open`) | System tier: bouncer, then opens | Opens, leaving the lock screen |
| Reply (RemoteInput) | Not available | Not available on the lock screen in this spec. The card shows "Unlock to reply"; tapping it leaves the lock screen and opens the floating card with the keyboard. |

- The card is not focusable and shows no keyboard on the lock screen.
- Rich remote bodies (`SurfacePackage` from the publisher's content service) are not bound on the
  lock screen. The native template shows instead. This keeps an app's live surface off the keyguard.
- Every button passes the stock falsing check.

### 6.4 Leaving the lock screen

- Unlocking with the card pinned does not open the floating card on Home. The activity continues as
  the status-bar capsule. Exception: Taku Agent's existing reveal of a waiting reply after unlock
  (`revealPending`) is unchanged.
- Locking with a floating card open folds it, as today. The lock screen then applies §6.2.

## 7. Settings

| Setting | Storage | Default | GUI |
| --- | --- | --- | --- |
| Show activities on the lock screen | `Settings.Secure` `taku_island_lock_screen` (`0` / `1`) | `1` | A switch on the "Activity island" page |

- Off: the lock screen shows only the padlock, as today. System-tier activities keep Android's own
  lock-screen presentation (full-screen call and alarm, recording notification).
- The island's main switch (`setEnabled`) off also removes activities from the lock screen. The
  padlock is not an activity and is unaffected by either switch.

## 8. Agent interface

The same operations serve both surfaces; none is lock-screen only. Additions:

| Read / operation | Change |
| --- | --- |
| `cmd statusbar island state` → `presentation` | New `surface`: `status-bar`, `lock-screen` or null. New `lockScreen {capsuleVisible, card, redacted}`, where `redacted` is `none`, `glyph` or `hidden` for the selected activity. |
| `cmd statusbar island settings` | New `lockScreen` (boolean) |
| `cmd statusbar island setLockScreen 0\|1` | New. Trusted callers only, like `setEnabled`. Writes the same `Settings.Secure` value as the GUI switch. |
| `expand KEY`, `tap KEY`, `collapse`, `select KEY`, `next`, `dismiss KEY` | Work on the lock screen with the rules of §5.3 and §6. |
| `action KEY JSON`, `open KEY` | Apply the table in §6.3. |
| `cmd statusbar lockscreen state` | New `island {key, expanded}` beside `lockStatus` |
| `cmd statusbar lockscreen notifications` | No longer lists a notification the island represents |

New errors:

- `locked`: the operation needs authentication the caller cannot give. An Agent never authenticates
  for the user. Replaces `presentation_unavailable` for this case.
- `reply_unavailable_on_lock_screen`: `action` with `reply` while the keyguard shows.

Third-party callers keep their existing scope: only activities their own UID published. The
`taku-island` and `taku-open-access` skills document all of the above when it ships.

## 9. Architecture and plan

### 9.1 Structure

- **Unchanged:** `IslandEngine`, `IslandStore`, extraction, ordering, policy, access rules.
- **`IslandPresenter`** keeps selection and the single presentation state. It gains a surface:
  status bar when the keyguard is gone, lock screen when the keyguard shows unoccluded. It asks a new
  `IslandLockScreenPolicy` what the selected activity may show (`full`, `glyph`, `hidden`).
- **`IslandStatusView`** (keyguard capsule host) is generalised from the padlock to "padlock plus
  activity", using `IslandCapsuleView` as it does now. The capsule view learns one new arrangement:
  a status in the disc with an activity in the pill.
- **Card:** a new island view that wraps the native card for inline use, hosted by
  `TakuNotificationListView` as its pinned header. `system/lockscreen` hosts; `system/island` owns
  the view and its behaviour, as with the capsule.
- **Lock-screen policy inputs:** `KeyguardStateController` (showing, dismissible, occluded),
  `NotificationLockscreenUserManager` (visibility, sensitive content), the activity's tier.
- **Patches:** owned sources only, in the existing island and lock-screen patches. No new upstream
  hook is expected.

### 9.2 Phases

| Phase | Content | Verification |
| --- | --- | --- |
| 0 | Run the existing padlock on an emulator (never run so far): position, shackle, hand-over to the status bar, TalkBack. | `system/lockscreen/device-tests/acceptance.py --first-unlock`, capsule render tests |
| 1 | Compact capsule with activities: policy, padlock-plus-pill arrangement, gestures, setting, Agent fields. | Host tests for the policy table; emulator runs for each row of §3 and §4 |
| 2 | Pinned card: inline card view, triggers, actions, de-duplication with notification cards. | Emulator: recording, timer, a Taku-format activity with buttons, a locked work profile |
| 3 | Occluded keyguard (status-bar capsule for system tier) and a screen-off form. Needs its own design. | To be defined |

### 9.3 Acceptance cases

1. Locked, screen recording running: padlock in the disc, timer in the pill, card with Stop pinned.
   Stop works without authentication.
2. Locked, a delivery activity from a third-party app: padlock, the app's glyph in the pill, no text,
   no card. Tap shows the bouncer; after the PIN the card is pinned.
3. Face authentication on the lock screen: padlock opens, 1.5 s later the disc shows the activity's
   glyph; the card appears with full content.
4. A `VISIBILITY_SECRET` activity never appears on the lock screen in either state.
5. Shade pulled over the lock screen: capsule and card hidden, the notification is in the stock stack.
6. The represented notification is absent from the lock screen cards and from
   `lockscreen notifications`, and returns when the activity is hidden.
7. Setting off: only the padlock. `island state` reports `lockScreen: false`.
8. Unlock with a card pinned: Home shows the status-bar capsule, no floating card.
9. An Agent's `expand KEY` while locked on a tier-3 activity returns `locked`; the GUI tap shows the
   bouncer.
10. TalkBack reads the capsule as "Locked. <activity title>" and reaches the card's buttons.

## 10. Owner decisions

Each has a recommendation, which the text above assumes.

| # | Question | Recommendation | Alternative |
| --- | --- | --- | --- |
| D1 | Is the card pinned by default on the lock screen, or only after a tap? | Pinned by default (Xiaomi's behaviour; the lock screen has the room). | Capsule only until tapped, matching the status bar. |
| D2 | While locked, how much of an ordinary activity shows? | Glyph only, consistent with "no notification content while locked". | Honour `VISIBILITY_PUBLIC` and show full content for those. |
| D3 | May system-tier activities show full content and run their stop/answer actions while locked? | Yes; stock Android already allows it. | Require authentication for everything. |
| D4 | Does the padlock keep the disc while locked when an activity is present? | Yes, the lock state is always visible. | The activity takes the disc and the padlock becomes the waiting circle. |
| D5 | Reply on the lock screen | Not in this spec; "Unlock to reply". | Inline composer on the lock screen (lock-screen design `979:1033`). |
| D6 | Screen-off (always-on) form | Phase 3, separate design. | Leave out. |
