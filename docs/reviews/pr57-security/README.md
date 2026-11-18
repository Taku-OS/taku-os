# PR #57 security review follow-up

Reviewed check: [security-review 132303686152](https://github.com/Taku-OS/taku-os/pull/57/checks?check_run_id=132303686152),
head `c894dcc28f73864656a1c65c4eacf900d11c1482`, policy `2026-11-11.2`.
The check failed. Its output states that medium/low findings do not block merging;
the duplicated high finding about the audio settings facade is the blocking category.

## Assessment and changes

Numbers below follow the ten findings in the check output, not the severity order.

| Findings | Assessment | Follow-up |
| --- | --- | --- |
| 1, 4: exported text overlay | Local callers really can open the UI. That operation is intentionally public; implicitly cancelling a recording on a repeated text open is avoidable. | Shared session text-open becomes a no-op during pending hold/capture/finalization. Explicit voice-to-text/cancel still works. Invalid/replayed overlay intents no longer finish an active overlay. This does not restrict explicit cancellation or UI launching by local callers. |
| 2: widget content/action | Accurate capability description; reading visible widget content and invoking its controls is required by the Agent contract. | Documented manifest and threat boundary. No signature allowlist or new per-click confirmation that would remove Agent parity. Real PendingIntent effects remain possible. |
| 3: Feed event values/history | Accurate: events include user form values and are readable to device-local callers. | Documented explicitly. No redaction that would silently give the Agent a different source of truth from the GUI/Memory pipeline. |
| 5, 7: audio credentials and policy (duplicate high finding) | The settings facade can replace/remove credentials and route subsequent cloud dictation. Engine-to-broker authentication does not authenticate the original app. This is a real risk under Android's ordinary untrusted-app model, and a deliberate capability under the current repository contract. | Keep the shared GUI/Agent operation and validation. Document that clearCallingIdentity is not caller authorization. No claim that this risk is eliminated; security-policy reconciliation remains necessary if the gate requires caller isolation. |
| 6: global cleartext traffic and remote SVG | Valid defense-in-depth finding, independent of device-local Agent access. | Release manifest denies cleartext; generated/state-card images require HTTPS. Remote SVG checks MIME, HTTPS final URL, deadline, size and a static drawing subset; scripts, CSS, entities and external resources are rejected. Packaged design SVG and the local browser gallery retain their asset path. HTTP images (including LAN) now fail rather than being silently rewritten. |
| 8: Clock metadata/media controls | The exposure is real and intentional. Platform signing grants permissions but does not give Clock the platform UID; the report conflates these. | Documented metadata and transport exposure. No hidden caller restriction. |
| 9: Weather location/requests | Location mutation is intentional. Unbounded repeated network attempts are unnecessary. | Shared per-process 30-second forecast interval with one coalesced pending refresh, 2-second city-search interval. GUI and Agent expose/use the same retry state. This is ordinary request throttling, not protection against malicious process restarts. |
| 10: voice gesture activation/transcripts | These public methods provide recording and transcript access, as the existing voice-start interface does. Gesture IDs are ordering state, not authorization secrets. | Document the actual boundary; retain Android microphone policy and existing gesture lifecycle validation. No assertion that the separate Assistant launcher signature check secures the provider. |

The engineering contract is [AGENTS.md](../../../AGENTS.md); the explicit boundary
and remaining risks are in [Agent open access](../../agent-open-access.md) and in
the preinstalled `taku-open-access` skill. These descriptions are not a security
approval or an instruction to suppress findings. No security workflow, required
check, reviewer policy, or reported check status was modified.

## Maintainer decision

On 2026-11-14 the maintainer explicitly chose to retain the current open-access
contract and ask the security reviewers to confirm risk acceptance. In particular,
the requested follow-up is not to add a signature allowlist to credential/backend
writes. The concrete local-app risks above remain. This records the product
decision, not approval from the security reviewer or a successful required check.

## Validation

- App source: 372 tests passed; ESLint and TypeScript checks passed.
- Native Home unit tests: 117 passed; release Agent and desktop-widget APK builds passed.
- Weather request-budget test passed (boundary times and 20 concurrent callers).
- Root `npm run verify` passed (including its pre-existing environment-dependent skips).
- After pushing `bcbcea6`, verified on the existing assigned `emulator-5586`: installed
  release network policy rejects public/LAN cleartext; repeated text/invalid gesture
  intents preserve capture and finalization through the provider and overlay; earlier
  widget placement and drag regressions still pass. The voice adapter was a test double;
  this does not claim microphone/recognition acceptance.
- Weather: Agent location writes appeared in the actual GUI; original Guangzhou
  location was restored and re-read. GUI refresh and provider refresh shared one
  pending operation; concurrent city searches returned one success and one RATE_LIMITED.
- [Structured acceptance results](acceptance.json). No new emulator, user-data wipe,
  credential replacement, real recording or model request was used.
- All eight source-check jobs passed on `bcbcea6`; the separate security review was
  still in progress when this evidence was recorded. This is not a claim that the
  high-risk open-access finding was accepted.
