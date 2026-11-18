# Taku system surfaces (development version 0.3)

An independent Android system component, `org.agentos.surfaces`, for hints, choices, and results across apps. It renders caller-provided data and returns user actions. It has no dependency on the assistant, models, task queues, screen understanding, or business cases, and no network permission. Current visuals validate capability boundaries; the final interaction design can evolve.

## Module boundaries

- [SurfaceSpec](contract/org/agentos/surfaces/SurfaceSpec.java) / [v1 protocol](../../spec/system-surface.schema.json) / [v2 protocol](../../spec/system-surface-v2.schema.json): versioned content, action IDs, placement, revisions, artifact references, and lifetime.
- [SurfaceStore](contract/org/agentos/surfaces/SurfaceStore.java): caller-UID-isolated IDs, revision/expiry/capacity checks, and duplicate-tap protection; content remains in memory.
- [SurfaceService](src/org/agentos/surfaces/SurfaceService.java): independent bound service managing caller connections and the system display lifecycle.
- [SurfaceRenderer](src/org/agentos/surfaces/SurfaceRenderer.java): display boundary. The first [SurfaceWindow](src/org/agentos/surfaces/SurfaceWindow.java) uses native windows and controls. Replacing presentation does not require business execution logic.
- `SurfaceDragHandle` / `SurfacePosition` / `SurfacePositionPrefs`: dragging, safe-area calculation, and user position preferences, independent of the message protocol and content lifecycle.
- [SurfaceClient](client/org/agentos/surfaces/client/SurfaceClient.java): independent Android client available through the `taku-surfaces-client` Soong library.
- `demo/`: synthetic producer with a separate package and UID, excluded from system modules and ROM.

See the [calling API](API.md) for integration, `showHint / showChoice / showResult`, typed callbacks, and raw messages. Builds export `out/taku-surfaces-client.jar` for other Android projects.

Actual interface verification is recorded in [SDK 0.2 acceptance](../../docs/reviews/system-surfaces-api-2026-10-21/README.md).

## Calling protocol

Minimum Android API 36. Callers declare `org.agentos.permission.PUBLISH_SURFACES` and must share the platform signing certificate. The system package needs overlay authorization. This component is not a SystemUI process patch, does not cover the lock screen, and does not replace the status bar.

Explicitly bind `org.agentos.surfaces/org.agentos.surfaces.SurfaceService` with Android Messenger. All requests include `replyTo`. SDK methods and callbacks run on the main thread. Send requests after `onConnected`; disconnected calls fail immediately without hidden queuing.

| `Message.what` | `Bundle.payload` | Meaning |
| --- | --- | --- |
| 1 | v1 / v2 JSON content | Publish or replace your own item |
| 2 | Surface ID | Remove your own item; repeated removal has no side effects |
| 3 | Empty string | Clear this connection's items; SDK `close()` sends this automatically |

Callbacks use `what=100`, with `type/id/revision/value` in the Bundle. Events are `published`, `action`, `dismissed`, `withdrawn`, `removed`, `expired`, and `error`; the SDK also provides `disconnected`. `published` means the request was accepted. Locked or screen-off devices defer display, so it does not mean the user has seen it. `action.value` is the original action ID; result actions also include an artifact reference. The SDK exposes typed events through `onAction(ActionEvent)`. Callers decide subsequent actions and update with a higher revision or explicitly remove content.

```json
{
  "version": 1,
  "id": "example",
  "revision": 1,
  "kind": "choice",
  "placement": "top",
  "title": "Choose a presentation",
  "summary": "This surface only provides choices and callbacks.",
  "blocks": [{"type": "field", "label": "Content source", "text": "Provided by the caller"}],
  "actions": [{"id": "option-a", "label": "Choose A", "primary": true}],
  "ttlMs": 60000
}
```

Beyond JSON Schema, the host requires unique action IDs, at most one primary action, and a payload no larger than 24000 UTF-16 units. Text lengths also use UTF-16. Unknown fields, types, and versions are rejected. HTML, JS, arbitrary URLs, and action scripts cannot serve as native rendering instructions. Supported content is plain text, key/value fields, short facts, highlighted notices, and buttons; there is no general script execution or WebView.

## Display and lifecycle

Supported presentation includes top/bottom placement, compact capsules/expanded cards, Chinese text, and bounded scrolling. Both light and dark system backgrounds use black material with Taku orange accents. Up to 8 items can be switched between. Updates to the same ID must increase `revision`. One selection disables all actions for that revision; stale taps cannot trigger a newer revision. User dismissal only withdraws the surface and does not imply cancellation of the caller's task. Lifetime is 1 second to 30 minutes; resending an old revision does not extend it.

v2 can specify initial `compact/expanded` state and a capsule-tap action. Typed hints default to direct tap callbacks, choices to expanded presentation, and results to details callbacks with artifact references. The right arrow always only expands. Original v1 retains compact defaults and tap-to-expand behavior.

The system window does not take input focus. Touches outside go to the original app, and layout avoids the status bar, cutouts, and keyboard. Screen-off/lock hides content; unlocking restores only valid items. Caller death, explicit connection closure, or host destruction clears items. Stale decisions are never persisted or replayed after reboot. Without a binding the component does not stay resident, collect screens, or poll networks.

Drag capsules directly or use the expanded card's top brand/control bar. Body scrolling and button taps retain their behavior. Dragging starts only after the system touch-slop threshold and produces no business callbacks. Multi-touch interruption or cancellation does not save position. Release saves a user preference shared across items, expanded/compact states, and service restarts. Long-press the capsule or card header and choose “Restore default position” to resume the caller's top/bottom placement.

Position storage contains only two normalized center coordinates calculated against the keyboard-free safe area. It stores no content, caller IDs, actions, or artifact references. Expansion, rotation, and keyboard changes clamp the actual window to available space without rewriting the preference; position restores when the keyboard closes. After an explicit drag, user position takes precedence over caller `placement`. No API, protocol, or SDK fields were added.

See [dragging and position persistence acceptance](../../docs/reviews/system-surfaces-position-2026-10-22/README.md) for real gestures, service restarts, rotation, and keyboard recovery.

## Build and verification

```sh
bash system/surfaces/test.sh
bash system/surfaces/test-wire-host.sh
bash system/surfaces/build.sh --demo
```

Builds reuse repository Android SDK/JDK discovery and the fixed AOSP development test signature. APKs and test outputs remain in ignored `out/`. Scripts neither install on devices nor change permissions. `TakuSurfaces` is included in this branch's subsequent development ROM product list; other branches and running immutable build snapshots are unchanged.

On a dedicated development emulator, explicitly install both APKs, grant the host overlay permission, and open the independent preview. Device commands must specify the actual serial. After testing, remove preview data or pause the emulator to restore the prior state. Component builds and host lifecycle tests do not establish system-window, hardware, ROM, or release acceptance. Actual UI verification is in [initial acceptance](../../docs/reviews/system-surfaces-2026-10-21/README.md) and [style alignment acceptance](../../docs/reviews/system-surfaces-alignment-2026-10-21/README.md).

On a disposable emulator, `tests/verify-device.py --serial emulator-… --out <ignored-directory> --position` additionally changes position preferences and rotation. It checks dragging, callbacks, service restarts, expansion/collapse, and keyboard recovery, restoring the default position on success. This option rejects physical devices. Ordinary interface regression requires manually restoring default position first and does not silently clear user preferences.

## Visual references and brand

The capsule's compact hierarchy references Eugene Yarovoy / mindset's [Ticker](https://dribbble.com/shots/27545186-ticker-an-ambient-ai-companion-in-the-dynamic-island). Expanded-card black material, spacing, secondary gray text, and compact actions reference Masum Parvej / Halal Lab's [Dynamic Island Component](https://dribbble.com/shots/26808109-Dynamic-Island-Component-Delivery-App). Layout is adapted to phone dimensions without copying delivery business content, fixed progress steps, external brands, or images.

Layout and style reside in `SurfaceWindow` / `SurfaceStyle`, outside protocol and lifecycle modules. Cards use 20dp content padding with shared text/field alignment. Status markers are 24dp high. Expanded actions have at least 48dp touch targets, icons at least 44dp. Expand, collapse, and close use vector icons with matching viewports, strokes, and dimensions to avoid font-character baseline shifts. Body line spacing grows with system font size; long field text aligns at its first line.

`res/drawable/taku_brand_mark.xml` uses the project's existing Taku brand vector, from the same source as the assistant resource. It is bundled in the independent APK without assistant-process or resource-loading dependencies. Standard `npm run build:android` includes this component; `npm run build:surfaces` builds it separately. Independent previews still require explicit `--demo`.

## 0.2.1 English and Chinese host text

Host APK v3 / 0.2.1 provides English default resources and Chinese resources. Generic badges, expand/collapse, close, position reset, and accessibility labels follow the host language. Producer titles, bodies, and action text are preserved. The v2 wire protocol and client contract remain unchanged. See [daily-app acceptance](../../docs/reviews/taku-memory-daily-apps-2026-10-22/README.md) for actual English memory hints.

## 0.3.0 Structured confirmation cards

Host APK v4 / 0.3.0 uses `fact` for short fields such as date, time, and party size, and `notice` for conditions and notes to review. Both retain `label/text`. Consecutive facts form rows based on measured text width and wrap on narrow screens or at large font sizes. The primary confirmation button occupies a full row. Two secondary buttons sit side by side when space permits and wrap otherwise, without truncating confirmation text. Native Taku branding, dark material, collapse, close, dragging, revisions, and single-action behavior remain.

`SurfaceLayoutTest` checks measured widths at normal/narrow/large-font configurations, oversized content, and keyboard-safe height. `SurfaceCodecTest` checks wire round trips for all four block types, v1 compatibility, and rejection of unknown types. `build.sh --demo --tests` builds the independent host, synthetic preview, and Android codec tests without device operations.

The synthetic preview adds `scenario=confirmation`, supports the existing `bottom` parameter, and follows Chinese/English system language. It is explicitly a layout fixture. Buttons only callback to the test producer; they neither create reservations nor contact stores. This example and host checks do not constitute real booking, hardware, or full-ROM acceptance.

Long `field` values stack below labels and align left according to measured width; long `fact` values use normal body size at full card width. Body and action footer scroll independently, prioritizing space for complete buttons. `confirmation-fields` is a synthetic fixture for long addresses, dates, and fee conditions. Real Pixel 9 APK checks at English font scales 1.0 and 1.3 are in [this acceptance record](../../docs/reviews/manual-screen-case-2026-10-26/confirmation-draft.md). These layout checks do not establish completed ROM or real draft-flow acceptance; the record distinguishes the actual reservation, unfinished WeChat draft, and full-ROM status.
