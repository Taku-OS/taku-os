# Taku Memory 0.15.0 acceptance: card lifecycle and feedback aligned with Home PR #33

Compared against Vela's `vela/TAKU-new-UI` branch at `006939c` (PR #33, not yet merged into main): `docs/feed-recommendation-api.md`, `HomeFeedProvider.kt`, `FeedRepository.kt`, `FeedActions.kt`, `FeedGeneration.kt`, and `system/agent-app/src/feed/feedCatalog.ts`. See the [module guide](../../../system/memory/README.md) for implementation changes.

## Aligned rules

| Home rule | 0.14.1 | 0.15.0 |
| --- | --- | --- |
| Expired cards still occupy the shared 100-card quota; callers must `feed/retract` | Retracts only Home AI cards; fixed-layout message cards remain | Registers all cards and retracts on expiry |
| User-deleted cards still consume quota | No handling | Retracts after readback discovers deletion |
| No callbacks; poll `feed/list`, with only 50 `recentActions` | Does not read feedback | Reads before publishing, after nightly organization, and when opening Memory; stores reminder `feedback` |
| No `feed/generate` reply means unknown outcome; inspect `feed/list` first | Publishes fixed-layout fallback without retry, potentially duplicating a generated card | Finds a card created after the call with matching buttons; retains only if validation passes |
| Full `feed/publish` evicts the oldest card, possibly another caller's | Falls back to fixed layout after `FEED_FULL` | Sends notification only on `FEED_FULL` |
| Sample cards rank 0.70–0.99; greater than 0.99 precedes every sample | 0.90–0.97, mixed among samples | 1.1–1.3 |
| Built-in pixel icons include `chat` | Message cards use `list` | Message cards use `chat` |

In `feed/action` logs, button operations are `action:<button id>` and `detail.effect` records the actual effect. A dismiss button such as Hide first records `remove`, then `action:hide` with `effect: dismiss`. Long-press Ask Taku records `action:ask-taku`. The guide's table says `ask-taku`, but actual code prefixes it with `action:`; Memory follows the code.

## Checks without a device

- **Card validation:** Updated 26 fixed-layout cards from 0.14.1 to the new ranks/icons and reran `validateFeedSpec` with the Vela branch's latest `feedCatalog.ts`. It added components/icons such as Cover, Chip, and Switch, while components used by Memory were unchanged. All 26 passed ([validate-latest.txt](validate-latest.txt)). Node directly ran copied files; npm was not run.
- **Readback:** Six JVM/org.json checks of `FeedFeedback` passed ([FeedbackRun.java](FeedbackRun.java), [feedback-jvm.txt](feedback-jvm.txt)): hidden cards record deletion/button events but not self-publication; duplicate reads are deduplicated; pin/Ask Taku are recorded; deletion missing from recent logs is recovered from `removed[]`; restored cards no longer count as deleted; only the latest 20 actions remain, with the last pin/unpin deciding state.
- **Builds:** Debug, release, test, and stand-in APKs compiled.

## Device checks

Not run. Other work saturated the Mac's CPU when TakuRecall started; it still had not booted after 25 minutes and the commit did not wait. Therefore 0.15.0 changes had not run on the test device: neither the seven component suites, including StoreTests rank/icon/readback additions, nor stand-in end-to-end tests ran. Scripts are ready for the next test-device session:

- [feed-e2e.sh](feed-e2e.sh): sends reminders through real paths, simulates user actions in the stand-in, then checks readback, retraction, card recovery, and disabling the switch.
- [check-e2e.py](check-e2e.py): verifies that output case by case.

## Remaining work

- Feedback is recorded, displayed, and readable by Agents, but does not yet change when Memory speaks or what it says.
- Home's 50-entry log is shared across callers. Frequent user actions combined with infrequent Memory polling can lose button actions. Deletions can be recovered from `removed[]`; pin and Ask Taku cannot.
- This had not run on a phone with actual Taku Home installed.

[Historical language evidence](../HISTORICAL-LANGUAGE.md) documents the intentionally retained Chinese-locale raw inputs, outputs, and reproduction scripts for this historical review.
