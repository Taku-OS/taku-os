# Publishing an activity island

The v1 contract is [spec/taku-island-v1.schema.json](../spec/taku-island-v1.schema.json).
SystemUI renders the shell and template. The source implementation targets the pinned Android 17
image; cloud/physical acceptance is tracked separately in [the ledger](dynamic-island-progress.md).

## Android baseline, no Taku SDK

Post a normal user-authorized ongoing notification with Android Live Updates/promoted ongoing
support. Android still decides promotion and channel/notification eligibility. SystemUI reads
`isPromotedOngoing`, short critical text, chronological fields, small icon, content intent, progress
and notification actions. Compact fallback order is short critical text → chrono/when → icon.
No Taku extras or custom publishing permission are necessary. Nonpromoted notifications stay in
the notification shade. CallStyle/clock notifications additionally have native category adapters.

## Optional custom template

An allowed publisher adds three extras to its normal notification. The POST_ISLAND signature
permission or Settings allow-list authorizes custom publishing; it does not bypass Android's
normal notification permission or channel policy. Use a stable notification tag/id for updates,
cancel it when the activity ends, and use a new identity for a genuinely new activity.

```kotlin
val stop = Notification.Action.Builder(null as Icon?, "Stop", stopPendingIntent).build()
val payload = JSONObject()
    .put("version", 1).put("category", "progress")
    .put("bigIsland", JSONObject().put("leading", JSONObject()
        .put("type", "text").put("text", "Uploading")))
    .put("expanded", JSONObject().put("template", "progress").put("title", "Upload")
        .put("progress", JSONObject().put("type", "progress").put("value", 0.4))
        .put("actions", JSONArray(listOf("stop"))))
val extras = Bundle().apply {
    putString("taku.island.v1", payload.toString())
    putBundle("taku.island.actions", Bundle().apply { putParcelable("stop", stop) })
}
val notification = Notification.Builder(context, channelId)
    .setSmallIcon(R.drawable.ic_upload).setContentTitle("Upload")
    .setContentText("40% complete").setOngoing(true)
    .setContentIntent(openPendingIntent).addAction(stop).addExtras(extras).build()
notificationManager.notify("upload:unique-operation", 1, notification)
```

For a picture slot, put an Android `Icon` under a lowercase key in `taku.island.pics` and use
`{"type":"pic","pic":"key"}`. Resource icons must belong to the posting package or Android;
bitmaps are limited to 512×512 and 1 MiB allocation. URI/data icons are not loaded. Actions must
be real `Notification.Action` objects, with PendingIntents created by the posting UID. Publish only
operations the source supports. The host preserves ordinary notification fallback on any failure.

Limits: 8192 UTF-8 JSON bytes; nesting ≤8; duplicate/unknown keys rejected; strict numeric types;
compact text ≤16 code points; title ≤80; body ≤2000; ≤8 steps of ≤120 code points; ≤4 unique action
IDs matching `[a-z][a-z0-9_]{0,31}`. `primaryAction` must be in `actions`. Templates are text, steps,
progress and media; steps/progress require their corresponding fields. Slots are pic/text/timer/
countdown/progress/number. Epoch timestamps are nonnegative exact integers ≤9007199254740991.
Use the JSON schema for every field; do not send UI markup, scripts or network URLs.

`islandTimeoutS` is 1–28800 (default 3600), measured from the first accepted post; updates cannot
extend the initial deadline. `staleAfterS` lowers stale items within the priority order. A rising
`alert:true` auto-expands for five seconds below the bar; continuous true updates do not extend it.
Critical calls/alarms and Android heads-up take priority. `statusBar` is advisory; IME, rotation,
lockscreen and other SystemUI policy can override it. User dismissal survives updates for the
same notification lifetime and never cancels the notification itself.

## Assistant compact presentation

`compact` accepts `auto`, `hidden` or `building`. Assistant-category notifications are compact-hidden
unless explicitly `building`, including legacy payloads that omit the field. `building` is valid
only for category `assistant`. Other notification categories retain their existing policies.
Expanded cards do not depend on compact visibility. The Agent's explicit app-development marker
is scoped to its current active turn; normal work, voice, errors, replies and approvals do not
qualify. See `assistant/app-build` in the preinstalled open-access skill.

Figma `1:4742` / `52:815`: the loading disc is 32 dp, camera disc 24 dp and text pill 32 dp high;
centres/edges are anchored to the camera, not symmetric halves of an arbitrary 220 dp box.
The two existing authored metaball paths are reused without stretching. The 16 dp loader uses
the designer's `dot-matrix-loader` thinking loop; SystemUI uses the shared native `DotMatrixLoader` timing code.

## How SystemUI draws your slots

Every island is drawn natively by SystemUI in the Taku design (Figma `1379:776` compact,
`1:4031` expanded); publishers supply data and actions, never chrome, and there is no
stock-widget fallback.

- **Compact (big island):** a 32 dp black disc centred 36 dp left of the camera, the 24 dp
  camera disc and a 32 dp pill starting 20 dp right of it, joined by the authored metaball
  necks. The disc shows the first of: a `pic`/`progress`/`animated` `bigIsland.leading`, the
  same kinds of `smallIsland`, a slot's `pic`, the app's small icon (tinted white), or the dot
  loader at rest (`animated:"working"` runs it). The pill shows `bigIsland.trailing` (text,
  timer, countdown, number, or progress as a percentage), else a textual `leading`, in Hanken
  Grotesk 12 sp at 92 % white (`emphasis` `muted` 48 %, `accent` orange). It grows with its
  text and is cut to the room left of the status icons; with no room only the disc remains.
- **Small island:** with several visible activities, the next one appears as a 24 dp disc
  after the pill, drawing `smallIsland` (pic or progress ring) or the app icon.
- **Expanded:** a 92 % black card, 40 dp corners and 16 dp padding. The header has the
  `expanded.pic` (or the app icon) on the left and the expand glyph on the right, which runs
  `primaryAction` or the content intent; tapping the card body does the same and an upward
  swipe collapses it. `title` uses Body/xl (18 sp, 80 %), `body` Body/lg (16 sp, 64 %),
  `steps` 14 sp; `progress` is a 4 dp line (a sweeping segment without `value`). `actions` are
  40 dp pill chips (white 8 %), the `primaryAction` chip filled white. Height follows the
  content up to a third of the screen; longer text scrolls.

A payload SystemUI cannot draw closes the card (`render_failed`) rather than leaving partial
chrome visible; the notification itself is untouched.

## Native assistant cards

Taku Agent publishes `expanded.template:"assistant"` with bounded reply text and
`expanded.assistant:{token,phase,heightDp,suggestions}`. `phase` is `listening`, `working`, or
`message`; `suggestions` contains existing notification action IDs only. Empty arrays render
no option row. A fresh hold publishes only `Taku is listening...`, without canned prompts.
Completed replies use the actual `turn.reply`, then its reported headline when no reply exists.
The primary action opens the conversation (or Keep talking while holding); model option actions
use the same `AssistantSession.reply` as `assistant/reply`.

SystemUI renders the whole card with native Android views (`IslandAssistantCard`), including
Hanken typography, 92% black, 40 dp corners, 16 dp padding, eyes/working dots, model option chips
and edge-only shaders. Height follows content, capped at one third of the display; the text
scrolls and the expand action opens the full conversation. The Agent's Compose island,
`IslandContentService`, snapshot decoder and remote-card action interface are removed.
There is no app-rendered card or generic template transition for assistant notifications.
Stock images without this SystemUI host report `listeningHeaderHost:"unavailable"`.

Read `cmd statusbar island state` / the island provider: `presentation.card.renderer` is
`systemui-native`, `layout` is `native-assistant`, `nativeReady` reports attachment and
`assistant:{renderer,text,suggestionCount}` reads the native view's current content. The
notification `get KEY` payload exposes option IDs/labels;
`cmd statusbar island action 'KEY' '{"id":"ID"}'` uses the GUI action.
`richReady` stays false: no remote surface is involved. Invalid schema still returns the
existing `invalid_payload` rejection; missing suggestion actions are rejected, never invented.

## Optional separate rich-content process

`expanded.content.service` is `package/full.ServiceClass`, in the posting package, exported and
signature-protected. SystemUI must hold that permission; the supported shared permission is
`org.agentos.permission.BIND_ISLAND_CONTENT`. The opaque token is ≤256 code points. Put the host
in a separate process, reuse the native template as fallback and keep task state in its existing
owner. This optional protocol is for other producers, not Taku assistant cards. Its test implementation is the isolated island fixture; the shared wire is
[`IslandContentProtocol.java`](../system/island/src/org/agentos/island/IslandContentProtocol.java).

Optional `expanded.content.layout` is `body` (default) or `card`. A `card` supplies its own
header and actions in the remote surface; SystemUI shows the native template only while binding
or after failure. `heightDp` is an optional integer from 96 to 340, further capped to one third
of the screen. The host keeps 8 dp screen margins, 40 dp corners and a rounded touch region.
Full cards are non-focusable even over the status bar, so listening/composer focus stays in the
underlying Activity. The translucent SurfaceView is above its host window so Android delivers
touches to the embedded controls. While a card covers it, the underlying status-bar content
(including the compact capsule) is hidden; collapse or a switch below the IME restores it.
Paint the card background once in the remote surface; SystemUI removes
its fallback background after READY. Taku assistant cards never use this path.
Figma node `297:415` keeps its title unfiltered. Only the final 16 dp of the chip row uses
the authored edge refraction (flare .25, dispersion .4); shader samples outside the layer return
transparent pixels instead of extending a vertical strip. Chip line height is 24 dp (1.5×16).

Bind returns a Messenger. ATTACH (`what=1`) has generation in `arg1`, a `replyTo` Messenger, and
Bundle keys `version=1`, `hostToken` IBinder, `width`, `height`, `display`, `token`. Create a
SurfaceControlViewHost for that display/token, attach a bounded View, then reply READY (`what=3`)
with the same generation and Bundle `surface` SurfacePackage **after its first frame**. Reply FAILED
(`what=4`) on failure. RELEASE (`what=2`) tears down that generation. Observe reply binder death;
release surfaces, lifecycle and View resources. Reject oversized/mismatched/stale requests; never
perform blocking backend work in the SystemUI binder callback. Current host timeout is 2500 ms,
dimensions ≤4096; the reference producer also caps area at 4 million pixels and two hosts.

There is no automatic retry loop for a failed rich body in the same open card: close/reopen to
rebind, while the native template and actions remain available. The producer must not cancel work
when its content service is destroyed. Android platform signing is a cloud-build prerequisite for
the bundled Agent; it retains an ordinary UID.

## Inspection

Use the preinstalled [Agent commands](../system/assistant/assets/skills/taku-island/SKILL.md).
`cmd statusbar island-validate PACKAGE JSON` runs the same strict parser without granting posting
permission or executing anything. Diagnostics distinguish denied/invalid extras from normal
fallback. Tests and the final cloud/device checklist are in [the handoff](dynamic-island-handoff.md).
