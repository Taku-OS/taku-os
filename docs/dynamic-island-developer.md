# Dynamic Island developer guide

English · [简体中文](dynamic-island-developer.zh-CN.md)

This guide explains how an Android app shows an ongoing activity in the Taku OS Dynamic Island.
Contract: [`spec/taku-island-v2.schema.json`](../spec/taku-island-v2.schema.json). Design and
decisions: [v2 spec](dynamic-island-v2-spec.md). Source and on-device acceptance status:
[the ledger](dynamic-island-progress.md).

There is no Taku SDK. Everything goes through an ordinary Android notification:

- **Path A, Android Live Updates (recommended first).** Post a promoted ongoing notification. It
  works on stock Android and other OEMs, and needs no Taku approval.
- **Path B, the Taku extension `taku.island.v2`.** Add JSON extras to the same notification when
  you need what Live Updates cannot express:
  - your own capsule icon or animation, numbers or percentages,
  - the card templates, layout and accent colour,
  - opening the app versus expanding on capsule tap,
  - card position,
  - automatic expansion.

  Post a valid Live Update as well, so the notification degrades gracefully everywhere else.

## 1. When to use the island

**Use it for** ongoing activities or services that usually last minutes to hours and show
information the user is actively following. Examples: a pickup code, flight or train status, a
delivery in progress, navigation, an active voice recording, a timer.

**Every activity needs** a clear start, a clear end (you cancel the notification), and a total
duration of **12 hours or less**.

**Do not use it for:**
- Events that last seconds. Use a normal notification, a heads-up or an in-app toast instead.
- Static results: "update finished", "signed in", "network connected", "saved", app errors.
- Marketing or promotions.
- Frequent interruptions such as likes and comments.
- Services with no clear end, such as continuous stock tracking.

Allow-listing for Path B is reviewed against these rules. SystemUI enforces the time limits.

## 2. What users see

The island has two states, never both at once:

- **Compact capsule.** A black capsule around the front camera: a 32 dp disc, the camera and a
  32 dp pill, joined by smooth necks that follow the device's real cutout size.
  - **Group A**, left of the camera, is one glyph: an icon, an animated icon, or a progress ring.
  - **Group B**, right of the camera, is key information as a short phrase or number, a running
    timer, a percentage, or an icon.
  - Text is a single line and is ellipsized to the room left of the status icons. There is no
    scrolling and no font shrinking. Text that does not fit shows as much as fits and ends with an
    ellipsis (…). Only when not even one character and the ellipsis fit does group A stand alone.
- **Expanded card.** A card with 40 dp corners, 8 dp from the screen edges, drawn natively by
  SystemUI from a template: black at 92 % over a 24 dp blur of the screen behind it, or your
  `backgroundColor` (a white card, say). It sits over the camera cutout (`cardPosition: "cover"`,
  default) or below it (`"below"`), exactly as you choose; the keyboard only limits its height.
  Taku Agent's own cards always cover the cutout.

Gestures:

| Where | Gesture | Result |
|---|---|---|
| Capsule | Tap | `tap: "expand"` (default) opens the card; `tap: "open"` sends your content intent |
| Capsule | Long press | Opens the card, whatever `tap` says |
| Capsule | Swipe left | Next activity, when several are active |
| Capsule | Tap the circle on the left | Shows that waiting activity |
| Capsule | Swipe right | Hides your activity until its notification ends |
| Card | Tap the body | Your `tapAction`, else your content intent. The card folds once your screen opens |
| Card | Swipe up / Back | Back to the capsule |

System behaviour you can rely on:

- **Several activities.** SystemUI manages them; you publish only your own. Calls, alarms and
  screen recording come first. After that, the most recently *triggered* activity is shown. A
  trigger is the first post, or an update that raises `alert` from false to true; routine updates
  never reorder. The next activity waits as a 32 dp circle on the left of the capsule, showing its
  group A glyph; the capsule itself does not move.
- **Your app in front.** Your capsule is not drawn while your app is the top task, your card folds
  when your app comes to the front, and an `alert` does not expand. The capsule appears when the
  user leaves the app, if you supplied capsule data.
- **Heads-up.** For Path B activities, while the island can present them (unlocked, shade closed,
  portrait), the notification does not also pop a stock heads-up. Use `alert: true` to expand the
  card briefly instead. Plain Live Updates (Path A) keep Android's normal heads-up.
- **Lifetime.** Your island ends at the first of:
  - you cancel the notification,
  - your declared timeout (`timeoutS` or `setTimeoutAfter`),
  - **one hour after the first post** if you declared none,
  - 12 hours.

  Updates never extend it. When island time ends, the notification itself stays in the shade.
- **Not yet supported:** lock screen, AOD and secondary displays. On the lock screen and in the
  shade, users see your ordinary notification row, so keep its title, text and actions complete.

## 3. Path A: Android Live Updates

Requirements are Android's:
- an ongoing notification with a content title,
- a standard, BigText, Call or `ProgressStyle` style,
- no custom views, not colorized, not a group summary,
- `android.permission.POST_PROMOTED_NOTIFICATIONS` declared in your manifest,
- a promotion request.

The user can turn promotion off per app; check
`NotificationManager.canPostPromotedNotifications()`.

```kotlin
val notification = Notification.Builder(context, channelId)
    .setSmallIcon(R.drawable.ic_delivery)          // group A when there is no large icon (tinted white)
    .setLargeIcon(courierIcon)                      // group A, untinted
    .setContentTitle("Delivery on the way")         // card title
    .setContentText("Courier is 12 minutes away")   // card body
    .setShortCriticalText("12 min")                 // group B
    .setStyle(Notification.ProgressStyle()
        .setProgressSegments(listOf(Notification.ProgressStyle.Segment(100))).setProgress(40))
    .setOngoing(true)
    .setRequestPromotedOngoing(true)                // API 36.1; on API 36 put extra "android.requestPromotedOngoing"
    .setTimeoutAfter(2 * 60 * 60 * 1000L)           // the island's declared end (≤ 12 h)
    .setContentIntent(openOrderPendingIntent)
    .addAction(cancelAction)                        // becomes a card chip
    .build()
notificationManager.notify(TAG, ID, notification)
```

| Notification field | Island |
|---|---|
| Large icon, else small icon (tinted) | Group A |
| `setShortCriticalText`, else a chronometer (`setUsesChronometer` / count-down), else the `when` time | Group B |
| `ProgressStyle` progress (or legacy `setProgress`) | `progress` card with a progress line |
| Title / text | Card title / body |
| Actions | Card chips, in order |
| Content intent | Card tap and the capsule's expand glyph |
| `setTimeoutAfter` | Island lifetime (otherwise 1 h) |

Live Updates always use `tap: expand` and `cardPosition: cover`.

## 4. Path B: `taku.island.v2`

### Authorization

Custom extras are read only from an authorized package:
- **Apps signed with the platform certificate** (the same certificate as SystemUI, such as Taku
  Agent and Notebook) are authorized automatically. There is nothing to declare and no package
  name to register.
- **Other apps** must be added to the user-visible allow-list (Settings → Display → Activity island,
  or `taku_island_allowed_packages`) after review against §1. Contact the Taku OS team to request
  it.

`canShowIsland` reports `customAllowed` and `platformSigned` for your package (§5).

Without authorization, or with invalid extras, SystemUI ignores the extras and shows your Live
Update presentation, if any. The notification is never dropped.

### Example: a voice memo

```kotlin
val stop = Notification.Action.Builder(null as Icon?, "Stop", stopPendingIntent).build()
val payload = JSONObject()
    .put("version", 2)
    .put("tap", "open")                 // tapping the capsule opens the recorder
    .put("timeoutS", 7200)              // declared end: at most 2 h of recording
    .put("compact", JSONObject()
        .put("a", JSONObject().put("type", "icon").put("pic", "mic").put("tint", true))
        .put("b", JSONObject().put("type", "timer").put("startEpochMs", startedAt)))
    .put("expanded", JSONObject()
        .put("template", "basic")
        .put("title", "Recording voice memo")
        .put("body", "Inbox · 00:42 saved so far")
        .put("actions", JSONArray(listOf("stop")))
        .put("primaryAction", "stop"))
val extras = Bundle().apply {
    putString("taku.island.v2", payload.toString())
    putBundle("taku.island.pics", Bundle().apply { putParcelable("mic", Icon.createWithResource(context, R.drawable.ic_mic)) })
    putBundle("taku.island.actions", Bundle().apply { putParcelable("stop", stop) })
}
val notification = Notification.Builder(context, channelId)
    .setSmallIcon(R.drawable.ic_mic).setContentTitle("Recording voice memo")
    .setOngoing(true).setUsesChronometer(true).setWhen(startedAt)
    .setContentIntent(openRecorderPendingIntent).addAction(stop).addExtras(extras).build()
notificationManager.notify("memo:" + memoId, 1, notification)
```

Re-post with the same tag and id to update. Cancel the notification to end the activity. Use a new
tag or id for a genuinely new activity.

### Payload reference

| Field | Rules |
|---|---|
| `version` | `2` |
| `compact` | Optional. Without it, the activity never shows a capsule |
| `compact.a` | `{"type":"icon","pic":KEY,"animate"?,"progress"?,"tint"?,"contentDescription"?}` or `{"type":"progress","value"?}`. `animate` runs an AnimatedVectorDrawable resource for up to 60 s per update, never when the user disabled animations. `progress` (0–1) draws a ring around the icon |
| `compact.b` | `{"type":"text","text":≤16,"emphasis":"normal"\|"accent"\|"muted"}`, `{"type":"number","value":"1"-"999","unit"?:≤4}`, `{"type":"timer","startEpochMs"}`, `{"type":"countdown","endEpochMs"}`, `{"type":"progress","value"?}` (shown as a percentage) or an `icon` |
| `animation` slots | `ai-working` and `ai-listening` belong to Taku Agent; others get `invalid_animation` |
| `expanded.template` | `basic` (text and actions), `progress` (adds a progress line; needs `progress`), `highlight` (adds one large value; needs `highlight`). `assistant` is Taku Agent's |
| `expanded.title` / `body` | ≤ 80 / ≤ 2000 characters |
| `expanded.pic` | Header icon; defaults to your small icon |
| `expanded.steps` | ≤ 8 rows of `{kind,text≤120,running?}` |
| `expanded.progress` | `{"value"?: 0–1}`; omit `value` for indeterminate |
| `expanded.highlight` | `{"text":≤12,"label"?:≤40}`: pickup code, gate, queue number |
| `expanded.actions` | Keys into `taku.island.actions`, in display order, as one horizontally scrolling chip row. No design limit; 32 is a parsing bound |
| `expanded.primaryAction` | One of `actions`, drawn filled |
| `expanded.tapAction` | Action key sent when the card body is tapped. It need not be a chip. Default: your content intent |
| `expanded.backgroundColor` | Template style: `#RRGGBB` card background, drawn at 92 % over the blur. On a light colour (such as `#FFFFFF`) text, chips, the progress track and the corner glyph turn black automatically. Not for `assistant` |
| `expanded.accentColor` | `#RRGGBB` with contrast ≥ 4.5:1 against the card: `backgroundColor` if set, else `#121212` (else `low_contrast`). Used for the primary chip, the progress line, the highlight value and `accent` capsule text |
| `tap` | `expand` (default) or `open` |
| `cardPosition` | `cover` (default) or `below` |
| `alert` | A false-to-true change expands the card for ≤ 5 s and moves your activity to the front. At most once per 30 s per notification (cancelling and re-posting does not reset this); faster flips are ignored |
| `timeoutS` | 1–43200. Island lifetime from the first post; default 3600 |
| `staleAfterS` | 1–43200. Without updates for this long, the capsule is drawn muted |

Shared rules:
- The notification must be ongoing (`setOngoing(true)`); otherwise `not_ongoing`. One-off
  results and errors belong in the shade.
- 8192 UTF-8 bytes; nesting ≤ 8.
- Unknown or duplicate keys and wrong types are rejected.
- Keys match `[a-z][a-z0-9_]{0,31}`.
- Images are `Icon`s in `taku.island.pics`: bitmaps up to 512×512 px and 1 MiB, or resources of
  your own package. URI and data icons are not loaded.
- Actions are real `Notification.Action`s whose PendingIntents your app created.
- No markup, scripts or URLs.

`taku.island.v2` is the only Taku Island protocol; there is no earlier format to support or
fall back to.

### Design constraints

Keep group B to a short phrase or number. As a rule of thumb, use about 8 Latin or 4 CJK
characters. The capsule is shared with the clock and status icons, and long text is cut. Card
content must fit its template: text scrolls inside the card, and the card is at most a third of
the screen tall. Further visual constraints will be published here by the Taku design team.

## 5. Checking and debugging

From your app, ask whether you can publish. You may only ask about your own package, and omitting
the package answers for it:

```kotlin
// Add <queries><provider android:authorities="com.android.systemui.island" /></queries> to your manifest.
context.contentResolver.query(Uri.parse("content://com.android.systemui.island/canShowIsland"),
    null, null, null, null)?.use { if (it.moveToFirst()) JSONObject(it.getString(0)) }
// → {"ok":true,"enabled":true,"package":"…","customAllowed":false,"platformSigned":false,"liveUpdates":true}
```

Over ADB (a development shell has full access):

```sh
adb shell cmd statusbar island-validate org.example.app '{"version":2,…}'   # same parser and publisher checks
adb shell cmd statusbar island state       # activities, what is on screen, diagnostics
adb shell cmd statusbar island get 'KEY'   # canonical payload, source, tier, triggeredAtElapsedMs, timeoutKind
```

`diagnostics` lists `KEY:error` entries. Common codes:

| Code | Meaning |
|---|---|
| `publisher_not_allowed` | Not signed with the platform certificate and not on the allow-list |
| `not_ongoing` | The notification is not ongoing |
| `invalid_references_or_host` | A `pic` or action key is missing from its bundle, an icon is not allowed, or a PendingIntent is not yours |
| `unknown_field`, `invalid_enum`, `text_length`, `number_range`, `invalid_key`, `expected_*` | Schema violations |
| `missing_progress`, `missing_highlight`, `highlight_template_only` | A template without its data, or data on the wrong template |
| `missing_primary_action`, `duplicate_action`, `too_many_actions` | Action list problems |
| `invalid_color`, `low_contrast` | `backgroundColor` or `accentColor` problems |
| `reserved_template`, `invalid_animation`, `reserved_content` | Taku Agent or platform-only features |
| `payload_too_large`, `too_deep`, `unbalanced_json`, `invalid_payload` | Malformed JSON |

Third-party apps cannot read other apps' activities. Reading or operating the island from an app
needs a `taku.control` grant in the App Permission Center, and settings writes are never available
to apps (see the [Agent commands](../system/assistant/assets/skills/taku-island/SKILL.md)).

## 6. Platform-signed apps: rich content process

`expanded.content` embeds a body rendered by your own process (`SurfaceControlViewHost`). It is
limited to **platform-signed** publishers (else `reserved_content`).

- `service` is `package/full.ServiceClass` in the posting package: exported and protected by the
  signature permission `org.agentos.permission.BIND_ISLAND_CONTENT`.
- `token` is opaque, ≤ 256 characters. `layout` is `body` (default) or `card`. `heightDp` is 96–340
  and is further capped to a third of the screen.

Protocol ([`IslandContentProtocol.java`](../system/island/src/org/agentos/island/IslandContentProtocol.java)):
- Binding returns a Messenger.
- ATTACH (`what=1`) carries the generation in `arg1`, a `replyTo` Messenger, and Bundle keys
  `version=1`, `hostToken`, `width`, `height`, `display` and `token`.
- Reply READY (`what=3`) with Bundle `surface` (a SurfacePackage) after the first frame, or FAILED
  (`what=4`).
- RELEASE (`what=2`) tears down that generation.
- The host timeout is 2500 ms, and dimensions are ≤ 4096.
- The native template stays visible while binding and after a failure. There is no automatic retry
  in the same open card.
- Never cancel work when the content service dies.

## 7. Taku Agent

Taku Agent posts `taku.island.v2` like any other publisher.
- Its card uses the reserved `assistant` template, `expanded.assistant:{token,phase,heightDp,suggestions}`.
  It is drawn by `IslandAssistantCard`, with `suggestions` naming real action IDs only.
- It shows a capsule (`ai-working` plus "Building...") only while building an app.
- Its onboarding sign-in island uses Taku's own progress card chrome.
