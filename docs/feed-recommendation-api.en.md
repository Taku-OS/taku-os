# Publish recommendations to Taku Home Feed

[中文](feed-recommendation-api.md)

Third-party Android apps can publish cards directly to the device's Home Feed after App Permission Center authorization. Taku Agent, apps, and the Home GUI use the same `FeedRepository`.

## Access and permissions

Call `content://org.agentos.agent.home-feed` through `ContentResolver.call`. The exported Provider checks the calling UID for every method call and event-stream open.

- Ordinary third-party apps need `taku.control`; the default mode is `deny`. Users can enable **Use Taku modules & AI** for the app in **App permissions** under manual mode. Decisions follow the permission center's active mode.
- Home's own UID, root/system/ADB shell (0/1000/2000), and components signed with Home's certificate pass directly.
- Failed authorization or an unavailable permission service denies access. Methods return `PERMISSION_DENIED`; event-stream opens report a file exception.
- `taku.control` covers Feed reads, publication, management, actions, and events. Authorized callers currently share published cards, with no per-app ownership isolation. Use your own ID prefix and operate only on your own cards.

The Provider's export declaration and runtime authorization both apply. See the [permission policy](agent-open-access.md#caller-trust-boundary-and-app-permission-center), [HomeFeedProvider](../system/agent-app/modules/taku-home/android/src/main/java/org/agentos/agent/home/feed/HomeFeedProvider.kt), and [TakuCallerAccess](../system/agent-app/modules/taku-home/android/src/main/java/org/agentos/agent/home/TakuCallerAccess.kt).

## Choose a publication method

| Method | Request | Use case |
| --- | --- | --- |
| `feed/publish` | `{"card":{…}}` | Recommendations that need updates. The same ID replaces the whole card; returns `id` and `replaced` |
| `feed/generate` | `{"card":{…}}` | Import a complete card, validate its body, and create a new ID without a model call |
| `feed/generate` | `{"prompt":"…"}` | Generate a layout from verified facts using the device's configured Magpie `generateUi` route |

Each `generate` creates `agent-generated:<uuid>`, assigns `createdAt`, `rank`, and `pinned:false`, and returns the new ID. Prompt generation also returns the actual `model`. Use `publish` for recurring updates. All publication paths set `source.id` to `agent`, the current source classification; the calling UID determines access.

External apps use this Provider. The in-process `FeedSource` named `recommendation` remains reserved, and a remote webhook is not available.

## Minimal card

Save this as `rec-card.json`. It contains the demo content shown in the screenshot. For real recommendations, supply your own ID, verified content, and an appropriate `expiresAt`.

```json
{
  "schema": "taku.feed-card/1",
  "id": "rec.docs.feed-example",
  "source": {"id": "agent", "app": "Feed example"},
  "layout": {"span": "full", "surface": "card"},
  "rank": 0,
  "pinned": false,
  "summary": "Example reminder: review your travel checklist before leaving.",
  "pin": {"label": "Travel checklist", "icon": {"kind": "pixel", "glyph": "list"}},
  "ui": {
    "catalog": "taku-feed-1",
    "state": {},
    "spec": {
      "root": "card",
      "elements": {
        "card": {"type": "Column", "props": {"gap": 12}, "children": ["header", "title", "detail", "dismiss"]},
        "header": {"type": "AppHeader", "props": {"title": "Feed example"}, "children": []},
        "title": {"type": "Text", "props": {"text": "Ready for your trip?", "variant": "body-xl-medium"}, "children": []},
        "detail": {"type": "Text", "props": {"text": "Example reminder: review your travel checklist before leaving.", "tone": "tertiary"}, "children": []},
        "dismiss": {
          "type": "Button", "props": {"label": "Dismiss example", "variant": "secondary"}, "children": [],
          "on": {"press": {"action": "dispatch", "params": {"actionId": "dismiss"}}}
        }
      }
    }
  },
  "actions": {"dismiss": {"label": "Dismiss example", "intent": {"type": "dismiss"}}}
}
```

| Empty Feed | After publishing the example |
| --- | --- |
| <img src="images/feed-recommendation/empty.png" width="240" alt="Empty Feed with the Ask Taku entry" /> | <img src="images/feed-recommendation/card.png" width="240" alt="Demo card published through feed/publish" /> |

Captured on an Android 17 emulator running Taku Agent 0.5.1 (12). Home displays the empty-state Ask Taku entry and exposes it through `feed/list.emptyState`.

## Call from an Android app

For target SDK 30 or later, declare package visibility in the app manifest and obtain the authorization described above.

```xml
<queries>
  <provider android:authorities="org.agentos.agent.home-feed" />
</queries>
```

Run this Java snippet on a worker thread, with `card` as a parsed JSON object. Also handle Android exceptions for an unavailable or invisible Provider and JSON parsing errors.

```java
Uri uri = Uri.parse("content://org.agentos.agent.home-feed");
JSONObject request = new JSONObject().put("card", card); // card is a JSONObject
Bundle out = context.getContentResolver().call(
    uri, "feed/publish", request.toString(), null);
if (out == null || out.getString("result") == null) {
    throw new IllegalStateException("Feed provider returned no result");
}
JSONObject reply = new JSONObject(out.getString("result"));
if (!reply.optBoolean("ok")) {
    throw new IllegalStateException(reply.optString("code") + " " + reply.optString("message"));
}
JSONObject result = reply.getJSONObject("result"); // id, replaced
```

Each method returns a `result` string in its `Bundle`, containing `{"ok":true,"result":{…}}` or `{"ok":false,"code":"…","message":"…"}`. Calls are synchronous. `list/get` wait up to 5 seconds for loading; the `generate` caller wait limit is 120 seconds.

## Debug with ADB

Run from the host directory containing `rec-card.json` and set `SERIAL` to your device. Passing JSON through stdin preserves quotes and Unicode. ADB uses the trusted shell identity; verify third-party authorization separately from the app's own identity.

```sh
SERIAL=emulator-5596
FEED='IFS= read -r req || exit 1
exec content call --uri content://org.agentos.agent.home-feed --method "$0" --arg "$req"'
feed() {
  printf '%s\n' "$2" | adb -s "$SERIAL" shell -T "sh -c '$FEED' $1"
}
card_request() {
  python3 -c 'import json,sys; print(json.dumps({"card":json.load(open(sys.argv[1],encoding="utf-8"))},ensure_ascii=False,separators=(",",":")))' "$1"
}

feed feed/publish "$(card_request rec-card.json)"
feed feed/get '{"id":"rec.docs.feed-example"}'
feed feed/list '{}'
feed feed/retract '{"id":"rec.docs.feed-example"}'
```

## Card and action rules

| Field | Current behavior |
| --- | --- |
| `schema`, `id` | `taku.feed-card/1`; ID matches `^[A-Za-z0-9._:-]{1,96}$`. Prefer `rec.<app>.<key>` |
| `source` | Nonempty `app` display name, optional `package`; publication forces `id` to `agent` |
| `summary` | Required. Displayed when the body fails validation or rendering, and used as Ask Taku context |
| `layout` | `span` is `full/half`; `surface` is `card/control/media`; defaults to `full/card` |
| `rank`, `createdAt` | Descending `rank`, with source order for ties; `createdAt` does not affect sorting |
| `expiresAt` | Epoch milliseconds or `null`; expired cards are filtered from the visible Feed |
| `pinned`, `pin` | Prefer `pinned:false`. Required `pin` includes an icon and `label` (up to 24 characters) or `value` (up to 12); `detail` allows 32 |
| `ui` | `catalog:"taku-feed-1"`; `spec.root` names a node in `spec.elements`; `state` holds initial interaction state |
| `actions` | Action IDs map to `label` and `intent`; interactions reference declared IDs through `dispatch` |

[feedCatalog.ts](../system/agent-app/src/feed/feedCatalog.ts) defines supported body components and properties. Use its layout, text, image, and interaction components with color/type tokens. Adapt to the host width; half-width card bodies are square. Image references support `https://` or existing `asset:feed/…` resources bundled in the APK. Remote SVGs use a restricted static subset. Native pins have separate remote-image display limitations.

`feed/publish` validates the envelope and root node. Rendering performs full body validation and falls back to `summary` on failure. Before publishing, run the same validator. Install project dependencies in `system/agent-app` first and use Node 22.18+ or 24.

```sh
cd system/agent-app
node --input-type=module -e '
import {readFileSync} from "node:fs";
import {validateFeedSpec} from "./src/feed/feedCatalog.ts";
const card = JSON.parse(readFileSync(process.argv[1], "utf8"));
const issues = validateFeedSpec(card.ui.spec, card.ui.state ?? {}, new Set(Object.keys(card.actions ?? {})));
console.log(issues.length ? JSON.stringify(issues, null, 2) : "ok");
process.exit(issues.length ? 1 : 0);
' /absolute/path/rec-card.json
```

`publish` currently checks serialized UTF-16 string length, capped at 262,144. A `generate` request allows 96,000 UTF-8 bytes, a prompt allows 16,000 UTF-16 units, and a body allows 96 elements. Only one generation runs at a time; concurrent requests return `BUSY`. If a disconnect leaves the outcome unknown, read the Feed before deciding to retry.

| `intent.type` | Parameters and behavior |
| --- | --- |
| `open-app` | `package`, optional `uri`; opens the app or its page |
| `open-url` | `url`; opens an HTTPS page |
| `ask-taku` | `prompt`; creates a conversation and saves an editable draft for the user to send |
| `dismiss` | Hides the card, matching user removal |
| `none` | Records the action and returns `effect:"none"` |

Actions execute on a user interaction or a `feed/action` call. `select` can include values from specified state paths. Feed cards accept the five intent types above; `panel` is rejected.

## Updates, withdrawal, and feedback

- **Update** with the same ID through `feed/publish`. It replaces the whole card and preserves existing pinned state.
- **Withdraw** through `feed/retract {"id":"…"}`, which also unpins the card. Expired cards remain stored, so retract obsolete recommendations.
- **Respect removal** by checking `feed/list.removed[]` or `feed/get.removed`. Publishing the same ID clears removal and shows the card again.
- **Read feedback** by taking a `feed/list` snapshot, then subscribing from its `eventCursor` to avoid missing changes after the snapshot.

Run the reader from the repository root, replacing the cursor and using your own ID prefix.

```sh
node system/agent-app/modules/taku-home/scripts/read-feed-events.mjs \
  --serial "$SERIAL" --after '<eventCursor>' --card-prefix 'rec.docs.' --follow
```

Android apps open `content://org.agentos.agent.home-feed/events?after=<cursor>&follow=1` with `ContentResolver.openFileDescriptor(uri, "r")`. Preserve cursors as opaque strings and encode them in the URI. NDJSON events support `cardId`, `cardIdPrefix`, `origin`, and `op` filters. Save each processed cursor, then save through after fully processing a checkpoint. Reconnect after EOF from the processed cursor. An action's `phase:"accepted"` means it was recorded; confirm external execution separately. Filters select events, while the interface enforces access.

Current cards and events live in local SQLite, with no fixed card count or event retention cutoff. `feed/reset` clears current cards and preferences while preserving event history. See the [module README](../system/agent-app/modules/taku-home/README.md#agent-access) and [event contract](../system/agent-app/modules/taku-home/CONTRACT.md#event-stream) for other management operations, event fields, and errors.

## Common errors

| Code | Response |
| --- | --- |
| `PERMISSION_DENIED` | Check the app's `taku.control` authorization and permission center state |
| `INVALID_ARGUMENT`, `INVALID_CARD`, `CARD_TOO_LARGE` | Correct the request or card using the returned message |
| `INVALID_SPEC`, `INVALID_MODEL_OUTPUT` | Correct the generated body or model output |
| `NOT_FOUND`, `ID_CONFLICT`, `NOT_AGENT_CARD` | Check the ID, current card state, and source |
| `STATE_WRITE_FAILED` | Investigate storage failure and read current state before deciding to retry |
| `BUSY` | Retry after the current generation completes |
| `RUNTIME_UNAVAILABLE`, `RUNTIME_LOST`, `RUNTIME_TIMEOUT` | Check the app's generation runtime; prompt generation also needs a working model configuration |
| `APP_NOT_INSTALLED`, `NO_ACTIVITY` | Check the target app and URI handler |

Checked against `main` at `65917a4a`. The example passed body validation and emulator checks for publication, reads, same-ID replacement, event replay, and withdrawal. The demo card was retracted after capture. Third-party authorization was checked in source; device calls in this pass used ADB shell.
