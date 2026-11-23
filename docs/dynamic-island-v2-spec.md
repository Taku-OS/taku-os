# Dynamic Island v2: third-party activities — implementation spec

Status: **owner decisions recorded (2026-11-23); implementation on `vela/dynamic-island`.**
Origin: [issue #30](https://github.com/Taku-OS/taku-os/issues/30) (Notebook asked to publish to the
island). Builds on [the current architecture](dynamic-island-architecture.md), the native renderer
and the onboarding island work already on `main`.
Reference: Xiaomi Super Island [product rules](https://dev.mi.com/xiaomihyperos/documentation/detail?pId=2140)
and [developer guide](https://dev.mi.com/xiaomihyperos/documentation/detail?pId=2131).

## 1. Goals

1. Any app, including Taku Agent, can show an **ongoing activity** in the island, using either Android
   Live Updates or Taku's custom JSON format. Both go through one pipeline.
2. The island has exactly **two states**: the **compact capsule** and the **expanded card**.
3. SystemUI owns ordering, switching, hiding, lifetime and all drawing. Apps publish only their
   own activity.
4. Every state and operation stays readable and operable by Agents (AGENTS.md). Third-party apps
   cannot read or operate other apps' activities.

Out of scope for v2: lock screen and AOD presentation (next version), secondary displays, landscape
cards.

## 2. Terms (renames)

| Old name | v2 name | Meaning |
|---|---|---|
| Big Island, `bigIsland` | **compact capsule**, `compact` | The capsule around the camera cutout |
| `bigIsland.leading` / `trailing` | **group A** / **group B** (`compact.a` / `compact.b`) | A is left of the camera, B is right of it |
| Small Island, `smallIsland` | *(removed as an app field)* | Multiple activities are managed by the system (§5.4) |
| Expanded | **expanded card**, `expanded` | Unchanged |
| `statusBar: auto/cover/below` | `cardPosition: cover/below` | Where the card sits relative to the cutout |
| `islandTimeoutS` | `timeoutS` | Requested lifetime |
| `compact: auto/hidden/building` | *(removed)* | Taku Agent now supplies its own capsule content |
| `category`, `relevance` | *(removed)* | Ordering is by recency (§5.5); system sources are identified by SystemUI itself |

The old names stay readable only in the v1 adapter (§9).

## 3. What qualifies as an island activity

This section ships verbatim in the developer guide. Allowlist review for the custom format enforces
it; SystemUI enforces only the time limits.

**Allowed:** ongoing activities or services that usually last minutes to hours and show
information the user is actively following. Examples: pickup codes, flight or train status, a
delivery in progress, navigation, an active recording, a timer, Taku Agent building an app.

**Required:** a clear start condition, a clear end condition (the app cancels the notification),
and a total duration of **12 hours or less**.

**Not allowed:**
- Events lasting seconds. Use a normal notification, a heads-up, or an in-app toast instead.
- Static results with no ongoing value: "update finished", "signed in", "network connected",
  "saved", app errors.
- Marketing or promotions.
- Frequent interruptions such as likes and comments.
- Services with no clear end, such as continuous stock tracking.

## 4. Publishing paths

**Decision: support both.**
- **Android Live Updates is the default**, and the developer guide recommends it first.
- **`taku.island.v2` is an optional extension** for what Live Updates cannot express:
  - choosing what goes in capsule groups A and B, including animated icons, numbers and percent,
  - the card templates, layout and accent colour,
  - `tap: open` versus `expand`,
  - `cardPosition`,
  - alert expansion,
  - Taku Agent's assistant card.
- Supporting Live Updates alone would rule out several of this spec's own requirements. Xiaomi
  HyperOS 3.1 uses the same layering.
- Publishers using the extension should still post a valid Live Update, so the same notification
  works on stock Android and on other OEMs.

Both paths produce the same internal `IslandActivity`. Ordering, gestures and drawing are identical
for both.

| | Android Live Updates (baseline) | Taku custom format |
|---|---|---|
| App work | Standard promoted ongoing notification | The same notification plus `taku.island.v2` extras |
| Taku approval | None. Android's promotion eligibility and the user's per-app Live Updates switch apply | Allowlist (`taku_island_allowed_packages`) or the `POST_ISLAND` signature permission |
| Capsule A | Large icon, else small icon | `compact.a` |
| Capsule B | `shortCriticalText`, else chronometer, else `when` | `compact.b` |
| Card | `basic` or `progress` template from title, text, progress and actions | Any public template (§6) |
| Lifetime | `setTimeoutAfter`, else the default (§5.5) | `timeoutS`, else the default |

If the custom extras are invalid or not allowed, the notification falls back to the Live Update
path when it is promoted. Otherwise it stays an ordinary notification. It is never dropped.

**Live Updates mapping work (verify against the API 37 SDK at the pinned commit):**
- `Notification.ProgressStyle`: progress value, segments and points become the card progress line.
- The large icon becomes the group A icon.
- `getTimeoutAfter()` becomes the lifetime.

The extractor today reads only the legacy `EXTRA_PROGRESS*` fields.

## 5. Behavior

### 5.1 Compact capsule

Shape: the authored metaball capsule (Figma `1379:889`). Disc 32 dp, camera 24 dp, pill 32 dp high.

| Group | Content types | Notes |
|---|---|---|
| **A** (left) | `icon`: an app image, optionally animated (§5.6), optionally with a progress ring. `animation`: a system animation | One glyph, 16 dp drawing area |
| **B** (right) | `text`, `number`, `timer`, `countdown`, `progress` (percent), `icon`, `animation` | Single line, Hanken 12 sp. Width follows the content and is cut to the room left of the status icons |

- Text must state key information as a short phrase or number. Hard limit: 16 code points.
  Recommended limit: 8 Latin or 4 CJK characters, pending the owner's design constraints.
- On overflow, B is ellipsized. If not even a short label fits, only A remains. There is no
  scrolling and no font shrinking.
- Every app supplies its own A and B. The capsule is optional: an activity that supplies no
  `compact` data never shows a capsule.
- The special cases currently in `IslandActivity` are deleted:
  - the hard-coded `Building...` (`building`),
  - the `statusLabel` shortcut, which only covers `progress` + pic + text,
  - the assistant-only capsule rule.

  Taku Agent publishes its capsule content itself (§8).
- **Taku Agent only:** the nine-dot loader (`animation: "ai-working"`) and the listening pulse
  (`"ai-listening"`). Other publishers get `invalid_animation`.

### 5.2 Expanded card

- A native card in Taku styling (92 % black, 40 dp corners). Content comes from a template (§6).
- The old card style shown in issue #30 is our earlier template card with grey rectangular buttons.
  Main still draws it, and `dcd3d352` replaces it. v2 keeps the rule that anything the native
  renderer cannot draw closes the card (`render_failed`). There is no fallback renderer.
- Position comes from `cardPosition`:
  - `cover`: the card sits over the cutout and status bar.
  - `below`: the card leaves both visible.
  - Default is `cover`. A visible IME forces `below`. Automatic alert expansions use the app's
    choice. Today they always open below.
- Tapping the card body runs `expanded.tapAction` (an action key), else the notification's
  content intent.
- Collapse happens through: swipe up on the card, back while the card covers the status bar, the
  shade opening, the keyguard, a heads-up, the activity ending, or an alert timing out. Outside taps
  still do not collapse it (decision D5).

### 5.3 Transitions and gestures

| From | Input | Result |
|---|---|---|
| Capsule | Tap | `tap: "expand"` (default) opens the card. `tap: "open"` sends the content intent and leaves the capsule as it is |
| Capsule | Long-press | Opens the card, regardless of `tap`, so `open` apps keep a way to reach it |
| Capsule | Horizontal swipe **left** | Switches to the next activity (wraps). No effect with one activity |
| Capsule | Horizontal swipe **right** | Hides the current activity (§5.4) |
| Capsule | Vertical drag | Goes to the shade (unchanged) |
| Card | Tap body | `tapAction` or the content intent |
| Card | Swipe up / back | Collapses to the capsule |
| Publisher app goes to the background | — | Its capsule appears (§5.7) |

**Mutual exclusion:** while any card is open, no capsule is drawn. This changes today's behavior,
where the capsule stays visible under a `below` card.

The activity chooser card (long-press today) and the `choose` operation are removed. Switching and
the secondary disc replace them.

### 5.4 Multiple activities (replaces Small Island)

- SystemUI keeps one ordered list (§5.5) and shows the **selected** activity as the capsule.
- When room remains, a system-drawn 24 dp **secondary disc** after the pill shows the next
  activity's group A icon. Apps no longer supply anything for it.
- Swipe left selects the next activity. An explicit selection persists until a newer activity is
  triggered (below) or the selected one ends.
- **Hide** (swipe right, Agent `dismiss`) removes that activity from the island for the rest of its
  notification's lifetime. Updates from the app do not bring it back. A new notification identity
  does. The notification itself is never cancelled. This keeps today's dismissal semantics.

### 5.5 Ordering and lifetime

**Ordering**, highest first:
1. **System tier:** calls, alarms, and screen or audio recording and casting. These are privacy and
   safety signals and must never sit behind an app activity.
   - SystemUI identifies them from its own sources: CallStyle plus the dialer role, the alarm
     full-screen intent, and the recorder and cast controllers. It never takes them from a JSON field.
2. An activity inside its alert window (at most 5 s).
3. **Everything else by `triggeredAt`, newest first.** `triggeredAt` is the time of the first post,
   refreshed when an update raises `alert` from false to true. Routine updates such as progress
   ticks or countdown changes do **not** reorder.
4. Ties are broken by key.

**No `category` field.** v1 needed one for two jobs, and v2 needs neither:
- **Ranking:** replaced by recency.
- **Reserving privileged presentations:**
  - The system tier comes from SystemUI's own sources (above).
  - The `assistant` template and the `ai-*` animations are reserved by checking the publisher: the
    package must be `org.agentos.agent` with platform signature, else `reserved_template` or
    `invalid_animation`.

For comparison, Xiaomi's `business` field is required but only used for statistics.

**Lifetime:**
- **Start:** the first accepted post.
- **End**, whichever comes first:
  - the app cancels the notification,
  - `timeoutS` or `setTimeoutAfter`, counted from start,
  - the default timeout,
  - the **12 h hard cap** from start (today it is 8 h),
  - the user hides the activity.
- **Default when the app gives no timeout: 1 hour from the first post.** Updates do not extend it.
  Apps whose activities can run longer declare it with `timeoutS` (≤ 43200) or `setTimeoutAfter`.
- This mirrors Xiaomi's `islandTimeout` ("岛自动消失的时间", default 3600 s). Xiaomi keeps that
  separate from the notification's own `timeout` (720 min).
  - Xiaomi does not document whether updates restart either timer.
  - A fixed deadline is the predictable reading. It also forces long-running apps to state their
    end, which §3 requires.
  - It matches today's store, so no change is needed there.
- When an activity's island time is up, the activity leaves the island and its notification stays
  in the shade.
- Xiaomi's `islandOrder` defaults to false: an update does not reorder the island. That matches the
  "routine updates don't reorder" rule above.
- `staleAfterS` is kept: a stale activity is drawn muted. It no longer changes rank, because recency
  rules.

### 5.6 App-supplied images and animation

- Images are `android.graphics.drawable.Icon` objects in `taku.island.pics`. Allowed kinds are
  bitmap, adaptive bitmap, or a resource **from the posting package** (the existing `validIcon`
  check). No URI icons.
- If a resource icon loads as an `Animatable2` drawable (AnimatedVectorDrawable), it runs when
  `animate: true`. It stops when the capsule is hidden and when animator duration scale is 0. It
  stops after 60 s of continuous looping.
- The optional `progress` (0–1) on an A icon draws the 2 dp ring around it.
- `tint: true` tints a monochrome icon white.

### 5.7 Foreground suppression

An activity's capsule is not drawn while its **publishing app's task is the focused full-screen
task**. The app already shows that information itself. When the user goes home or switches apps,
the capsule appears, provided the activity supplied `compact` data. This is how "returning to the
home screen collapses into the capsule" works (owner-confirmed).

- Exempt when the publisher is the default home app, so Taku Agent's `Building...` stays visible on
  the home screen.
- Cards are not affected. An alert from a foreground app still expands.
- Implementation: SystemUI's `TaskStackChangeListeners` (`onTaskMovedToFront`) combined with the
  focused root task. Verify the exact API at the pinned commit.

### 5.8 Heads-up

Today an island activity can also raise a stock heads-up, which then hides the capsule. Island
`alert` is the island-native replacement.

v2 rule: while SystemUI can present the island (unlocked, shade closed, island enabled, activity
not hidden), a notification that is an accepted island activity **does not heads-up**. Its alert
expands the card instead. When the island cannot present it, Android's normal heads-up applies.

- Implementation: one small upstream hook that registers a filter with
  `VisualInterruptionDecisionProvider`. Verify the API at the pinned commit.
- Calls and alarms keep their full-screen intents.

## 6. Card templates (public)

Shared fields:
- `title` (≤ 80), `pic`,
- `actions`: **no design limit**, one optional `primaryAction` (drawn filled),
- `tapAction`,
- `accentColor`: `#RRGGBB`, contrast ≥ 4.5:1 against the card. Rejected with `low_contrast`
  otherwise. It is used for the primary chip, the progress line and highlight text.

Action chips are a **single row that scrolls horizontally** and never wraps. Developers decide how
many to provide. The only bounds are technical: the 8 KB payload and 32 action keys, to bound
parsing.

The onboarding progress card on `main` (`IslandProgressCard`) stays Taku Agent's own renderer.
- **Deviation from the first draft (implementation, 2026-11-23):** it implements an accepted design
  (Figma `1155:1965`) with equal-width buttons and a Close button. That conflicts with the public
  rule of one scrolling action row.
- It therefore remains selected only for Taku Agent's simple onboarding payload, and the public
  `basic` template keeps the scrolling chips.

| Template | Layout | Typical use |
|---|---|---|
| `basic` | Header icon, text block (`title` + `body` ≤ 2000), action chips. This is the current design | Status with actions |
| `progress` | Title, `body`, progress line (value or indeterminate), chips | Delivery, upload, ride |
| `highlight` | One large emphasized value (`highlight.text` ≤ 12, e.g. a pickup code or gate) with `highlight.label` and `body`, chips | Pickup code, flight, queue number |
| `assistant` | Native Taku Agent card, **Taku Agent only** | AI conversation |

Template content must fit its component. SystemUI clips at the template's limits and caps card
height at one third of the screen with scrolling, as today. The owner's design constraints will set
the final per-field limits.

Remote content surfaces (`expanded.content`, `SurfaceControlViewHost`) are restricted to
platform-signed publishers in v2.

## 7. Custom format `taku.island.v2`

Same transport as v1:
- `taku.island.v2`: a String, JSON, at most 8 KB UTF-8, duplicate keys rejected, depth ≤ 8,
- `taku.island.pics`: a Bundle of String to Icon,
- `taku.island.actions`: a Bundle of String to Notification.Action.

A new `spec/taku-island-v2.schema.json` replaces v1. The shape:

```json
{
  "version": 2,
  "compact": {
    "a": {"type": "icon", "pic": "logo", "animate": false, "progress": 0.4},
    "b": {"type": "text", "text": "A123", "emphasis": "accent"}
  },
  "expanded": {
    "template": "highlight",
    "title": "Order ready",
    "highlight": {"text": "A123", "label": "Pickup code"},
    "body": "Counter 3 · ready until 14:30",
    "pic": "logo",
    "actions": ["directions", "done"],
    "primaryAction": "done",
    "tapAction": "open_order",
    "accentColor": "#FF8A3D"
  },
  "tap": "expand",
  "cardPosition": "below",
  "alert": true,
  "timeoutS": 5400,
  "staleAfterS": 600
}
```

| Field | Rule |
|---|---|
| `version` | `2` |
| `compact` | Optional. Omitting it means the activity never shows a capsule |
| `compact.a` | `icon {pic, animate?, progress?, tint?, contentDescription?}` or `animation {name}` |
| `compact.b` | `text {text ≤16, emphasis normal/accent/muted}`, `number {value 1–3 digits, unit ≤4}`, `timer {startEpochMs}`, `countdown {endEpochMs}`, `progress {value?}`, `icon`, `animation`. At least one of A and B is required |
| `animation.name` | `ai-working` and `ai-listening` are Taku Agent only. Any other system animations are added only together with a design |
| `expanded` | §6 |
| `tap` | `expand` (default) or `open` |
| `cardPosition` | `cover` (default) or `below` |
| `alert` | A false-to-true change expands the card for ≤ 5 s and refreshes `triggeredAt` |
| `timeoutS` | 1–43200 |
| `staleAfterS` | 1–43200 |

Errors keep today's codes and add `reserved_template`, `invalid_animation`, `low_contrast` and
`too_many_actions` (the technical bound of 32). The fields `category`, `relevance`, `bigIsland`,
`smallIsland` and `statusBar` are `unknown_field` in v2. Rejected extras appear in `diagnostics` and fall back to
the Live Update path.

## 8. Taku Agent

Taku Agent publishes v2 with its own capsule content:
- While building an app: A = `animation ai-working`, B = `text "Building..."`.
- In other states it omits `compact`, so there is no capsule. This keeps the current product rule
  (no capsule for listening, replies or errors), but the rule now lives in the publisher
  (`IslandPublisherService.kt:97-137`) instead of in SystemUI.

## 9. Security and access (prerequisite)

**Current defect:** `IslandProvider` is `exported="true"` with no permission
(`image/island/systemui-probe.patch:31`) and clears the calling identity. Any installed app can do
all of the following today:
- read every app's island content,
- run `action` and `open` on other apps' PendingIntents, with background activity start allowed,
- call `setEnabled`,
- call `setAllowed`, which lets it add itself to the publisher allowlist.

This must be fixed before any third-party publisher is allowed.

| Caller | Access |
|---|---|
| UID 0, 1000, 2000, the SystemUI UID, platform-signed packages | Everything (unchanged for Agents and ADB) |
| A third-party app granted `taku.control` in the App Permission Center | `state`, `list`, `get`, `select`, `next`, `expand`, `collapse`, `dismiss`, `open`, `action`. Never `setEnabled` or `setAllowed` |
| Any other third-party app | `capabilities`, and `canShowIsland` **for its own packages only** (the equivalent of Xiaomi's `canShowFocus`) |

Reuse the existing gate pattern (`TakuCallerAccess.kt`, `AudioOpenAccessProvider.kt`). Unauthorized
calls return `permission_denied`.

**Migration:** SystemUI accepts `taku.island.v1` through an adapter that maps it onto v2 until the
Taku Agent APK that publishes v2 has shipped. The adapter is then deleted. The agent APK and the
SystemUI image release separately (`docs/agent-app-releases.md`), so they must overlap for one
release.

## 10. Agent interface changes

`cmd statusbar island …` and `content://com.android.systemui.island` keep one implementation:

| Change | Operation |
|---|---|
| Add | `next`: same as swipe left |
| Add | `tap KEY`: runs the capsule's configured tap behavior (expand or open) |
| Keep | `dismiss KEY`, now also reached by swipe right; `hide` is added as an alias |
| Remove | `choose` |
| `state.presentation` | Adds `selectedReason`, `suppressedByForeground`, `tap`, `cardPosition`, `secondaryKey` |
| `get` | Adds `triggeredAtElapsedMs`, `timeoutKind` (`explicit` / `default` / `cap`), `source` (`live-update` / `custom-v2` / `custom-v1` / `system`) |
| Settings | `taku_island_protocol` becomes `2`. `capabilities.protocols` = `[1, 2]` during migration |

Update the preinstalled skill (`system/assistant/assets/skills/taku-island/SKILL.md`) in the same
change as each operation, as AGENTS.md requires.

## 11. Implementation plan

Each phase is one reviewable PR. Regenerate the SystemUI patch (`system/island/update-patch.py`)
in every phase that touches SystemUI sources.

| Phase | Work | Main files |
|---|---|---|
| **P0 Prerequisites** | Gate the provider (§9); the native renderer is already on main. Tests for each caller class | `IslandProvider.kt`, new `IslandCallerAccess.kt`, `systemui-probe.patch` |
| **P1 Protocol v2** | v2 schema and validator; v1 adapter; rename internals (`bigIsland` → `compact.a/b`, remove `smallIsland`, `building`, `statusLabel`, `category`, `relevance`); publisher-reserved template and animations; 12 h cap; 1 h default from first post; `triggeredAt` ordering; Live Updates mapping (ProgressStyle, large icon, `timeoutAfter`) | `spec/taku-island-v2.schema.json`, `IslandPayload.java`, `IslandActivity.kt`, `IslandStore.java`, `IslandEngine.kt`, `IslandExtractor.kt`, `IslandParserTests.java`, `IslandStoreTest.java` |
| **P2 Presentation** | Capsule A/B renderer with app icons, animated drawables and ring; delete hard-coded Building; mutual exclusion; tap/long-press/swipe-left/swipe-right; secondary disc from the next activity's A icon; remove the chooser; foreground suppression; `cardPosition` honored for alerts; heads-up filter hook | `IslandCapsuleView.kt`, `IslandPresenter.kt`, `IslandCardWindow.kt`, new `IslandForeground.kt`, small upstream hook in the patch |
| **P3 Templates** | `basic` (adopting the `IslandProgressCard` chrome), `progress`, `highlight`; `accentColor` with contrast check; one horizontally scrolling chip row; `tapAction`; remove the `org.agentos.agent` progress-card special case | `IslandTemplateCard.kt`, `IslandProgressCard.kt`, `IslandStyle.kt` |
| **P4 Taku Agent** | Publish v2 (§8); probe tests | `IslandPublisherService.kt`, `NativeIslandProbe.kt` |
| **P5 Agent surface and docs** | §10 operations; skill; developer guide (§12); update the architecture doc and README; progress ledger | `IslandEngine.kt`, `SKILL.md`, `docs/dynamic-island-developer.md`, `docs/dynamic-island-architecture.md`, `system/island/README.md` |
| **P6 First partner** | Seed `org.agentos.notebook` in the default allowlist. It is platform-signed, so it may also declare `POST_ISLAND`. The Notebook team owns its use cases, such as voice memos | Image settings defaults |

**Verification, per AGENTS.md, on an isolated emulator from the patched image:**
- Parser and store unit tests: v2 validation, the v1 adapter, reserved categories, both timeout
  kinds, the 12 h cap and `triggeredAt` ordering.
- Device fixture (`system/island/device-tests/Fixture.java`) posting:
  - one Live Update, one v2 activity and one v1 activity,
  - two concurrent apps to check switching, hiding and the secondary disc,
  - a foreground and background transition,
  - `tap: open` vs `tap: expand`,
  - `cardPosition` cover and below,
  - an alert with heads-up suppression,
  - expiry.
- Each check is read back through `cmd statusbar island state` and compared with a screenshot. The
  GUI and Agent operations must reach the same state.
- Provider access matrix: shell, a platform-signed caller, a granted third-party test app, and an
  ungranted third-party test app.

## 12. Developer integration guide (P5)

I'll write this after the spec is approved and P1–P3 land, so it documents real behavior. Planned
contents:
1. When to use the island (§3, verbatim).
2. Path A, Live Updates: a code sample, the field mapping and lifetime (`setTimeoutAfter`).
3. Path B, the custom format: requesting allowlisting, the v2 JSON, pics and actions bundles,
   updating with the same notification ID, ending by cancel.
4. The design constraints for capsule A/B and the templates (owner-supplied).
5. Behavior apps can rely on: ordering, hiding, foreground suppression, heads-up replacement,
   lock screen not yet supported.
6. Checking capability (`canShowIsland`) and debugging (`diagnostics` error codes).

## 13. Owner decisions (2026-11-23)

1. **Publishing paths:** both. Live Updates is the default and `taku.island.v2` is the extension
   (§4).
2. **No `category`** field (§5.5).
3. **Actions:** no design limit, one horizontally scrolling row (§6).
4. **Gestures:** swipe left switches to the next activity, swipe right hides the current one (§5.3).
5. **Default lifetime:** 1 hour from the first post, extendable only by an explicit timeout of up to
   12 h (§5.5).
6. **Foreground suppression:** confirmed. A capsule appears after leaving the app only if the
   activity supplies `compact` data (§5.7).
7. **First partner:** `org.agentos.notebook`, platform-signed (same certificate as SystemUI in
   TakuOS 1.4.0). The Notebook team defines and implements its qualifying use cases, such as
   recording a voice memo. This project only provides the general island capabilities.
