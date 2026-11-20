# Taku Home (`modules/taku-home`)

Native Kotlin + Jetpack Compose home screen inside the `org.agentos.agent` app, so the
same process hosts the React Native runtime that renders AI-generated card bodies.
Design source: Figma “Taku Mobile OS”, frames 0, 0-0 … 0-5 and 4-1.

| Piece | File |
|---|---|
| HOME activity, ReactHost lifecycle, unlock → home page 0 | `HomeActivity.kt` |
| Pager (feed \| home pages), drag page turns and scrolling, scene layer, status-bar edge blur | `ui/HomeScreen.kt` |
| Home pages (page 0 with widgets), fixed icon grid with sliding icons, layout menu | `ui/LauncherPages.kt`, `ui/Widgets.kt`, `ui/AppIcons.kt` |
| Icon drag-and-drop, icon menu, folders | `ui/AppDrag.kt`, `ui/LauncherOverlays.kt` |
| Installed apps, home pages (model, storage), widget data | `apps/AppCatalog.kt`, `apps/HomePages.kt`, `apps/HomeLayoutRepository.kt`, `apps/HomeWidgets.kt` |
| Agent access to apps/layout/widgets | `apps/HomeLayoutProvider.kt` |
| Feed: header, pinned row, infinite list, card surface | `ui/FeedPage.kt`, `ui/PinnedRow.kt` |
| Long-press menu (0-3) / pinned card menu | `ui/CardMenu.kt` |
| Dust removal (0-4) | `ui/DustDissolve.kt` |
| Bottom glass assistant entry (0-0 / 0-1) | `ui/AssistantEntry.kt` |
| AGSL: progressive blur, edge refraction, bottom glass | `ui/effects/Shaders.kt` |
| Card model, sources, repository, intents | `feed/*.kt` |
| Agent access | `feed/HomeFeedProvider.kt` |
| New-card generation and headless runtime | `feed/FeedGeneration.kt` |
| JS bridge for `TakuFeedCard` | `TakuHomeModule.kt` |
| React Native card renderer (`taku-feed-1` catalog) | `../../src/feed/` |
| Dynamic native Panel pages and shared GenUI root | `panels/PanelPageUi.kt`, `../../src/panels/PanelRoot.tsx` |
| Design system: tokens, theme, components | `design/` (see `design/README.md`) |
| Card/bridge contract | `CONTRACT.md` |
| Host description / real JSON file caller | `scripts/generate-feed-card.mjs` |
| Host event replay and listener | `scripts/read-feed-events.mjs` |
| Sample data from the design | `android/src/main/assets/feed/sample-feed.json` |

## Pages

`[feed] ↔ [home 0] [home 1] … ↔ [pinned Panels in native directory order]`. Unlock
and the Home button show home page 0. Page dots show every page. There is no empty app page in reserve: one appears only
while an icon is dragged past the last app page, and an app page other than 0 disappears once empty.

* **Home page 0** (Figma 4-1): two widget rows (clock with 12 edge ticks and staggered digits,
  next alarm with switch, weather glyph, date; sleep pixel trend, now playing), then three rows
  of four apps/folders on 83 dp rounded-rectangle icons (corners 18/60 of the size, Figma 338:3610).
  Glyph apps and folders sit on a Surface/600 glass plate with a 1 dp Surface/400 rim; folder
  miniatures are rounded the same way. On first launch every launchable app
  (LauncherApps; phone, browser, messaging, camera first, shipped Taku apps in a folder) is laid
  out from page 0 on. The four core roles (phone, browser, messaging, camera) and unbranded AOSP
  utilities are drawn as #1B1B1B glyphs like the design; every other app (Google, third-party,
  Taku) fills the whole rounded rectangle with its adaptive icon (background layer edge to edge),
  deliberately unlike the frame's 48 dp circles, so no artwork is cropped to a circle.
* **Panel pages** follow all app pages and come from the Assistant's authoritative
  `PanelRegistry`. There are no default Kyoto, Home or Work panels. Compose hosts the
  page and its lifecycle; the `TakuPanel` React Native root accepts only `panelId`,
  resolves the current Matter, and uses the same GenUI and card state as chat.
  The public `content://org.agentos.assistant.open-access` `panels/open` command opens
  Home at that exact ID. Rename, move, unpin and continue use the same native validation
  as the GUI. See the [Panel contract](../../../../docs/panel-rebuild-spec.md) and the
  [preinstalled command guide](../../../assistant/assets/skills/taku-open-access/SKILL.md).
* **Further home pages**: six rows of four, the height of page 0's widgets plus its three rows.
  Pages never scroll; a full page pushes its last icon to the next page.
* **One scrolling page** (alternative): long-press empty home space → "Show all apps on one
  page". Page 0 then lists every app under the widgets and scrolls under the edge blurs; "Show
  apps on pages" switches back. The arrangement is the same in both.
* **Arranging** (iPhone-style): long-press an icon and drag. The icons slide aside to open a gap
  where the finger rests (cells are fixed, so the target never chases the animation). Resting
  over the middle of another app makes a folder (named from the apps' shared Android category),
  over a folder joins it (a ring marks it). Resting at a side edge turns the page (not onto the
  feed); past the last page an empty page appears. On one scrolling page the edges scroll. Drag
  an app out of an open folder to move it; a folder of one becomes the app. New installs join
  the last page; uninstalled apps leave. Long-press and release instead opens the icon menu
  (App info, Uninstall for non-system apps; Ungroup for a folder).
* **Widget data**: clock/date/alarm from the system, music from the active media session
  (MEDIA_CONTENT_CONTROL), weather/sleep from the `glance` of a real source's feed card
  (`weather-now`, `sleep-last-night`). The design's sample cards never fill a widget: without a
  real source weather shows "--" and sleep "No data". Tapping weather/sleep scrolls the feed to
  that card (or says the source isn't connected); the clock and alarm open the clock app, play
  without a session opens the music app, and anything that can't open says so in a toast.
  Long-pressing a widget does nothing (the layout menu belongs to empty home space).
* **System entry**: Taku ROM assigns HOME and ASSISTANT to this app in RoleController and
  repairs the selected voice service in VoiceInteractionManager, before any Activity starts.
  `HomeRole` additionally repairs roles/bindings on boot, unlock and app update. Hardware
  assistant entry opens the new Home AI input; handle gestures retain their existing
  text/voice overlay. Quickstep still implements navigation/overview, with HOME targeting
  this app. Advanced native controls and background task ownership remain in the backend.

## Data pipeline

Memory / Agent `feed/publish` → `FeedCard` (`taku.feed-card/1`) → `FeedRepository`
→ Compose shell + React Native body. A recommendation service becomes another
`FeedSource` returning the same `FeedCard`s; nothing in the UI changes. Agent cards
enter through `feed/generate` to create a new card or `feed/publish` to upsert a caller-owned ID, and are ranked with the rest.
Recommendation producers: see [Pushing proactive recommendations into the Feed](../../../../docs/feed-recommendation-api.en.md)
([中文](../../../../docs/feed-recommendation-api.md)).

Production registers no sample source. Design cards and `SampleFeedSource` remain test
fixtures; they never populate the live feed or its pinned row. With no published cards,
the UI shows its empty state. Upgrade preserves published cards and their user state.

Pins, removals, and Agent cards are stored with append-only events in the companion's
`databases/home-feed.db`. GUI and Agent use the same repository. Both publication
paths accept any number of cards without evicting older cards. Device storage is
finite. A failed durable write returns an error instead of dropping another card.

The first upgrade imports `files/home-feed/state.json` once, including its retained
actions, cards, pins, and removals. The SQLite transaction also saves the migration
marker and database UUID. The old file remains read-only migration input. It is
neither updated nor used as a fallback after migration. A downgrade to an old APK
cannot read new database changes. Corrupt migration input is an explicit failure.
Events have no count or time retention limit. `feed/reset` clears current Feed state
and adds a reset event while preserving history and the UUID. Clearing app data or
replacing the database invalidates old cursors.

## Web gallery

For the web card gallery, run `npm run gallery` from `system/agent-app`.
Dependency installation and the `start`, `web`, and `gallery` scripts generate
`public/feed-assets` from this module's `android/src/main/assets/feed` directory.
The generated copy is ignored by Git; the APK assets remain the single source of truth.
After editing assets while the server is running, run `npm run prepare:feed-assets`
to refresh the copy. Do not commit an asset symlink or duplicated assets.

## Install as home

```sh
adb -s "$SERIAL" install -r system/agent-app/out/TakuAgent.apk
adb -s "$SERIAL" shell cmd role add-role-holder --user 0 android.app.role.HOME org.agentos.agent
```

The backend no longer advertises a HOME or ASSIST Activity. Old explicit home links forward
to this HomeActivity; explicit legacy generated-panel links retain their native panel host.
Install both updated APKs together. Full pre-Activity binding-loss recovery also requires
the framework/PermissionController patches in `image/home`, not just an APK update.

## Gesture regression tests

Run `bash system/agent-app/modules/taku-home/scripts/test-gesture-policy.sh` from the
repository root with `JAVA_HOME` pointing to JDK 17 or newer. It uses the shared
Kotlin toolchain and Gradle-cached JUnit dependencies; no emulator is needed for
these policy tests. Device cancellation, nested scroll and icon drag behavior
also require the isolated-emulator checks recorded in
[issue 54 verification](../../../../docs/reviews/home-gesture-54-2026-11-13.md).

## Agent access

Home gesture diagnostics: `content call --uri content://org.agentos.agent.home-layout --method gestures/get --arg '{}'`. This returns a live, read-only projection of the same
gesture policy and Pager used by the GUI: window gesture insets in pixels, touch origin,
direction/terminal state, page/offset/scrolling and icon-drag state. `available:false`
means Home is not composed; it does not open Home. See the preinstalled `taku-open-access`
skill for fields and errors. No touch coordinates are persisted. Edge-origin scrolls
cannot move the Pager, while content direction locks after the system touch slop. Stock
Compose nested scrolling and snapping remain in use, and programmatic icon edge paging
is independent. System-handle voice/double-tap behavior belongs to the ROM input layer.

Run on the device. Every call returns `Bundle[{result=<json>}]`; check `ok`.

```sh
U=content://org.agentos.agent.home-feed
content call --uri $U --method feed/list --arg '{}'
content call --uri $U --method feed/get --arg '{"id":"slack-uiux"}'
content call --uri $U --method feed/load-more --arg '{}'
content call --uri $U --method feed/remove --arg '{"id":"starbucks-reorder"}'
content call --uri $U --method feed/restore --arg '{"id":"starbucks-reorder"}'
content call --uri $U --method feed/pin --arg '{"id":"uber-home","index":0}'
content call --uri $U --method feed/unpin --arg '{"id":"weather-now"}'
content call --uri $U --method feed/reorder-pins --arg '{"ids":["music-now-playing","weather-now"]}'
content call --uri $U --method feed/action --arg '{"id":"starbucks-reorder","actionId":"orderNow","values":{"/ui/size":"grande"}}'
content call --uri $U --method feed/ask-taku --arg '{"id":"slack-uiux"}'
content call --uri $U --method feed/publish --arg '{"card":{…taku.feed-card/1…}}'
content call --uri $U --method feed/retract --arg '{"id":"my-agent-card"}'
content call --uri $U --method feed/reset --arg '{}'
```

| Method | Parameters | Result |
|---|---|---|
| `feed/list` | – | `revision`, `eventCursor`, `cards[]` (visible order), `pins[]`, `removed[]`, `loading`, `endReached`, `error`, `sources[]` |
| `feed/get` | `id` | `card` (full FeedCard JSON), `pinned`, `removed` |
| `feed/load-more` | – | loads the next page (what scrolling does), then `feed/list` |
| `feed/remove` / `feed/restore` | `id` | `removed`, `changed`, `animated` (the dust animation played on screen) |
| `feed/pin` | `id`, optional `index` into the `pins` shown by `feed/list` (default 0 = first); no pin limit | `pinned`, `changed`, `index` |
| `feed/unpin` | `id` | `pinned`, `changed` |
| `feed/reorder-pins` | `ids` = permutation of the `pins` shown by `feed/list` (stored pins that do not resolve right now, e.g. a source page not loaded yet, keep their place) | `pins` |
| `feed/action` | `id`, `actionId`, optional `values` (state path → JSON value) | `eventCursor`, `effect` (`ask-taku`, `open-app`, `open-url`, `dismiss`, `none`), and details |
| `feed/ask-taku` | `id` | `eventCursor`; opens the conversation with the card as an editable draft |
| `feed/publish` | `card` (`taku.feed-card/1`; `source.id` is forced to `agent`) | `id`, `replaced` |
| `feed/generate` | exactly one of `prompt` (description) or `card` (full parsed `taku.feed-card/1`) | fresh `id`, `replaced:false`, actual `model` for description success |
| `feed/retract` | `id` of an Agent card | `retracted` |
| `feed/reset` | – | clears pins, removals, and Agent cards, reloads sources, preserves event history |

Errors (`ok:false`, `code`): `NOT_FOUND` (card not loaded – call `feed/list` or
`feed/load-more`), `INVALID_ARGUMENT`, `INVALID_CARD` (message names the field),
`CARD_TOO_LARGE` (256 KiB), `CARD_REMOVED` (restore before pinning),
`INVALID_ORDER`, `ID_CONFLICT` (id belongs to another source), `NOT_AGENT_CARD`,
`UNKNOWN_ACTION`, `APP_NOT_INSTALLED`, `NO_ACTIVITY`, `STATE_READ_FAILED`,
`STATE_WRITE_FAILED`, `EVENT_TOO_LARGE`, `TIMEOUT`, `UNKNOWN_METHOD`.

`ask-taku` intents only pre-fill the conversation; nothing is sent until the user sends
it. Sample cards whose real integration does not exist use `none` or an `ask-taku`
prompt; they never report a fake success.

## Read and follow card actions

On the device, replay existing GUI actions for a card. Finite replay is the default.
The final checkpoint marks the end of the captured history window.

```sh
content read --uri 'content://org.agentos.agent.home-feed/events?after=0&cardId=acme.review.1&origin=gui&op=action'
```

To replay history and then wait for new choices, add `follow=1`. To wait only for
choices committed after opening, use `after=now`.

```sh
content read --uri 'content://org.agentos.agent.home-feed/events?after=0&follow=1&cardId=acme.review.1&origin=gui&op=action'
content read --uri 'content://org.agentos.agent.home-feed/events?after=now&follow=1&cardIdPrefix=acme.&origin=gui&op=action'
```

From the repository root on the host, supply the target device serial explicitly.
The first command finishes after replay. The second follows two generated card IDs.

```sh
node system/agent-app/modules/taku-home/scripts/read-feed-events.mjs --serial "$SERIAL" --card-id acme.review.1 --origin gui --op action
node system/agent-app/modules/taku-home/scripts/read-feed-events.mjs --serial "$SERIAL" --after 0 --follow --card-id "$FIRST_CARD_ID" --card-id "$SECOND_CARD_ID" --origin gui --op action
node system/agent-app/modules/taku-home/scripts/read-feed-events.mjs --serial "$SERIAL" --after "$PROCESSED_CURSOR" --follow --card-prefix acme. --origin gui --op action
```

Use the actual IDs returned by publication. `--card-id` can repeat up to 128 times.
`--card-prefix` matches a literal, case-sensitive prefix and excludes `--card-id`.
Other options are `--after`, `--follow`, `--origin`, `--op`, `--adb`, and `--help`.
The adb selection follows the generation caller's environment conventions. The
script encodes the URI and quotes it for the remote shell. It forwards bytes with
stdout backpressure and does not accumulate even a large legacy event line.
Ctrl-C closes adb and exits 130. A closed output pipe closes adb and exits 0.
Other transport failures exit nonzero. The caller requires an opening ready frame
and a complete final checkpoint. It also reports bounded, safe provider diagnostics,
because Android `content read` can print an exception and still exit 0. Raw error
text and selected values are not included in its stderr diagnostics.

Each NDJSON connection contains `ready`, flat `event`, and `checkpoint` objects.
Action events include `cardId`, `actionId`, `actionSource`, `intentType`, and `values`.
Selected state paths remain object keys, such as `"/ui/size"`, and retain their JSON
types. A number stays a number and a checklist selection stays an array. New action
events have `phase="accepted"`. Feed commits the choice before attempting the local
intent. Acceptance does not prove that an order, model turn, or app operation
succeeded. An intent error can follow a durable accepted event. Storage failure
prevents execution of that intent. Successful action replies include `eventCursor`.

Keep processed progress in the consumer's own durable storage.

1. Process each event in cursor order. Save its full cursor with the business result
   in the same transaction, or use the cursor as an idempotency key for external work.
2. Save `checkpoint.through` only after every earlier matching event has completed.
   Concurrent handlers must wait for all earlier work before advancing past it.
3. After EOF, a disconnect, or a process restart, reopen from that stored cursor.
   Discard an incomplete trailing JSON line. Already delivered but uncommitted events
   can repeat. Preserve cursor strings without numeric conversion.

`ready.through`, pipe writes, and stdout writes describe transport progress. They do
not acknowledge business processing. Save progress separately for each device,
database UUID, and filter. Changing the filter can require an earlier start cursor.
For a current Feed snapshot plus later changes, read `feed/list` once and open the
stream after its `eventCursor`. Snapshot and cursor are captured together.

Immediate notification requires a running listener. Follow connections send an idle
checkpoint every 10 seconds and end after a 300-second lease. The CLI does not
reconnect or save cursors. Your running consumer reopens with its processed cursor.
An offline consumer replays stored history when it returns. Feed does not wake it.
The host CLI exits nonzero on any EOF while following, including lease expiry.
The tested Android `content read` command can omit reliable-pipe error details.
Treat its EOF as a disconnect even when the command exits 0.

### Read from Android

Call `ContentResolver.openFileDescriptor(uri, "r")` on a worker thread. This function
copies finite replay to a caller-owned inbox file without accumulating event lines.
Supply the consumer's stored cursor, or `"0"` for its first replay.

```kotlin
import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileOutputStream

fun replayCardActions(context: Context, processedCursor: String, inbox: File) {
	val uri = Uri.parse("content://org.agentos.agent.home-feed/events").buildUpon()
		.appendQueryParameter("after", processedCursor)
		.appendQueryParameter("follow", "0")
		.appendQueryParameter("cardId", "acme.review.1")
		.appendQueryParameter("origin", "gui")
		.appendQueryParameter("op", "action")
		.build()
	val descriptor = requireNotNull(context.contentResolver.openFileDescriptor(uri, "r"))
	ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
		FileOutputStream(inbox).use { output ->
			input.copyTo(output, 16 * 1024)
			descriptor.checkError()
			output.fd.sync()
		}
	}
}
```

The file is replay input, not a processed-cursor acknowledgement. Do not treat a
partial file or a reliable-pipe error as complete replay. Process complete NDJSON
frames and commit progress with business state before replacing this file. For
live delivery, set `follow` to `"1"` and consume the input as it arrives. Closing
the descriptor cancels the listener, including when the caller stops its worker.
New events are at most 128 KiB and new `values` objects are at most 64 KiB UTF-8.
Imported legacy lines can be larger. Use streaming parsing or a file inbox for
those lines. See the [event stream reference](CONTRACT.md#event-stream) for all
filters, frame fields, limits, migration fields, and errors.

### Verify delivery on an isolated emulator

After installing the built APK on the assigned isolated emulator, run the actual
Provider and host caller checks from the repository root.

```sh
python3 system/agent-app/modules/taku-home/scripts/verify-feed-events.py --serial "$SERIAL" --adb "$ADB" --gui --out system/agent-app/out/feed-events-verify
```

The [device verification script](scripts/verify-feed-events.py) publishes and
generates more than 100 cards through each path, replays 75 actions with selected
values, and exercises cursor recovery. `--gui` also changes a real card control and
presses its button. It records evidence in `--out`. This command mutates Feed state
on its target emulator. Supply the explicit serial of an isolated AVD.

## Generate a new Feed card

From the repository root, use the host caller with an explicit device serial:

```sh
node system/agent-app/modules/taku-home/scripts/generate-feed-card.mjs --serial "$SERIAL" --prompt 'Create a progress card: 3 of 8 checks completed, 37.5%. Use these facts only.'
node system/agent-app/modules/taku-home/scripts/generate-feed-card.mjs --serial "$SERIAL" --json-file /absolute/path/card.json
```

Pass `--adb /absolute/path/adb` if needed. Otherwise the caller uses `ADB`, the SDK under
`ANDROID_SDK_ROOT` or `ANDROID_HOME`, then `adb` on PATH. It reads the actual UTF-8 file;
the file contains the inner FeedCard object, without a request wrapper. For example:

```json
{
  "schema": "taku.feed-card/1",
  "id": "review-template",
  "source": {"id": "agent", "app": "My cards"},
  "summary": "Review the supplied draft.",
  "pin": {"label": "Review draft", "icon": {"kind": "pixel", "glyph": "list"}},
  "ui": {
    "catalog": "taku-feed-1",
    "state": {},
    "spec": {
      "root": "body",
      "elements": {
        "body": {"type": "Text", "props": {"text": "Review the supplied draft.", "variant": "body-xl-medium"}, "children": []}
      }
    }
  },
  "actions": {}
}
```

Success prints `{"ok":true,"result":{"id":"agent-generated:...","replaced":false}}`;
description success also includes the configured model or routing-group ID. Each call creates a fresh card,
including repeated imports of the same template. Native publication sets identity,
rank, creation time, `source.id="agent"`, and `pinned=false`. File content, layout, pin
representation, initial state and declared actions are preserved. The new card starts
above the currently loaded cards, without evicting or pinning an existing card.

Descriptions use the existing configured `generateUi` route and the Feed catalog.
Files use the same body validator without calling the model. Both work without an
open Feed page and publish only after validation and successful storage. Generation
does not execute declared actions. The caller does not prepare providers or retry;
model unavailability returns an error and creates no placeholder card.

The description route requires Magpie running on Android and a usable authenticated
model in its catalog. It does not depend on the computer's Magpie. A connected service
with an empty catalog cannot generate a card. Connect a provider or subscription in
Taku AI's model-service management page, then bind a text-capable routing group to
`agent-ui.text`. See the [Android Magpie guide](../../../../adapters/magpie-runtime/README.md).

The compact request must fit 96,000 UTF-8 bytes. Descriptions allow at most 16,000
UTF-16 units; bodies allow at most 96 elements. Invalid bodies return `INVALID_SPEC`,
malformed model text returns `INVALID_MODEL_OUTPUT`, and existing gateway codes such
as `RUNTIME_UNAVAILABLE` are preserved. `BUSY` rejects concurrent generation without
queueing. Publication has no card count cap. Storage failures return `STATE_WRITE_FAILED`.
Errors exit nonzero. After a timeout, disconnect or unreadable reply, inspect
`feed/list` before choosing to call again because the outcome may be unknown.

Android callers can use `ContentResolver.call` with method `feed/generate` and one JSON
argument, `{"prompt":"..."}` or `{"card":{...}}`, from a worker thread. See
[`CONTRACT.md`](CONTRACT.md#new-card-generation) for lifecycle and commit semantics.

## Agent access: home pages

```sh
L=content://org.agentos.agent.home-layout
content call --uri $L --method apps/list --arg '{}'          # key, package, label, system, glyphIcon, iconAsset, page, index, folderId
content call --uri $L --method apps/launch --arg '{"key":"PACKAGE/ACTIVITY"}'
content call --uri $L --method apps/info --arg '{"key":"PACKAGE/ACTIVITY"}'
content call --uri $L --method apps/uninstall --arg '{"key":"PACKAGE/ACTIVITY"}'    # shows the system confirmation
content call --uri $L --method layout/get --arg '{}'         # mode, capacities, pages[].items[]: app | folder, in GUI order
content call --uri $L --method layout/set-mode --arg '{"mode":"scroll"}'   # pages (default) | scroll
content call --uri $L --method layout/move --arg '{"key":"PACKAGE/ACTIVITY","page":0,"index":2}'   # insert before item 2
content call --uri $L --method layout/move --arg '{"key":"PACKAGE/ACTIVITY","page":1}'             # end of page 1
content call --uri $L --method layout/move --arg '{"key":"PACKAGE/ACTIVITY","page":0,"index":4,"into":true}'  # onto item 4: folder
content call --uri $L --method layout/move --arg '{"folderId":"FOLDER_ID","page":2,"index":0}'
content call --uri $L --method layout/ungroup --arg '{"folderId":"FOLDER_ID"}'
content call --uri $L --method layout/rename-folder --arg '{"id":"FOLDER_ID","name":"Travel"}'
content call --uri $L --method layout/reset --arg '{}'       # every app back on page 0 in the default order
content call --uri $L --method widgets/get --arg '{}'        # clock, date, alarm, weather, sleep, media
content call --uri $L --method widgets/media --arg '{"command":"play-pause"}'   # previous | next
content call --uri $L --method widgets/alarms --arg '{}'
```

`layout/move` is the GUI's drag and drop. Give exactly one of `key` (an app, also one inside a
folder) or `folderId`, and `page`; `page` equal to the page count creates a new page, like
dragging past the last one. `index` is the position in the page as it is now (0 … item count;
omitted = end). With `"into":true` an app is dropped onto the item at `index`: onto an app it
makes a folder (named from the apps' shared Android category, else "Apps"), onto a folder it
joins it. The result reports `effect` (`moved`, `page-created`, `folder-created`,
`added-to-folder`, `unchanged`), the item's final `page`/`index`, `folderId` when it is in a
folder, and the page count. Page 0 holds `firstPageCapacity` (12: three rows under the two
widget rows), other pages `pageCapacity` (24: six rows). Inserting into a full page pushes its
last item to the start of the next page (a cascade, creating a page at the end when needed),
exactly as when the user drags there. A folder left with one app becomes that app; a page
other than page 0 disappears when it has no items, so later page numbers shift down. Every
installed app is on exactly one page or in one folder: new apps join the last page (or a new
one when it is full), uninstalled apps leave, and there is no "remove from home" (move it, or
`apps/uninstall`).

`mode` is how the GUI shows these pages: `pages` (default) swipes between them, `scroll` shows
every app on one vertically scrolling page under the widgets, in page order. The user switches
by long-pressing empty home space ("Show all apps on one page" / "Show apps on pages");
`layout/set-mode` is the same setting (`effect`: `changed` | `unchanged`; other values return
`INVALID_ARGUMENT`). The arrangement is shared: `layout/get` and `layout/move` always use pages,
and a move in scroll mode still fills pages, so switching back keeps the order.

Errors: `APP_NOT_FOUND`, `NOT_ON_HOME`, `NOT_FOUND` (folder), `EMPTY_CELL` (`into` without an
item there), `FOLDER_FULL` (12 apps), `UNAVAILABLE` (the app list is still loading; retry),
`SYSTEM_APP`, `LAUNCH_FAILED`, `NO_MEDIA_SESSION`, `NO_ACTIVITY`, `INVALID_ARGUMENT`
(bad page/index, both or neither of key/folderId, a folder with `into`, folder names must be
1–24 characters), `STATE_WRITE_FAILED`, `TIMEOUT`, `UNKNOWN_METHOD`. `widgets/get` reports
`media.unavailable` (`NO_MEDIA_SESSION` or `MEDIA_PERMISSION_DENIED`) instead of inventing a
track; `alarm` is null when no alarm is set; `weather`/`sleep` name the feed card and source
they come from. The layout and mode persist in `files/home-layout/layout.json` (version 2); a
version 1 file (the former single extra page) is migrated once: the apps are laid out over pages
and the former extra page follows as its own page.


## AI entry and Dynamic Island

Figma 1-1 … 1-6. Native Kotlin/Compose (`assistant/`, `ui/assistant/`); state lives in one
`AssistantSession`, shared with Agents through `content://org.agentos.agent.home-assistant`
(commands in the preinstalled `taku-open-access` skill).

* **Full chat (2-1)**: the island's expand button opens the RN chat on the same thread; the
  chat's collapse button calls `assistant/resume` (`AssistantSession.resume`), which expands
  the island with that thread's latest turn and brings Home forward.

* **Conversation**: the session calls the system Agent gateway from this package
  (`createThread`, `sendMessage`, or `steer` while a turn runs) and follows the thread over
  `subscribeSession` (`ThreadProjection` ports the RN stream reducer); `readSession(statusOnly)`
  supplies queued/approval/failed task states. One thread per home session; dismissing the
  island while idle ends it, the expand button continues it in the full chat. A failed send,
  a task that fails later (`TASK_FAILED`) or a failed turn keeps the request (`failedText`),
  restores it as the draft unless a newer draft exists, and offers "Try again" / "Edit".
  Offline the runtime keeps retrying without failing, so after 30 s with no network while working
  the home stops the work (interrupt / cancel queued) and reports `NETWORK_UNAVAILABLE` the same way;
  nothing is simulated.
* **Hold to talk (1-1)**: long-press the desktop sphere or, on the patched ROM with Taku
  selected, the system navigation handle. Both render the same session, 24 dp backdrop blur,
  original Figma aurora/glass assets and Cancel / Keep talking arcs (297:374/505/571).
  Releasing latches the listening page; silence, capture limits and recognition errors never
  replace it with Send/Cancel/Text buttons. Cancel stops audio immediately then dissolves the
  transcript. Keep talking expands a black surface over 820 ms and transfers the same capture
  into the full chat's Live Talk mode. Text and voice share the 80% black translucent chat
  surface (300:661 / 297:433), thread and message history. Closing Live Talk returns to text.
  Live Talk finalizes after measured speech plus 3 s silence and resumes after the reply;
  it uses the desktop's scoped backend preference. Unavailable video input is disabled.
  Designer waveform and light-field source/masks are bundled locally by
  `scripts/build-voice-effects.cjs`; see `voice-effects/README.md` for provenance and isolation.
  Multi-touch, focus loss and system cancellation never send. System gestures carry ordered
  ids/sequences, a UI-ready handshake and a heartbeat lease while the pointer is held.
  Stock images without the SystemUI island provider show an in-page listening header;
  ROMs with that provider use SystemUI only. `assistant/get.listeningHeaderHost` records it.
  Agent methods and protocol are in the preinstalled `taku-open-access` skill. The
  `AssistantOverlayActivity` supplies cross-app presentation through the native assistant;
  it does not create a Home controller or a second recording/session.
* **Text (1-2, 1-3)**: double tap. A glass capsule pops off the sphere with a metaball neck and
  flies up, widening into the 380 × 64 Surface/900 field 16 dp above the keyboard; the lower
  screen frosts (σ0→6, #E3E3E3 → 90 %). Empty field: microphone (dictation, 1-5); text: brand
  send button. Closing the keyboard closes the field. Dictation keeps the field focused (hidden
  behind the transcript), so the keyboard stays up as in 1-5.
* **Island**: covers the status bar (hidden while expanded or collapsed: the status bar window
  would otherwise swallow taps on the pill) at 8 dp, radius 40, Content/900 composited over
  the canvas so it is opaque and feed text never shows through.
  Eyes blink while idle; "Taku is working" uses the 3×3 dot matrix sweeping its Figma opacity
  columns. Headline = the agent's latest text, steps = real tool activity (search, reading a
  site, actions), chips = only replies the agent offered (`chatEnhancements` actions without
  `actionRef`). Height is capped at ⅓ of the screen; overflowing text gets the 1-4 bottom edge
  (horizontal stretch, R/B split, progressive blur, fade). Tap to read the full reply, swipe up
  or tap outside to fold it into the camera pill while working (1-6: dots, metaball necks,
  status label around the cutout from `DisplayCutout`; tap anywhere on it to reopen), otherwise
  it closes.
* **Working edge (1-4, 1-5)**: the four Figma "working gradient" layers, each a full-frame inset
  shadow (blur 32 → σ16, erfc falloff, ~½ at the edge) with its own 4 dp offset, drawn in Figma
  order and blend mode: green (0, −4) and indigo (0, 4) plus-lighter, sky (4, 0) and orange
  (−4, 0) normal (plus-lighter is what washes them to the frame's pale edge on the light home),
  along the display's rounded corner radius. It shows only while Taku responds (from the send
  until the turn and its task complete), fading in over 700 ms and out over 250 ms; meanwhile the
  offsets turn slowly together and the strength breathes slightly (92–100 %). It is drawn in its
  own full-display system overlay window (`WorkingGlowOverlay`, `INTERNAL_SYSTEM_WINDOW`), so the
  whole glow sits above the keyboard, the status bar and every app, never taking focus or
  touches (window alpha 0.8, the system's touch-occlusion limit, with the strength compensated).
  In that separate window the plus-lighter layers blend with each other, not with the screen
  beneath. Where the overlay is refused, the glow is drawn in Home's own window as before.
* **Voice recognition** uses the shared Audio Engine (`system/audio-engine`) exclusively.
  The desktop hold gesture and text-field microphone open `DICTATION` with
  `settingsScope:"assistant-entry"`. Taku Audio saves the backend, language, and optional
  startup fallback through the same root commands exposed to Agents. Defaults remain
  local Zipformer, system language, and no fallback. Optional fal Whisper uploads audio
  for source transcription only. Full chat text-field dictation keeps its unscoped language preferences; Live Talk uses `assistant-entry`.
  There is no external transcript injector; an unavailable Engine produces an error
  while manual text input remains available.

  Partials replace the open segment and finals commit it by sequence and segment identity.
  fal Whisper transcribes successive audio ranges during capture and replaces one live
  preview with their accumulated text. Stopping runs a full-recording transcription
  that replaces that preview before successful delivery. Updates depend on completed
  fal requests; this file interface does not provide token streaming.
  Text release opens the editable draft immediately, finishes the same capture, then adds
  the complete successful result once to the draft as it exists then. It never sends.
  Ordinary release keeps 1-1 open without sending. Live Talk sends only successful finals. Cancel, stale operations,
  conversation changes and failed partial results cannot deliver text. Draft overflow
  keeps the full result selectable alongside the original draft instead of truncating it.

  `assistant/get` exposes operation identity, phase, outcome, delivery and retained-result
  state, actual backend and processing location, settings revision, and backend deadlines.
  The hold overlay, island, and keyboard composer show recognition status and transcripts
  without backend/model labels. Backend details remain available in Taku Audio settings
  and through `assistant/get`.
  `ready` is true only while capturing. Local Engine finalization is bounded to 5 seconds;
  Whisper capture is bounded to 60 seconds and finalization to 100 seconds. The client
  waits the Engine's absolute deadline plus 3 seconds of transport grace. Expiration is
  a failure. Waveform history comes from the Engine's measured microphone PCM RMS,
  normalized from -60 dBFS to 0 dBFS into 0..1. `levels` and `levelSource:"pcm-rms"`
  expose the same values through `assistant/get`. Missing history stays still.

  The live transcript wraps across the text area and scrolls within the space above
  the keyboard. A separate centered waveform row keeps the text width clear. Its
  symmetric bars reach 32 dp and use a stronger display range for ordinary speech.

```sh
A=content://org.agentos.agent.home-assistant
content call --uri $A --method assistant/voice-start
content call --uri $A --method assistant/voice-to-text
content call --uri $A --method assistant/get
content call --uri $A --method assistant/voice-cancel
```

  The engine admits the app because it is platform-signed and the root Broker lists
  `org.agentos.agent` in the engine's consumer list under the existing `agent-ui` switch
  (`AudioConfigBridge.consumers`). The full chat uses the same class through the `TakuVoice`
  Expo module. `start(language)` returns its operation snapshot; `finish(operationId)` and
  `cancel(operationId)` affect only that recording. `onVoice` snapshots retain the operation
  identity through cancellation, so old cleanup cannot stop or deliver into a newer recording.
  Starting one recorder cancels the other because the Engine owns one microphone lease.

* **Keyboard**: the frames show a neutral keyboard (Main/Canvas #E3E3E3, white keys r6,
  Surface/Secondary #F1F1F3 function keys, #1B1B1B accent keys). The preinstalled Taku IME's
  `TakuLight` theme is warm (#F4F1EB / #FFFDFA keys, brand-orange accent, radius 10), so it
  clashes next to the cool frost and field. Recommended: retune `TakuLight` in
  `system/inputmethod/patches/0001-taku-inputmethod.patch` to those values (radius 6, accent
  #1B1B1B) in its own IME release; it is a signed, hash-locked artifact (`rom-input-lock.json`),
  so it is not changed here.

## Design notes

* All desktop pages share the existing wallpaper drawn once behind the pager. The wallpaper
  stays fixed in screen coordinates while page content slides; panels have no separate backdrop.
* Blur radii: Figma blur R is a Gaussian with σ = R/2 (Backdrop Blur/lg R32 → σ16 dp,
  /md → σ12, /sm → σ4). `Shaders.gaussian` converts σ to Android's Skia blur radius.
* Glass cards/pills sit only on the wallpaper, so their backdrop blur is the wallpaper
  pre-blurred once (σ16) and sampled at the element's screen position (`Backdrop`).
* Bottom edge: one RenderEffect chain on the scene layer — progressive blur σ0→12 over the
  158 dp fade frame, #E3E3E3 fade (0→90 %), and the "Live refractive glass" ellipse
  (rim refraction over 24 dp, chromatic split, top-lit 1 px rim, 4 % milky fill, 8 % halo).
  Figma's GLASS effect is not exported; rim brightness and the 0-1 glow blobs (centres,
  σ, intensity) were tuned by rendering the shader against the Figma frames in WebGL.
* Pinned row edges use the verbatim "Taku · Edge Refraction" maths (flare 0.25,
  dispersion 0.4, 12 dp) on both sides plus a progressive horizontal blur (28 dp,
  σ ≤ 6 dp); items snap to the start.
* The status-bar edge blur (σ6 → 0) is a product request; the frames leave it sharp.
* No frame contains motion; animation curves are springs/timings chosen here
  (menu 220 ms scrim + staggered pills, dissolve 1.1 s, entry spring).

## Not yet available

* Memory publishes proactive recommendations through `feed/publish`; capture, analysis,
  notification access, a usable model connection, `nudges` and `feedCards` must be enabled.
* Pinned pills render `asset:` raster images and pixel glyphs only; `https:` pin icons
  fall back to a generated pixel glyph.
* Home widgets use their application AppWidget providers independently of the feed.
* The alarm switch reflects the next system alarm; Android offers no API to toggle another
  app's alarm, so the tile opens the clock app.


### Built-in icons and the Taku app folder

Only the four core roles of Figma 4-1 (phone, browser, messaging, camera, whichever app holds
them) and unbranded AOSP utilities (`com.android.*` settings, clock, calendar, contacts, files,
gallery, calculator) render as black glyphs on the glass backplates; Google, third-party and Taku
apps keep their own icons. `apps/list` reports `glyphIcon` and the local `iconAsset` (or null
when supplied by Android). On first launch page 0
lists every app with phone, browser, messaging and camera first, and the shipped Taku
activities (when at least two are installed) in folder `builtin-taku`. After that it is an
ordinary folder: drag apps in or out, rename it (`layout/rename-folder`), or ungroup it.
Reading a folder's `apps` is the Agent counterpart of opening it; use `apps/launch` with a
member key to launch it.
