# Taku Home feed contract (`taku.feed-card/1`)

The feed is one pipeline with replaceable sources:

```text
Memory reminders (`feed/publish`) ─┐
agent `content call … publish`     ├─► FeedCard (this contract) ─► FeedRepository ─► Compose shell + RN card body
future recommendation source      ─┘                                   ▲
                                          content://org.agentos.agent.home-feed (Agent)
```

The native shell (Kotlin + Compose, `org.agentos.agent.home`) owns the page, the
glass card surface, ordering, paging, pinning, removal, long-press, blur and
animation. The card **body** is AI-generatable UI and is rendered by React Native
with `@json-render` from `ui.spec`. Pinned pills are rendered natively from `pin`.

Production registers no in-process sample source. An empty Feed shows an **Ask Taku**
invitation after loading succeeds, provided both the card list and pinned row are empty.
The native invitation uses the shared Figma card tokens and opens the text composer;
it is not a published FeedCard and never enters the database, event log, or pin/removal list.
`feed/list.emptyState` exposes its text and action descriptor from the same `FeedState`
as the GUI (otherwise `null`). Its action is `assistant/open {"mode":"text"}` on
`content://org.agentos.agent.home-assistant`; `assistant/get` reads back `entry:"text"`.
Loading failures keep their retry action (`feed/load-more`).
Sample JSON remains a rendering/test fixture only. Upgrade retires source-only
pins/removals and preserves published cards and their management state.

## FeedCard

```jsonc
{
  "schema": "taku.feed-card/1",
  "id": "slack-uiux-review",            // stable, ^[A-Za-z0-9._:-]{1,96}$
  "source": {
    "id": "sample",                      // which FeedSource produced it: sample | agent | recommendation | system
    "app": "Slack",                      // display name of the originating app/service
    "package": "com.Slack",              // optional Android package, used by open-app intents
    "icon": "asset:feed/slack/logo-slack-16.svg"
  },
  "layout": {
    "span": "full",                      // full (380dp) | half (two halves share one row, square)
    "surface": "card"                    // card: surface/900, radius 24, padding 12
                                         // control: surface/600, fully rounded, padding 4, height 64
                                         // media: no glass and no padding; the body draws a photo (Cover)
  },
  "rank": 0.92,                          // higher first; ties keep source order
  "createdAt": 1793865600000,
  "expiresAt": null,                     // optional epoch ms; expired cards are dropped
  "pinned": false,                       // initial pin state requested by the source
  "summary": "Jack asked in #UIUX whether you can review the final mockups today.",
  "pin": {                               // compact representation used once pinned
    "label": "Jack",                     // ≤ 24 chars; may be omitted when "value" is set (weather: "23°")
    "detail": "Review mockups",          // optional ≤ 32 chars, second line
    "value": null,                       // optional display value: "23°", "8h21m", "4 min" (digits get display type)
    "icon": {"kind": "image", "src": "asset:feed/slack/avatar-jack.png", "shape": "circle"},
                                         // or {"kind": "pixel", "glyph": "car"} – see PixelGlyphs.kt
    "trailing": null                     // optional {"icon": "play", "actionId": "playPause"}
  },
  "glance": null,                       // optional data for native home widgets, e.g. weather
                                         // {"kind":"weather","glyph":"sun","value":"23°"} or sleep
                                         // {"kind":"sleep","value":"8h21m","series":[0..1 per column],"intensity":[0..1]}
  "ui": {
    "catalog": "taku-feed-1",
    "spec": { "root": "card", "elements": { /* json-render elements */ } },
    "state": { /* initial json-render state, e.g. {"data": {...}, "ui": {...}} */ }
  },
  "actions": {
    "reply": {"label": "Reply", "intent": {"type": "ask-taku", "prompt": "Draft a reply to Jack…"}},
    "addTask": {"label": "Add to tasks", "intent": {"type": "ask-taku", "prompt": "Add a task: …"}}
  }
}
```

### Intents

| `intent.type` | Fields | Effect |
|---|---|---|
| `ask-taku` | `prompt` | Opens the Taku conversation with `prompt` as an editable draft. Never auto-sends. |
| `open-app` | `package`, optional `uri` | Launches the app (or VIEW `uri` inside it). |
| `open-url` | `url` (https) | Opens the URL. |
| `share-text` | `package` (`com.whatsapp`), `text` | Opens WhatsApp's text share picker. The user chooses a recipient and sends from WhatsApp. |
| `confirm-dining-share` | none | Home adds this action to OpenTable dining reservation cards. The user confirms from the card after sending to the original group. |
| `dismiss` | – | Removes the card (same path as long-press → Remove, including the dust animation). |
| `none` | – | Accepted and recorded; used by sample cards whose real integration does not exist yet. |

A card carrying a retired `panel` intent fails validation as `INVALID_CARD`; the old
`PanelActions` bridge and its provider no longer exist. Content panels open through the
Assistant's `panels` operation instead.

Unavailable integrations must say so (`none` + a truthful prompt), never fake success.

### Asset references

Card-body image references accept `asset:feed/<path>` and `https://…`.
HTTP URLs, including LAN addresses, are rejected without guessing a replacement URL.
Bundled paths resolve to the APK asset `feed/<path>`. React Native decodes raster images;
bundled SVG is read through `TakuHome.readAssetText`, and network SVG is fetched and rendered through `SvgAst`.
SVG URLs use a `.svg` path suffix, optionally followed by a query or fragment.
Remote SVG requires `image/svg+xml`, an HTTPS final response URL, a 10-second fetch deadline, and at most
256 Ki characters (also rejects a declared Content-Length above 256 KiB). Only static
geometry/text, gradients, masks and local fragment references are accepted; entities,
external references, CSS, scripts, foreignObject and embedded images are rejected.
Fetch requests redirect rejection, but React Native's XMLHttpRequest-backed fetch may follow
HTTPS redirects before returning the final URL. Android release network policy blocks HTTP
downgrades; do not claim that redirect rejection prevents all intermediate HTTPS requests.
These are rendering checks; a failure uses the existing image error presentation.
The developer web gallery may still serve bundled assets from its configured local server.
The native `source.icon` and `pin.icon.src` metadata retain their separate envelope rules;
these card-body rules do not add network-image rendering to native pin pills.

## React Native side

* Root component registered with `AppRegistry`: **`TakuFeedCard`**, initial props `{cardId: string}`.
* The root renders the card body on a **transparent** background; the native shell draws the glass
  surface and padding. Width is the surface's inner width (356dp full, 158dp half; control: 372dp).
* The root wraps content in a `View` with `onLayout` and reports its height via `reportCardHeight`.
* json-render action **`dispatch`** `{actionId: string, select?: string[]}` → `TakuHome.dispatchAction(cardId, actionId, valuesJson)`
  where values is an object of `{statePath: value}` for each `select` path.

## Native module `TakuHome`

| Member | Kind | Notes |
|---|---|---|
| `getCard(cardId)` | sync → `string \| null` | FeedCard JSON. |
| `readAssetText(path)` | sync → `string \| null` | UTF-8 text of an APK asset under `feed/` (SVG). |
| `reportCardHeight(cardId, heightDp)` | sync | Native sizes the surface to this height. |
| `dispatchAction(cardId, actionId, valuesJson)` | async → `string` | `{"ok":true,"result":{…}}` or `{"ok":false,"code","message"}`. |
| `onCardChanged` | event `{cardId}` | Re-read with `getCard`. |
| `beginFeedGeneration(attemptId)` | sync → `boolean` | Internal headless-task claim for the exact active ReactContext. |
| `completeFeedGeneration(attemptId, receiptJson)` | sync → `boolean` | Internal one-shot preparation receipt; never publishes. |
| `panelNavigation()` | sync → `string` | Raw `{available: string[], visible: string[], selectedPanelId: string \| null}`. Never throws; before the first projection, with HomeActivity stopped or with a foreign Assistant contract it is `{"available":[],"visible":[],"selectedPanelId":null}`. |
| `onPanelsChanged` | event `{listRevision}` | A committed Panel directory change, cross-process included. An invalidation token: reread every page, never infer a delta. |
| `onPanelNavigation` | event `{available,visible,selectedPanelId}` | Emitted on every settled selection or mounted-page change. |

`openPanel` is gone. Opening a panel uses the Assistant operation
`callAgent('panels', {operation:'open', args:{panelId}})`.

## Panel pages

Desktop panel pages are not feed cards. Compose mounts the RN root **`TakuPanel`** with props
`{panelId}` only; the root resolves the matter through the Assistant (`panels/get` +
`readMatter`) and renders the same `GenUiCard` the chat renders. Compose owns the page title,
order, pager keys (`panelId`) and lifecycle; it mounts the selected page plus at most one page
either side. A page that is mounted but not selected holds no matter subscription: the shared
coordinator in `src/panels/matterDemand.ts` subscribes only when
`visible.includes(panelId) && selectedPanelId === panelId`, under two leases of at most 32
matters. Continue calls `TakuAgent.openPanelConversation(panelId)` and sends nothing itself.
`content://org.agentos.agent.panel-navigation` (method `navigation`) projects the same pager
state for shell callers; unknown methods answer `{ok:false,code:"UNKNOWN_METHOD",message}`.

Long-pressing the desktop card opens the Unpin menu. Normal input, selection and action taps keep their existing behavior. The menu uses the shared card's native Unpin operation and displays its errors. A root runs one read at a time and coalesces invalidations received during that read into the latest pending read. Starting directory observation is idempotent. The initial directory replay is retained: it can include a commit that arrived while the root was starting, and the root coalesces it with its initial read.

## Agent access

`content call --uri content://org.agentos.agent.home-feed --method <method> --arg '<json>'`
returns `Bundle[{result=<json>}]` with `ok`. Methods: `feed/list`, `feed/get`, `feed/remove`,
`feed/restore`, `feed/pin`, `feed/unpin`, `feed/reorder-pins`, `feed/action`, `feed/ask-taku`,
`feed/publish`, `feed/retract`, `feed/load-more`, `feed/reset`, `feed/generate`.
See `README.md` for parameters and errors.

Dismissals have no count-based eviction limit: removing later cards cannot reveal
earlier dismissed cards. `feed/restore`, replacement `feed/publish`, and `feed/reset`
clear the relevant dismissal. A restored or replaced card is no longer a leaving
animation item, and appears at most once in the GUI and `feed/list` snapshot.

`feed/list` returns `eventCursor` with the Feed snapshot, captured under the same
repository lock. This cursor is the boundary for reading changes after that
snapshot. `recentActions` is removed from `feed/list`. Existing history consumers
use the event stream below and own their durable processed cursors.

## Yelp candidates and WhatsApp polls

Call these methods on `content://org.agentos.agent.home-assistant`.
`assistant/get` projects the completed primary reply as `options`, including the selected
item and visibility, and the active poll editor as `share`. The Agent reads and changes the
same Assistant session that the GUI renders.

| Method | Arguments |
| --- | --- |
| `options/open`, `options/dismiss` | `replyId` |
| `options/select` | `replyId`, `itemId` |
| `options/share/open`, `options/share/pick-group`, `options/share/cancel` | `replyId` |
| `options/share/set-poll` | `replyId`, `question` |
| `options/share/send-poll` | `replyId`, with a group selected in WhatsApp's chooser |
| `options/share/continue-poll`, `options/share/check-poll` | Empty object, current poll execution |

The share methods require `taku.root`. Opening the editor, picking a group and editing
the question do not send. The explicit send action registers the completed source
reply, exact group name, question and choices in the native journal. The preview and
`share.wireQuestion` include the reference suffix that WhatsApp displays. A separate execution
thread carries out this one intent. Native code must verify a unique matching group and
every single-choice poll field. A confirmed send requires a native message anchor.
A poll with uncertain delivery can only be checked against its original attempt.

Outdated replies or editors fail with `REQUEST_EXPIRED`. Invalid poll fields fail with
`INVALID_POLL_TARGET`, `INVALID_POLL_QUESTION` or `INVALID_POLL_OPTIONS`.

## Event stream

The read-only resource `content://org.agentos.agent.home-feed/events` has MIME type
`application/x-ndjson`. Android callers open it with
`ContentResolver.openFileDescriptor(uri, "r")`. The device command is
`content read --uri 'content://org.agentos.agent.home-feed/events?after=0'`.
There is no consumer registration, server acknowledgement, or Bundle events method.
The Provider remains open to device-local callers. Filters do not provide access
control or identify card ownership.

### Query parameters

The complete URI is at most 16 KiB UTF-8. Unknown keys and duplicate scalar keys
are rejected before opening a pipe.

| Parameter | Contract |
|---|---|
| `after` | Optional, default `0`. `0` starts before the first event. `now` captures the current committed head when opening. Otherwise supply the full returned UUID:decimalSequence cursor. The cursor itself is excluded. |
| `follow` | Optional, default `0`. Only `0` and `1` are valid. `0` ends after the captured history window. `1` replays history, then waits for later commits. |
| `cardId` | Repeatable, at most 128 IDs. Each ID matches `^[A-Za-z0-9._:-]{1,96}$`. IDs within this parameter combine with OR. |
| `cardIdPrefix` | Optional, mutually exclusive with `cardId`. A nonempty prefix of at most 96 valid ID characters. Matches literally and case-sensitively, including underscores. |
| `origin` | Optional, `gui` or `agent`. Describes the operation's entry point, not the publisher or a tenant. |
| `op` | Optional, `action`, `publish`, `generate`, `remove`, `restore`, `pin`, `unpin`, `reorder-pins`, `retract`, or `reset`. |

Different filter dimensions combine with AND. An absent filter accepts all values
for that dimension. Global `reset` and `reorder-pins` events have no single card ID
and do not match a card filter. Event filtering uses persisted event fields, even
after a card is replaced or retracted. Changing a filter does not recover events
before the supplied cursor.

Cursors are opaque strings containing the database UUID and a decimal sequence.
They are independent of timestamps and UI revisions. Clients preserve the complete
string without JavaScript numeric conversion. Malformed, wrong-database, and
future cursors fail explicitly. They never fall back to `0` or `now`.

### NDJSON frames

Every complete frame occupies one UTF-8 JSON line. A connection starts with `ready`,
then emits flat `event` objects in increasing cursor order. A `checkpoint` follows
each fully scanned, bounded history window. Unmatched events can cause sequence
gaps. Example values below illustrate the format.

```json
{"type":"ready","schema":"taku.feed-event-stream/1","after":"7ce87ec1-59d8-4fe8-906b-8ff6ad2d12a6:0","through":"7ce87ec1-59d8-4fe8-906b-8ff6ad2d12a6:44","follow":true,"legacyImportedCount":0,"legacyHistoryMayBeIncomplete":false,"leaseMillis":300000}
{"type":"event","schema":"taku.feed-event/1","cursor":"7ce87ec1-59d8-4fe8-906b-8ff6ad2d12a6:44","at":1794096000000,"origin":"gui","op":"action","cardId":"acme.review.1","actionId":"confirm","actionSource":"declared","phase":"accepted","intentType":"none","values":{"/ui/page":2,"/ui/size":"grande","/ui/done":["review"],"/ui/confirmed":true}}
{"type":"checkpoint","through":"7ce87ec1-59d8-4fe8-906b-8ff6ad2d12a6:44"}
```

`ready.after` is the resolved starting cursor. `ready.through` is the opening
window's committed head, not evidence that events have been delivered or processed.
`follow` is a JSON boolean. `legacyImportedCount` is the actual imported row count.
`legacyHistoryMayBeIncomplete` reports whether old storage could already have lost
earlier history. `leaseMillis` is `300000`.

Common event fields are `type="event"`, `schema="taku.feed-event/1"`, `cursor`,
`at`, `origin`, `op`, and `cardId`. `at` is the recorded epoch time in milliseconds.
Mutation events contain `detail`. New action events also contain `actionId`,
`actionSource="declared"` or `"ask-about"`, `phase="accepted"`, `intentType`, and
`values`. The reserved Ask Taku action uses `actionId="ask-taku"` with
`actionSource="ask-about"`. `intentType` is `ask-taku`, `open-app`, `open-url`,
`share-text`, `confirm-dining-share`, `dismiss`, or `none`. A successful `share-text` launch emits a
second action event with `phase="dispatched"`, `acceptedCursor` and
`intent:{package,textSha256}`. The digest is lowercase SHA-256 over UTF-8 shared
text. The dispatch event records Android accepting the picker launch. WhatsApp
does not report recipient selection or message delivery through this Intent.
The `confirm-dining-share` action records a local GUI acceptance. Assistant
checks that it follows a matching picker launch on the same reservation card
before offering to add the meal to Calendar. Agent API calls to the confirmation
action return `PERMISSION_DENIED`.
The `open-calendar` action on a dining calendar card also records a successful
dispatch with `intent:{package,uri}` for its exact Calendar event. The ride
handoff separately checks the event shown on the device.

`values` preserves the selected state paths and JSON types. It can contain strings,
numbers, booleans, arrays, objects, or null values. Paths are keys, not reconstructed
nested objects. No selected values produces `{}` for a new accepted action.
New `values` objects are at most 64 KiB UTF-8. Complete new events are at most
128 KiB UTF-8, excluding the newline. Oversized input fails with `EVENT_TOO_LARGE`
before any local intent executes.

Feed resolves the card and declared action and persists the accepted choice under
the repository lock. The existing intent executes from that accepted snapshot only
after the durable commit. `accepted` records a user choice. It does not prove that
an external app, model turn, or business operation succeeded. Existing local intent
errors still return after acceptance. Successful action replies add `eventCursor`.
The `share-text` action also returns `dispatchCursor` and `requiresUserConfirmation:true`.
The dining calendar action returns `dispatchCursor` for its event launch.
A failed acceptance write prevents intent execution. Replay does not execute the
local intent again. An accepted dismiss can also produce a separate remove event.

`checkpoint.through` identifies a fixed window whose matching events all precede
that checkpoint. A finite connection ends after its final checkpoint. A follow
connection reads later committed windows and sends a checkpoint every 10 seconds
while idle. It expires after 300 seconds with `STREAM_LEASE_EXPIRED`, which can
interrupt a frame. A reliable-pipe error is a stream failure, not successful replay.
A trailing incomplete JSON line is not an event.

### Processed cursors and reconnects

Consumers own durable processed cursors. No pipe write, stdout write, `ready`, or
checkpoint is an automatic business acknowledgement. A consumer can save an event
cursor after processing that event and every earlier matching event. It can save
`checkpoint.through` after all earlier matching events finish, including concurrent
handlers. Business state and progress belong in the same consumer transaction.
External business work can use the full event cursor as its idempotency key.

Reopening with `after` excludes the stored cursor and can repeat delivered events
whose processing was not committed. Consumers deduplicate by cursor. Progress is
specific to the device, database UUID, and complete filter. A broader filter needs
an earlier cursor or an explicit choice to begin at `now`.

A running listener is required for immediate delivery. Feed stores events during
downtime but does not wake stopped consumers. The consumer decides when to reopen
after lease expiry, EOF, transport failure, or restart, using its own processed
cursor. A snapshot consumer can open after `feed/list.eventCursor` without a gap
between the snapshot and subsequent changes.

### Resource limits and failures

The companion accepts at most four active streams with at most four writer threads.
One bounded scheduler owns their leases. Each stream reads bounded metadata batches
and BLOB chunks of at most 16 KiB. Pipe writes and waits hold neither the repository
lock nor a database cursor. Partial writes and backpressure are handled without an
unbounded event queue. Five seconds without write progress closes a slow stream.
Closing the read descriptor cancels its connection and releases its resources.

Open errors include `INVALID_ARGUMENT`, `INVALID_CURSOR`, `CURSOR_EPOCH_MISMATCH`,
`CURSOR_AHEAD`, `STREAM_BUSY`, and `STATE_READ_FAILED`. Errors after opening use the
reliable pipe's error status, including `STREAM_LEASE_EXPIRED` and
`STREAM_SLOW_CONSUMER`. Android consumers check `ParcelFileDescriptor.checkError()`
after reading to EOF and before closing the descriptor. Error EOF cannot advance
processed progress past complete events that the consumer actually committed.

### Host caller

`scripts/read-feed-events.mjs` uses Node builtins. `--serial` is required.
`--after` defaults to `0`. `--follow` selects `follow=1`; omission selects finite
replay. `--card-id` repeats up to 128 times. `--card-prefix`, `--origin`, and `--op`
map to their query parameters. `--adb` selects an executable and `--help` shows usage.
All options except `--card-id` are scalar. Unknown options, positional arguments,
and duplicate scalar options fail.

The caller uses `ADB`, then the SDK under `ANDROID_SDK_ROOT` or `ANDROID_HOME`,
then `adb` on PATH unless `--adb` overrides it. It invokes an explicit device with
`adb -s SERIAL shell -T content read --uri URI`. Query encoding and remote shell
quoting preserve the URI as one argument. stdout receives the raw bytes with
backpressure, including arbitrarily large legacy lines. A bounded 4 KiB control
frame detector requires an opening `ready`, a final complete `checkpoint`, and a
complete trailing line. For finite replay, the final checkpoint must match the
opening head. Event lines larger than that detector buffer pass through without
JSON parsing. No complete legacy line or complete replay is accumulated.
Shell mode keeps provider diagnostics separate from event bytes.

Android `content read` can exit 0 after a provider exception. Its
[reader copies the raw file descriptor](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/cmds/content/src/com/android/commands/content/Content.java)
without checking the reliable-pipe status. The tested API 36 command omitted lease
and slow-reader error details. The host caller treats every EOF while following as
a disconnect and exits nonzero, even after complete checkpoints. Finite replay
still requires its exact opening-head checkpoint.

The caller also checks stderr. It retains at most an 8 KiB diagnostic prefix and
reports only known error codes or classes, without raw provider text or selected
values. Normal adb daemon startup messages are allowed. Empty output, missing
control frames, child failures, and provider diagnostics fail explicitly even if
adb exits 0. The caller never saves cursors, acknowledges processing, or reconnects.
SIGINT closes adb and exits 130. SIGTERM closes adb and exits 143. A closed stdout
pipe closes adb and exits 0. Device, host, and Android examples are in
[Read and follow card actions](README.md#read-and-follow-card-actions).

## Persistence and migration

`FeedRepository` is the single writer of current Feed state and events. Both live
in the companion's `databases/home-feed.db`, using SDK SQLite, WAL, and
`synchronous=FULL`. Each state mutation and its event commit in one transaction
before the in-memory state changes or notifications are published. Published cards
are separate rows. Replacing an ID preserves its insertion order. An action appends
an event without rewriting every card or earlier event.

`feed/publish` and `feed/generate` have no card count cap and do not evict older or
pinned cards. Existing ID, expiry, rank, and pin rules still apply. Actual disk or
transaction failures are explicit. There is no count or time retention cutoff for
events. Disk usage grows with persisted cards and history. `feed/reset` clears
current cards and preferences, appends a reset event, and preserves history, UUID,
and sequence. Clearing app data or replacing the database creates a different UUID.

The first open imports legacy `files/home-feed/state.json` and its actually retained
actions once. Cards, pin order, removals, defaults, history, UUID, and the migration
marker commit together. A crash before commit retries the transaction. A crash
after commit does not duplicate imports. The old file remains read-only migration
input and never becomes a fallback or a second writer. Old APKs do not see later
SQLite changes. Corrupt legacy state fails explicitly instead of starting empty.

Imported events have `phase="legacy-recorded"` and `legacy` containing the exact
old entry. Only fields present in the old entry are normalized. `action:ID` maps
to `op="action"` and its recorded action ID. Missing values, time, origin, or
intent fields are not invented. Legacy recording happened under the old timing
rules and does not claim acceptance before the local intent. Legacy bytes are not
cut to new event limits. The old 50-entry retention policy can already have removed
history. `legacyHistoryMayBeIncomplete` exposes that limitation, and
`legacyImportedCount` reports only rows actually recovered.

## New-card generation

`feed/generate` accepts exactly one own request key, `prompt` or `card`. `prompt` is a
nonblank description; `card` is a complete parsed FeedCard object. File paths and JSON
strings are not card objects. The host caller in `scripts/generate-feed-card.mjs`
loads the real file and submits its object. Request-level extra keys are rejected.

The description path calls the existing configured `generateUi` route with the
`taku-feed-1` vocabulary. It parses the entire returned text as one JSON object with
`summary`, `ui` and optional `actions`, then assembles the host-owned envelope.
Fences, prose, JSONL, partial JSON and malformed content fail. The gateway's nonblank
model or routing-group ID is returned on success. The file path makes no model call and retains
the original candidate. Both use the canonical `validateFeedSpec` before publication,
including conditional branches, references, graph cycles and actual repeat scopes.
Empty repeat templates check literals and expressions without inventing item values.

Native publication assigns a fresh `agent-generated:<uuid>` ID, creation time, a finite
rank above the loaded maximum, `source.id="agent"` and `pinned=false`. All other file
content is retained. An expired card or exhausted rank fails without replacing or
evicting another card. There is no published-card count cap. A SQLite transaction
stores the new card and its generate event before readable in-memory insertion and
notifications. An unsuccessful write does not expose a new card. Declared actions
execute only through normal user or Agent action calls after generation.

The operation initializes the existing shared ReactHost and a module-scope
`TakuFeedGeneration` headless task. It needs no Activity or Feed surface.

ReactHost acquisition and startup are queued on the main thread after application
initialization, because Provider Binder calls can arrive before `Application.onCreate`
has initialized the native libraries.

One process attempt runs at a time; concurrent calls return `BUSY`. Internal claims and receipts
must match the attempt and ReactContext; duplicate, retired and expired receipts are
rejected. The JS task validates and reports, while the waiting native operation owns
publication. Finishing a headless task does not cancel an upstream model request, but
a closed attempt cannot later publish its response.

Requests allow at most 96,000 UTF-8 bytes, descriptions 16,000 UTF-16 units, model text
96,000 UTF-8 bytes, and bodies 96 elements. Expression nesting is bounded at 64 levels
and expanded bodies at 2,048 elements. Validation permits at most 8,192 visits and
deduplicates empty templates by element and scope. The JS claim has a 10-second startup deadline;
the precommit deadline is 60 seconds, including up to 5 seconds for source readiness.
The caller waits up to 120 seconds. Once persistence starts, native waits for its actual
result rather than reporting a timeout that could be followed by a late insertion.

Results use the existing Bundle envelope with fresh `id`, `replaced:false`, and optional
`model` for descriptions. Failures preserve safe gateway codes and bounded diagnostics.
Runtime loss, unavailability, invalid body/output and storage failure insert no
placeholder. A transport failure or process death around commit can leave an unknown
outcome; read `feed/list` before choosing to resend. This is a create operation with no
automatic retry or deduplication across process death. Android callers must invoke it
off the main thread.

## React Native notes

Implementation: `src/feed/` (`feedCatalog.ts` = `taku-feed-1`, `feedRegistry.tsx` renderers,
`FeedCardRoot.tsx` = `TakuFeedCard`, `native.ts` = `TakuHome` wrapper). `validateFeedSpec(spec, state, actionIds)`
is the full check (json-render structure + every element's prop schema + events + dispatched actionIds);
`npm test` runs it over `sample-feed.json`, and the root renders the card `summary` instead of a rejected
or crashing spec (never a blank card).

* **Components**: Column, Row, Spacer, Text, Value, Icon, AppHeader, CornerTag, Inset, MessageQuote, Button,
  IconButton, Tags, Segmented, ProductImage, Image, Avatar, Checklist, ProgressBar, ArcGauge, MapRoute,
  OptionRow, MediaStack, SlideToAct, Pager, Dots. Descriptions and the generation prompt live in the catalog
  (`feedCatalog.prompt()`). Colors and type are tokens only (`tone`, `variant`, `fill`).
* **Events**: Button/IconButton/OptionRow/MediaStack `press`, SlideToAct `complete` (after a confirm haptic;
  the thumb resets when `dispatchAction` settles), Segmented/Checklist/Pager `change`. Every binding must be
  `{"action":"dispatch","params":{"actionId","select?"}}`. While a dispatch is pending the button shows a spinner;
  a `{"ok":false}` result is shown under the body as text.
* **Per-page actions**: a Pager cannot change an actionId, so a card with per-page actions renders one button row
  per page with `"visible": {"$state":"/ui/page","eq":<i>}` (see `slack-uiux`), and also selects `/ui/page`.
* **State paths** used by the sample: `/ui/page` (Pager + Dots), `/ui/size` (Segmented), `/ui/done` (Checklist).
* **Sizes**: a `half` body renders square (`aspectRatio: 1`, so 158×158) and its root Column should use
  `flex: 1`; `control` bodies are 56dp tall inside the 4dp padded 64dp track.
* **Glass**: React Native cannot blur what is behind it. Sub-surfaces that are blurred in the design
  (Uber option row, Instagram front post, slide thumb) use their flat translucent token (`surface/800`, `surface/900`).
* **Pin glyphs** used by the sample: `sun`, `moon`, `car`, `lock`, `list` (`PixelGlyphs.kt` must define them).
* **Ask Taku handoff**. The Feed creates a conversation and saves the prompt as its editable draft.
  The Feed opens `org.agentos.agent/.MainActivity` with the saved `threadId` after the commit.
  A successful action returns the `threadId`, `draft`, and `eventCursor`. The handoff never submits a request.
