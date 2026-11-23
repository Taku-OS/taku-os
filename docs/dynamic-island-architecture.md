# Dynamic Island architecture (system-wide activity display)

Status: **implementation in progress; integrated ROM/device acceptance pending**. See the
[phase ledger](dynamic-island-progress.md).

> **v2 (2026-11-23)** supersedes the v1 parts of this document. The current behaviour is in
> [the v2 spec](dynamic-island-v2-spec.md) and [the developer guide](dynamic-island-developer.md),
> with the contract in [`spec/taku-island-v2.schema.json`](../spec/taku-island-v2.schema.json).
> - Two states only: the compact capsule (groups A/B) and the expanded card. Small Island became a
>   system-managed secondary disc.
> - No `category`/`relevance`. Order is system tier (call, alarm, recording/cast), then a live
>   alert, then the most recently triggered activity.
> - Lifetime is 1 h from the first post by default, up to 12 h when declared.
> - Gestures: tap follows `tap` (`expand`/`open`), long press expands, swipe left selects the next
>   activity, swipe right hides. The chooser card is gone.
> - Capsules are hidden while their app is in front.
> - Island activities replace their stock heads-up while presentable.
> - Provider callers are authorized: third-party apps need an App Permission Center grant.
>
> v1 extras are adapted onto v2. Statements below about big/small island, category arbitration,
> the 8 h cap and the long-press chooser describe v1.

## Goal

Upgrade the Dynamic Island from a Compose component inside the home app into a system-wide activity
display owned by SystemUI:

- **Compact:** a black capsule around the front-camera cutout *inside the status bar*. The clock,
  signal and battery stay visible on both sides; the status bar is never hidden for the island.
- **Expanded:** a card at the top of the screen that covers the status bar or sits below it,
  depending on the situation. It is **non-modal**: the rest of the screen keeps working.
- Apps submit island data (big island, small island, expanded content, actions) through
  notifications; SystemUI renders it and offers query interfaces.
- The island stays visible across apps. Home, assistant and Agents drive one shared state.
- Animations, touch and lifecycle belong to the native shell. Content (AI, React Native) can only
  fill slots and never blocks or crashes SystemUI.

### Today (to be replaced)

`taku-home` draws the island (`ui/assistant/DynamicIsland.kt`) inside `HomeActivity`. Because its
window sits below the status bar window, it hides the whole status bar to make the capsule tappable
(issue #25 F1), and a near-full-screen transparent layer catches outside taps to collapse the card.
It disappears when another app is in front.

## Prior art

Every comparable system renders the island itself from structured data; apps never draw it.

| Platform | Renderer | App supplies | Transport | Arbitration | Lifetime |
|---|---|---|---|---|---|
| Xiaomi HyperOS Super Island | SystemUI templates | JSON in `extras["miui.focus.param"]` (`param_v2` / `param_island`: `bigIslandArea` A/B zones, `smallIslandArea`, `baseInfo`), images `miui.focus.pics`, actions `miui.focus.actions` | Notification / MiPush | Not documented (`islandOrder`, `sequence`) | `islandTimeout`, scenes < 12 h, reviewed allow-list |
| Apple Live Activities | System (sandboxed SwiftUI extension) | State ≤ 4 KB + compact leading/trailing, minimal, expanded regions | Local / APNs with budget | One compact; others minimal; `relevanceScore` | 8 h + 4 h, `staleDate` |
| Android Live Updates | SystemUI | Promoted ongoing notification (`setRequestPromotedOngoing`, `shortCriticalText`, `when`, ProgressStyle/CallStyle); no custom views | Notification | Fixed chip priority, primary + secondary chip | Notification lifetime |
| Huawei Live View Kit | System templates | Capsule (text/timer/progress) + card areas | Local / Push Kit (rate-limited) | Not documented | 8 h; stale 2 h hides, 4 h ends |
| Samsung Now Bar, OPPO Fluid Cloud | System | Converging on Android Live Updates (+ proprietary payloads) | Notification | — | OPPO capsule ~5 min |

Adopted: template slots instead of custom views; leading/trailing compact slots plus a small
secondary form; central arbitration with host-enforced lifetimes; standard Live Updates as the
baseline so third-party apps work without a Taku SDK. Live Updates alone lack Taku's layouts,
stacking and AI cards, so Taku extends them on the system side.

## Decisions

| # | Decision |
|---|---|
| D1 | SystemUI hosts the island. The compact capsule is part of the status bar; the expanded card is a SystemUI-owned window. |
| D2 | Producers submit data through notifications: Android Live Updates as the baseline, `taku.island.*` extras for Taku layouts. |
| D3 | Content is slots, not views. Template slots render natively; a rich AI card body may come from a separate content host as an embedded surface. |
| D4 | The native shell owns geometry, motion, touch and lifecycle; content updates never delay animations. |
| D5 | **Outside taps never collapse the expanded card.** It collapses only through explicit gestures (see [Expanded card](#expanded-card)). |
| D6 | Calls, alarms and recording use system events directly inside SystemUI, without AI or JavaScript. |
| D7 | React Native / AI content runs in a separate content-host process; its loading or failure cannot affect SystemUI. |
| D8 | Every state and operation is available to Agents through standard interfaces (AGENTS.md). |

## Base platform and hook points

Image: LineageOS 24.0 / Android 17 (API 37), `lineage_tokay`. SystemUI is
`LineageOS/android_frameworks_base` pinned at `7f6de40b6429462d47ba9ba351b99943269c8067`
(`image/manifests/platform.xml`). Changes follow the existing pinned-patch model
(`image/apply-updater-patches.py`): a reviewed patch against that exact commit, an explicit target
file list and hash checks. Taku code lives in a new package `com.android.systemui.taku.island` with
its own Dagger module; upstream files only get small hooks to keep rebases cheap.

| Need | Existing anchor at the pinned commit | Use |
|---|---|---|
| Capsule in the middle, real icons on both sides | `res/layout/status_bar.xml`: `status_bar_start_side_container` (clock, notification icons, chips) · `cutout_space_view` (zero-width `Space` that "covers the notch and lets other views lay out around it") · `status_bar_end_side_container` (`system_icons`, battery); Lineage `centered_area` overlay | Draw the capsule over `cutout_space_view` and widen it to the capsule width so both sides re-layout around it |
| Compose in the status bar | `StatusBarRoot.kt` inflates the legacy layout in an `AndroidView` and attaches Compose (`addStartSideComposable` for chips, `addEndSideComposable` for icons/battery) | Add an `addCenterComposable` hook for the capsule |
| Tap vs. shade pull-down | `forwardDragAndSwipeToShadeRootView` forwards vertical drags past touch slop to the shade root | Capsule handles taps/long-press; drags still open the shade |
| Width budget | `chipsMaxWidth()` budgets chips from clock/side-container bounds | Same budget per capsule side |
| Notification content | `statusbar/notification/promoted/PromotedNotificationContentExtractor.kt` (`@WorkerThread`) extracts `shortCriticalText`, `When`, progress, style, icons, colours (not actions) | Island extractor extends it and adds Taku extras and actions |
| System events | Ongoing activity chips (call, screen record, share, cast); `OngoingActivityChipsViewModel.pickMostImportantChip` | Island sources for call/alarm/recording; one arbiter for chip vs. island |

Window layers (AOSP `WindowManagerPolicy`): `APPLICATION_OVERLAY` 11 < `STATUS_BAR` 15 <
**`STATUS_BAR_ADDITIONAL` 16** < `NOTIFICATION_SHADE` 17 (shade and keyguard). `DisplayPolicy`
requires `STATUS_BAR_SERVICE` for status-bar window types; only the status bar, shade and navigation
bar are singletons.

## Architecture

```
 ┌──────────── SystemUI process (native shell; never waits on content) ─────────────┐
 │ IslandSources ─▶ IslandRepository ─▶ IslandArbiter ─▶ IslandPresenter (state+motion)│
 │  · NotifPipeline extractor: Live Updates + taku.island extras                       │
 │  · system events: call (Telecom/CallStyle), alarm (CATEGORY_ALARM FSI), rec/cast     │
 │ CompactCapsule : Compose in StatusBarRoot over cutout_space_view                     │
 │ ExpandedCard   : TYPE_STATUS_BAR_ADDITIONAL window, non-modal, touchable = card      │
 │ Agent/query API: content provider · `cmd statusbar island …` · Settings · dumpsys    │
 └──────────▲───────────────────────────────────────────────▲───────────────────────┘
   notifications (data, actions)                    SurfacePackage (rich card body)
 ┌──────────┴────────────────────┐            ┌─────────────┴─────────────────────────┐
 │ Producers                      │            │ Content host (org.agentos.agent)       │
 │ · AssistantSession (home + AI) │            │ · IslandContentService +               │
 │ · third-party Live Updates     │            │   SurfaceControlViewHost               │
 │ · Taku apps with taku.island.* │            │ · AI card body (Compose / RN)          │
 └────────────────────────────────┘            └────────────────────────────────────────┘
```

- **Repository:** island items keyed by notification key; in memory; revision order from
  notification updates; host-enforced `islandTimeoutS`, staleness and an 8 h hard cap.
- **Arbiter:** picks primary (big island) and secondary (small island), decides chip vs. island and
  heads-up vs. card.
- **Presenter:** the only owner of presentation state (`hidden`, `compact`, `expanded(cover|below)`)
  and of all motion.
- **Content host:** optional. SystemUI binds an exported, signature-protected service named by the
  producer, receives a `SurfacePackage` and embeds it with `SurfaceView.setChildSurfacePackage`.

## Producer protocol

### Baseline: Android Live Updates

Any promoted ongoing notification (`POST_PROMOTED_NOTIFICATIONS`, `setRequestPromotedOngoing(true)`,
ongoing, content title; style standard/BigText/Call/Progress) becomes an island item. Compact data:
`shortCriticalText`, then `when`/chronometer, then the icon (Android's chip precedence). Expanded data:
the native template from the extracted content model.

### Taku extension: notification extras

| Key | Type | Content |
|---|---|---|
| `taku.island.v1` | String (JSON, ≤ 8 KB UTF-8) | Validated against [`spec/taku-island-v1.schema.json`](../spec/taku-island-v1.schema.json) on a SystemUI worker thread |
| `taku.island.pics` | Bundle<String, Icon> | Image keys referenced by `pic` fields (`^[a-z][a-z0-9_]{0,31}$`) |
| `taku.island.actions` | Bundle<String, Notification.Action> | Action keys referenced by `expanded.actions` |

Rules:

- The notification must still be valid on its own; without island support or permission it shows as
  a normal notification (or Live Update) and is never dropped.
- Taku extras require `org.agentos.permission.POST_ISLAND` (signature, or user allow-list in
  Settings). Without it SystemUI ignores the extras and uses the Live Update baseline.
- Re-posting the same notification id updates the item; cancelling the notification ends it. Unknown
  fields, oversize payloads, unknown pic/action keys or invalid values reject the extras (logged in
  `dumpsys`), falling back to the baseline.
- No HTML, scripts, URLs to load or custom `RemoteViews`. Actions are only `PendingIntent`s of the
  producer's own notification.

### Query interfaces

| Interface | Methods / keys |
|---|---|
| `content://com.android.systemui.island` (`call`) | `capabilities` (protocol version, templates, limits), `canShowIsland` (package), `list`, `get` (key) |
| Settings (`settings get/put global …`) | `taku_island_enabled`, `taku_island_protocol` (read-only, `1`), `taku_island_allowed_packages` |

## Compact capsule

- **Geometry:** leading and trailing halves left and right of the camera from
  `DisplayCutout.getCutoutPath()` / `getBoundingRectTop()` (the 1-6 metaball shape). Without a cutout:
  centred top capsule. Landscape: compact only, v1 hides it on secondary displays.
- **Layout:** `cutout_space_view` widens to the capsule width; start and end sides re-layout around it.
  Per-side width follows the `chipsMaxWidth()` budget; on overflow the trailing slot falls back to
  icon-only, then the item shows its small island.
- **Lineage centred clock / centre network traffic** cannot share the middle: while an island shows,
  they move to the start side.
- **Touch (inside the status bar window):** tap expands; long-press shows the item list or the
  secondary item; horizontal swipe switches primary/secondary; vertical drag goes to the shade via
  `forwardDragAndSwipeToShadeRootView`. Handlers act on in-memory state and respond immediately even
  while content is loading.
- **Shade:** the capsule moves with the status bar; inside the shade the item is represented by its
  promoted notification card.
- **Duplicates:** the arbiter hides the equivalent ongoing chip while the island shows the item.

## Expanded card

- **Window:** `TYPE_STATUS_BAR_ADDITIONAL`, added by SystemUI: above the status bar, below the shade and
  keyguard, so pulling down the shade covers it.
- **Non-modal (D5):** `FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCH_MODAL`; the touchable region is exactly the
  card's current shape, updated every animation frame. Touches outside the card go to the app below,
  which stays fully usable (including the lower half of the screen). **Outside taps never collapse the
  card.** The near-full-screen transparent catcher is removed.
- **Explicit collapse only:** swipe up on the card; tap the capsule area / card handle; back gesture
  while the card covers the status bar; an action that ends the item; the shade opening; the producer
  ending the item; the timeout of an *alert* expansion.
- **Covering the status bar** (host policy; the producer's `statusBar` hint is advisory):

  | Trigger | Position |
  |---|---|
  | Alert (incoming call, alarm, AI needs confirmation) or user tap on the capsule | Covers the status bar, grown from the capsule |
  | Docked / long-lived (AI reply while using another app, composer open) | Below the status bar; clock and battery stay visible |

- **Keyboard:** the window receives the display's IME insets without taking focus; maximum height stays
  above the IME and it never takes focus from the app's text field.
- **Heads-up notifications** (shade window, above the card): the card collapses to the capsule while a
  heads-up shows.
- **Accessibility:** native nodes for template content; the embedded content host exposes its own
  accessibility tree across the `SurfaceControlViewHost` boundary.

## Motion and content isolation

- The presenter runs all transitions (capsule ⇄ card, primary/secondary swap, stale dimming) in
  SystemUI on container geometry.
- Content arrives as snapshots. A new snapshot never restarts or delays a running animation; it is
  applied at the next stable frame or cross-faded in place.
- A rich body is a separate surface that SystemUI only clips and transforms. Until its first frame, or
  if the host is slow, crashes or is missing, the native template (title, body, steps, actions) is
  shown. Host failure never affects SystemUI.

## Sources and shared state

| Source | Path | Waits on AI/JS? |
|---|---|---|
| Incoming / ongoing call | SystemUI call pipeline (Telecom, CallStyle full-screen intent), accept/decline from CallStyle actions | No |
| Alarm / timer | `CATEGORY_ALARM` / full-screen-intent notifications, snooze/stop actions | No |
| Screen recording, cast | Existing ongoing-chip interactors | No |
| Taku AI | `AssistantSession` posts its island notification (template fields) and names the content host for the rich body | Template no; rich body yes (content host) |
| Third-party apps | Live Updates baseline; extras with permission | No |

`AssistantSession` stays the single authority for the AI conversation; home, assistant and Agents
drive it as today. Card actions (stop, open conversation, reply chips) are `Notification.Action`
`PendingIntent`s into the session. The home keeps only the entry (sphere, voice hold, composer) and
stops drawing the island or hiding the status bar.

## Arbitration

Priority (extends the AOSP chip order):
**call > alarm > screen recording/cast > navigation > timer > assistant > media > progress > other**.
Within a category: `relevance`, then recency. Primary = big island, secondary = small island. Alerts
preempt temporarily without reordering. Stale items drop in rank; `islandTimeoutS` and the 8 h cap end
items. The same arbiter decides chip vs. island and heads-up vs. card.

## Agent parity

| Interface | Operations |
|---|---|
| `adb shell cmd statusbar island …` (SystemUI command registry; pass-through to be confirmed in phase 0) | `list`, `get <key>`, `expand <key>`, `collapse`, `dismiss <key>`, `action <key> <action>` |
| `content://com.android.systemui.island` | `capabilities`, `canShowIsland`, `list`, `get` |
| Settings | `taku_island_enabled`, `taku_island_protocol`, `taku_island_allowed_packages` |
| `dumpsys` | Items, arbitration decisions, rejected extras with reasons, geometry |

Agents produce islands the standard way, by posting notifications, under the same validation and
permission as apps. All paths use the repository and arbiter the GUI renders from. Commands and errors
go into the preinstalled agent documentation when implemented.

## Reuse and rework

| Reuse | Rework |
|---|---|
| `AssistantSession` state, failure/offline handling, provider methods | Host: home Compose → SystemUI patch |
| Expanded body visuals (headline, steps, chips, 1-4 bottom-edge refraction) move to the content host | Status bar layout: capsule over `cutout_space_view`, width budget, centred clock |
| Capsule visuals (1-6 metaball, working dots, eyes) re-implemented natively from the same design tokens | Touch: capsule gestures in `StatusBarRoot`, non-modal card window, catcher removed |
| Working glow and design tokens | Lifecycle across apps: notification-driven, shade/heads-up/keyguard coordination, home stops hiding the status bar |

## Delivery plan

| Phase | Deliverable |
|---|---|
| 0. Technical validation | Minimal `frameworks/base` patch on the pinned commit: capsule over `cutout_space_view` with both sides re-laid out; tap vs. shade drag; non-modal `STATUS_BAR_ADDITIONAL` card with card-shaped touchable region (lower half reaches the app, outside taps keep the card); one embedded `SurfacePackage` from the agent app, killing its process leaves SystemUI intact; parse the draft extras |
| 1. Protocol | Final `taku.island.v1` schema, SystemUI extractor, permission and Settings, provider and `cmd` |
| 2. Native shell | Cutout layout, capsule gestures, non-modal card, presenter and embedded-surface lifecycle |
| 3. Taku AI | `AssistantSession` publishes; content host renders the card body; home island and status-bar hiding removed |
| 4. System sources | Call, alarm, recording; arbitration with chips and heads-up |
| 5. Third-party | Live Updates baseline, allow-list UI, preinstalled skill documentation |

## Risks to settle in phase 0

1. Building and validating needs a Lineage 24 / Android 17 image build and an emulator on the build
   host.
2. Lineage status bar options (clock position, network traffic, Lineage items) competing for the middle.
3. `SurfaceControlViewHost` input transfer and accessibility across the embedding.
4. Rebase cost: keep upstream hooks minimal, all Taku code in its own package.
5. Lock screen and AOD: at layer 16 the card is behind the keyguard; island presence there is a separate
   decision (`AODPromotedNotification` is the natural hook).

## Sources

- Xiaomi Super Island: [compact design spec](https://dev.mi.com/xiaomihyperos/documentation/detail?pId=2143),
  [development guide](https://dev.mi.com/xiaomihyperos/documentation/detail?pId=2131),
  [FAQ](https://dev.mi.com/xiaomihyperos/documentation/detail?pId=2146),
  [version info](https://dev.mi.com/xiaomihyperos/documentation/detail?pId=2141)
- Apple: [HIG Live Activities](https://developer.apple.com/design/human-interface-guidelines/live-activities),
  [ActivityKit](https://developer.apple.com/documentation/activitykit/displaying-live-data-with-live-activities)
- Android: [Live Updates](https://developer.android.com/develop/ui/views/notifications/live-update),
  [`setShortCriticalText`](https://developer.android.com/reference/android/app/Notification.Builder#setShortCriticalText(java.lang.String)),
  [`DisplayCutout`](https://developer.android.com/reference/android/view/DisplayCutout)
- AOSP: [`WindowManagerPolicy`](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/policy/WindowManagerPolicy.java),
  [`DisplayPolicy`](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/wm/DisplayPolicy.java),
  [`ScreenDecorations`](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/packages/SystemUI/src/com/android/systemui/ScreenDecorations.java),
  [status bar chips](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/packages/SystemUI/src/com/android/systemui/statusbar/chips/)
- Pinned SystemUI: [`status_bar.xml`](https://github.com/LineageOS/android_frameworks_base/blob/7f6de40b6429462d47ba9ba351b99943269c8067/packages/SystemUI/res/layout/status_bar.xml),
  [`StatusBarRoot.kt`](https://github.com/LineageOS/android_frameworks_base/blob/7f6de40b6429462d47ba9ba351b99943269c8067/packages/SystemUI/src/com/android/systemui/statusbar/pipeline/shared/ui/composable/StatusBarRoot.kt),
  [`PromotedNotificationContentExtractor.kt`](https://github.com/LineageOS/android_frameworks_base/blob/7f6de40b6429462d47ba9ba351b99943269c8067/packages/SystemUI/src/com/android/systemui/statusbar/notification/promoted/PromotedNotificationContentExtractor.kt)
- Huawei: [Live View Kit](https://developer.huawei.com/consumer/cn/doc/harmonyos-guides/liveview-introduction)
