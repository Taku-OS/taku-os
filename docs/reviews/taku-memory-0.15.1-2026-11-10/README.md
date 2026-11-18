# Taku Memory 0.15.1 acceptance: five bug fixes

See the [2026-11-13 supplemental acceptance](../taku-memory-0.15.1-2026-11-13/README.md) for later status. The following retains the November 10 verification scope.

Both #38 (0.11.0–0.15.0) and #33 (Home feed) had merged into main. Comparing Memory with actual main Home code (`FeedRepository.kt`, `FeedCard.kt`, `HomeFeedProvider.kt`) found four card bugs, then a fifth: new Taku Home could be observed as a memory source. This version fixes all five. See the [module guide](../../../system/memory/README.md).

## Fixes

| Bug | Cause | Fix |
| --- | --- | --- |
| Agents cannot read reminders | Documented `org.agentos.memory.open-access` forwards allowlisted methods, but omitted new `nudges`; documentation also placed card IDs in broker-only `nudge` | Allowlist `nudges`, document its command, and read IDs there |
| Emoji in names/reminders prevents card publication | Home counts UTF-16 units for pinned labels (≤24) and subtitles (≤32); emoji use two. Memory truncated by characters, causing `INVALID_CARD` and notification-only fallback | Make `cut` count UTF-16 without splitting emoji |
| Home AI cards can persist indefinitely | Generated cards lack their own expiry; retraction ran only after successful nightly organization or another reminder, so failure/offline/disabled organization could prevent it | Also maintain every 15 minutes: check locally first, retract only when due, read feedback only if cards exist from the last three days, retract everything when the switch is off |
| New Taku Home can become an observation source | Memory excluded itself and `org.agentos.assistant`, but new Home/chat use `org.agentos.agent`, preinstalled since #47. It appeared in app selection; selecting it could re-record Memory's own cards/Taku replies | `MemoryStore.observing` consistently excludes Memory, both Taku apps, system surfaces, and System UI even if selected; hide them from the app list and expose the list in `status.neverObserved` |
| Newest reminders of one type appear last | Home orders equal ranks oldest first, while same-type cards used fixed rank | rank = tier + time × 10⁻¹⁴ (about 0.018 then, increasing only 0.0003/year; tiers are 0.1 apart) |

## Checks

- **Component tests:** StoreTests adds five checks: UTF-16 truncation without splitting emoji; emoji-only names/reminders stay within pinned-label limits; newer same-type cards rank first without changing tier order; selected Taku/System UI packages still cannot be observed, including `org.agentos.agent`; `status` lists never-observed packages. Emoji cards also pass full card validation, including UTF-16 pinned length, components, and buttons. The test APK compiled but had not run on a device.
- **JVM:** Extracted source `cut` and `rank` functions passed all nine checks ([CutRankRun.java](CutRankRun.java), [cut-rank-jvm.txt](cut-rank-jvm.txt)): pinned labels ≤24 and subtitles ≤32 UTF-16 units without broken emoji; unchanged Chinese truncation; a card one minute newer ranks higher, with tiers still separate one year later.
- **Builds:** Debug, release, test, and stand-in APKs compiled.
- **Test device:** TakuRecall was not running because the Mac relay was inactive; seven component suites and end-to-end tests remained unexecuted. Scripts extend 0.15.0 with emoji in both name/reminder, newest-first ordering, and open-access reminder reads ([feed-e2e.sh](feed-e2e.sh), [check-e2e.py](check-e2e.py)).

## Remaining work

- At this checkpoint neither 0.15.0 nor 0.15.1 had run on the test emulator or a phone with actual Home.
- `feed/generate` accepts no expiry, so Memory must retract generated cards. Cards expiring while the phone is off remain until the next maintenance job after boot, up to 15 minutes. Home could support `expiresAt` during generation.
- Previously identified behavior remains: notifications/cards are not synchronized; message cards stay for 24 hours regardless of event time; immediate retraction after user deletion can invalidate Home's Undo; feedback does not yet influence reminders.

[Historical language evidence](../HISTORICAL-LANGUAGE.md) documents the intentionally retained Chinese-locale raw inputs, outputs, and reproduction scripts for this historical review.
