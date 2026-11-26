---
name: taku-island
description: Read and operate SystemUI activity islands and their settings through the same Android provider used by the GUI.
---

# System activity islands

The owner is SystemUI, not the launcher or AI content process. Discover capabilities before using
these commands: old images do not have the provider. Commands below run in an Android shell;
from a workstation prefix them with `adb -s SERIAL shell`. No private file or JS state is authoritative.

```sh
content query --uri content://com.android.systemui.island/capabilities
content query --uri 'content://com.android.systemui.island/canShowIsland?package=org.example.app'
content query --uri content://com.android.systemui.island/state
content query --uri content://com.android.systemui.island/list
content call --uri content://com.android.systemui.island --method get --arg 'KEY'
cmd statusbar island state
cmd statusbar island get 'KEY'
cmd statusbar island select 'KEY'
cmd statusbar island next
cmd statusbar island tap 'KEY'
cmd statusbar island expand 'KEY'
cmd statusbar island collapse
cmd statusbar island dismiss 'KEY'
cmd statusbar island hide 'KEY'
cmd statusbar island open 'KEY'
cmd statusbar island action 'KEY' '{"id":"stop"}'
cmd statusbar island action 'KEY' '{"id":"action_0","reply":"Continue"}'
```

## Caller access

Root, system and ADB shell (`UID 0/1000/2000`), SystemUI itself and platform-signed packages
use every operation below directly. Ordinary third-party apps (`UID >= 10000` without the
platform certificate) are governed by the App Permission Center
(`content://org.agentos.assistant.permissions`, default `deny`):

- Without a grant they may call only `capabilities` and `canShowIsland` for **their own**
  package. Omitting the package answers for the caller's own single package. Asking about
  another package returns `permission_denied`.
- A `taku.control` grant allows reading and operating **only the activities the caller's own
  UID published**. `state` and `list` return those activities plus `settings.enabled` and
  `settings.protocol` (no allow-list, `presentation` or `diagnostics`); `settings` returns the
  same two fields. `get`, `select`, `tap`, `expand`, `dismiss`, `hide`, `open` and `action`
  (including `reply`, which fills a `RemoteInput`) accept the caller's own keys; another app's
  key returns `not_found`, exactly like a missing one.
- `collapse` and `next` act on whichever activity is presented, and `setEnabled`, `setAllowed`
  and `setProtocol` change settings: all return `permission_denied` for third-party apps, even
  with a grant. Use the GUI, ADB or a trusted Agent.
- Trusted callers are unaffected: an Agent still reads every activity and may operate or reply
  on any of them.

An unavailable permission center counts as a refusal. `cmd statusbar island` is shell-only and
unaffected.

Always take KEY and action IDs from `list`/`get`; never construct notification keys or assume
`action_0` means stop. `availableActions` supplies IDs and producer labels. `open` invokes the
notification content intent (or its full-screen intent if no content intent exists). CallStyle
answer/decline/hangup and native recording/casting stop actions are exposed here when available.
Only invoke the operation the user requested. Reply requires the action's free-form RemoteInput;
reply text is limited to 4,000 characters. AI suggestion actions instead use their published IDs.

Queries return one `json` column. Calls return Bundle field `json`; `ok:false,error:CODE` is a
failure, not success. Every cmd operation is also a provider call with the same method. `--arg`
is its first parameter; `--extra value:s:JSON` carries its second parameter, for example:

```sh
content call --uri content://com.android.systemui.island --method action --arg 'KEY' --extra 'value:s:{"id":"stop"}'
```

`state` returns policy, active activities and actual presentation (selected key, compact visibility,
card geometry, rich readiness, focusability and reason). `get` additionally returns revision,
firstSeenElapsedMs, triggeredAtElapsedMs, expiresElapsedMs, stale, dismissed, `source`
(`custom` for `taku.island.v2` extras, `live-update` or `system`), `tier` (0 call, 1 alarm, 2 recording/cast,
3 every other activity), `timeoutKind` (`explicit`, `default` or `cap`) and payload. `payload` is
always the `taku.island.v2` shape (`compact.a`/`compact.b`, `expanded`, `tap`, `cardPosition`,
`timeoutS`), also for Live Updates and system sources. The list contains active,
undismissed items even when the global switch hides them; presentation says what is on screen.

Order: system tier first, then an activity inside its five-second alert, then the most recently
triggered activity. A trigger is the first post or a false-to-true `alert`; routine updates never
reorder. An island ends when its notification is cancelled, at its `timeoutS` (or the Live
Update's `setTimeoutAfter`), one hour after the first post when neither is given, or at the 12 h
cap. Updates cannot extend that deadline. The notification itself stays in the shade.
`dismiss` hides only this island for that source lifetime. It does not stop a task, end a call,
cancel a notification, or permit the producer to redisplay it by updating the same key.
Use the explicit source action to stop work. A removed/reposted notification begins a new lifetime.
## Gestures and their commands

The island has two mutually exclusive states: the compact capsule and the expanded card. While
any card is open no capsule is drawn. Each capsule gesture has a command reaching the same code:

| Capsule gesture | Command | Effect |
|---|---|---|
| Tap | `tap KEY` | `payload.tap` `open` sends the content intent (falls back to expanding when there is none); `expand` (default) opens the card |
| Long press | `expand KEY` | Opens the card regardless of `tap` |
| Swipe left | `next` | Selects the next activity that can show a capsule; `no_other_activity` when there is none |
| Swipe right | `dismiss KEY` / `hide KEY` | Hides that activity for its notification lifetime |
| Tap the waiting circle (left of the disc) | `next` | Brings the waiting activity forward |

Card taps that open the publisher's screen (`open`, `tap` with `open`, or an `action` whose
PendingIntent starts an activity) fold the card (`reason` `opened:KEY`), and the card also folds
when its app comes to the front (`app_opened`). Taku Agent's own cards always cover the camera;
other publishers' `cardPosition` decides, and the keyboard never changes it. Taku Agent's card also
expands without a tap once when a turn the user left running in the full chat completes, needs the
user or fails; nothing is drawn while that turn runs. Home owns that handoff
(`assistant/handoff`, `assistant/reclaim` in the open-access skill).

`select KEY` chooses the primary item; it holds until a newer activity is triggered or it ends.
The activity chooser card and `choose` no longer exist. A capsule is not drawn while its
publishing app is the top task (the app already shows it); it appears when the user leaves the
app. The default home app and SystemUI's own recording/cast sources are exempt.
`state.presentation.suppressedByForeground` lists those keys, `foregroundPackage` the top app,
`secondaryKey` the activity drawn as the 32 dp waiting circle left of the capsule, and `tap` the
selected activity's tap behaviour. An `alert` from the app in front does not expand.

A Taku-format island activity (`taku.island.v2`) does not also raise a stock heads-up while
the island can present it (unlocked, shade closed, portrait, island enabled, activity not hidden);
its `alert` expands the card instead, at its `cardPosition`. A false-to-true `alert` counts at
most once per 30 s per notification key (cancel and repost included); faster flips neither expand
nor reorder. Each accepted five-second alert window expands at most once: after `collapse` or a
GUI upward swipe, ordinary updates (`alert: false`, held `true`, or cooldown-rejected flips) do
not reopen the card. A new accepted false-to-true alert after the cooldown may expand again.
Read `cmd statusbar island state` → `presentation.expanded` to verify the same GUI state.
While a call, alarm, recording or cast is live it holds the head of the order, so
other activities keep their stock heads-up. Third-party custom extras on a notification that is not ongoing are ignored with
diagnostic `not_ongoing` (Taku Agent is exempt). Calls, alarms, plain Android Live
Updates (which have no alert field), hidden activities and invalid extras keep Android's heads-up.
Calls, alarms and stopwatches that SystemUI recognises stay up to the 12 h cap.

An activity without capsule data
(no `compact`, such as Taku Agent outside app building) never takes the capsule; the next
activity with one is shown. It remains available to `expand KEY`; expanding it keeps that card
selected until collapse. Read `state.presentation` to inspect the actual selected key and
compact visibility.

To check a payload without posting it: `cmd statusbar island-validate PACKAGE 'JSON'`. It runs the
production parser with the package's real platform-signature identity, and returns `{"valid":…,"error":…}`. It grants nothing and runs no
action. The publisher contract is `docs/dynamic-island-developer.md` in the source tree.

Every island SystemUI shows is drawn natively in the Taku design; there is no stock-widget
fallback. `state.presentation.compact` (null when hidden) reports what the capsule shows:
`text` (the pill as drawn, ending in `…` when cut; empty only when not even one character and the ellipsis fit), `disc` (`dot` when there is no glyph, `pic`, `progress`,
`working` or `listening`), `pillGlyph` (group B drawn as `pic`, `working` or `listening`, else
null), `animatingIcons` (running app animated icons; each loops for at most 60 s and never
with animations disabled), `secondary` (a second activity's circle), `secondaryPosition` (`left`),
`cameraRadiusDp` (radius from the visible `DisplayCutout.getCutoutPath()`, not the padded
`boundingRectTop` exclusion; defaults to `12 * scale` when no centred circular path is available) and
`scale` (below 1 on a status bar shorter than 32 dp). `state.presentation.card`
reports `renderer` (`systemui-native`, or `remote-surface` once a rich body is ready) and, for
non-assistant cards, `template:{title,template,highlight,backgroundColor,accentColor,tapAction,body,actionCount,progress}` read from the drawn
views, plus `cover`, `fill` (ARGB of the card fill) and `backdropBlur` (false when compositor
blur is off and the opaque fallback is drawn). A card whose content cannot be drawn closes with `reason` `render_failed:...` (or
`window_failed:...` when it never opened) instead of staying on screen.

All summary entry paths (returning from the publishing app, collapsing a card, selecting another
activity, and Android Live Updates) use the same renderer. `state.presentation.compact.geometry`
reports the drawn `cameraCenterX`/`cameraCenterY` in screen pixels, `discDiameterDp`, `pillHeightDp`,
`pillWidthDp`, and `secondaryGapDp` (null when there is no waiting circle). The normal disc/pill
height and minimum pill width are 32 dp; the waiting-circle gap is 4 dp, scaled on short bars.
Use `cmd statusbar island state` before and after `collapse` / `next` / `select KEY` to compare
geometry. `compact` and `secondaryKey` are null while a card hides the summaries. Both actually
drawn activities replace their identical upstream chips; unrepresented activities keep theirs.

## Settings shared with the GUI

Open Android Settings → Display → Activity island, or use the explicit activity:

```sh
am start -n com.android.systemui/com.android.systemui.taku.island.IslandSettingsActivity
cmd statusbar island settings
cmd statusbar island setEnabled 0
cmd statusbar island setEnabled 1
cmd statusbar island setAllowed org.example.app,org.example.other
content call --uri content://com.android.systemui.island --method setAllowed --arg ''
settings get global taku_island_enabled
settings get global taku_island_protocol
settings get global taku_island_allowed_packages
```

Apps signed with the platform certificate (the same certificate as SystemUI, such as Taku Agent
and Notebook) may publish custom extras without any entry; the allow-list holds reviewed
third-party packages only and starts empty. `canShowIsland` reports `customAllowed` and
`platformSigned`. `setAllowed` replaces the **whole** allow-list; read first, preserve unrelated entries, then write
and read back. Empty clears it. At most 128 comma-separated Android package names, each ≤255
characters; total input ≤32768 characters. Leading/trailing whitespace is trimmed, duplicates
removed, storage sorted. Noninstalled names are allowed for later installation. The list governs
custom publishing by apps without the platform certificate, not Agent access and not Android
Live Updates. The GUI and Agent operations use the same provider validation.

Enabled is strictly `0` or `1`; it hides islands without stopping sources. Global settings affect
all users; only the current unlocked/profile-available user's activities are presented. Protocol
is capability value `2` (`taku.island.v2`, the only custom protocol), not user configuration. `setProtocol` returns `read_only_protocol`. Global
Settings has no per-key immutable primitive: invalid direct `settings put` values are restored by
an observer; use the provider for immediate validation. Publishing a custom notification requires
the platform certificate or this allow-list, in addition to normal Android notification
permissions.

Errors: `permission_denied` (caller not allowed; see Caller access), `no_other_activity` (`next` with fewer than two capsules), `not_found` (removed/dismissed/expired activity), `island_disabled`, `presentation_unavailable`
(no attached bar, locked/shade/heads-up/landscape, or window failure), `action_unavailable`,
`reply_unavailable`, `reply_too_long`, `missing_action`, `missing_key`, `invalid_enabled`,
`invalid_allowed_packages`, `read_only_protocol`, `settings_write_failed`, `source_ended`,
`unknown_operation`, `unsupported_query`, `systemui_starting`, `systemui_unavailable`,
`systemui_timeout`. Other malformed JSON/Android PendingIntent errors retain the platform message.
After a timeout, read state before retrying: the operation may already have started. A cancelled
PendingIntent is not replaced with an invented broadcast. For diagnostics:

```sh
dumpsys activity service com.android.systemui/.SystemUIService
```

## Actual upstream limits

There is no universal Android database of third-party timers, snooze controls, call transcripts,
AI tasks, delivery locations or arbitrary app UI. Only published notification fields/actions and
existing recording/casting controllers are available. A clock that omits a snooze PendingIntent
cannot be snoozed through this island API. A nonpromoted ordinary notification is not automatically
an island; invalid custom data falls back to an eligible Live Update or remains an ordinary notification.
Full-screen call/alarm and heads-up policy remain with Android. The island does not dismiss them.
No lockscreen/AOD or secondary-display island presentation is promised yet.

The rich body token is opaque and actions remain with the notification owner. Content-host failure
leaves the native template; it does not cancel the backend task. Capsule/counter geometry and
SystemUI process acceptance require the patched image; an APK-only install on a stock emulator is
not a successful SystemUI migration. Follow the build's acceptance ledger before claiming GUI parity.

Recording/casting durations start at the current SystemUI observation because these controller APIs
do not expose a durable session start timestamp or generation ID. SystemUI restart preserves the
observed lifetime/dismissal, but cannot distinguish a stop-and-restart entirely missed while it was
dead. Do not report that counter as an upstream authoritative recording duration.

A dismissed card stays dismissed during passive updates. A new voice entry, text send, or
explicit expand creates a fresh presentation request, including after process recovery.
All card windows preserve the underlying app's input focus; swipe/header and the `collapse`
command close them. Landscape closes expanded cards and restores the status bar.

### Expanded Taku setup progress

Simple setup progress cards use native, content-sized SystemUI chrome. `Close`
only folds the card (`cmd statusbar island collapse`); it does not cancel sign-in.
`Back to setup` uses the notification action shown by `get` / `list` (normally
`cmd statusbar island action KEY '{"id":"open"}'`). The body remains the current
notification text; expanding a sign-in does not mean onboarding is complete.
`state` exposes `presentation.card.layout=native-progress`, `renderer`,
`nativeReady`, `focusable`, and `progress.text` / `progress.buttonCount`.
