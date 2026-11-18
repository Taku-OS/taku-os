# Taku Memory 0.14.1: revised descriptions for Home AI

2026-11-09 (UTC+8), dedicated test AVD `TakuRecall` (`emulator-5634`). Taku Memory 0.14.1 (versionCode 17); Home comparison used `origin/vela/TAKU-new-UI` at `b8a1368`. Artifact SHA-256s are in [acceptance.json](acceptance.json).

**Status:** Only the descriptions sent to Home AI (`feed/generate`) changed. Memory's validation, fallback, and retraction behavior are unchanged. This had not run on a phone with Taku Home installed; “Let Home AI design cards” remains off by default.

## Changes

Passing 0.14.0 descriptions to Home's generation code exposed three problems:

- **Structural errors:** Pager/Dots were malformed or absent from the root tree, so Home rejected them.
- **Copied numbering:** More than half the cards copied description numbering such as “1. 2. 3.” into their content.
- **Unhelpful pinned labels:** Almost every summary described the card itself, such as “Bedtime reminder | List 8 items” (translated). Home uses its first 24 characters as the pinned label.

This version revises `FeedCards.describeEvening` / `describeMessage` / `rules` and their corresponding English/Chinese strings:

1. **Explicit element tree:** A Column root, with elements attached in order; paginated cards attach the heading, Pager, Dots, and button row.
2. **Explicit pagination syntax:** Pager uses `{"index":{"$bindState":"/ui/page"}}`; Dots uses `{"count":pageCount,"index":{"$state":"/ui/page"}}`; `ui.state` uses `{"ui":{"page":0}}`. Here `pageCount` stands for the numeric page count.
3. **Unnumbered items:** List items with “· ” and explicitly request no numbering.
4. **Inset quotes:** Message cards quote with Inset, with the sender in a small line above.
5. **Exact summary:** Bedtime reminders use the same title/first item/“(N more)” pattern as fixed-layout cards; message reminders use the sentence Taku should say.
6. **Indented actions:** Format the actions JSON the model copies with indentation and remind it about closing brackets.
7. **Unescaped slashes:** Android writes `/` as `\/`; models sometimes copied an extra backslash, changing the message passed to Taku and failing validation. The historical example was a Chinese shared-drive/product/October-retention spreadsheet path. Description actions now leave slashes unescaped, with unchanged parsed JSON.

## Results

The same 26 reminders (21 bedtime reminders and five message reminders) were passed to Home's own `prepareFeedGeneration`:

- `gpt-6-luna` through Codex, no tools, low effort;
- Home's latest component catalog;
- two to four runs per wording variant, with 26 descriptions each.

“Usable” means both Home generation validation and Memory validation passed ([per-run summary](wording-trials.json)):

| Wording | Usable | Structural errors | Malformed JSON | Rejected by validation | Cards copying numbering |
| --- | --- | --- | --- | --- | --- |
| 0.14.0 baseline, four runs | 91 / 104 (88%) | 7 | 6 | 0 | 48 / 91 |
| Explicit structure/pagination, no numbering, Inset quotes | 46 / 52 | 4 | 2 | 0 | 0 |
| Also require exact summary | 43 / 52 | 5 | 3 | 1 | 0 |
| Also specify paginated element tree and indent actions, four runs | 99 / 104 (95%) | **0** | 5 | 0 | 0 |
| 0.14.1 device-generated descriptions, before slash fix | 49 / 52 | 0 | 1 | 2 | 0 |
| **Final 0.14.1** ([descriptions](describe-asks.json), [individual results](generation-runs.json)) | **49 / 52 (94%)** | **0** | 2 | 1 | **0** |

- **Structural errors:** None in eight runs/208 descriptions after specifying both the element tree and pagination syntax. Pagination syntax alone, without saying where Dots attaches, more often left Dots outside the tree.
- **Remaining failures:** Most were malformed JSON brackets (`INVALID_MODEL_OUTPUT`) across different reminders. One message card rewrote the original message and Memory rejected it. Both pre-fix slash rejections came from night 10.
- **Up to two attempts:** Memory retries once. A simple calculation using a 94% per-attempt success rate gives about 99.6% within two attempts; this estimate is not independent measured end-to-end acceptance. Fixed-layout fallback remains available.
- **Pinned labels:** Most baseline labels described the card, such as “Bedtime reminder | List…”; revised labels contain the reminder itself, for example “Handle integration tonight: reply to Kevin promptly about whether integration testing can proceed” (translated).
- **Latency:** Median about 25 seconds/card, unchanged. Average description length increased by about 260 characters, from about 1,390 to 1,650.

On the **test device**:

- All seven component suites passed ([output](store-tests.txt)); StoreTests had **322** checks. New checks cover unnumbered items, indented actions, explicit element tree, summary equality with fixed-layout cards, and unescaped slashes with identical parsed values.
- The `feed/generate` stand-in was updated to read indented actions. The description path was rerun using a 0.14.1 debug APK, with the same outcomes as 0.14.0 ([log](describe-e2e-logcat.txt)): valid cards remain; rewritten Taku messages fail twice then use fixed-layout fallback; rejected generation falls back after two attempts; expired cards retract on time.

## Reproduction

- **Descriptions:** Run `describe-preview.sh` from the 0.14.0 record on a debug build.
- **Generation and validation:** Use that record's `generate-real.mjs` and `CheckRun.java`.
- **Wording variants:** Generate from 0.14.0 descriptions with [prompt-variants.py](prompt-variants.py).

## Known limitations

- **Results apply only to `gpt-6-luna` through Codex**, not the actual model/transport used by Home's on-device `agent-ui.text`.
- **Remaining failures mostly involve JSON brackets.** Prompt wording has limited scope to help; Home-side retry or format repair is likely more effective.
- **More detailed layout instructions constrain Home AI's design freedom.** Explicit element-tree/pagination rules trade some flexibility for avoiding structural errors.

[Historical language evidence](../HISTORICAL-LANGUAGE.md) documents the intentionally retained Chinese-locale raw inputs, outputs, and reproduction scripts for this historical review.
