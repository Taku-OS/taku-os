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

This host has no Android SDK build tools, emulator, Gradle cache or SystemUI build, so no stage has
run on a device or in a real Soong/Dagger build. The baseline for the compile check is the set of 46
hidden-framework-API errors present on `main`; later stages only add further uses of the same
hidden APIs (`createContextAsUser`, `UserHandle.of`, `isPromotedOngoing`).
