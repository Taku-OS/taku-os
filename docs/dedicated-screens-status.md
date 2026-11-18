# Dedicated background displays: current version notes

Session deletion/archiving update (2026-11-17, follow-up worktree after PR #62): durable pending work, three Java workers, 25 seconds per blocking wait, and retries after 30 seconds. GUI/Agent requests and progress queries share the same authority; jobs finish only after group cancellation and resource cleanup complete. See [session removal and cleanup](session-removals.md) for implementation, validation scope, and remaining hard-timeout limitations for system I/O. This paragraph does not update other review items.

Implementation snapshot: `feat/dedicated-screens`, `573c32a09519a01f5f45a3760f29b427763725c4`. Compiled and code-reviewed: 2026-11-11.

Spec 2 update (2026-11-12, current worktree): A3 now revokes access per app and passed the targeted checks below. Other entries and original-candidate statistics retain the baseline above. The original full review and ticket 12's complete real-model acceptance were not rerun.

Spec 3 + Spec 5 update (2026-11-12, uncommitted worktree based on `8a82b48`): A4 adds occupancy markers; A6 adds two task-cleanup attempts, confirmed display closure, and retained failures for retry. Spec 2 remains; other entries follow the original snapshot, and Spec 1 remains withdrawn.

Spec 4 update (2026-11-13, uncommitted worktree based on `2222d07f`): A5 adds synchronous idle-display reduction when obtaining a new display. Existing occupancy remains; counts include displays being created. Excess idle displays are detached under lock, then individually stopped and awaited outside the lock. Without a new-display request, the original idle timeout still applies.

Standards 1 + 2 + 4 update (2026-11-13, based on `2222d07f` plus existing Spec 4 WIP): adds a standard close-display entry, simplified automatic updates for designated built-in instructions, and configuration documentation. Standards 2 uses the newly confirmed scope: automatically update unmodified files; preserve and notify for modified files or missing trusted old records. Do not automatically merge user edits.

Reference for PR review, packaging, and physical-device testing: this page centralizes current triggers, design tradeoffs, and validation scope. See [background-display list interface](dedicated-screens-ui-fields.md) for fields. Design goals are distinguished by the current implementation status recorded here.

## Validation scope

| Scope | Record as of this snapshot |
| --- | --- |
| Code review | 59 items A1–E13: 46 still have corresponding implementation paths or boundaries; 12 retain historical observations awaiting dynamic revalidation; E7's stop-duration summary was corrected. Code risk means a trigger can still be constructed, not that it has been reproduced on a device. |
| Existing local/script regressions | All 19 host summaries passed, including reused and rerun results; component emulator S1–S8 passed 282 checks total; real subagent revocation record passed. |
| Real-model component emulator | Second round ongoing. Device-model reads completed 5 passing runs. Full-queue testing has 5 valid samples: 4 strict passes and 1 accepted deviation. Targeted script checks passed for disabling the master switch while retaining existing work. A1 and A2 each have two scenario types, with 5 failures per type; see entries. |
| Targeted checks this round | Existing `PhoneGestureSessionTest` ran in isolation during code review: 44 assertions passed, covering active cancellation, timeout, and rejecting late touches after completion. |
| Spec 2 repair checks | Host checks passed for the dedicated-display ledger and real revocation worker, native phone-tool routing, gestures, and return-control receipts; `npm run verify` and Android component builds passed. Isolated `Taku_Spec2` / `emulator-5584` (Android 16/API 36, hardware GPU + Guest ANGLE, SELinux Enforcing) passed 22 component probes covering actual task cleanup, canceling held gestures, shared management authority, notifications, 30-second protection, and takeover preservation. Codex metadata used fixtures, without a real model. Evidence: development worktree `out/spec2/`. |
| Spec 3 + Spec 5 repair checks | 97 new lifecycle host checks passed; together with existing ledger, Spec 2 revocation worker, routing, gestures, and return receipts, 1156 passed. Verify, isolated all-Android-component builds, and final broker compilation passed. The same isolated Android 16/API 36 component emulator checked real task retries, confirmed closure, failure lists, shared GUI/Agent authority, and 30-second automatic cleanup retries. Late `await` used constructed timing, not complete device-thread interleaving; Codex metadata was fixture-based, without a real model. Original records, artifact hashes, and scope: `out/spec3-spec5/`. S1–S8, Pixel 9, complete ROM, and performance acceptance were not rerun. |
| Spec 4 repair checks | 109 new capacity host checks passed; 1265 total with existing ledger, Spec 2/3/5, routing, return receipts, and gestures. Verify and isolated all-Android-component builds passed. Isolated `Taku_Spec2` / `emulator-5584` (Android 16/API 36, host + GuestAngle, SELinux Enforcing, development su domain) passed 40 actual dedicated-display/task checks. The same probe reproduced the issue on old artifacts and passed on new ones. Coverage: synchronous waiting, closure outside the lock, occupancy preservation, cleanup despite rejected requests, and continuing after closure failures. Faults/wait interleavings were injected; Codex metadata used fixtures, without a real model. Evidence: `out/spec4/`. Full S1–S8, real models, Pixel 9, complete ROM, and performance acceptance were not rerun. |
| Standards 1 + 2 + 4 checks | 1265 relevant host-core checks and 69 new built-in-instruction checks passed; verify, isolated all-Android-component builds, and final Assistant build passed. Fresh isolated-SDK Android 16/API 36 `Taku_Standards124` / `emulator-5588` passed 33 actual APK/process-recovery checks, 38 management/configuration component checks, and 6 native-cache refresh checks. Skill-preservation notices were visually reviewed in native screenshots and checked against Agent queries sharing the same authority. Thread identities were metadata fixtures; no model ran. Evidence: `out/standards124-20261113-182028/`. Original full review, complete S1–S8, real models, Pixel 9, and complete ROM were not repeated. |
| Physical device and complete ROM | No completed acceptance record yet for this candidate's Pixel 9 regression, UI stress with 10 dedicated displays plus default display (11 total), or complete-ROM cold boot. The component emulator uses software rendering; durations represent that environment only. |

Existing results come from dedicated-display development records and ticket 12 acceptance snapshots. Original summaries are in development-worktree `out/workspace-revocation/`; model logs and images are in `out/ticket12/` and second-round batches. These originals were not copied into the repository with this page. Repository script entry points: [S1–S8](../system/assistant/tests/dedicated-screens/e2e/), [local dedicated-display checks](../system/assistant/test-dedicated-screens-host.sh), and [touch checks](../system/assistant/tests/org/agentos/lab/PhoneGestureSessionTest.java).

## Issues and boundaries

IDs support later review, testing, and repair records. Verification status distinguishes code paths, actual observations, and untested inferences. “Dynamic revalidation pending” retains original candidate/model/app/device limitations. Deferral reasons record existing decisions only; `—` means no reason recorded.

### A. Current functionality and integration

See targeted repairs for A3, A4, A5, A6, A8, A9, and A11 below. Other entries follow the original snapshot and their own evidence scope.

| ID | Issue, trigger, and impact | Verification status and evidence | Recorded deferral reason |
| --- | --- | --- | --- |
| A1 | **Cross-app question/confirmation reminders for ordinary sessions are invisible**: while another app such as Clock is foreground, an ordinary session suspends on `ask_user` or `confirm_action`, but neither foreground nor notifications show a reminder. | Code path remains; 5 failures for each type on the same candidate's component emulator. [Card entry][native-cards], [background-task display scope][jobs]. | — |
| A2 | **Returning control from detail page fails**: after takeover, returning the main session's or subagent's dedicated display from details leaves control `user` and does not resume the task; the entry still calls removed `returnControl`. | Code path remains; 5 component-emulator failures for each type. [Detail page][detail], [broker dispatch][control]. | — |
| A3 | **Spec 2 fixed (current worktree)**: disabling A cleans up only A, preserving allowed B, the turn, and dedicated display without triggering the subagent display-close block. The next phone tool first returns a reassessment prompt. Releasing the last app retains the 30-second protection; failures preserve uncleared occupancy and takeover contracts remain. | Targeted host/isolated component-emulator checks passed; real-model continuation, Pixel 9, and complete ROM untested this round. [Automatic revocation][revoke]. | — |
| A4 | **Spec 3 fixed (current worktree)**: every display allocation generates `occupancy`, referenced by granted tickets. Late ticket withdrawal revokes apps only for that occupancy, preserving later occupancy both across sessions and within the same session. Full-display release invalidates the marker at its start; pause, takeover, return, and app changes within one occupancy preserve it. Confirmed-close failure restoring original occupancy also restores its marker. | New lifecycle host checks passed; component emulator used real ledger/Android tasks and constructed late `await` to check session changes, same-session reuse, and closure boundaries, not complete device-scheduling reproduction. [Request][request], [withdrawal][withdraw], [turn cleanup][finish-turn]. | — |
| A5 | **Spec 4 fixed (current worktree)**: obtaining a new display first detaches excess idle displays using `ledger.size() + opening` and the latest limit. Capacity checks and acquisition/reservation occur under lock; synchronous per-display closure occurs outside the lock before returning. Sessions with existing displays continue. Active, queued, taken-over/paused, 30-second-protected, and releasing occupancy is not trimmed. If still above limit after reduction, new allocation is rejected; at the limit, reuse remains allowed. | Host and isolated Android component comparisons passed. [Request][request]. A failed individual shutdown records `key`, `generation`, and error, then continues closing the batch. Failed displays are not reassigned, but physical processes may remain. This adds no automatic retry; original idle-timeout closure failure boundaries remain. | Synchronous implementation: requests await all shutdown attempts in the batch; asynchronous optimization deferred. |
| A6 | **Spec 5 fixed (current worktree)**: `turnEnded` and `sessionGone` attempt task cleanup at most twice. Continued failure keeps resources nontransferable until `host.stop` is confirmed outside the lock. Failed closures remain in the shared list with `cleanup_failed` and `lastError`; background retries closure every 30 seconds, with repeated failures continuing to consume capacity. | Host/isolated component fault-injection checks passed; real GUI screenshots and Agent `content` queries share the same authority. Partial task deletion followed by retry, actual display destruction, and fault recovery checked. Natural device failure rate unmeasured. See [interface notes](dedicated-screens-ui-fields.md) for fields/waiting. A7 active-close boundary remains. [Cleanup][finish-turn]. | — |
| A7 | **User-initiated closure may falsely report success**: task-cleanup or display-process stop failure may only be logged; the ledger entry is already removed without confirmed physical cleanup. | Code risk remains, specifically on active-close path. [close][screen-close]. | — |
| A8 | **Standards 1 fixed (current worktree)**: standard `workspaces/release` accepts recently queried `threadId`, `screenKey`, and `generation`, retaining root/system checks and the GUI's common close path. | Isolated Android components checked real Provider, ordinary-UID rejection, incorrect identity, native GUI button, and physical display status. Existing `turnStarted` blocking after child-display closure verified with metadata fixtures and production ledger, without real models. A7 active-close failure feedback remains. | — |
| A9 | **Standards 2 fixed (designated built-in instructions only)**: automatically update `taku-open-access/SKILL.md` only when it matches the last trusted official digest. Preserve local edits/unknown old versions and notify through skill queries, context catalog, and native skill details. Staged text and durable update records support interrupted completion and force rediscovery of changed instructions. Other built-in skills retain their mechanisms. | 69 new file checks passed on host/Android. Validated consecutive real APK-resource updates, old-logic failure, preservation of modified/unknown origins, six actual process-exit/recovery points, and cache refresh in the same native process. No automatic merge; old installations lacking records and differing from the current APK must still be preserved with a notice. | Simplified long-term automatic updating confirmed this round; automatic merging deferred. |
| A10 | **Child-display return receipts have mismatched IDs**: after returning a subagent display, `threadId` belongs to the child, but `turnId` may belong to the parent receiving the relay. Clients pairing both may track the wrong turn; relay execution itself works. | Code-contract issue remains. [Relay target][relay], [receipt generation][receipt], [receipt session fields][receipt-view]. | — |
| A11 | **Standards 4 fixed (current worktree)**: preinstalled instructions now document `allowBackground` and `maxActiveScreens` types, defaults, ranges, query/update examples, actual post-save semantics, and typical errors, retaining full-replacement requirements for the disabled list. | Valid/invalid documented parameters passed through real `content`/shared policy validation on an isolated emulator; actual APK/device instructions checked. Configuration behavior unchanged. | — |

### B. App and user-operation boundaries

These include accepted usage boundaries; specific third-party app screen behavior retains dynamic-revalidation status.

| ID | Issue, trigger, and impact | Verification status and evidence | Recorded deferral reason |
| --- | --- | --- | --- |
| B1 | **The same app is used serially, with shared accounts/data**: sessions must take turns and share login state, orders, carts, and other persistent data. | Implementation boundary remains. [Grants][grants], [opening apps under the same Android user][open-app]. | Multiple instances in one process interfere; no multi-instance allowlist this cycle. |
| B2 | **Reopening does not guarantee a usable entry**: although tasks are cleared, third-party apps may retain CAPTCHA or location-error state. Older samples include Dianping human verification and Yelp location failure. | Dynamic revalidation pending; old app samples retained, with source alone insufficient to establish reproducibility in the same current app version. [Reopen entry][open-app]. | — |
| B3 | **Main-display residual tasks block the Agent**: apps foregrounded or in Recents block Agent display operations; hidden pages absent from Recents may also block, leaving no card for users to find. | Check path remains; hidden-task behavior observed on an older emulator. [Main-display task check][screen-check], [task query][task-query]. | Avoid moving user pages to dedicated displays; per-page launch compatibility checks out of scope this cycle. |
| B4 | **User actions may interrupt the Agent's page**: opening the same app on the main display mid-task exposed a partially completed page in old experiments; dismissing its task may lose the Agent's original page/progress. | Dynamic revalidation pending; page movement/recovery depends on app and Android version. [Opening behavior][open-app], [user-occupancy notice][user-holds]. | Originally recorded as Android behavior. |
| B5 | **Race remains between checking and opening**: after checking the main display, the user may open the same app just before the Agent, potentially moving the user's task. | Code risk remains; query and actual launch are not one Android atomic operation. [Action after check][screen-check]. | — |
| B6 | **Cross-app navigation is detected afterward**: entering an unrequested app is blocked only on the next operation. If someone else uses that app, its page may already have moved; the latter is inferred. | Check path remains; premature page-movement impact awaits device reproduction. [Pre-action check][screen-check]. | Explicitly no pre-navigation interception this cycle. |
| B7 | **Multiple user-occupied apps need individual handling**: when two needed apps are on the main display, each encounter names only one; users may receive two consecutive cards. | Code path remains. [Check individually and return immediately][screen-check]. | Handle each when encountered. |
| B8 | **Permission modes restrict dedicated displays**: “Ask every step” prompts for `request_apps` and `release_apps`; “Read-only” sessions cannot request a display. | Permission classification remains; formal permission modes have sampling records. [Permission checks][permissions]. | Requests occupy apps and releases delete tasks; reclassification requires architectural work, deferred. |
| B9 | **Command-line access bypasses phone-tool controls**: direct low-level commands can bypass phone tools' usage-right checks. | Design boundary remains; controls cover managed phone tools. [Session permissions][native-tick], [engineering contract][contract]. | Full session privileges are deliberately retained without demotion; original design accepted this boundary. |

### C. Queues, display capacity, and duration

Old durations and token counts retain only their original experimental meaning. Current candidate performance, model choices, and load changes require fresh sampling.

| ID | Issue, trigger, and impact | Verification status and evidence | Recorded deferral reason |
| --- | --- | --- | --- |
| C1 | **Waiting for an app also occupies a display slot**: after waiters fill capacity, a new session wanting only an idle app still receives `SCREEN_LIMIT_REACHED`. | Code path remains; requests acquire a display before waiting for apps. [request][request]. | Acquiring apps before waiting for displays can introduce mutual waits, or a long wait wasted by no display capacity. |
| C2 | **Empty displays retain a 30-second protection**: after releasing all apps, a new request at capacity cannot immediately take the display. After 30 seconds it is reclaimed/reassigned, removing the original session's row from the display list. | Code path remains. [Empty-display reclaim][reclaim], [list filtering][ui-list]. | Protects the brief “release X, then request Y” gap against repeated display changes or failed requests. |
| C3 | **Full capacity alone does not make cards surrender apps/displays**: a card pending 30 seconds at full capacity retains resources if nobody queues for its apps, blocking new tasks. | Code path remains; surrender depends on app waiters. [Card handover conditions][hand-over]. | Trigger only when all conditions coincide; accepted on 11-08. |
| C4 | **Takeover/pause can hold resources indefinitely**: without returning or closing, display slots and app rights remain occupied while others wait. | Design boundary remains; ordinary turn completion preserves takeover occupancy, excluded from empty-display reclaim. [leave][leave], [reclaim][reclaim]. | Preserve occupancy under takeover contract. |
| C5 | **Holding apps during long unrelated work blocks others**: waiters have no lease timeout and must await release or turn completion. | Implementation boundary remains; app holders have no lease. [Wait][await], [turn end][turn-ended]. | — |
| C6 | **Additional requests may never succeed**: a session holding X and requesting Y may repeatedly be rejected while others keep waiting for X. | Code path remains. [Request/additional-request rejection][request]. | Holders cannot block others while continuing to wait, preventing mutual deadlock. |
| C7 | **Upstream abandonment may leave stale tickets [inference]**: if upstream abandons a tool call before the turn ends, broker may not know; the request remains queued, and another request returns `REQUEST_PENDING`. | Code risk remains; prolonged active abandonment not triggered on-device. [Wait/withdraw][await], [native request completion][native-events]. | Longer active abandonment untested. |
| C8 | **Waiting may consume substantial tokens**: code-mode app queues or user-card waits keep polling the model. An old 11-08 sample used about 300,000 tokens over a 129-second subagent wait, mostly cache hits. | Dynamic revalidation pending; old data is not a current usage guarantee. [Runtime startup configuration][models], [pinned runtime][runtime-lock]. | Behavior and speed of invocation-mode changes await separate experiments. |
| C9 | **Printing image results as text expands context**: an old sample stringified image-bearing results, adding much text and substantially increasing later usage. | Dynamic revalidation pending; old model sample retained. [Image tool returns][tools-screen], [runtime version][runtime-lock]. | Invocation-mode experiments incomplete. |
| C10 | **Models do not always obey retry guidance**: observed immediate retries despite a 30-second wait instruction and an extra attempt after five consecutive rejections. | Dynamic revalidation pending; current candidate still constrains retries through prompts. [Rejection/guidance][queue-refusal]. | Only “one extra retry before reporting app busy” accepted as a deviation; others unrecorded. |
| C11 | **Models may bypass explicitly requested UI work**: when asked to complete or verify something in an app UI, the model sometimes reads the answer directly from the runtime without observing the specified UI. This records deviation from a specified entry point. | Dynamic revalidation pending; old model sample. Legitimate `device_info` itself reads system properties directly. [Phone tools][tools-screen]. | — |
| C12 | **Per-action task queries accumulate latency**: frequent display actions each query Android tasks; old 11-06 query-only measurements were about 30ms on emulator and 110ms on Pixel 9. | Duration needs dynamic revalidation; current path still runs `am stack list` every step. [Display actions][screen-check], [query implementation][task-query]. | Performance optimization awaits a separate experiment. |
| C13 | **Many simultaneous session starts may time out**: old tests starting 7–9 turns through broker concurrently saw some fail to start. Jev/gateway sessions use this path; subagents start through another entry. | Dynamic revalidation pending; old counts are not a universal concurrency threshold. [Broker request queue][native-tick]. | — |
| C14 | **Disabling background work preserves existing tasks**: existing app rights and queued requests continue; new requests are rejected. | Confirmed semantics; targeted script checks passed. [Policy save][policy-config], [new requests][request], [existing queue grants][grants]. | Confirmed on 11-11 as “stop accepting new work.” |

### D. Subagent stopping, return, and capacity

Display rights and subagent thread slots are managed separately. Model-relay risks and runtime-internal observations follow their individual evidence status.

| ID | Issue, trigger, and impact | Verification status and evidence | Recorded deferral reason |
| --- | --- | --- | --- |
| D1 | **Stopping the main session does not stop subagents**: chat stop, task cancel, 15-minute timeout, or gateway cancellation may leave subagents operating the phone. Natural-language cancellation relies on the model interrupting them individually and may miss some. | Code boundary remains; ordinary stop targets one session/turn. [Stop handling][native-stop], [phone-tool interruption][workers]. | Deliberately distinguish “stop current turn” from “cancel task,” retaining other parallel work. |
| D2 | **Jev background tasks lack direct child-task cancellation**: the bottom task panel has no composer, and Stop does not stop spawned subagents. | Entry-point boundary remains. [Task-panel actions][job-actions], [stop entry][job-stop]. | Original record expected few subagents for these tasks; handle when encountered. |
| D3 | **Returned child displays may resume very late**: if the parent only uses `send_message` without waking the child through `followup_task`, display/apps remain occupied. An old test resumed after 109 seconds. | Relay dependency remains; duration is an old sample. [Relay guidance][subagents], [return path][relay]. | Correct tool named in prompts; revisit if frequent. |
| D4 | **Nested-subagent display return may fail [inference]**: return goes to the direct parent, itself a subagent; runtime rejects a broker-started turn for it and the display falls back to paused. | Code risk retained; routing still targets the direct parent, without full device-scenario reproduction. [Parent identification][subagent-parent], [relay target][relay]. | Existing tests spawned one level only; revisit if frequent. |
| D5 | **Relay racing parent-turn completion may fail [inference]**: the parent turn selected for relay ends before sending, rejecting the request and returning the child display to paused. | Code risk remains; not observed on-device. [Pre-send check][relay-guard], [failure fallback][relay-rejected]. | Not yet encountered. |
| D6 | **Cancellation during takeover may resume the old task after return [inference]**: the parent may wake the child without mentioning cancellation, letting it continue. | Model-relay dependency remains; no broker-side natural-language cancellation marker. [Relay instructions][subagents]. | Cancellation is natural language; broker cannot see its task semantics. |
| D7 | **Parents may misinterpret takeover interruptions**: after a child's turn is blocked, the parent may not know the takeover reason and may spawn duplicate work thinking the child failed. | Risk remains; dedicated parent notification currently covers display closure. [Turn-block events][native-events], [closure notification][closed-text]. | — |
| D8 | **Unread messages may consume subagent slots**: old runtime records show idle children receiving messages without being awakened cannot be evicted normally. Ten stuck-plus-running children prevent new spawns. | Dynamic revalidation pending; old runtime observations/source investigation retained; current asset remains pinned to 0.161.0. [Version][runtime-lock], [capacity settings][models]. | Requires system-runtime replacement and broad regression; deferred on 11-09. |
| D9 | **Independent child archiving is unreliable**: GUI archives children only with the parent. An old interface experiment archived, recalled, then re-archived a child and received `no rollout found`. | Dynamic revalidation pending; the latter is an old runtime experiment, not established by broker source alone. [Runtime version][runtime-lock]. | — |
| D10 | **“Continue” after closing a display may not restart work**: in an old model sample, the parent only said the child had stopped and did not spawn a replacement. | Dynamic revalidation pending; current prompts still require a user request before repeating work. [Closure prompt][closed-text]. | Cannot distinguish whether “continue” was sent before or after closure; avoid repeating work the user just closed. |
| D11 | **Closed-display blocklist can grow indefinitely**: without parent archiving or broker restart, closed-subagent records remain in memory. | In-memory list and lifecycle remain. [List][closed-map], [session cleanup][session-gone]. | Estimated at 10 closures daily, reaching 10MB in about 7 years; no cap accepted. |
| D12 | **Returning control depends on model participation/availability**: even when the user finishes the task, the model must inspect results; unavailable models fail preflight. Child returns can relay into an existing parent turn. | Implementation boundary remains. [Preflight/return][relay]. | Keep only “return and continue,” letting the model judge completion. |

### E. Cleanup, display, and diagnostics

E7 retains corrected triggers. “Stopping a turn always waits 15 seconds” is no longer a current issue statement.

| ID | Issue, trigger, and impact | Verification status and evidence | Recorded deferral reason |
| --- | --- | --- | --- |
| E1 | **Deleting a parent may leave a taken-over child display [inference]**: if a child is under user takeover during deletion, it may become undiscoverable afterward while retaining capacity and app rights. | Code risk remains; not reproduced on-device. [Post-deletion cleanup][native-observe], [listing children during cleanup][session-gone], [descendant query][descendants]. | Delete entry only on native interface page; handle when encountered. |
| E2 | **Display-close blocks disappear after broker restart [inference]**: lost blocklist may let a parent wake a previously closed child to work and request displays again. | Code boundary remains; blocklist is memory-only. [List][closed-map], [turn blocking][turn-started]. | — |
| E3 | **Pages actively used by users can be reclaimed**: archiving/deleting discoverable session displays or Codex disconnection also reclaims taken-over/paused displays; fullscreen pages disappear and later actions error. Missed children after deletion are E1. | Design behavior remains. [Session cleanup][session-gone], [disconnect][codex-gone]. | Cleanup follows session/connection lifecycle uniformly. |
| E4 | **Displays releasing during Codex disconnect may remain**: a display in cleanup is skipped during disconnect; afterward it becomes idle and retains physical capacity until reuse or the two-hour expiry. | Code path remains. [Skip releasing displays on disconnect][codex-gone], [idle destruction][idle-close]. | Avoid release/closure races accidentally closing a display just reassigned. |
| E5 | **Abnormal destruction may leave empty Recents cards**: broker restart or display-process crash destroys pages, but old emulator tests retained pageless records in Recents. | Dynamic revalidation pending; old Android experiment retained; current destruction flags still destroy display content. [Display flags][display-flags]. | Ledger is memory-only and does not persist Android task IDs. |
| E6 | **Concurrent request error may be inaccurate after display crash**: another queued request for the session may be withdrawn with `TURN_ENDED` rather than `SCREEN_LOST`; a simultaneous display-action failure reports display loss. | Code path remains. [Loss handling][lost], [wait return][await]. | Display-action response already explains loss; handle when encountered. |
| E7 | **Corrected: stop and explicit app release handle touch differently.** Normal stop or turn-end receipt actively cancels touches and interrupts phone-tool threads. If the turn still runs and calls `release_apps` directly after `DOWN`, it does not actively clear broker-held touch state; absent `UP` / `CANCEL`, the 15-second idle timeout remains. Failed cancellation also retains state for retry. | Original “always finish before releasing, up to 15 seconds” summary was incorrect. Existing touch host tests passed 44 checks. [Stop][native-stop], [touch cancellation][gesture-events], [timeout/cancel][pointers], [app release][release]. | Display release still waits for the input lock; 15 seconds is the idle deadline for unfinished touches. |
| E8 | **Autoformatted inputs may falsely report failure**: phone-number fields or other formatted inputs add spaces/formatting, making readback differ and returning `TEXT_READBACK_MISMATCH`. | Comparison path remains; specific app triggers require UI validation. [Text readback][text-readback]. | — |
| E9 | **Capturing a new display without rendered frames times out**: before opening an app, a frameless capture waits 5 seconds server-side; an old end-to-end sample took about 6 seconds. | No-frame timeout path remains; end-to-end duration retains old-environment meaning. [capture][capture]. | Old Pixel 9 real-model experiments opened apps first; expected infrequently, handle if encountered during acceptance. |
| E10 | **Failure logs are overwritten by the memory buffer**: dedicated-display warnings go only to logcat, without module-owned disk persistence/upload. Under old 11-09 emulator test load, a 2MiB buffer retained about 3 hours; Pixel 9 unmeasured. | Logging path remains; retention duration is historical. [Device warnings][logs]. | — |
| E11 | **Chat/gateway previews omit child displays**: filtering by current `threadId` shows only the main session's own display. | Filtering path remains. [Chat preview][chat-preview], [gateway preview][gateway-preview]. | Showing subagent displays remains a product decision. |
| E12 | **App queues have no dedicated status copy**: `waiting_for_app` falls back to generic UI status without identifying queuing. | Copy gap remains. [WorkspaceUi.status][ui-status]. | — |
| E13 | **Wait reasons may mix Chinese and English**: when system and model-card languages differ, the first clause follows system language and the second retains original card text. | Concatenation path remains. [Wait reason][wait-text]. | — |

## Previously fixed items

**R1: Existing queued requests could still be granted after an app was disabled.** Fixed in `573c32a`, excluded from the 59 current items above. Saving a disabled-app policy reprocesses the waiting room; requests containing disabled apps return `APP_NOT_ALLOWED`. Granted requests not yet returned recheck policy too. Evidence: [policy save][policy-config], [grant check][grants], [return check][await].

## Record sources

Design tradeoffs, deferral decisions, and historical experiments come from the original dedicated-display design, 2026-11-11 review, and ticket 12 component-emulator acceptance records, consolidated here. Implementation references below point into this repository; line numbers match the implementation commit at the top. Historical runtime-internal observations retain their original version scope. Static broker code does not replace runtime or device retesting.

[native-cards]: ../system/assistant/broker/org/agentos/lab/NativeBridge.java#L231
[jobs]: ../system/assistant/src/org/agentos/assistant/BackgroundScreenTasks.java#L198
[detail]: ../system/assistant/src/org/agentos/assistant/WorkspaceDetailActivity.java#L80
[control]: ../system/assistant/broker/org/agentos/lab/WorkspaceControl.java#L34
[revoke]: ../system/assistant/broker/org/agentos/lab/WorkspaceControl.java#L230
[screen-close]: ../system/assistant/broker/org/agentos/lab/DedicatedScreens.java#L1210
[request]: ../system/assistant/broker/org/agentos/lab/DedicatedScreens.java#L707
[withdraw]: ../system/assistant/broker/org/agentos/lab/DedicatedScreens.java#L899
[finish-turn]: ../system/assistant/broker/org/agentos/lab/DedicatedScreens.java#L1267
[screen-check]: ../system/assistant/broker/org/agentos/lab/DedicatedScreens.java#L905
[open-access]: ../system/assistant/src/org/agentos/assistant/AgentOpenAccessProvider.java#L28
[skills]: ../system/assistant/broker/org/agentos/lab/AgentSkills.java#L48
[relay]: ../system/assistant/broker/org/agentos/lab/WorkspaceExecutionRuntime.java#L58
[receipt]: ../system/assistant/broker/org/agentos/lab/WorkspaceExecution.java#L165
[receipt-view]: ../system/assistant/broker/org/agentos/lab/WorkspaceExecution.java#L203
[preinstalled]: ../system/assistant/assets/skills/taku-open-access/SKILL.md#L128
[grants]: ../system/assistant/broker/org/agentos/lab/DedicatedScreens.java#L833
[open-app]: ../system/assistant/broker/org/agentos/lab/PhoneNativeTools.java#L121
[task-query]: ../system/assistant/broker/org/agentos/lab/PhoneTasks.java#L19
[user-holds]: ../system/assistant/broker/org/agentos/lab/DedicatedScreens.java#L1006
[permissions]: ../system/assistant/broker/org/agentos/lab/NativeBridge.java#L250
[native-tick]: ../system/assistant/broker/org/agentos/lab/NativeBridge.java#L152
[contract]: ../AGENTS.md
[reclaim]: ../system/assistant/broker/org/agentos/lab/DedicatedScreens.java#L1537
[ui-list]: ../system/assistant/broker/org/agentos/lab/DedicatedScreens.java#L1307
[hand-over]: ../system/assistant/broker/org/agentos/lab/DedicatedScreens.java#L582
[leave]: ../system/assistant/broker/org/agentos/lab/DedicatedScreens.java#L1162
[await]: ../system/assistant/broker/org/agentos/lab/DedicatedScreens.java#L801
[turn-ended]: ../system/assistant/broker/org/agentos/lab/DedicatedScreens.java#L1072
[native-events]: ../system/assistant/broker/org/agentos/lab/NativeBridge.java#L176
[models]: ../system/assistant/broker/org/agentos/lab/ModelConnection.java#L130
[runtime-lock]: ../image/runtime-assets.lock.json
[tools-screen]: ../system/assistant/broker/org/agentos/lab/PhoneNativeTools.java#L107
[queue-refusal]: ../system/assistant/broker/org/agentos/lab/DedicatedScreens.java#L711
[policy-config]: ../system/assistant/broker/org/agentos/lab/DedicatedScreens.java#L296
[native-stop]: ../system/assistant/broker/org/agentos/lab/CodexAppServer.java#L172
[workers]: ../system/assistant/broker/org/agentos/lab/NativeBridge.java#L264
[job-actions]: ../system/assistant/src/org/agentos/assistant/BackgroundScreenTasks.java#L157
[job-stop]: ../system/assistant/src/org/agentos/assistant/BackgroundScreenTasks.java#L253
[subagents]: ../system/assistant/broker/org/agentos/lab/PhoneSubAgents.java#L44
[subagent-parent]: ../system/assistant/broker/org/agentos/lab/PhoneSubAgents.java#L19
[relay-guard]: ../system/assistant/broker/org/agentos/lab/WorkspaceExecutionRuntime.java#L163
[relay-rejected]: ../system/assistant/broker/org/agentos/lab/WorkspaceExecutionRuntime.java#L198
[closed-text]: ../system/assistant/broker/org/agentos/lab/PhoneSubAgents.java#L65
[closed-map]: ../system/assistant/broker/org/agentos/lab/DedicatedScreens.java#L84
[session-gone]: ../system/assistant/broker/org/agentos/lab/DedicatedScreens.java#L1091
[native-observe]: ../system/assistant/broker/org/agentos/lab/NativeBridge.java#L154
[descendants]: ../system/assistant/broker/org/agentos/lab/PhoneSubAgents.java#L80
[turn-started]: ../system/assistant/broker/org/agentos/lab/DedicatedScreens.java#L506
[codex-gone]: ../system/assistant/broker/org/agentos/lab/DedicatedScreens.java#L1130
[idle-close]: ../system/assistant/broker/org/agentos/lab/DedicatedScreens.java#L1258
[display-flags]: ../system/display/src/org/agentos/lab/VirtualDisplayHost.java#L129
[lost]: ../system/assistant/broker/org/agentos/lab/DedicatedScreens.java#L965
[gesture-events]: ../system/assistant/broker/org/agentos/lab/PhoneGestures.java#L142
[pointers]: ../system/assistant/broker/org/agentos/lab/PhoneGestureSession.java#L23
[release]: ../system/assistant/broker/org/agentos/lab/DedicatedScreens.java#L1036
[text-readback]: ../system/assistant/broker/org/agentos/lab/PhoneTextInput.java#L100
[capture]: ../system/display/src/org/agentos/lab/VirtualDisplayHost.java#L80
[logs]: ../system/assistant/broker/org/agentos/lab/DedicatedScreensOnDevice.java#L20
[chat-preview]: ../system/assistant/src/org/agentos/assistant/ChatWorkspacePreviewProtocol.java#L113
[gateway-preview]: ../system/assistant/src/org/agentos/assistant/AgentGatewayProvider.java#L452
[ui-status]: ../system/assistant/src/org/agentos/assistant/WorkspaceUi.java#L81
[wait-text]: ../system/assistant/broker/org/agentos/lab/DedicatedScreens.java#L1408
