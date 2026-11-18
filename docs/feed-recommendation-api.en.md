# Pushing proactive recommendations into the Taku Home Feed

[中文](feed-recommendation-api.md)

For colleagues working on proactivity and recommendations: how to put recommendation results into the Home Feed of the client app (`org.agentos.agent`) as cards, how to update and withdraw them, and how to read user feedback. The interface comes from `23a0515` (Feed card generation) and `fe7ba0e` (GenUI Kotlin runtime, which provides the `generateUi` route). The authoritative contract remains [`CONTRACT.md`](../system/agent-app/modules/taku-home/CONTRACT.md) and the [module README](../system/agent-app/modules/taku-home/README.md); this guide arranges them for the recommendation use case. If they disagree, the contract and the code win.

## Choose an integration path

Every path goes through the same `FeedRepository`, so the GUI and Agents see the same state.

| Path | Use it for | Card ID | Update / withdraw | Model call |
| --- | --- | --- | --- | --- |
| **`feed/publish`** (recommended) | The producer assembles the complete card | Chosen by the caller, stable | Publishing the same ID again replaces it; `feed/retract` withdraws it | No |
| `feed/generate` + `card` | Importing one card once, with full body validation before publication | A new `agent-generated:<uuid>` every call | Cannot be replaced by your own ID; retract it by the returned ID | No |
| `feed/generate` + `prompt` | No fixed template, only facts; the model lays out the card | A new ID every call | Same as above | Yes (the on-device Magpie `agent-ui.text` route) |
| In-process `FeedSource` | A future production recommendation service | Chosen by the source | Driven by the source's pages | — |

Recommendations usually use **`feed/publish`**: a recommendation has its own stable key (for example `rec.commute.leave-now`), needs to be replaced when its data refreshes, and withdrawn when it expires or no longer applies. `feed/generate` creates a new card on every call, with no deduplication or replacement; use it to import one card or to generate one from a description.

The in-process `FeedSource` is **not available to outside callers** today: the production feed registers no sample source and displays only published cards, or its empty state. The `recommendation` value of `source.id` is reserved but not implemented. Cards written by outside callers through `feed/publish` or `feed/generate` always have `source.id` rewritten to `agent`.

## Quick start (adb)

Card JSON usually contains quotes and non-ASCII text, so do not splice it into an `adb shell` command line. The pattern below sends the request as one line of JSON on stdin to `content call` on the device, the same approach `scripts/generate-feed-card.mjs` uses:

```sh
SERIAL=emulator-5554          # your device
FEED='IFS= read -r req || exit 1
exec content call --uri content://org.agentos.agent.home-feed --method "$0" --arg "$req"'
feed() {  # usage: feed <method> <one-line JSON request>
  printf '%s\n' "$2" | adb -s "$SERIAL" shell -T "sh -c '$FEED' $1"
}
card_request() {  # wrap card.json as a one-line {"card":{...}} request
  python3 -c 'import json,sys;print(json.dumps({"card":json.load(open(sys.argv[1],encoding="utf-8"))},ensure_ascii=False,separators=(",",":")))' "$1"
}

feed feed/publish "$(card_request rec-card.json)"
feed feed/get '{"id":"rec.commute.leave-now"}'
feed feed/list '{}'
feed feed/retract '{"id":"rec.commute.leave-now"}'
```

Simple requests can also run directly in the device shell:

```sh
adb -s "$SERIAL" shell content call --uri content://org.agentos.agent.home-feed --method feed/list --arg '{}'
```

Every method returns `Result: Bundle[{result=<json>}]`. Check `ok` first: success is `{"ok":true,"result":{…}}`, failure is `{"ok":false,"code":"…","message":"…"}`. A successful `feed/publish` returns `{"id":"…","replaced":false|true}`.

## Card format (`taku.feed-card/1`)

The following recommendation card can be published as is. Its body was checked with the host validation command below, and its envelope with the actual `FeedCard.parse` code.

```json
{
  "schema": "taku.feed-card/1",
  "id": "rec.commute.leave-now",
  "source": {"id": "recommendation", "app": "Commute", "package": "com.google.android.apps.maps"},
  "layout": {"span": "full", "surface": "card"},
  "rank": 0.96,
  "createdAt": 1793865600000,
  "expiresAt": 1793869200000,
  "pinned": false,
  "summary": "Leave in 10 minutes to reach the 15:00 design review at Office B on time.",
  "pin": {"label": "Leave 14:20", "detail": "Design review 15:00", "icon": {"kind": "pixel", "glyph": "car"}},
  "ui": {
    "catalog": "taku-feed-1",
    "state": {},
    "spec": {
      "root": "card",
      "elements": {
        "card": {"type": "Column", "props": {"gap": 12}, "children": ["header", "reason", "buttons"]},
        "header": {"type": "AppHeader", "props": {"title": "Commute", "trailingIcon": "clock", "trailing": "in 10 min"}, "children": []},
        "reason": {"type": "Inset", "props": {"tag": {"label": "Relevant", "tone": "ai"}}, "children": ["reasonBody"]},
        "reasonBody": {"type": "Column", "props": {"gap": 4, "padding": 12}, "children": ["title", "detail", "tags"]},
        "title": {"type": "Text", "props": {"text": "Leave at 14:20 for the design review", "variant": "body-xl-medium", "lines": 2}, "children": []},
        "detail": {"type": "Text", "props": {"text": "Traffic to Office B is heavier than usual (32 min).", "tone": "tertiary"}, "children": []},
        "tags": {"type": "Tags", "props": {"items": ["Calendar", "Traffic"]}, "children": []},
        "buttons": {"type": "Row", "props": {"gap": 8}, "children": ["later", "go"]},
        "later": {"type": "Button", "props": {"label": "Not now", "variant": "secondary", "flex": 1}, "children": [],
          "on": {"press": {"action": "dispatch", "params": {"actionId": "dismiss"}}}},
        "go": {"type": "Button", "props": {"label": "Navigate", "variant": "primary", "flex": 1}, "children": [],
          "on": {"press": {"action": "dispatch", "params": {"actionId": "navigate"}}}}
      }
    }
  },
  "actions": {
    "navigate": {"label": "Navigate", "intent": {"type": "open-app", "package": "com.google.android.apps.maps", "uri": "google.navigation:q=Office+B"}},
    "dismiss": {"label": "Not now", "intent": {"type": "dismiss"}},
    "ask": {"label": "Ask Taku", "intent": {"type": "ask-taku", "prompt": "Help me plan my trip to the 15:00 design review at Office B."}}
  }
}
```

### Envelope fields

Validated by `FeedCard.parse`; a violation returns `INVALID_CARD` with a `message` naming the field.

| Field | Rule | Advice for recommendations |
| --- | --- | --- |
| `schema` | Must be `taku.feed-card/1` | — |
| `id` | `^[A-Za-z0-9._:-]{1,96}$` | Use your own namespace and a stable key, such as `rec.<producer>.<key>`; publishing the same ID replaces the card |
| `source.id` | Rewritten to `agent` on publication | You may write `recommendation` to state intent, but it is not kept |
| `source.app` | Required, nonblank | A source name the user understands |
| `source.package` | Optional | Package of the related app |
| `source.icon` | Optional, `asset:feed/<path>` or `https://…` | Other schemes (`file://`, `content://`) are rejected |
| `layout.span` | `full` (default) or `half` | Most recommendations use `full`; a `half` body is square and two share a row |
| `layout.surface` | `card` (default), `control`, `media` | Use `card` |
| `rank` | Number, default 0; higher first, ties keep source order | See "Ordering" |
| `createdAt` | Epoch ms, default 0 | Not used for ordering |
| `expiresAt` | Epoch ms or `null`; hidden once reached | Always set it for time-bound recommendations; see "Lifecycle" |
| `pinned` | `true` pins the card to the front on publication | Keep `false`; pinning is the user's decision |
| `summary` | Required, nonblank | One complete sentence. Shown when the body cannot render, and used as context by long-press "Ask Taku" |
| `pin.label` | ≤ 24 characters; may be omitted when `pin.value` is set | Main text of the pill once the user pins the card |
| `pin.detail` | Optional, ≤ 32 characters | Second line |
| `pin.value` | Optional, ≤ 12 characters | For example `23°`, `4 min` |
| `pin.icon` | `{"kind":"pixel","glyph":…}` or `{"kind":"image","src":…,"shape":"circle"\|"rounded"}` | Built-in pixel glyphs: `sun moon car lock list chat cup bolt music photo pin home`; other names produce a generated symmetric pattern. `https:` images fall back to a generated pattern on the pill |
| `pin.trailing` | Optional `{"icon","actionId"}`; `actionId` must be a declared action | — |
| `ui` | `catalog` must be `taku-feed-1`; `spec.root` must name an element in `spec.elements` | See the next section |
| `actions` | The action table, see "Actions" | — |

A serialized card may not exceed 256 KiB (`CARD_TOO_LARGE`).

### Card body (`ui`)

The body is an `@json-render` spec rendered by React Native in the client. The native shell owns the glass surface, padding, ordering, pinning and removal; the body background is transparent.

- **Components** (`taku-feed-1`): Column, Row, Spacer, Text, Value, Icon, AppHeader, CornerTag, Inset, MessageQuote, Button, IconButton, Tags, Segmented, ProductImage, Image, Avatar, Checklist, ProgressBar, ArcGauge, MapRoute, OptionRow, MediaStack, SlideToAct, Pager, Dots, Cover, Chip, Switch, LevelTrack, Timeline, FlightRoute, Link. Prop definitions and descriptions are in [`feedCatalog.ts`](../system/agent-app/src/feed/feedCatalog.ts). Colors and type use tokens only (`tone`, `variant`, `fill`).
- **Events**: every event must be bound as `{"action":"dispatch","params":{"actionId":"…","select":["/ui/…"]}}`. `actionId` must be declared in `actions`; the optional `select` sends those state values along with the action.
- **State**: `ui.state` is the initial state object (for example `{"ui":{"size":"tall"}}`) bound by Segmented, Checklist, Pager and similar components.
- **Sizes**: a `full` body is 356dp wide; a `half` body is 158×158 and its root Column should use `flex: 1`.
- **Images**: `asset:feed/<path>` refers to assets bundled in the client APK, which recommendation producers rarely need; card-body remote images require `https://`; HTTP (including LAN URLs) is rejected without rewriting the URL. SVG URLs use a `.svg` path suffix with optional query or fragment and must return `image/svg+xml`, using static geometry without external resources, scripts or CSS; native pin icons keep their separate rules. Do not use asset paths you have not confirmed.

**`feed/publish` does not validate the body.** It checks only the envelope and `spec.root`. A body that does not match the catalog still publishes successfully, but the card shows only its `summary` (the client logs `[feed] <id> spec rejected`). Check the body on the host with the same validator before publishing:

```sh
cd system/agent-app   # inside the repository, with node_modules installed
node --input-type=module -e "
import {readFileSync} from 'node:fs';
import {validateFeedSpec} from './src/feed/feedCatalog.ts';
const card = JSON.parse(readFileSync(process.argv[1], 'utf8'));
const issues = validateFeedSpec(card.ui.spec, card.ui.state ?? {}, new Set(Object.keys(card.actions ?? {})));
console.log(issues.length ? JSON.stringify(issues, null, 2) : 'ok');
process.exit(issues.length ? 1 : 0);" /absolute/path/card.json
```

It prints `ok` on success; otherwise it lists each element and reason, for example `{"element":"go","message":"on.press dispatches unknown action missing"}`. It needs Node 22.18+ or 24 (which load `.ts` directly).

## Actions

| `intent.type` | Fields | Effect | For recommendations |
| --- | --- | --- | --- |
| `open-app` | `package`, optional `uri` | Launches the app, or VIEWs `uri` inside it | The main action: "Open", "Navigate". Returns `APP_NOT_INSTALLED` if the app is missing |
| `ask-taku` | `prompt` | Opens the Taku conversation with `prompt` as an editable draft; **never sends it automatically** | When the Agent should do the work for the user (add `"icon":"sparkle"` to the button). State the facts, and ask for confirmation before payments and similar steps |
| `open-url` | `url` (must be `https://`) | Opens the page | A details link |
| `dismiss` | — | Removes the card, like long-press → Remove (with the dust animation) | "Not interested / Later" |
| `none` | — | Recorded only; nothing runs | State truthfully that an integration is not connected yet; never pretend success |
| `panel` | `method` (`panels/…`), `args` | Runs a panel operation | Panel pages only; do not use it in recommendations |

Actions run only when the user taps them (or an Agent calls `feed/action`). Neither publication nor generation runs any action.

## Lifecycle

- **Update**: call `feed/publish` again with the same `id`; it returns `replaced:true`. The whole card is replaced; there are no partial updates. A card the user pinned stays pinned.
- **Withdraw**: `feed/retract {"id":…}` deletes a card you published and unpins it. A missing card returns `NOT_FOUND`; a sample card returns `NOT_AGENT_CARD`.
- **Expiry**: expired cards are hidden but remain stored until retracted. Retract recommendations that no longer apply.
- **User removal**: when the user removes a card (or taps a `dismiss` action), its ID joins the `removed` list. **Publishing the same ID again takes it off the `removed` list and the card reappears.** Before refreshing, check `removed` in `feed/get` or `removed[]` in `feed/list` and respect the user's removal; use a new ID for a genuinely different recommendation.
- **Capacity**: publish and generate have no fixed card-count cap and never evict other cards. Device storage still limits capacity; retract expired recommendations.
- **Reset**: `feed/reset` clears all published cards, pins and removals. Use it only for development and recovery.

### Ordering

Visible cards are sorted by `rank`, highest first. The production feed no longer loads or repeats design samples or seeds sample pins. Upgrade preserves published cards and their pin/removal state, retiring only state from disabled sources. Publishing is ready on first install and after `feed/reset`, without waiting for a source page. `feed/generate` ignores the file's `rank` and places the card above the highest currently loaded rank.

## Reading user feedback

Read the current snapshot, then subscribe to durable events from its cursor:

```sh
feed feed/list '{}'
```

`feed/list` returns `cards[]`, `pins[]`, `removed[]` and `eventCursor`; `recentActions` is removed. Replay durable NDJSON via `content read --uri "content://org.agentos.agent.home-feed/events?after=0&follow=0"`. Use `follow=1` to subscribe and `after=<cursor>` to resume. Filters: `cardId`, `cardIdPrefix`, `origin`, `op`. Persist a cursor only after consuming its event, or checkpoint through after the whole window; reconnect on follow EOF. Action events contain `op:"action"`, `actionId`, `values`, `intentType`, and `phase:"accepted"`: persistence precedes local execution and does not prove that execution succeeded. See the [complete event protocol](../system/agent-app/modules/taku-home/CONTRACT.md#event-stream).

`feed/get {"id":…}` returns the full card JSON plus its `pinned` and `removed` state.

## Calling from an Android app

The provider is exported and requires no permission (a product decision; see the repository `AGENTS.md`). Callers targeting SDK 30 or later must declare package visibility:

```xml
<queries>
  <provider android:authorities="org.agentos.agent.home-feed" />
</queries>
```

```java
package com.example.rec;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import org.json.JSONException;
import org.json.JSONObject;

/** Minimal client for content://org.agentos.agent.home-feed. Call every method from a worker thread. */
public final class TakuFeed {
  public static final Uri URI = Uri.parse("content://org.agentos.agent.home-feed");

  /** Provider error: {@link #code} is the stable `code` field (INVALID_CARD, NOT_FOUND, ...). */
  public static final class FeedError extends Exception {
    public final String code;
    FeedError(String code, String message) { super(code + ": " + message); this.code = code; }
  }

  private TakuFeed() {}

  /** Calls one feed method and returns its `result` object. */
  public static JSONObject call(Context context, String method, JSONObject arg) throws FeedError {
    Bundle out;
    try {
      out = context.getContentResolver().call(URI, method, arg.toString(), null);
    } catch (IllegalArgumentException e) { // provider not installed or not visible (<queries>)
      throw new FeedError("PROVIDER_UNAVAILABLE", String.valueOf(e.getMessage()));
    }
    if (out == null || out.getString("result") == null) throw new FeedError("PROVIDER_UNAVAILABLE", "no result");
    try {
      JSONObject reply = new JSONObject(out.getString("result"));
      if (!reply.optBoolean("ok")) throw new FeedError(reply.optString("code"), reply.optString("message"));
      return reply.getJSONObject("result");
    } catch (JSONException e) {
      throw new FeedError("BAD_REPLY", String.valueOf(e.getMessage()));
    }
  }

  /** Adds or replaces the card with card.id. Returns true when an earlier version was replaced. */
  public static boolean publish(Context context, JSONObject card) throws FeedError, JSONException {
    return call(context, "feed/publish", new JSONObject().put("card", card)).getBoolean("replaced");
  }

  /** Withdraws a published card. NOT_FOUND means it is already gone. */
  public static void retract(Context context, String id) throws FeedError, JSONException {
    call(context, "feed/retract", new JSONObject().put("id", id));
  }
}
```

`PROVIDER_UNAVAILABLE` and `BAD_REPLY` are client-side codes defined by this example, not returned by the provider. Every call is a synchronous Binder call: `feed/list` and `feed/get` wait up to 5 seconds for the first page to load, and `feed/generate` can wait two minutes, so call from a worker thread.

## `feed/generate`

The request has exactly one key: `{"prompt":"…"}` or `{"card":{…}}`.

- **`card`**: no model call. The envelope field types are checked, then the body is validated with the same `validateFeedSpec` as above, and the card is published only if everything passes. Publication assigns a new `id` (`agent-generated:<uuid>`), `createdAt` and `rank` (above the current highest), and forces `pinned:false` and `source.id:"agent"`. Everything else is kept as is. Note: an invalid envelope returns only the generic `INVALID_CARD: The supplied card envelope is invalid` without naming the field; to debug, use `feed/publish` or the host checks to get the specific reason.
- **`prompt`**: calls the configured on-device `generateUi` route (the GenUI runtime). The model generates only `summary`, `ui` and `actions`; the host fills in the envelope (`source.app` is `Taku`, `pin.label` is the first 24 characters of `summary`, the glyph is `list`). A successful result includes the actual `model`. Write only verified facts in the prompt; the system instructions forbid the model to invent facts, media URLs or asset paths. It needs Magpie running on Android and a usable text model bound to `agent-ui.text`; see the [Android Magpie guide](../adapters/magpie-runtime/README.md).

On the host you can use the existing caller script:

```sh
node system/agent-app/modules/taku-home/scripts/generate-feed-card.mjs --serial "$SERIAL" --json-file /absolute/path/card.json
node system/agent-app/modules/taku-home/scripts/generate-feed-card.mjs --serial "$SERIAL" --prompt 'Create a commute reminder card from these facts: leave at 14:20, design review at Office B at 15:00, traffic 32 minutes.'
```

Limits: requests are at most 96,000 UTF-8 bytes, `prompt` at most 16,000 UTF-16 units, and bodies at most 96 elements. Only one generation runs at a time; concurrent calls return `BUSY` (no queueing). The caller waits at most 120 seconds. After a timeout, disconnect or unreadable reply the outcome is unknown (the card may have been published), so read `feed/list` before deciding to retry. Generation never retries automatically and does not deduplicate.

## Error codes

| Code | Meaning and handling |
| --- | --- |
| `INVALID_ARGUMENT` | Missing parameter or invalid JSON (for example no `card`, or a malformed `id`) |
| `INVALID_CARD` | An envelope field is invalid; for `feed/publish` the `message` names the field |
| `CARD_TOO_LARGE` | The card exceeds 256 KiB |
| `ID_CONFLICT` | The ID already belongs to a source card such as a sample; choose another ID |
| `NOT_FOUND` | No such card (or not loaded yet) |
| `NOT_AGENT_CARD` | Tried to retract a source card; source cards can only be `feed/remove`d |
| `STATE_WRITE_FAILED` | Storage write failed; the change did not take effect |
| `UNKNOWN_METHOD` | Wrong method name |
| `INVALID_SPEC` / `INVALID_MODEL_OUTPUT` | `feed/generate` only: the body does not match the catalog / the model output is not one complete JSON object |
| `BUSY`, `FEED_UNAVAILABLE`, `RANK_EXHAUSTED`, `REQUEST_TOO_LARGE`, `RUNTIME_UNAVAILABLE`, `RUNTIME_TIMEOUT`, `RUNTIME_LOST`, `CANCELLED`, `GENERATION_FAILED` | `feed/generate` only; none inserts a placeholder card. Model gateway codes are passed through |

Parameters and errors of the other methods (`feed/action`, `feed/pin`, …) are in the [module README](../system/agent-app/modules/taku-home/README.md#agent-access).

## Not yet available

- A production recommendation source (`FeedSource`): outside callers can only use `feed/publish` / `feed/generate`, and the `source` shows as `agent`.
- Remote Webhooks: unavailable; use on-device `/events` replay and subscriptions.
- Caller isolation: the provider does not distinguish callers, so any local caller can replace or retract any published card. Use your own ID prefix and touch only your own cards.

## What this guide was verified against

The example card's body passes `validateFeedSpec`, and its envelope passes the actual `FeedCard.parse` / `parseGenerated` code (along with several negative cases). The stdin pattern was confirmed with a stub `content` command to deliver the JSON unchanged, and the Java example compiles against `android.jar`. No emulator was available while writing, so the `adb` commands have not been run one by one on a device; the described behavior follows the code of `HomeFeedProvider.kt`, `FeedRepository.kt`, `FeedCard.kt`, `FeedGeneration.kt` and `feedGeneration.ts`.
