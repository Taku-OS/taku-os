# Feed durable-events integration

Source commit: `680c59524eb0bd925e1fd720f2590f6933cd1303`.
Target: `vela/TAKU-new-UI`, based on `a91e39569d99b269e483d2473ec3b57b38ccaef2`.
Earlier UI and GenUI changes in the source history were already present on the target.
Only the incremental Feed change was applied, with conflicts integrated into the current implementation.

## Integration decisions

- Retain the production push-only Feed, with no sample sources, current card renderer,
  long-press menu, removal animation, unlimited pins and visible-pin ordering.
- Store publications, preferences and append-only events in SQLite. Import the previous
  JSON state once. Both ordinary and generated publications can exceed 100 cards.
- Record selected values before local action execution. Preserve panel-card dispatch
  outside the Feed and the existing primary assistant/audio UI.
- Expose replay and live subscriptions through `content://org.agentos.agent.home-feed/events`,
  using the same repository as GUI actions. See the [contract](../../../system/agent-app/modules/taku-home/CONTRACT.md#event-stream).
- Adapt Memory's feedback consumer to finite durable replay. Acknowledge its checkpoint
  after feedback writes, deduplicate by cursor, and retain compatibility with an older
  installed launcher's snapshot log. Imported legacy feedback does not duplicate already
  saved entries. Accepted actions appear as `selected`, without claiming an external effect
  completed. The suggestions GUI and Agent `nudges` / `nudge` share that feedback.

## Validation environment

An independent API 37 AVD (`Taku_Audio_ROM_Merge`, `emulator-5690`) with host GPU.
The demo emulator was not changed. All test cards and reminders are synthetic; generation
checks supply card JSON and do not test remote model output or real recommendations.

## Results

- `npm run verify`: passed.
- Agent typecheck, lint and all 365 JavaScript tests: passed.
- `:taku-home:testDebugUnitTest`: 112 tests, zero failures.
- App API smoke tests: 9 passed.
- `npm run build:android`: release APK built successfully.
- Memory debug component and test builds: passed; on-device StoreTests: 340 checks passed.
- Legacy JSON upgrade: pinned card and 90 KB old selected value retained; restart did not duplicate import.
- Ordinary publication and supplied-JSON generation: 105 cards each retained, including the first pin.
- 16 pins and visible-row reorder: passed.
- 75 accepted actions, Unicode values, finite replay, live events, offline recovery, literal filters,
  fixed high-water checkpoints, oversized selection rejection and acceptance before failed local effects: passed.
- Actual Node caller read new and large legacy events; restart retained 210 cards and their history.
- Actual GUI radio selection produced the correct `origin=gui` event and selected value.
  The first GUI attempt hit the emulator's idle screen timeout; the GUI-only retry passed
  after adding an explicit wake/unlock step to the verification script.
- Existing push-only Feed, long-press menu and GUI Pin, pin reorder, hide/restore, supplied generation,
  restart persistence and test-card cleanup: passed.

- Memory-to-Feed integration: 75 accepted selections consumed, latest 20 feedback entries retained,
  public `nudges` matches the suggestions GUI, `selected`/`pinned` shown, reconnect and process
  restart do not duplicate feedback. Public `status.feedFeedbackCursor` matches the acknowledged
  Feed checkpoint and survives restart. The probe was corrected to use the debug/root provider
  for fixture creation and public `nudges` for computed reactions, then resumed successfully.

Device commands (from repository root, using an explicitly selected disposable AVD):

```sh
python3 system/agent-app/modules/taku-home/scripts/verify-feed-events.py \
  --serial emulator-5690 --adb "$ANDROID_HOME/platform-tools/adb" \
  --gui --out out/pr4-feed-sync/events
python3 system/agent-app/modules/taku-home/scripts/verify-feed.py \
  --serial emulator-5690 --adb "$ANDROID_HOME/platform-tools/adb" \
  --output out/pr4-feed-sync/interactions
python3 docs/reviews/feed-events-sync-2026-11-13/verify-memory.py \
  --serial emulator-5690 --adb "$ANDROID_HOME/platform-tools/adb"
```

The migration run additionally used `--legacy-id migration-regular --legacy-large-id migration-large`
after seeding those synthetic cards using the previous APK. Logs and captures are local under
`out/pr4-feed-sync/`. The Memory probe requires `TAKU_DEBUG_HOOKS=1 bash system/memory/build.sh`
and intentionally changes settings on its disposable AVD only.
