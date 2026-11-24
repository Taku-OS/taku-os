---
name: tesla-model-y
description: "Use for the user's bound Tesla Model Y: horn, lights, target temperature, lock/unlock, previous/next media track, or sending a navigation destination. This development package retains historical results; target-system vehicle replay is still pending. Navigation takes the supplied place text without full-address or parking questions."
---

Use the phone's existing command tool as UID 0. This is a version-pinned local adapter to the official Tesla App, not an Internet vehicle-control service. It does not need a Mac during normal use. Never log in, pair a phone key, install/downgrade the App, or change the catalog/binding to make a request pass.

## Readiness and allowed actions

The catalog is the authority for actions, parameters, App version and validation scope. Run:

```sh
sh /data/local/agentos/home/.codex/skills/tesla-model-y/scripts/control.sh list
```

Only these eight actions are packaged: `flash_lights`, `honk_horn`, `set_temperature`, `lock`, `unlock`, `next_media_track`, `previous_media_track`, `navigate_to_address`. Other old experiments are outside this package. Do not add or retry an action by guessing App classes or shell commands.

The private `vehicle-binding.json` identifies one vehicle. A valid Tesla login and this vehicle's already-paired phone key must be set up by the user in Tesla App. Horn/lights also need the user's own Tesla widget ID and its actual host package. Version, binding or widget mismatch means stop and report what is missing. Do not read tokens or keys.

Historical observations came from stock Android 17 + Magisk + Taku v58 components. All actions are `prepared` for the target TakuOS build; a local check cannot promote them. While the owner is away or asks only to prepare, use `check` only. At the separately arranged vehicle replay, an explicit instruction to test that action allows one `test`; this is not permission to run the whole list. `send` stays blocked until the maintainer records target-system acceptance. Keep old untested actions out of follow-up plans.

## One request through one entrypoint

Send JSON to `request.cjs`; it allocates and prints the actual request ID before invoking the shared `control.sh` guard. Always preserve that ID. Do not generate a placeholder or build a shell command from user text. Use a quoted heredoc and JSON escaping:

```sh
env LD_LIBRARY_PATH=/data/local/agentos/rea-pack/lib \
  /data/local/agentos/rea-pack/bin/node \
  /data/local/agentos/home/.codex/skills/tesla-model-y/scripts/request.cjs <<'TESLA_REQUEST'
{"mode":"check","action":"navigate_to_address","parameters":["麦当劳"]}
TESLA_REQUEST
```

The required fields are `mode`, `action`, `parameters`; `requestId` is optional. Parameter values are strings in catalog order. `check` constructs and roundtrips a request but creates no Intent/broadcast/attempt record. `test` and `send` consume the same atomic one-shot namespace. Reuse the ID for a result query, never for another send. Unknown outcome, interruption or timeout must not generate a replacement ID. Another action needs a new user instruction.

The command adapter uses existing phone-key Bluetooth only. Do not fall back to OWNER_API; the archived global App path was region-rejected. Shared REA Node must already be installed; this Skill does not provision a runtime or start REA research.

## Destination and control semantics

For navigation, extract the user's place phrase and keep it unchanged. “导航去麦当劳” means `麦当劳`. Do not demand a street address, city or branch, add details from a map/search, guess coordinates, or ask whether the car is parked/in P/nearby. Ask only if no destination can be recovered from the current request or conversation. This sends destination text and may replace an existing route; it does not promise automatic first-result selection, route start or driving/FSD. The development-replay mode above remains separate from everyday navigation confirmation.

Use explicit `lock` or `unlock`, never a toggle. Unlock does not open a door or guarantee later relocking. Temperature only changes the driver's and passenger's target to the same value; it does not also start HVAC or defrost. Respect catalog ranges, and report them as local input limits rather than proof that every value was physically tested. Track commands do not start music or guarantee a particular media source.

Do not infer Tesla phone-key connectivity from generic Android `STATE_DISCONNECTED` or headphone state. A successful `check` also says nothing about Bluetooth connectivity. Report a connection issue only from the matching App result, without inventing a cause from elapsed time.

## Read the result without sending again

For command-route actions, query the same real ID:

```sh
sh /data/local/agentos/home/.codex/skills/tesla-model-y/scripts/control.sh status REQUEST_ID --once
```

Replace `REQUEST_ID` with the actual output. Omit `--once` to read for up to 20 seconds. This only reads existing local receipts/App telemetry and matches the complete UUID plus `sourceContext`; do not use nearby events from a different request. It never sends a command or changes one-shot state.

- `appOutcome=failed`: report the App result and stop.
- `unknown`: report that the matching result is unavailable or unrecognized; do not infer success or automatically resend.
- `succeeded`: report that Tesla App accepted the request. Vehicle destination/physical effect is a separate observation. Do not demand a result confirmation on every daily request merely to improve a test record.

Horn/lights use the official widget route; their App-generated trace is not linked to this package's UUID. Android broadcast completion is dispatch evidence only, and `status` cannot manufacture a matching App/vehicle result for them.

Keep VIN, widget identity, destinations, receipts, App logs, credentials and keys private. Never modify other Skills, global permissions, account settings or existing sessions. There is no persistent controller to stop; disabling this Skill does not unpair the Tesla key. Already-submitted requests cannot be promised cancellable.
