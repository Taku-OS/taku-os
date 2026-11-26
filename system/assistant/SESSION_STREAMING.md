# Agent session stream

The ordinary-UID Agent UI calls `subscribeSession` with `{threadId}` or
`{threadId, brokerEpoch, afterSeq}`. The gateway returns `{streamUri, subscriptionId}`.
Open the URI once as a read-only JSONL stream; call `unsubscribeSession` with the
subscription ID when leaving the screen. Closing or reconnecting a stream never
starts, interrupts, or replays an agent task.

Each line has this shape (the epoch/sequence are Taku relay fields):

```json
{"schema":"taku.session-events/1","brokerEpoch":"uuid","threadId":"uuid","seq":42,"method":"item/agentMessage/delta","params":{"turnId":"turn","itemId":"item","delta":"text"}}
```

- A new subscription or an unavailable cursor starts with `session/snapshot` and
  `params.thread`. Replace the whole thread; that snapshot covers every sequence
  through its `seq`. Registration and snapshot capture share the reducer lock.
- A valid cursor starts with `session/ready` at `afterSeq`, followed by every later
  event. Ready/heartbeat are transport controls and do not advance the applied
  cursor. Duplicates are safe to discard; a sequence gap requires recovery.
- Apply deltas to the exact turn/item. `item/completed` replaces the item's full
  text. `_streamCompleted` on snapshot items records per-item completion;
  `turn/completed` merges its items by ID, preserving earlier tools/messages.
- A `reasoning` item is the model's public reasoning summary; its `summary` array holds
  the parts. `item/reasoning/summaryTextDelta` with
  `params:{turnId,itemId,summaryIndex,delta}` appends to `summary[summaryIndex]` of that
  item, making the item if its start has not arrived; `item/completed` replaces the
  summary. The relay adds `startedAtMs` when the item starts and `durationMs` when it
  completes, both from the runtime's own item timestamps, and keeps `durationMs` for a
  known item across history reads, which carry no timing. Raw reasoning content has no
  stream event. Reasoning text has its own display budget: a snapshot can omit `summary`
  from older items, and a client then keeps the summary it was already given;
  `summary: []` means there is none. A client that does not know the event skips it.
  The gateway does not: it ends a stream on an event outside its list, so the broker
  and the Assistant that carry this event are installed together.
- Primary answers publish `session/reply` with `params:{turnId,reply,items}`.
  Apply the canonical reply under `turn.mainReplies[reply.id]` and its sanitized
  source items atomically. `reply.revision` is scoped to that native reply; an
  older/equal revision cannot roll it back. Its `card` contains the typed V2
  request, directly supplied view and field readiness. No model records are
  parsed in the client, and no second transport cursor is introduced.
- `session/reaction` with `params:{turnId,itemId,reaction}` puts Taku's emoji reaction
  (`""` clears it) on that `userMessage` item. It is out of band: it repeats the
  stream's latest `brokerEpoch`/`seq`, does not advance the cursor and is never a gap.
  Apply it only to a known user item of the current epoch; otherwise ignore it, since
  snapshots and `readSession` already carry `item.reaction`.
- Native code suppresses framed source deltas until a valid prefix is available.
  Public item text, canonical reads and terminal events contain the deterministic
  readable projection, never primary wire JSON. One participating source item
  carries the body and `replyId`; the other source items have empty display text.
- A valid logical `done` produces `sealed`; only native turn success can produce
  `completed`. Failure, confirmed interruption and runtime loss retain the last
  accepted prefix with their actual lifecycle. Native source identity is stable
  across canonical item-ID changes. Tool/user/commentary and ordinary unframed
  text events keep their existing semantics.
- Explicit history refresh can emit a new `session/snapshot` in an existing stream.
  Active turns retain the relay's full live turn, because app-server cold history
  may use different item IDs and omit an active prefix. Completed history replaces
  the old snapshot as a whole. Stream snapshots carry no `historyComplete` claim;
  the normal `readSession` response retains responsibility for history evidence.
- `session/resync`, EOF, or an invalid sequence requires a new subscription.
  Reconnect with the last *applied* cursor, or omit both cursor fields to force a
  snapshot. Runtime restart changes the epoch. If the runtime cache is unavailable,
  the existing read/resume path reloads the thread; no replacement task is submitted.

The gateway checks the signature permission, exact package and ordinary UID for
both the call and URI open. Tokens are UID-bound, single-use and expire after 30
seconds unopened. Four pending/active streams are allowed, each with a five-minute
lease. It filters the event method/fields and uses a nonblocking pipe so abandoned
readers cannot hold a writer indefinitely. The broker socket remains system/root
only and is separate from request/response RPC.

The relay retains at most 1,024 events / 4 MiB globally. Each of at most eight broker
subscribers has a 256-event / 2 MiB queue, plus its initial snapshot (at most 8 MiB).
Overflow produces resync instead of dropping text or blocking the app-server pump.
Gateway line parsing is limited to 8 MiB. Heartbeats arrive every ten seconds;
normal five-minute lease renewal resumes from the cursor.

Native raw source reduction is immediate. Public legacy projection files contain
the readable answer and accepted primary snapshots, checkpointed at most four
times per second during progress and immediately at terminal/control transitions.
While subscribed, `readSession({threadId,statusOnly:true})` reads native/task/request
state without loading or transferring the body. Default `readSession` and the
existing approval/submission flows remain available for recovery.

Host verification: `bash system/assistant/test-session-stream-host.sh` and
`bash system/assistant/test-agent-gateway-host.sh` (set `JAVA_HOME` to a real JDK).
