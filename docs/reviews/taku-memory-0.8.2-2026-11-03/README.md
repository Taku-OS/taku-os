# Taku Memory 0.8.2: first step toward memory-driven proactive reminders

2026-11-03 (Beijing time), Android 16 / API 36 / ARM64, dedicated test AVD `TakuRecall` (`emulator-5634`, `sdk_phone64_arm64` userdebug, SELinux Enforcing). Taku Memory 0.8.2 (versionCode 12), 0.8.1 Broker retained, assistant v74. Artifact SHA-256 values: [acceptance.json](acceptance.json).

**Status: emulator_verified.** Changed only how the user card selects “Plans and deadlines over the next two days”; no model calls or organization changes. Effectiveness figures come from one synthetic user's 22-day timeline. Scenario quotations/names below are translated/transliterated; raw evidence retains actual Chinese inputs and replies.

## Why start here

The 22-day synthetic timeline marks 80 moments when a proactive reminder is warranted (night before a deadline, evening review, early-morning flight cancellation, friend asking a question, etc.). Individual inspection found:

- 70 occur at 23:30 with no incoming message, requiring a time trigger; ten involve an incoming message or notification.
- Median gap from the first clue to when it is needed: **53 hours**; 57 exceed one day and 36 exceed three days. Only three require the clue within five minutes of its appearance.
- Existing proactive suggestions judge only the just-recorded item (“Look only at this record”) and expire if unaccepted within five minutes, structurally covering at most those three. The remaining 77 need information recorded days earlier.

So first inspect the memory layer: at each moment, is the relevant fact on the per-message user card supplied to the assistant? 0.8.1 scored **68/80**. Of twelve misses, three are precisely the cases where the message itself is the clue (left to per-item judgment); nine evening misses had these causes:

| Cause | Count | Example |
| --- | --- | --- |
| Privacy rules mistake the user's own appointment for another person's illness | 3 | Dental-cleaning follow-up and two “Book company annual medical checkup” items |
| Item limit truncates only by time | 2 | Approving Zhang Hao's board access and afternoon-tea reimbursement displaced while “Write daily report” remained |
| Recognizes event date, not an earlier action deadline | 1 | Gas annual inspection on November 4 needs rescheduling in advance |
| An item available from a date disappears after that date | 1 | Suit “Available for pickup from the 13th” |
| Undated relationship | 1 | Ajie moves to Shenzhen at month-end, so the gathering is also a farewell |
| Timeline data itself | 1 | Weekly report |

## Changes in this version

1. **The user's own appointments are no longer blocked** (`Privacy.agendaSensitive`): for graph items mentioning medical checkups/follow-ups, the old exclusion still applies if another person immediately precedes the term (“Mom's checkup,” “Grandma hospitalized”) or the user is not the subject. User-booked appointments (“Xiaolin booked a dental-cleaning follow-up,” “Book company annual medical checkup”) enter the schedule normally. Other people's illnesses, diagnoses, and private resignation plans remain excluded.
2. **Prioritize by cost of missing an item when space is limited** (`Recall.stake`): prioritize penalized deadlines (overdue, invalidation, water shutoff, rejection), “Must do before…” deadlines, commitments to others, birthdays/weddings, and money-related items; routine notices such as daily reports or “No need to be home” rank lower. Selected items remain time-ordered. Two same-day nodes describing the same event (“Got two match tickets” recorded on two nights) yield one item.
3. **New section: “Tasks newly appearing in the past day”** (`Recall.newlyAdded`): up to four tasks newly appearing within a day but due more than two days out reach the assistant that evening (for example, a medical-checkup booking deadline eleven days away).
4. **Keep unfinished items available from a particular date for a week**: for example, “Available for pickup from the 13th,” labeled “From this date (not yet seen completed).” Ordinary overdue unfinished items remain excluded (20–35 each evening in the later test data; the graph rarely marks completion, so including them would overwhelm the card).

## Results

Same 22-day data and scoring (whether pre-annotated keywords for each moment appear on the card; [script description](#reproduction), [per-item results](needs-coverage.csv)):

| | 0.8.1 | 0.8.2 |
| --- | --- | --- |
| Relevant item present on card (all 80) | 68 | **74** |
| Evening moments (70) | 61 | **67** |
| Message-triggered moments (10) | 7 | 7 (the three misses are the messages themselves) |
| Card length (median) | About 1,670 characters | About 1,930 characters |

Newly covered: dental follow-up, two checkup bookings, suit pickup, access approval, afternoon-tea reimbursement. No previously covered item was lost through prioritization. Still missed: rescheduling gas inspection (needs an earlier action deadline), Ajie's farewell (needs relationships), weekly report (data issue). These require organization changes and remain for the next step.

## Component tests

**120 StoreTests** (new: retain own appointment, exclude another person's follow-up, retain items available from a date, prioritize penalized deadlines over routine notices at capacity, show new tasks that evening), [output](store-tests.txt).

## Conversation regression

Debug package, restoring the same 22-day synthetic data and asking assistant v74 at each question's timestamp ([answers](regression-answers.txt)), scored under the same criteria as 0.8.1:

| | 0.8.2 (one run) | 0.8.1 (two runs) |
| --- | --- | --- |
| Difficult cross-topic questions | 10, 9 / 11 | 10, 10 / 11 |
| Association questions | 11/11 | 11/11 |
| Trigger messages | 6/7 | 7/7, 7/7 |
| Bedtime review | 23/25 | 24/25 |

Differences fall within single-run variation: this trigger-message run mentioned the November 22 gathering but omitted 6 p.m. at the usual place; bedtime review recalled Ajie's gathering time/place but missed Aurora K75 and the Beijing hotel. The six evening regression questions barely overlap the newly covered moments, so this only checks for deterioration.

**Full assistant on newly covered evenings**: asked the same bedtime question on November 8, 9, 15, 17, and 19, once each with 0.8.1 and 0.8.2 ([answers](evening-answers-081-vs-082.txt)). Both versions mentioned dental cleaning, suit pickup, access approval, and the checkup-booking deadline; both missed the checkup booking newly appearing on the 9th evening. The full assistant retrieves missing card information itself through memory search, at 147,000–230,000 tokens per answer.

**Card-only bedtime reminders (the future lightweight proactive path)**: for 21 annotated evenings, supplied each 0.8.1/0.8.2 card to `gpt-6-luna` with no tools, asking for at most eight bedtime reminder items ([prompt and script](#reproduction), [results](card-only-briefs.json)); checked mentions across the 70 evening moments:

| | 0.8.1 card | 0.8.2 card |
| --- | --- | --- |
| Mentioned in reminder (70 evening moments) | 57 | **62** |
| Tokens per call (median) | 5,477 | 5,647 |

New mentions: dental cleaning, two checkup bookings, suit pickup, access approval, afternoon-tea reimbursement, and ordering times for Grandma's electric blanket and Auntie's insulated cup. Three mentions were lost (Beijing hotel, Ajie's gift, tape for moving), all model prioritization changes under the eight-item cap; the information remained on the cards. About 5,600 tokens per call, roughly 1/30 of the full assistant answering the same question. One run each.

Conclusion: the full assistant can search for information absent from the card, at high cost; the future lightweight proactive path sees only the card, so inclusion determines whether an item can be mentioned. This version mainly prepares that path.

## Reproduction

```bash
TAKU_DEBUG_HOOKS=1 bash system/memory/build.sh      # Debug package; supports simulated time
bash system/memory/build-tests.sh
adb shell am instrument -w org.agentos.memorytests/org.agentos.memory.StoreTests
```

Card-only bedtime reminder: prepend “It is now X month X day, 23:30” to each evening's card. Use system instructions “You are the proactive assistant on a Taku phone… Write a bedtime reminder of things needing attention or advance preparation tomorrow and over the next two days, ranked by importance, at most eight items… Use only information in the card.” Call `gpt-6-luna` once with low reasoning, no tools, and nonpersistent threads (prompt translated here; recorded experiment used its original wording).

Proactive-moment coverage: after restoring 22-day synthetic data, set `testNow` for each moment and call `profile {forAgent, text}` with that evening's review question (or incoming message). Check pre-annotated keywords on the card, requiring all terms for multiword annotations. This is approximate scoring: keyword presence does not guarantee the assistant mentions it, and absence may reflect paraphrasing; conversation regression was therefore run separately.

Run only on the dedicated test AVD; do not rerun capture cases on a device containing real memories.

## Known limits

- Scoring checks card presence, not whether the assistant proactively speaks. Time/message triggering has not been implemented; this version only surfaces the relevant memory at that moment.
- Cost judgments are keyword rules derived from this same synthetic dataset and may need adjustment for another person. A more robust approach is for organization to write “Cost of missing” and “Latest action date.”
- Cards grew about 15%.

[Historical language evidence](../HISTORICAL-LANGUAGE.md) documents the intentionally retained Chinese-locale raw inputs, outputs, and reproduction scripts for this historical review.
