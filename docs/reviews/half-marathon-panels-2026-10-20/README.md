# Half-marathon generic panel: final emulator acceptance

2026-10-20 · `emulator_verified` · Owner: Li Danxin; shared-capability support: Liu Hai. UI labels, requests, and source names below are English translations/transliterations of the recorded Chinese test material; original evidence retains its actual bytes.

Completed: user request → native confirmation for date/days → current Agent creates generic panel → independent-background Keep/Baidu Maps searches → incremental writeback → UI complete/undo → matching API → reopen persistence. Original half-marathon app/data remain; this plan is content in the native system Panel host, without a new APK.

## Versions

- Branch `codex/remote-update-check-20261019`; PR base `codex/agent-interface-20261018`, SHA `bc21fe297001e579e3cf1d6a29962fe92e8b11ca`; initial independent baseline `d6556c1fd4a6f8ee0824727363f51de0a599115f`.
- Final functional source `8ae4c6f0702766d84979fd2af68d93537d4c9495`; only evidence organization afterward.
- Confirmation card was generated before component upgrade; confirmation submission, creation, initial cross-app search used merged components; continuation/final reopening used final repaired components. These are not one uninterrupted test at one SHA.
- `emulator-5582 / AgentOS_ImageCheck`, Android 16 / API36 / arm64 / userdebug; Assistant 57 (20261020-panel-magpie), Keep 9.1.80, Baidu Maps 22.0.40, original half-marathon app 0.3.0. Exact versions/source/installed hashes: [artifacts.json](artifacts.json).

## Reproduction input

Synthetic acceptance conditions, not an actual user training profile. The request below is translated from Chinese.

```text
I am preparing for a half marathon. Create a new training/nutrition panel without changing existing plans. Ask race date and weekly training days through a confirmation card first. Other conditions: first race, finish goal; currently two runs/8 km weekly, longest 5 km, no known exercise restrictions; Xuhui, Shanghai, CNY 50 per meal, no dietary restrictions. After confirmation, generate/open the panel, then use Keep on the independent background display for pre-run warmup/post-run stretching and Baidu Maps for Xuhui gyms/light-meal restaurants. Inspect results, write them incrementally to the same panel, retain actual names/source/search terms/capture time, and mark unverified fields pending. No subscription, purchase, or reservation. Name it “Half-marathon end-to-end acceptance.”
```

On the actual confirmation card, select `2027-01-17` and `3` weekly days, then confirm. No panel is created before answers; submission continues the same conversation. Reproduction should create a fresh panel rather than overwrite this record.

## Expected and actual

| Check | Actual result |
| --- | --- |
| Confirmation→creation | Native date/number fields usable; Agent created 6 overview/table/checklist/sources modules, with 5 checkable actions |
| Open before execution | Panel first showed actual running status; Keep/Baidu results arrived in batches, then actual completed status |
| Actual Keep search | Official pre-run warmup K1/8 minutes/8.1; post-run stretch K1/5 minutes/8.7; saved values match screenshots |
| Baidu search/details | KPRO light meals (Xuhui Greenland Being Fun City), 562 Dong'an Road; SUPERMONKEY fitness (Bailian Xuhui Commercial Plaza), floor 4 room 403, 2038 Huashan Road; detail names/addresses matched saved data |
| Complete→API | Native check `actions/easy1`, v6→v7, `completed=true` with actual completion time, other business content unchanged |
| Same-panel continuation | Uncertain original name rechecked using same source ID/pending marker; clear-name gym appended at v9 without rebuilding, retaining completion time/other content |
| Reopen completed | Final components force-stopped Assistant, reopened same panelId; whole v9 object unchanged, UI 1/5 |
| Undo→API→reopen | Native uncheck, v9→v10, `completed=false / completedAt=null`; force-stop/reopen retained whole v10, UI 0/5 |
| Original data/apps | Original 3 generic panels unchanged by object; old half-marathon file byte-identical, SHA `913b0605653da9086aeff4860137b5856493952a040db58d8fbea5520de789e0`; other installed package versions unchanged |

Panel `cab72800-231f-4983-95c8-f7298ea37599`, thread `01a15758-ad0c-7262-982b-a413a2d3f9be`; confirmation task `1792467847615`, final search task `1792469745508`. Device Agent `taku_panel` and actual UI performed business writes; development tools only read API, never fabricated results through file/API writes.

All [12 offline evidence checks](checks.json) passed: completion, source-update preservation, two reopens, undo, original data, persistence, source fields, five actual phone-tool types, actual keyword input, and 5 sources matching successful upsert parameters/receipts. Actual tools: `device_info / observe_background / open_app / tap / type_text`. These check collected evidence, not infer merchant-name truth from scripts.

## Findings and fixes

1. First run produced a plan table without tasks, preventing complete/undo. `taku-panels` now requires stable-ID checklists for ongoing/completion-tracked plans; second run generated five actions. Pure informational queries do not require tasks.
2. One full gym name could not be reliably read; same source ID retained with “full name pending,” then a clear result added through panel conversation. Guesses were not facts. Fees/packages/nutrition/actual-origin distance remain pending; emulator distance is not user distance.
3. Recovery mistook grep containing the main-class string for Broker. Now verifies actual system app_process executable/main-class argument; 11 process regressions plus actual/idempotent recovery passed.
4. Image-heavy conversation reopening first timed out on resume, then revealed full-history response exceeding 8Mi characters. Internal reads use metadata, resume uses supported `excludeTurns:true`, and metadata saves retain bounded projection without raising message limit. Failed reads retain request/retry, do not show stale execution or create new conversations. Final same image-heavy thread continued and wrote a new source.
5. Fixed cross-conversation callbacks, premature sending before first snapshot, and treating terminal tasks as steerable. Sending requires ready conversation/editor; fallback running state must match actual task/request/thread/started turn.

## Checks

| Command/scope | Result |
| --- | --- |
| `npm run verify`, final source | 50 passed, 1 existing platform skip, no failures |
| `npm run build:android`, final source | Assistant/Broker/Display/Overlay/app-kit built; device updated Assistant/Broker only |
| `npm test --prefix examples/scenarios/half-marathon-panel` | 36 old half-marathon business regressions passed |
| Pure-fixture JAR on explicit 5582 | `AgentViewProjectionTest: 8 cases`, `NativeConversationTest: 58 assertions` passed without business/model access |
| Final offline evidence | 12 passed, checks.json |

## Images and original-speed videos

- [Confirmation](confirmation.png) · [Panel opened with actual execution state](panel-running.png)
- Original Keep observations: [warmup](keep-warmup.png), [stretch](keep-stretch.png), original PNGs extracted from two tool returns in this exact test conversation, not redrawn.
- Actual Baidu details: [light meals](baidu-meal.png), [added gym](baidu-gym.png)
- Actual panel: [Keep sources](panel-keep.png), [Baidu merchants](panel-places.png)
- [Completed reopen](completed-reopened.png) · [Undone reopen](undone-reopened.png)
- [Baidu original-speed segment](baidu-original-speed.mp4): 146.50 seconds, original segment03; [state/reopen segment](state-original-speed.mp4): 123.99 seconds, segment09. Original-file copies without editing/speed changes.

Multiple recording segments have gaps; two selected PR clips do not constitute uninterrupted coverage. Second-run 10 originals, API snapshots, failures/tool evidence remain in `out/panels/half-marathon-e2e-20261020-111026/`, indexed/hashed in artifacts.json. Full chats/tools, accounts, app caches, APKs/ROM excluded from PR.

## Manual intervention and scope

Development operators entered synthetic requests/confirmation, checked/unchecked, opened/closed pages, viewed background mirror, and collected evidence through visible native controls. Current device Agent performed third-party background taps/input. Existing user-completed login reused; no delegated login/subscription/purchase/reservation.

Records retain first-run missing tasks, one manual Broker start via existing commands during repair, managed component/skill updates, two name-QA conversations/retries. Host monitor also actually auto-recovered services after runtime failure; final healthy. Only this tool built-in skill copy synchronized, original backed up, installation-provenance receipts not fabricated.

Evidence fixture initially searched before scrolling to checkbox and did not click; failed snapshot retained, then relocated and undid successfully. Bounded projection collapses old tools, so offline evidence merges two actual snapshots by native item ID, preserving originals/source mapping. No fabricated tool results/business-state changes.

Only this device half-marathon flow/continuation/persistence recovery is established, not arbitrary apps/scenarios. Course details/fees/all cancel-repeat-confirmation faults, physical hardware, full ROM, and this round full reboot untested. Auto-recovery depends on Mac sign-in/ADB, not independent ROM startup. Code awaits human review/merge.
