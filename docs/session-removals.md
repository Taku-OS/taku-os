# Conversation deletion, archiving, and whole-family cleanup

Follow-up worktree implementation for PR #62, 2026-11-17. Original review:
<https://github.com/Taku-OS/taku-os/pull/62#pullrequestreview-5920595978>.
This page describes conversation deletion/archiving and necessary cleanup; other review items retain their original status.

## Behavior

Native GUI `thread/delete` and `thread/archive`, and Agent
`sessions/removals`, write to the same persistent `SessionRemovals` work records.
Each conversation has only one active record; duplicate requests for the same target reuse progress, archive can upgrade to delete,
and delete cannot downgrade. `accepted` is returned after confirmed disk persistence. Accepted conversations disappear from lists,
while progress remains queryable in conversation management. Hiding does not mean the Codex target or resource cleanup is complete.

```text
GUI / Agent
  -> atomically register or upgrade, persist reliably
  -> hide conversation and block new turns/resources across the family
  -> claim fairly across three available execution slots, retaining the record in the table
  -> verify latest state, save complete family and resource ownership
  -> advance Codex operation if target is unmet, then verify again
  -> cancel one member or clean one resource at a time, save progress and yield
  -> target reached or absence confirmed, plus whole-family cleanup complete -> close record

Failure/timeout -> exit current attempt, retain original record -> reverify after 30 seconds
```

Both ordinary and archived lists must be fully enumerated to confirm deletion. Missing pages, repeated cursors, page-count
limits, query failures, and timeouts do not mean absence. If history lists do not contain the conversation, query native in-memory conversations;
Codex first persists ordinary new empty conversations before archiving them. Only confirmed absence from all three locations ends as no record;
local cancellation and resource cleanup must still finish. Normal archiving must confirm actual archived state and preserve history.
Normal creation and forks use UUIDv7 from the pinned Codex version; manual import/replacement of content under the same ID,
restoration abnormally overlapping old deletion intent, and similar cases retain the ID limitations accepted in this round.

When an in-progress archive upgrades to delete, the original record's target and revision persist immediately, and the next attempt reads the new target.
An old attempt's verification or late response cannot overwrite the revision, revoke deletion, or close the record early. Operations already sent to Codex
may finish after a local timeout; later attempts act on the actual state.

## Persistence and execution boundaries

Records are stored at `/data/local/agentos/session-removals/<threadId>.json`.
Writes are acknowledged only after temporary-file fsync, atomic replacement, and directory fsync. Claiming and retrying update the original record;
a restart recovers unfinished records and verifies first. Successful cancellation/cleanup steps are saved individually. On completion, family and
resource evidence is removed, retaining at most 100 recent completion receipts; queries for expired receipts return unavailable.

There are three fixed Java worker threads; claims are limited to those three execution slots, and the in-memory execution queue is bounded.
Stepwise round-robin scheduling advances one stage per claim: whole-family native processing, one member cancellation, resource recapture,
one resource cleanup, or final verification. Claims continue after the previous position; ordinary yielding immediately
wakes scheduling without a one-second poll or 30-second retry delay for every step. At most one step runs per record; the next may use a different
thread, and an already-running slow step is not preempted. Family ownership persists across yields; after one failure, other
members/resources are attempted before postponing an incomplete round. Successful progress persists; restart first rechecks the native target,
then skips confirmed cancellations/cleanups. Parent and child have no additional ordering dependencies, and local cleanup does not require online conversations.
Local operations for the same conversation and recognized overlapping families do not run concurrently. Locks, RPC responses, and external-command waits
use a fixed 25 seconds; failures defer for 30 seconds without making workers sleep until retry. There is no shared
25-second budget for the entire round. Ordinary call paths retain original default lock/command waiting behavior.

Deletion uses nonblocking RPC enqueueing; responses only complete the current wait, without resource
cleanup in the shared event pump. Timeout removes unsent requests and pending-response references; late responses do not enter the ordinary deletion-success observer
branch. External-command output goes to temporary files, without helper threads that could block reading pipes;
both commands and exit confirmation are time-limited. Commands whose exit is unconfirmed retain their execution quota, up to three, preventing unbounded
replacement subprocess creation.

Dedicated-screen cleanup saves `screenKey`, `generation`, conversation, and each occupation's `allocationId`.
It rechecks occupancy after acquiring the input lock, protecting the same conversation's next turn and resources already transferred to another conversation.
Successful cleanup makes the screen idle for reuse; failed task cleanup retains the two-attempt and confirmed-screen-close fallback,
and failed items retain ownership. Failure of one member does not prevent attempts for others; failed members cannot hand off resources early.

After broker restart, saved physical identities can still be used for cleanup. `phone-display removal-status`
reports gone only when the socket is explicitly absent and Android also lacks the named display; permission,
identity, or communication failures remain unconfirmed. `removal-stop` retains the lifecycle lock and execs into the real
CLI process so a worker timeout can terminate it instead of killing only a waiting wrapper shell.
Deployment must update the display wrapper, display JAR, and assistant components together.

### Remaining hard-timeout limitation

`FileDescriptor.sync()` / `FileChannel.force()` in `SessionRemovals.save`
and Android synchronous Binder input injection in `PhoneGestures.inject` have no caller-configurable
Java timeout parameter. If the filesystem or system service becomes completely unresponsive inside the kernel, this implementation cannot
guarantee those underlying calls exit within 25 seconds; it does not use an outer Future or replacement thread to claim termination.
The current implementation preserves synchronous persistence semantics, keeps original records and unconfirmed resources on failure, and documents this boundary honestly.
Fully covering system-level hangs requires a separate system-layer or process-isolation decision, outside this round.

## GUI and Agent

GUI: Conversation management → Deletion and archive progress. Archive and permanent deletion still start from existing native operation entries.
Agent: `sessions/removals` at `content://org.agentos.assistant.open-access`,
with parameters/examples in preinstalled `taku-open-access/SKILL.md`.

| Parameter | Meaning |
| --- | --- |
| `action` | `submit`, `get`, `list`; defaults to list |
| `threadId` | Native conversation UUID required by submit/get |
| `target` | Required by submit: archive or delete |
| `requestId` | Optional for get; read only the matching receipt |

Outer `ok` means the interface call succeeded. `accepted` means persistence is confirmed, `targetReached`
means the Codex target is verified, and `completed` means processing and whole-family cleanup are complete. These are independent.
When absence of an archive target is confirmed, the response is `completed: true`, `targetReached: false`,
`outcome: "no_record"`; the GUI displays “No record, completed,” which does not mean archived.
`status`, `phase`, counts, and `nextAttemptAt` come from the same record; error causes use existing
logs, without another error table. `unavailable` is not success, and `persistence` is not reliable acceptance.

## P1-2 stepwise round-robin verification

Implemented on 2026-11-17 in independent worktree `taku-os-pr62-p1-2-round-robin` after integrating stable baseline
`2db4a5e`; `taku-os-dedicated` was read-only reference. Logs, patches, and source digests
are retained in that worktree's `out/p1-2-round-robin-verification-20261117/`.

- Real Java coordinator, files, and threads: existing recovery/failure regression plus new checks for small records finishing before large ones,
  single-step yielding, later items continuing after failure, overlapping-family ownership, upgrades between steps, and automatic wakeup.
  A real JVM halt between member cancellations was added alongside the four original checkpoints to verify restart continuation.
- Red/green evidence: the baseline failed the small-record fairness check; an independent shadow version of the new scheduler temporarily changed to keep scanning from the table head
  also failed. Production source passed; the shadow version did not replace production files.
- Related dedicated-screen ledger, native-routing, and execution-screen regressions, `npm run verify`, diff checks, full
  assistant APK/broker builds, and device-probe JAR/signed fixture APK builds passed.

These are host/build checks; this round did not run device, GUI, or real-model acceptance, and retains the
system-I/O hard-timeout boundary below. Python prototypes and prior P1-1 results do not count as new P1-2 verification.

## P1-1 verification record

These are actual results from the current worktree on 2026-11-17, with uncommitted changes based on `7ea3b8d`, without
reusing prior PR passes. The assistant host passed 1,940 checks; display additionally passed 54
assertions and 106 wrapper calls. Full logs and source SHA-256 values are retained in the development worktree's
`out/session-removals-verification-20261117/`.

| Scope | Actual result |
| --- | --- |
| Persistent records | `test-session-removals-host.sh`: 270 checks; `fixed-timeout` mode: 3 checks, with all three actual Runnables returning within 25,077ms under the production 25-second default |
| Dedicated screens and input | `test-dedicated-screens-host.sh`: 789+18+97+109+166 checks; `test-execution-screens-host.sh`: 49; question/suggestion/gesture host: 13+13+44 |
| Native routing and return | `test-native-routing-host.sh`: 115+36; return: 25; persistent receipts: 96; UI logic: 28 |
| Documentation and display | Built-in instructions: 69; `DisplaySlotTest`: 54; real wrapper with fake Android commands: 106 calls |
| Engineering | `npm run verify`, `git diff --check`, full assistant APK/broker and display builds, device-probe JAR/fixture APK compilation, and signature verification passed |

New checks cover concurrent deduplication, upgrades and late responses, actual exit and execution-slot release after RPC/lock/command
timeouts, continuing later items after individual member-cancellation failures, admission rejection for descendants created after acceptance,
and ownership rechecking when occupancy changes while waiting for the input lock. A shadow version removing the post-lock ownership
check failed this race test; production with the check passed. The JVM was also halted directly at acceptance, claiming, upstream operation,
and partial cleanup; every restart continued from real files.

Isolated AVD `Taku_PR62_Removals_20261117` used SDK Emulator 37.2.12.0, Android
36 arm64. Host graphics, software graphics, and disabled acceleration each entered the
QEMU hang detector and SIGSEGV before Android boot. No device installation, GUI taps, or actual Provider
reads/writes were performed; passing host/probe compilation cannot count as emulator acceptance. Re-runnable probes are in
[isolated emulator cleanup probes](../system/assistant/tests/session-removals-device/README.md).

Physical-device and real-model acceptance are deferred at the user's request, to be scheduled after other conversations release the device/model.
