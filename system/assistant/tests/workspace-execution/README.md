# Workspace interactions

The new Broker actions deliver user input to the existing native thread. They do
not create a replacement task, lease, chat history, or business-result store.

Send `op: "workspaces"` with one of:

| Action | Required fields | Behaviour |
| --- | --- | --- |
| `returnAndResume` | `threadId`, `screenKey`, `generation`, `controlRevision`, UUID `requestId` | Preflight the selected image model, stop and verify quiescence, return control, then resume the original main Agent or relay a sub-agent return to its parent. The resumed agent calls `observe_background` before continuing; return does not depend on a pre-resume screenshot. |
| `addRequirement` | Same fields, plus nonblank `text` (at most 4,000 characters) | Steer the exact active turn, or start a turn when idle. User-owned, paused, waiting-for-user, realtime, and conflicting executions reject. |
| `executionStatus` | `threadId`, `screenKey`, `generation`, `requestId` | Read the saved receipt without executing anything. |

`returnControl` is rejected. Returning a screen uses `returnAndResume` and its
receipt. `pause` and `takeControl` retain the existing stop/process-proof gates.

## Receipt semantics

The action initially returns `pending`. Poll `executionStatus` with the same ID.
`accepted` means the native runtime acknowledged a specific `turnId` in
`targetThreadId`; it does not
mean the Agent or the external App completed the task. Follow canonical native
turn state and inspect the actual business result.

`threadId` always identifies the original workspace and request. `targetThreadId`
identifies the native thread that actually receives the input, and pairs with
`turnId`. They match for a main Agent. A sub-agent's return is sent to its parent
(`turn/steer` if active, otherwise `turn/start`), which must wake the same sub-agent;
the parent's acknowledgement does not prove the sub-agent has resumed.
The target is saved before dispatch and read unchanged after restart. It may be
absent before preparation identifies it. Historical receipts without this field
keep an unknown target; queries never infer it or replay the request to fill it in.

A repeated ID with identical content only reads the existing receipt. Changing
the action, lease, revision, or text under an existing ID rejects. A definite
`failed` receipt with `requiresReconciliation: false` permits a new request only
after the user explicitly retries with a refreshed lease/revision and new ID.

`unknown` means delivery or stopping could not be confirmed. Query the existing
ID; never resend with a new ID. The thread remains fenced across a transport or
Broker restart. A late native acknowledgement can resolve a live receipt. There
is no automatic reconciliation API for a receipt left unknown after restart.

## Observation after return

Return sends text guidance without a pre-resume image. The original main agent
is instructed to call `observe_background` before continuing. A child return is
relayed to its parent, which wakes the same child with `followup_task` and asks it
to observe its own screen first, or end if the task was cancelled. Parent
acceptance does not prove child execution. Actual observation and task completion
require separate device/model evidence.

The receipt engine's optional image-evidence fixture and capture validators
remain host-tested; those fixtures do not mean current returns attach images.

## Session removal

Before forwarding `thread/delete` or `thread/archive`, the broker saves the full
live and archived descendant list, explicitly including every source kind and
provider (the native default hides spawned sources). First App requests also cache immutable spawn
ancestry, so concurrent children and screen openings remain identifiable after
the SDK erases its index. Family queries and physical task cleanup run outside
the ledger lock. New grants and execution admission for the affected family are
fenced while the removal is pending; unrelated families continue normally.

Confirmed success reuses existing whole-turn cleanup for every saved member,
including taken-over and paused screens, and existing phone-turn cancellation.
Explicit failure restores admission without releasing valid resource holds.
`SCREEN_SESSION_REMOVING` rejects new App requests in a fenced family;
`SESSION_FAMILY_INCOMPLETE` rejects incomplete ancestry or pagination.
Incomplete lists (including the 20-page bound or repeated cursors) fail before
the destructive request is sent. A result still unknown after 40 seconds keeps
its snapshot and checks native active/archived lists; inconclusive checks retry
after 30 seconds. Confirmed active tasks regain admission while the saved family
is retained for a late result. This is in-memory state: runtime shutdown still
uses the existing all-screen cleanup rather than a new persistent reaper.

## Verification

Run `bash system/assistant/test-workspace-execution-host.sh`. An existing trusted
JSON dependency can be passed using `TAKU_TEST_JSON_JAR`. This compiles all Broker
sources against the Android SDK, then checks receipts, duplicate/conflicting
requests, concurrency, stale/paused rejection, definite/uncertain interruption,
restart and late-ack barriers, and capture/image evidence on the host.

`test-dedicated-screens-host.sh` separately checks real admission/reservation
logic and stale lease/revision boundaries against the dedicated ledger.
It includes family removal, failed/unknown outcomes, concurrent openings and
pagination completeness. `test-native-routing-host.sh` checks snapshot-before-send
and response handling through production native routing.
`test-workspace-return-host.sh` exercises production return preparation and the
final dispatch guard, including a delay that previously expired the screenshot.
`test-execution-screens-host.sh` covers screen identity and input connections.
`test-workspace-ui-host.sh` runs the production status mapper and durable UI
request client with host-only preference/transport doubles. It covers App-wait
labels, ownership priority, repeated return clicks, page recreation and uncertain
or late acknowledgements. It does not render Android views or prove that the
detail-page button was clicked; those checks require separate device acceptance.
Device regression is `tests/dedicated-screens/e2e/s8.py`, replacing the retired
explicit-binding checks in `scripts/test-workspaces-device.py`. These checks do not prove Binder,
device rendering, provider inference, or a completed cross-App task. Emulator,
Pixel, and full-ROM acceptance must be recorded independently.
