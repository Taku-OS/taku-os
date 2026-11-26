# Dedicated screens: background-screen list interface for UI

Design dated 2026-11-05, **partially implemented** (see updates below). Branch `feat/dedicated-screens`, based on main `8506bd2`; code governs implemented portions.

## Updates

| Date | Change |
| --- | --- |
| 11-06 | One screen counts as one task, with one card per row |
| 11-07 | `list` adds top-level `limits`: accepted `configure` ranges, used by settings for limit choices |
| 11-07 | `waitReason.text` follows system language: Chinese for Chinese systems, English otherwise |
| 11-08 | `waiting_for_user` / `WAITING_FOR_USER`: `text` combines localized “Waiting for your answer:” with original card text |
| 11-08 | Takeover/pause add `user_control`, `paused`, and transitional `stopping`; `controlOwner`, `controlRevision`, `canResume` are authoritative; `mainThreadId` now resolves the main conversation |
| 11-09 | Detail-page Close (`release`) works on dedicated screens and removes the row from `screens`. Closing a subagent's screen prevents that subagent from further work; closing the main conversation's screen stops only its running turn |
| 11-10 | Remove `status=unavailable` and `waitReason=ERROR`: on screen-process failure, broker restart, or Codex disconnect, broker removes the screen/row rather than retaining an error state; a new request creates a new row. The list therefore does not show those error reasons |
| 11-10 | Ticket 11 removes the old ledger/explicit binding: `executionScreen` operations (`bind`, old `list`, `release`, `releaseStale`) are deleted; `workspaces` and `state.executionScreen` share the dedicated-screen ledger |
| 11-12 | Spec 5: turn cleanup attempts task removal at most twice, then waits for confirmed screen closure; if closure also fails, retain `cleanup_failed` in the list and automatically retry closure every 30 seconds |

Implemented fields include `mainThreadId`, `taskTitle`, `packageName`, `status`, `waitReason`, `controlOwner`, `controlRevision`, `canResume`, and `limits`. The status table below lists all current values. If the broker cannot query Codex, `mainThreadId` initially falls back to the screen's `threadId`.

Each conversation that needs the phone gets its own background screen. Ordinary turn completion returns that screen for reuse. A successful **Return & continue** turn or a turn that successfully opens an app and leaves it available can retain its workspace in `ready`. A read-only observation does not change this outcome. The Feed shows occupied screens, with one card per main conversation or subagent. Idle screens are hidden.

## Interface

Reuse the existing request, without adding another interface:

```json
{"op":"workspaces","action":"list"}
```

The response structure remains (`schema`, `policy`, `screens`), with added per-row fields. `screens` lists occupied conversation screens and failed occupancy where both cleanup and closure failed; idle screens and normally closing screens are absent.

`cleanup_failed` means the turn ended but screen, apps, and capacity are unreleased. `lastError` starts with `SCREEN_CLEANUP_FAILED` and includes the closure error; `heldApps` lists retained access rights; `cleanupRetryDelayMs=30000` is the delay from each failure to the next automatic closure retry, not a completion countdown. `stopping=true`, `admitted=false`, `canResume=false`; the failed screen cannot be taken over, returned, or operated. A background thread retries without holding the ledger lock, waiting 30 seconds after each failed closure until confirmed success removes the ledger/resources. Archiving or Codex disconnection does not restart cleanup of that failed occupancy. Broker restart retains existing in-memory-ledger/startup-cleanup boundaries.

GUI and standard `workspaces/list` read the same failure state; conversation state and `screen_status` expose it too. With only failed rows, Android tasks are not queried. If normal rows coexist and task querying still fails, retain the list with empty `packageName` / `topApp` meaning unknown current page. Failed screens do not count toward `idleScreens`. This fallback applies only to whole-screen cleanup from `turnEnded` / `sessionGone`; explicit closure and partial app revocation retain their paths.

Optional `threadId` reads only one conversation; no screen returns empty `screens`. `state.executionScreen` uses the same ledger and returns `{"bound":false}` without a screen. Models request screens/apps through `request_apps` and release through `release_apps`; phone actions without a dedicated screen return `APPS_NOT_REQUESTED`. `returnControl` is rejected; returning uses `returnAndResume` and existing receipts.

Saving `blockedPackages` takes effect immediately. Queued requests containing disabled apps return `APP_NOT_ALLOWED`. Automatic revocation waits for current input to settle and clears unfinished gestures, then cleans only disabled-app tasks/access. Other allowed apps, current turn, and dedicated screen remain, without triggering subagent screen-close interception. Releasing the last app clears whole-screen tasks and begins the existing 30-second empty-screen grace period; turn completion makes it idle. User-controlled screens remain. Main/subagent conversations receive permission changes and actual cleanup results before their next phone-tool call, and that call does not execute; the Agent chooses the next step using remaining permissions. Pending reminders clear at turn end. User-initiated closure still stops the turn and closes the whole screen.

`list` and `configure` return recent `revocations`, each with `threadId`, `screenKey`, `generation`, `taskTitle`, `packageNames`, and `status`. States are `pending`, `released`, `retained`, `failed`, or `superseded`. Cleanup receipts additionally contain `released` (apps actually released), `heldApps` (apps still held on the original screen), and `screenRetained` (whether the conversation retained that screen at cleanup). `released` means app cleanup finished, not turn stop/screen closure. `failed` includes `error` and retains uncleaned occupancy; partial cleanup still lists released portions in `released`. `superseded` means screen identity changed or app access was restored before work began. System notifications use the same actual results. Device `workspaces/list` and `workspaces/configure` use the same broker; commands are in preinstalled `taku-open-access`.

Ticket 11 device regression: `python3 system/assistant/tests/dedicated-screens/e2e/s8.py SERIAL`, covering production RPC ownership checks, list/conversation-state consistency, configuration, and screen-close release. `s5.py` verifies return-and-resume; `s7.py` injects restart/screen-process death. Default-screen full views/chat previews have separate visual checks; a component emulator lacking `taku_display` init service cannot count these as complete-ROM cold-boot acceptance.

## Per-row fields

| Field | New/existing | Meaning | Feed use |
| --- | --- | --- | --- |
| `mainThreadId` | New | Main conversation owning the screen; equals `threadId` for its own screen. Nested subagents resolve to the topmost main conversation | Optionally group cards from one main conversation |
| `taskTitle` | Existing, now populated | One-sentence purpose supplied by this conversation when requesting apps, such as “Book a table for two tomorrow evening on Dianping”; per-screen and updated on subsequent requests. Normally nonempty; existing `WorkspaceUi.taskLabel` reads it and falls back if empty | Task name |
| `packageName` | Existing, changed meaning | Currently open app on the screen (top task's app), empty before opening one. UI resolves display names from package, as existing `WorkspaceUi.appLabel` does | Current app |
| `status` | Existing, changed values | Conversation state on this screen, below | Task state |
| `waitReason` | New | Waiting reason or `null`; `code` is machine-readable, `text` is a displayable localized short sentence (Chinese system → Chinese, otherwise English) | Waiting/error reason |
| `threadId`, `screenKey`, `generation` | Existing | Occupying conversation plus screen identity/generation; all three required to open/take over/return the screen (`WorkspaceUi.preview`, `WorkspaceUi.detail`) | Background-screen entry |
| `allocationId` | New | Identifies one allocation of the display. The ID changes when the broker assigns the same display again | Detect replacement of an open preview |
| `controlOwner`, `controlRevision` | Existing | Owner (`agent` / `user` / `paused`) and control version, used by takeover/tap as before | Takeover |
| `canResume` | Existing | Whether Return is available: user-owned or paused and not transitioning into takeover/pause | Return button |

Other existing fields remain returned but new UI need not depend on them. `label` becomes an empty string.

### status values

| Value | Meaning |
| --- | --- |
| `ready` | The turn completed and the live workspace is available to preview or take over |
| `running` | Working |
| `waiting_for_app` | Queued for an app (new) |
| `waiting_for_user` | Waiting for card interaction: answer, confirmation, or dismissing an app occupied by the user |
| `user_control` | User has control |
| `paused` | Paused |
| `stopping` | Stopping after takeover/pause selection, during the estimated few seconds until Agent settles |
| `cleanup_failed` | Turn cleanup and screen closure both failed; screen/apps retained, closure retried every 30 seconds and removed after confirmation |

Old `idle`, `awaiting_resume`, and `rebind_required` no longer appear under dedicated screens.

### waitReason.code values

| code | When | Example text (English rendering) |
| --- | --- | --- |
| `WAITING_FOR_APP` | Queued for app access | Waiting for WeChat; 1 ahead |
| `WAITING_FOR_USER` | A card is pending; localized “Waiting for your answer:” plus original model-written question/title | Waiting for your answer: Meituan is in your recent tasks. Dismiss it, then select Continue |

## Example response

IDs are illustrative; actual IDs are UUIDs. Other existing fields are omitted.

```json
{
  "schema": "taku.workspaces/1",
  "policy": {"allowBackground": true, "maxActiveScreens": 10, "blockedPackages": []},
  "screens": [
    {
      "mainThreadId": "T-main", "taskTitle": "Book a table for two tomorrow evening on Dianping",
      "threadId": "T-main",
      "screenKey": "task-1d551d0d288b4095ba16", "generation": "G-1",
      "controlOwner": "agent", "controlRevision": 2,
      "packageName": "com.dianping.v1",
      "status": "running", "waitReason": null
    },
    {
      "mainThreadId": "T-main", "taskTitle": "Tell Wang on WeChat that the reservation is confirmed",
      "threadId": "T-sub1",
      "screenKey": "task-064fd0ef01744c109a8c", "generation": "G-2",
      "controlOwner": "agent", "controlRevision": 0,
      "packageName": "",
      "status": "waiting_for_app",
      "waitReason": {"code": "WAITING_FOR_APP", "text": "Waiting for WeChat; 1 ahead"}
    },
    {
      "mainThreadId": "T-jev", "taskTitle": "Reply to the work-group message",
      "threadId": "T-jev",
      "screenKey": "task-0b1c5246ded246859a5e", "generation": "G-3",
      "controlOwner": "user", "controlRevision": 5,
      "packageName": "com.tencent.mm",
      "status": "user_control", "waitReason": null
    }
  ]
}
```

Each row is a task. The first two share the same main conversation (`mainThreadId`): it books on Dianping while its subagent waits for WeChat. The third is a Jev background task currently controlled by the user.

## Other UI-related changes

- **One return operation remains**: detail-page Return uses return-and-resume (`returnAndResume`, with receipts as in the full-screen view). Broker rejects `returnControl`. After return, the Agent observes the screen and judges whether work is complete.
- **Settings limit**: 1–10, default 10. Broker supplies top-level `limits` in `list` (`{"maxActiveScreens":{"min":1,"max":10}}`); settings derives choices instead of hardcoding.
- **Copy for the new state**: `waiting_for_app` is new; existing `WorkspaceUi.status` lacks corresponding copy.
- **Close**: detail-page Close now works on dedicated screens. Closing a subagent's screen stops it from continuing; UI decides whether the confirmation explains this.

## Open decision

- **Show subagent screens in chat previews?** Current chat/gateway previews filter by `threadId` and show only the main conversation's own screen. To include subagents, broker can make `list` filtering by `threadId` also match `mainThreadId`.
