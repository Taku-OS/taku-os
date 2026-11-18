# Proactivity 0.1: independent integration and native UI

Delivered an independent app, public protocol, optional SDK, and separately signed page observer. Host owns authorization, policy versions, bounded context, and system hints; developers own event sources/plugin apps.

## Design basis

Continues native [MemoryUi](../../../system/memory/src/org/agentos/memory/MemoryUi.java), [Taku Memory branding](../taku-memory-brand-2026-10-22/README.md), and [Surfaces](../../../system/surfaces/README.md). Warm white `#F7F8F5`, ink headings `#242825`, orange actions `#FF5A1F`, 20dp cards, 22dp page margins; fonts/thin icons and Taku brand resource follow existing conventions.

Pages follow user tasks:

| Page | Primary content/actions |
| --- | --- |
| Overview | Dark-green activity heatmap, orange-bar/green-line hint trends, status ring, active-source bars; linked time selection and direct pending-review entry |
| Observers | Name, provider, status, trigger cadence; maintenance in details |
| Details | Explain source/trigger/hint preview before authorization; controls use seconds/counts while developer APIs use milliseconds |
| Versions/maintenance | Current/historical policy, distinguishing policy rollback from APK update; confirmation for removal |
| Activity | Plain-language registration, user maintenance, display, and acceptance results traceable to observer |
| Integration details | Collapsible package name, event ID, protocol, and signature for developers |

Pending authorization, pending update, enabled, and disabled states are distinct. New versions/declarations stop triggering until approved; user edits create new policy versions and reconnect does not overwrite them. Refresh retains scroll position; dark system-bar icons fit light backgrounds; primary actions are at least 48dp and long content scrolls vertically. Full-screen policy editor scrolls independently, Save stays above keyboard, and unsaved input survives rotation. Landscape keyboard hides editor top bar to preserve field visibility; closing it restores Cancel. Chinese accessibility labels use actual action names. No invented usage, hits, or demo statistics.

## Overview data/chart contract

Overview uses current persisted structure; all charts render natively on-device without demo data, extra screen capture, or telemetry storage. Dark-green main chart provides visual emphasis, with warm-white/orange Taku branding. Large numbers, Chinese headings, native time selectors, and exact counts explain graphs.

| Display | Existing fields/calculation | Limits/interaction |
| --- | --- | --- |
| Activity heatmap | `registry.items[*].trace[].at/type`; 4 rows × 24 hours for policy maintenance, hint display, user viewing, other outcomes | Local timezone, including current partial hour; actual bounds shown. Colors represent 0, 1, 2–3, 4–7, 8+ records; column tap/previous-next hour shows exact counts |
| Hint trend | Same trace `published`/`accepted`, aggregated every two hours; orange bars/green line represent retained display/view receipts | Linked heatmap selection; empty stays empty without interpolated history; counts separate, no conversion rate |
| Observer status ring | Current `pendingRevision`, `enabled`, valid `activeRevision`; pending takes precedence, otherwise enabled/disabled | Mutually exclusive states sum to current observers; enabled configuration does not mean plugin online; management opens observer page |
| Active observer bars | Retained activity count per observer in that window, descending count then stable key, at most 3 | Tap opens details; bar length relative to current maximum, not percentage |

Calendar buckets use only wall-clock `trace.at`; context `observedAt` is monotonic within this boot and is not used for calendar bins. Each observer retains at most 16 traces; removing it deletes history. Zero means no retained records, not proof of no event. Window starts 23 hour boundaries before current local hour; timezone/hour changes rebucket. `candidateId + type` deduplicates per observer; identical IDs across observers count separately. Plugin page-change metadata is bounded context, **not trace activity statistics**; no total observations, model hit rate, or time-saved metric.

Heatmap supports left/right keys and accessibility forward/back scroll; native 48dp hour buttons provide equivalent access. Selected time survives rebuilding. Units/scope are shown in UI.

Independent plugin follows the same visual rules without importing host UI classes. Page shows connection state, trial input, and update; resend/status inspection are in developer debugging. System capsule retains existing Surfaces styling.

## Verification boundaries

Dedicated blank ARM64 / Android 16 API 36 AVD `Taku_Proactivity_20261024`. Screenshots/events come from synthetic pages without accounts/personal messages. Builds, SDK tests, actual Binder/upgrade flow, and final chart UI are recorded separately with distinct APK hashes; older-UI full acceptance is not counted as new-APK regression.

This proves hints after stable events on plugin-owned pages and independent community registration/maintenance. Global observation, original-image Jev, image suggestions, Agent execution, hardware, and complete ROM are outside scope.

[Interfaces/build](../../../system/proactivity/README.md) · [Public protocol](../../../system/proactivity/API.md) · [Group-source context/design](../../proactivity-trigger-design.md)

## Results and screenshots

| Verification | Result/evidence |
| --- | --- |
| All Android development components | Passed; [build log](build.txt) |
| Repository checks | `npm run verify` passed; existing Linux FIFO cases skipped on macOS; [full log](verify.txt) |
| Scheduling/chart aggregation | 20 scheduling and 12 aggregation assertions: deduplication, windows, future records, source isolation, empty data, half-hour timezones, DST |
| Independent community build | Built outside host source using only Android SDK/public JAR, separately development-signed; [report](community-build.json) |
| Registration/authorization/hint/upgrade/rollback | Pre-chart host APK passed 105 real Binder, native hint-tap, independent APK-upgrade, and policy/debug-APK rollback checks; [report](acceptance.json) |
| Final chart UI | Final APK passed 51 checks: actual display/view receipts into charts, linked time selection, zero data after real removal, 1.3 font scale, landscape, unsaved-policy recovery, 48dp visible input area with landscape keyboard; [report](dashboard-acceptance.json) |

Protocol/upgrade and final-UI reports use **two different APKs** with separate hashes. UI changes did not change host protocol, authorization, or scheduling. [Build evidence](../../../evidence/builds/2026-10-24-proactivity.json) binds sources/artifacts/screenshots. [Final UI verifier snapshot](dashboard-verifier.py) records actual operations on the specified AVD, run from repository root; it resets its two explicitly specified synthetic test packages and is not for personal devices.

| UI | Actual images |
| --- | --- |
| Overview | [Heatmap](overview.png) · [Hint trends](trend.png) · [Status/source ranking](status-ranking.png) · [Historical-hour selection](selected-hour.png) |
| Observer maintenance | [List](observers.png) · [Details](detail.png) · [Initial authorization](pending.png) · [Update authorization](update-review.png) |
| Policy maintenance | [Editor](policy-editor.png) · [Save above keyboard](policy-keyboard.png) · [Landscape draft](editor-landscape-draft.png) |
| Hints/rollback | [Actual system hint](plugin-hint.png) · [Policy rollback](policy-rollback.png) · [Debug APK rollback](apk-rollback.png) · [Activity](activity.png) |
| Adaptation/empty data | [Large font](large-font.png) · [Large-font details](detail-large-font.png) · [Landscape](landscape.png) · [Zero data](empty.png) |

Chart data comes from actual example-page events, display ACKs, and tap receipts, without registry injection, backfilled history, or invented events. The 89-event catalog, individual sources, and original group-message S01–S06 links are retained.

## Source scope and integration boundaries

Baseline `codex/power-screen-assist-20261023`, parent `81524acaba06356964cbad5017e18c99c59194da`. Owned scope: `system/proactivity/`, `examples/proactivity-observer/`, `spec/proactivity-v1.schema.json`, two design docs, and this acceptance directory. Necessary shared changes only in `scripts/build-android.sh`, `scripts/check.mjs`, `package.json`, `docs/README.md`, `docs/getting-started.md`, `docs/project-structure.md`. Sixteen pre-existing modified files, including root `Android.bp` and Assistant dev68, retain original bytes and must not be bundled as proactivity integration. Necessary shared changes are in a separate [integration patch](shared-integration.patch) for three-way review.

Public protocol v1 / SDK 0.1 exposes `capabilities/register/event/inspect/disconnect`. Receiving `event` means queued; `published` is system-display ACK; asynchronous `accepted` means user viewed a candidate, not business completion. `candidateId`, `policyRevision`, `producerCodeVersion`, and plugin-local `pageRevision` retain separate semantics.

Memory may register as another event provider through the same public authorization path; this does not expand signed Memory-read permissions. Original image, Jev, and execution bridging await separately versioned contracts/composed acceptance. Retain `imageDecision=false / agentExecution=false`; no new TaskBroker, long-term memory database, or task graph.

Local `emulator-5590 / Taku_Proactivity_20261024` was dedicated to this round and closed afterward. Other conversations emulators, physical phones, main, and ROM were unchanged.
