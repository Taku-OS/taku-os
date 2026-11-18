# Panel integration on current main — 2026-11-17

Baseline: `3b042c211dc9208cfe3fdb3d3235bf2895a43c15` (main).
Branch: `vela/panel-card`.

The five Panel changes are integrated with the current launcher, chat and native
assistant. PanelRegistry is the authoritative directory for chat pinning, Taku
Home and desktop pages. The old scene panels, sample desktop pages and Keep sheet
are removed. MatterRuntime and generic tasks remain in use.

Conflict resolutions preserve main's dark blurred chat/Home, SystemUI-only voice
cards, voice background cleanup and chat backdrop lifecycle. Panel subscriptions
use independent reader leases. The legacy CodexHomeActivity redirects to the
current HomeActivity; it does not draw another panel. Chat's More tools buttons
open Taku Home, including its real Panels directory.

## Validation performed for this integration

- Agent typecheck, lint and all 385 JavaScript tests passed.
- Repository `npm run verify` passed.
- Android unit tests: 148 Taku Home and 27 Taku Agent tests passed.
- MatterRuntime host suite: 408 concurrency, retention and receipt assertions.
- Gateway protocol/submission and native routing host suites passed.
- Both AgentOSAssistant.apk and the platform-signed TakuAgent.apk built locally.
  The Agent APK binding is refreshed after committing its merged source inputs.
- A new isolated hardware-GPU AVD, `Taku_Panel_Migration_20261117`, on
  `emulator-5684` used the local Lineage system images with independent userdata.
  The user's running emulator was not modified.
- The independently signed, zero-permission probe (UID 10203) read
  `panels/describe` and `panels/list`. Invalid `panels/pin` arguments returned
  `INVALID_ARGUMENT`. Shell reads and the dark Taku Home GUI both showed an empty
  directory; `panels/navigation` likewise reported no panel pages. The new
  launcher and chat/Home screens rendered successfully.

Local device evidence is under `out/panel-migration-20261117/` (not committed).
This is a scoped integration smoke test, not a repetition of the earlier branch's
full acceptance matrix. This fresh AVD has no authenticated live Agent source;
real AI-generated pin/continue/update, filled-card GUI mutations, microphone
flows, Pixel hardware and a full ROM build were not re-accepted here. The host
tests exercise directory mutations and concurrency separately.

## Artifact availability

The imported Codex runtime lock describes the panel-retirement build with archive
SHA-256 `a6aecd99aa0fd7aa0d40cfe24621d23463e5e8c2463238d12d96ec6c524bcecc`.
At migration time that archive was unpublished and had no download URL. The
local older archive did not match it and was not substituted or installed. The
Agent APK was also a local candidate. These findings prompted the subsequent
native cross-build and publication work; see the [review fixes and verification
record](panel-review-fixes-2026-11-17.md) for the replacement artifacts and current
acceptance scope.
