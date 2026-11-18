# Taku Memory 0.12.0: addressing several limitations of 0.11.0

2026-11-05 (Beijing time), Android 16 / API 36 / ARM64, dedicated test AVD `TakuRecall` (`emulator-5634`, `sdk_phone64_arm64` userdebug, SELinux Enforcing). Taku Memory 0.12.0 (versionCode 14), `agentos-task-broker` built from the same commit, assistant v76. Artifact SHA-256 values: [acceptance.json](acceptance.json).

**Status: emulator_verified; proactive reminders remain experimental and disabled by default.** Figures come from one synthetic user's 22-day timeline; model answers were run one to three times. Scenario quotations/names below are translated/transliterated; raw evidence preserves actual Chinese inputs and replies.

## Changes

Limitations remaining after 0.11.0 acceptance:

- Organization rarely wrote latest-action dates: only nine of 247 nodes, omitting gas-inspection rescheduling;
- Reminders treated deadlines and check-in availability as appointments requiring attendance and reported conflicts;
- Things changed today contained noise.

This version addresses each:

1. **More complete organizer output** (`MemoryGen` graph prompt):
   - Latest-action date now means any action needed before the event, inferred where possible. For example, reschedule by the previous day if unavailable, or subtract shipping time to derive an order date.
   - Adds `attend`: whether the user must personally attend at that time. Meetings, appointments, and repair visits requiring someone home count; deadlines, delivery, check-in availability, and business hours do not.
2. **User card** (`Recall.dueSoon`):
   - Attendance times labeled “Attendance required.”
   - More latest-action dates caused advance preparations to displace tomorrow's actual deadlines, so three prioritization rules changed:
     - A dated task/object treats that date as a deadline. Earlier wording heuristics missed forms such as “Fill in Fengchao before 12:00.”
     - “Act before” determines the date only, without extra priority points.
     - Priority boost from organizer-written significance halved.
   - Two lines for the same person/time, at least one requiring attendance, count as one event.
3. **Conflicts compare attendance times only** (`Proactive`):
   - On-phone Possible conflict checks use only Attendance required entries. Old graphs lack this marker and retain time-based comparison.
   - Message prompt adds that deadlines, expiry, delivery, check-in availability, and business hours do not conflict with other arrangements.
4. **Recheck message reminders before delivery** (`Proactive.check`, new Broker `check` kind): after the model chooses to speak, `gpt-6-luna` checks only two error classes:
   - Treating deadline/check-in times as conflicts;
   - Conflating unrelated events.

   If found, suppress delivery. Adds one call only when a reminder is proposed, about once daily and about 4,500 tokens each.
5. **Denoise Things changed today** (`Recall.changes`): count rescheduling only if the original time had not already passed by last night. Weekly meetings rolling into next week and continuously updated online issues no longer count.

## Results

Rebuilt the graph from the 22-day synthetic timeline using the 0.12.0 organizer ([script](rebuild-graph.sh)); all 22 nights succeeded:

| | 0.11.0 graph | 0.12.0 graph |
| --- | --- | --- |
| Nodes (events/tasks/objects) | 247 (224) | 255 (234) |
| Items ever given latest-action dates | 9 | **41** |
| Marked Attendance required | — | 62 |
| With significance / related-node links | 56 / 88 | 66 / 91 |

Gas inspection now records “Reschedule before 11-03” and attendance required.

### Card coverage (80 annotated moments)

[Per-item results](card-ablation.csv), [script](card-ablation.sh):

| | 0.11.0 graph | 0.12.0 graph |
| --- | --- | --- |
| 0.11.0 card rules | 74 | — |
| 0.12.0 card rules | 74 | **75** |
| Organizer fields unused | — | 71 |

- Newly covered gas-inspection rescheduling at the first evening review, without losing prior coverage. New rules on the old graph still score 74, with no deterioration.
- Median card length about 2,140 → 2,400 characters.
- Changing only the organizer without prioritization yielded 71:
  - Four deadlines displaced by advance preparation: tomorrow-noon interview feedback, 18:00 access approval, wedding-candy pickup, Qiqi's leave approval;
  - The same dinner appeared as two lines.

  These motivated item 2 above.
- Five still missed:
  - Three moments where the message itself is the clue;
  - Weekly report (timeline-data issue);
  - Gift preparation four days before the party, beyond the two-day window.

### Bedtime reminders

Actual Broker calls to `gpt-6-luna` for 21 annotated evenings ([originals and changes visible that evening](evening-briefs.json)):

| | Mentions among 70 evening moments |
| --- | --- |
| 0.10.0 | 59 |
| 0.11.0 (two runs) | 62, 62 |
| **0.12.0 (two runs)** | **65, 64** |
| 0.12.0 without organizer fields | 55 |

- **New in both runs**: gas-inspection rescheduling, buying Grandma an electric blanket.
- **Once each**: medical-checkup booking, bringing tape to Ajie.
- **Still absent**: Beijing contracted-rate hotel, weekly report, party-gift preparation.
- **Mentioned one fewer time**: cousin's wedding-candy arrival evening, omitted in the second run.

### Things changed today

Applied old/new rules once each to the same 0.11.0 graph ([removed items](changes-noise.json)):

- Reduced 31 to 28; all three removed items were noise: two weekly product meetings rolling to next week and one continuously updated online issue.
- Actual changes remained: gas reinspection moved to the 8th, review rescheduled, leave request rejected, afternoon-tea reimbursement moved to December.

### Conflicts and message reminders

**Local conflict marking**:

- Full 1,510-record replay reduced Possible conflict from 27 occurrences (33 lines) to 14 (14 lines).
- Xiaoyu's tomorrow-evening dinner invitation marked only dinner with Tingting at 19:00; 0.11.0 also marked the 18:00 approval and 21:00 pickup deadlines.

**195-notification replay**: same offline method as 0.11.0, graded by `gpt-6.1-sol` ([per-item results](replay-nudges-graded.json)).

| | 0.11.0 | 0.12.0 (before recheck) |
| --- | --- | --- |
| Model wakeups | 37 | 32 |
| Reminders | 20 | 18 |
| Useful / Low value / Incorrect | 16 / 2 / 2 | 13 / 2 / 3 |

Prompts and Attendance required alone did not reduce errors. Three incorrect reminders:

- Conflict between review moved to the 13th at 10:00 and priorities due before the 13th;
- Conflating hotel check-in from 14:00 with a meeting;
- Advising against paying property fees during a water shutoff.

**With pre-delivery recheck**:

- **Offline**: one final-prompt recheck of all 38 reminders across both versions' replays.
  - Blocked all five incorrect reminders;
  - Blocked three of 29 useful reminders; one was another hotel-check-in conflict error, which the grading model had called useful;
  - Blocked one of four low-value reminders.
- **Actual path on test device**: six selected cases (three incorrect, three useful), each run twice ([originals](check-cases-device.json)).
  - Incorrect cases produced four proposed reminders; recheck blocked three: hotel twice and review once; water-shutoff cases stayed silent both times.
  - All six useful runs passed.
- **Annotated message moments**: three runs each with recheck ([originals](message-moments-3runs.json)); recheck blocked none, and all five wake-triggering moments produced reminders in all three runs.
  - Xiaoyu asking about the restaurant/Ajie's wishes and flight cancellation retained previous points; Ajie's party time was absent all three times.
  - Xiaoyu's tomorrow-evening invitation: all three mentioned 19:00 dinner with Tingting; two suggested another time.
  - Landlord's water-heater repair question: all three said it did not conflict with dinner at 19:00 and a reply confirming availability was possible.

## Component tests

**148 StoreTests** ([output](store-tests.txt)); the other six groups passed as usual. Added checks:

- Attendance times marked; pickup deadlines unmarked;
- Only attendance events compared for conflicts; same-period deadlines still listed without conflict marking;
- Two lines for same person/time treated as one event;
- Different people at the same time retained as two conflicting events;
- Two simultaneous deadlines from the same person both retained;
- Weekly meetings rolling to next week after completion do not count as rescheduling.

`npm run verify`, `npm --prefix tooling/app-kit/api test`, and `npm run build:android` passed.

## Reproduction

- [rebuild-graph.sh](rebuild-graph.sh): restores 22-day backup and rebuilds only the graph; restoration now uses [restore-safe.sh](restore-safe.sh).
- [card-ablation.sh](card-ablation.sh): card comparison at 80 moments.
- [replay-model.sh](replay-model.sh): three message-moment runs, two bedtime runs, one without fields.
- [check-cases.sh](check-cases.sh): recheck cases.

Run only on the dedicated test AVD; do not rerun capture cases on a device containing real memories. After acceptance, the device held 0.12.0 debug (proactive reminders off), 22-day synthetic data with graph rebuilt by 0.12.0 organizer, 0.12.0 Broker, assistant v76.

**Retest notes**:

- During the first graph rebuild, after the restore script deleted settings, the system immediately relaunched Memory; it read empty settings and wrote them back. The entire graph consequently named the user “I”; that run was invalidated.
- After extraction, `restore-safe.sh` stops the process again and confirms the settings file contains the user's preferred name.
- 0.11.0 acceptance data is unaffected; that graph named the user Xiaolin.

## Known limits

- **Small recheck sample**: offline 38 reminders blocked all five incorrect ones but also classified three useful ones as wrong; on-device blocked three of four incorrect proposed reminders. Recheck applies only to message reminders, not bedtime reminders.
- **Organizer field variability**: repeated rebuilds of the same timeline differ in nodes/wording, such as wedding candy or interview feedback; card coverage can fluctuate by one or two.
- **Longer cards**: median about 2,400 characters, roughly 12% longer than 0.11.0.
- **Single-source rules**: prioritization and recheck prompts were tuned only on this one synthetic user's data.
- User feedback on reminders (useful, wrong, done) is not yet written back to memory.
- Content already sent to model services cannot be retracted.

[Historical language evidence](../HISTORICAL-LANGUAGE.md) documents the intentionally retained Chinese-locale raw inputs, outputs, and reproduction scripts for this historical review.
