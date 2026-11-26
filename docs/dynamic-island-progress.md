# Issue #28 implementation and acceptance ledger

Owner instruction (2026-11-15): proceed through all code phases locally; defer the integrated ROM
build to the colleague's cloud builder and acceptance to a physical device. Track these separately.
No unchecked runtime gate is implicitly waived by source completion.

| Issue phase | Code | Local verification | Cloud/physical acceptance |
|---|---|---|---|
| 0 — platform feasibility | Pinned SystemUI probe and bounded draft parser baseline | Patch contract checks; 50 parser checks on API 37 emulator | Real SystemUI compile passed (2026-11-15); pending: window/input/stacking, surface death, frames |
| 1 — protocol/repository/Agent API | v1 validator, async notification extractor, revision/lifetime store, policy, content/cmd interfaces | 50 Android parser checks; 19 host lifecycle/order checks; owned-source Kotlin/Java typecheck with Android 17 + API doubles | Real Dagger/KSP/Soong compilation passed; pending: runtime initialization, GUI/Agent parity, notification permission/fallback and user switching on ROM |
| 2 — native shell and interaction | Native cutout/slots/metaball, selection/gestures, non-modal card, asynchronous SurfacePackage connection | Android 17 owned-source typecheck (SystemUI API doubles); source contract tests | Pending: touch/clock/IME/back/rotation/accessibility/frame timing and real surface-host death |
| 3 — AI publisher/content-host migration | SystemUI-bound publisher, notification actions, separate SCVH process, shared provider snapshot, launcher island/catcher/statusbar hiding removed | Native module release compilation; 129 unit tests; agent lint/typecheck; Android 17 owned SystemUI source typecheck | Pending: actual cross-app surfaces, process recovery, GUI/Agent parity and #25 F1–F5 |
| 4 — system-event sources and arbitration | Telecom-role/CallStyle and alarm/timer extraction; direct recording/cast controllers; native actions; priority and selected-chip/HUN ownership | 22 store checks + 15 system classification checks; pinned patch checks; Android 17 owned-source typecheck | Pending: real dialer/clock/controller events, runtime chip flow integration, HUN/FSI precedence and AI-off operation |
| 5 — third-party integration/settings/docs | SDK-free Live Updates baseline, Settings total switch/allow-list, preinstalled Agent and publisher docs, cloud integration/artifact gates | Source/patch/artifact checks; owned-source typecheck; full repository verify recorded below | Matching Agent ARM64 release built and certificate matched; pending: release publication/lock refresh, cloud ROM build, Settings dashboard/GUI parity and third-party acceptance |

Phase-0 parser result: `org.agentos.island.tests` on `emulator-5586`, Android 17/API 37, 50 checks
passed. This test runs the actual parser with Android `JsonReader`/`JSONObject`. It neither replaces
SystemUI nor proves any window behavior. Source pin:
`LineageOS/android_frameworks_base@7f6de40b6429462d47ba9ba351b99943269c8067`.

See [component commands and acceptance checklist](../system/island/README.md).

Phase-2 focus policy: automatic/below-bar cards are non-focusable. User-requested covering cards
use window focus for Back with ALT_FOCUSABLE_IM; IME appearance downgrades to below-bar mode.
This refines the original blanket no-focus wording and must be checked on the final image,
especially Back after an outside tap.

Source phases have been implemented. Real SystemUI Soong/Dagger compilation passed on the cloud builder; the later same-source emulator checkpoint below supersedes the initial full-image/runtime status. Final binary publication and physical acceptance remain open; follow [the handoff](dynamic-island-handoff.md).

Initial local results (2026-11-15): full `npm run verify` passed; final native module release compilation and 129 unit tests passed; agent lint/typecheck passed; final owned SystemUI Android 17 typecheck passed with API doubles. No final ROM or physical acceptance performed.

Cloud follow-up (2026-11-15): actual pinned platform SystemUI module built successfully, including
KSP/Dagger processing, R8, signing and dexpreopt. APK manifest/DEX and signature verified after
download. See [build receipt and scope](reviews/island-cloud-2026-11-15/README.md). This does not
complete phase-0 runtime acceptance or establish that a full image boots.

Companion release follow-up: full ARM64 Agent APK built locally from the same immutable commit;
manifest/DEX entries and ordinary UID verified, signature matches cloud SystemUI. Lint/typecheck
passed. Release publication/source-lock refresh and full-image artifact verification remain open.

## Same-source emulator follow-up (2026-11-15)

A complete Android 17 / Lineage 24 ARM64 emulator image built and booted on a **new** local AVD,
using all 1,239 pinned Pixel platform revisions with Goldfish hardware. The original SDK emulator
was untouched. Image/Agent source was frozen at `116147f`; later concurrent desktop commits are
not included. See [build/device evidence](reviews/island-emulator-2026-11-15/README.md).

Runtime checks now cover initialization/default roles, notification publication boundaries,
Settings GUI/command parity, cross-app capsule/card, outside touch, actual separate-process SCVH,
content-process SIGKILL fallback/rebind, IME-before-expansion input, Back, shade, rotation and a
real notification action. These are partial phase 0/1/2/5 checks; they do not accept the Agent AI
body or all system-event sources.

**Failed:** expanding a covering card before focusing the underlying editor retains island window
focus and blocks text delivery. The earlier covering-card focus exception above does not satisfy
the issue's no-focus requirement. Remove that exception, integrate platform Back routing, and
repeat both IME orderings/outside-input checks. Issue #28 remains open. Physical Pixel acceptance,
production publication, AI/backend acceptance, event arbitration, accessibility and performance
remain pending.

## Dynamic Island v2 (issue #30, branch `vela/dynamic-island`, 2026-11-23)

[Spec](dynamic-island-v2-spec.md) · [developer guide](dynamic-island-developer.md) ·
[schema](../spec/taku-island-v2.schema.json)

| Stage | Code | Local verification on this host | Cloud/device acceptance |
|---|---|---|---|
| 0: provider authorization | `IslandAccessPolicy` + caller checks in `IslandProvider` | 105 host policy checks | Pending: matrix of shell, platform, granted and ungranted third-party callers on the patched image |
| 1: protocol v2 | v2 validator, v1 adapter, canonical payload, Live Updates mapping (large icon, ProgressStyle, `setTimeoutAfter`), recency order, 1 h/12 h lifetime | 86 payload/adapter checks (host shim) + schema examples; 38 store checks; device parser test extended and compiled | Pending: `IslandParserTests` on Android JSON; real Live Update extraction |
| 2: presentation | Mutual exclusion, tap/long-press/swipe gestures, `next`/`tap`/`hide`, chooser removed, foreground suppression, secondary disc, A ring and animated icons, B glyphs, heads-up filter | Owned-source compile against the API 36 SDK with SystemUI API doubles: no errors beyond the hidden-API baseline. Capsule render test set compiles | Pending: gestures, `TaskStackChangeListeners`, `VisualInterruptionDecisionProvider` Dagger binding, AVD playback, all on device |
| 3: templates | `highlight`, `accentColor`, `tapAction`, scrolling chips | Same compile check; on-device template render cases added (40 actions) | Pending: render tests on device |
| 4: Taku Agent | v2 alongside v1 from the publisher and onboarding | Exact shapes validated on the host; changed lines compile in isolation | Pending: Agent build and probes |
| 5: docs and fixture | Developer guide, skill, fixture v2/Live Update cases | Fixture compiles against the API 36 SDK | Pending: fixture run |
| 6: first partner | `taku_island_allowed_packages` seeded once with `org.agentos.notebook` when never written | Compile check | Pending: first-boot seeding, GUI list and `settings get global` agreement on device; Notebook's own use cases (Notebook team) |
| 7: acceptance fixes (issue #30 report, 2026-11-23) | Taku Agent cards always cover; IME no longer moves cards; 24 dp backdrop blur + opaque fallback for every card; `backgroundColor` template style with palette; capsule necks from the real cutout radius/centre, short-bar scaling, 32 dp waiting circle on the left; animated icons inflated in the publisher's package; cards fold on open/app foreground; 30 s alert cooldown; `not_ongoing` | 40 store, 91 payload and geometry checks (neck matches Figma MetaballShape1); owned-source compile unchanged at the hidden-API baseline; capsule render cases added for a 46 dp cutout, a 24 dp bar and two activities | Pending: emulator re-run of report items 1, 2, 6, 7, 11 and the capsule/card screenshots against Figma 1:4742 and 297:374 |
| 8: one protocol, platform default, ellipsis | v1 parser/adapter/schema removed; Agent publishes only `taku.island.v2`; `source: custom`; platform-signed publishers authorized automatically (`POST_ISLAND` removed, allow-list empty by default, `platformSigned` in `canShowIsland`); group B ellipsizes instead of disappearing | 72 payload checks (v1 rejected as unknown fields); device parser tests rewritten for v2; render cases for ellipsis; owned-source compile at the hidden-API baseline | Pending: platform-signed fixture accepted without allow-list; a non-platform app rejected until allow-listed; long group B text ends in "…" on device |

This host has no Android SDK build tools, emulator, Gradle cache or SystemUI build, so no stage has
run on a device or in a real Soong/Dagger build. The baseline for the compile check is the set of 46
hidden-framework-API errors present on `main`; later stages only add further uses of the same
hidden APIs (`createContextAsUser`, `UserHandle.of`, `isPromotedOngoing`).

## Summary geometry follow-up (2026-11-24)

The summary regression came from treating the padded display-cutout exclusion rectangle as the
visible camera circle. The renderer now reads the visible cutout path, retaining fractional pixel
coordinates; a device without a usable punch-hole path uses Figma's 24 dp circle. Group A and B
remain 32 dp high, including one-character Live Updates. The waiting disc keeps its own animation
and shares native-chip ownership with the primary summary, preventing duplicate Live Update chips.
Card expansion/collapse also updates the readable summary state synchronously.

Validation on branch `vela/dynamic-island`, base `5ca4c930` plus the uncommitted fixes:

- `npm run check:island` and owned-source Android 17 typecheck passed.
- 109 component checks passed on `emulator-5596`, including exact outline restoration, attached
  view animation lifecycle, cutout-path parsing and primary/secondary chip ownership.
- The real pinned Android 17 / Lineage 24 SystemUI platform build passed. Its signed APK was
  installed on the isolated `Taku_PR27_Fresh` AVD, preserving userdata; installed SHA-256 matches
  `c8a3f8d5414c48a43bd056a436f338ea1e998ab8697f675e9d50714bd8840e9b`.
- 51 actual-SystemUI checks passed: application return, GUI expansion and upward-swipe collapse,
  command collapse, waiting-disc selection, animated waiting icons, ordinary Android Live Updates,
  two Live Updates without duplicate native ETA chips, mixed sources, short text and three-activity
  cycling. GUI screenshots and `cmd statusbar island state` agree on geometry and visibility.
- This AVD's visible camera is 72.5 px (27.62 dp), while its exclusion is 122 px (46.48 dp).
  The physical hole is preserved; it is no longer enlarged to the exclusion rectangle. The primary
  camera anchor stays fixed when the waiting disc appears. No crash-buffer entries were recorded.

Local receipts, screenshots and cleanup records are under `out/island-summary-20261124/`.
Fixture apps/notifications were removed and the original allow-list restored. This checkpoint
accepts the listed summary scenarios on this AVD; it is not a new full-ROM or physical-device
acceptance and does not supersede unrelated outstanding items above.

## Duplicate alert-card follow-up (2026-11-24)

Fixed [issue #30's same-window replay](https://github.com/Taku-OS/taku-os/issues/30#issuecomment-7105043464).
`IslandPresenter` now consumes the accepted alert-window deadline instead of the notification's
content revision. Quiet updates, held `true` and cooldown-rejected flips therefore cannot reopen
an already-collapsed card within that same window. Content refresh and the 30-second cooldown are
unchanged; a newly accepted alert can still expand.

The original sequence was reproduced on `emulator-5596`: initial alert at 1292 ms, collapse at
1392 ms, then `alert=false` at 2778 ms incorrectly reopened it. After installing the fix, the
same sequence was initial alert at 1379 ms, collapse at 1488 ms, then `alert=false` at 2982 ms
remaining collapsed. All 17 device checks passed, including GUI swipe collapse, held alerts,
cooldown flips, cancel/repost, independent notifications, foreground suppression, automatic
timeout, manual-card updates and a new alert after the real cooldown elapsed.

`npm run check:island` passed (44 store checks), Android 17 owned-source typecheck passed, and
the real SystemUI platform build passed. The installed APK matches SHA-256
`5b6f77e2b27e427be31967faf19e57c620539eb7d14c40e06ec2fbc6879c53b2`.
Source includes the preceding summary fixes plus the uncommitted alert fix on `5ca4c930`.
Evidence is in `out/island-alert-20261124/`; the temporary publisher and notifications were removed,
the original allow-list restored, and userdata retained. This accepts the duplicate-alert item
on the isolated AVD, not the other outstanding items in that report.
