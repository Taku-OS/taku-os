# Taku Memory 0.11.0: organization records action deadlines, significance, and related context; reminders can hand off to the Agent

2026-11-04 (Beijing time), Android 16 / API 36 / ARM64, dedicated test AVD `TakuRecall` (`emulator-5634`, `sdk_phone64_arm64` userdebug, SELinux Enforcing). Taku Memory 0.11.0 (versionCode 13), `agentos-task-broker` built from the same commit, assistant v76. Artifact SHA-256 values: [acceptance.json](acceptance.json).

**Status: emulator_verified; proactive reminders remain experimental and disabled by default.** Effectiveness figures come from one synthetic user's 22-day timeline; model answers were run one to three times. Scenario quotations/names below are translated/transliterated; raw evidence preserves actual Chinese inputs and replies.

## Implementation

Continues 0.10.0 proactive reminders with steps two and three of the proactive-reminder plan.

**Step two: write reminder-relevant information into the graph during organization** (`MemoryGen` graph prompt, `MemoryStore.applyGraph`, `Recall.dueSoon`)

- Two new node fields:
  - `due`: latest date to act, written only when different from the event date. For example, a personal-training trial on the 7th must be confirmed before the 5th; a lease expiring December 15 requires a reply to the landlord before November 22.
  - `stake`: one sentence stating the cost of missing it or why it matters, such as “Gas shut off without inspection,” “A competitor is pursuing this,” or “Promised Mom.” Empty if unsupported by evidence.
- Broader `related` requirement: besides obvious relationships, connect items that should be recalled together, such as a suit for a meeting, an umbrella to return, or why a gathering doubles as a farewell.
- The user card's “Plans and deadlines over the next two days” uses all three:
  - Items with `due` enter the schedule by that date, labeled “Act before (event itself at …)”;
  - Includes “(Significance: …),” prioritizing greater stakes when space is limited;
  - Each item can carry a few lines of “↳ Recall together: …”.

  Existing keyword-based cost heuristics remain as a supplement.

**Step three: triggering and delivery** (`Proactive`, `Recall.changes`, `ProactiveNotice`, `NudgeAction`, Broker `MemoryBridge.nudgeTask`)

- **Explicit conflict markers**: if a notification proposes a date/time period (“Tomorrow 8 p.m.,” “Tomorrow noon”) already occupied, the phone marks “Possible conflict: …” before sending it to the model. The prompt requires the model to confirm the same time period and required personal attendance before calling it a conflict, and state both event times when speaking.
- **State changes in bedtime reminders**: graph items rescheduled, canceled, or completed in the past day appear under “Things changed today.” Compares each node's previous-day and current versions, excluding newly created items. Bedtime instructions also require explaining the current state of changes affecting tomorrow or later and not reminding about completed items.
- **Delivery action: “Hand off to Agent”** on both notifications and reminder cards in Suggestions. Clicking it:
  - Broker reads the reminder and context originally shown to the model from Memory, rather than accepting caller-supplied bodies;
  - Queues a read-only task in the existing Agent: verify using memory search, explain conflicts or concerns, offer one or two solutions, and draft a reply if needed without sending;
  - Allows only one handoff per reminder;
  - Changes the Suggestions card to “View Agent result.”

## Results

### Step two: fields written during organization

Rebuilt the graph from the 22-day synthetic timeline using the current organizer ([script](rebuild-graph.sh)); all 22 nights succeeded:

- 247 nodes, including 224 events/tasks/objects;
- 56 with significance and 88 linked to related nodes;
- Nine items ever received a latest-action date. The organizer was conservative; it omitted the need to reschedule the gas annual inspection in advance.

**Card coverage**: 80 annotated moments, same rebuilt graph, with/without the three fields ([comparison script](card-ablation.sh), [per-item results](card-ablation.csv)):

| | 0.8.2 (old graph organized by 0.5) | Rebuilt graph, fields unused | Rebuilt graph, fields used |
| --- | --- | --- | --- |
| Relevant item on card | 74/80 | 71/80 | **74/80** |
| Card length (median) | About 1,930 characters | About 1,820 characters | About 2,140 characters |

- **Three lost without fields**: personal-training trial confirmation on two evenings, and landlord lease-renewal reply. The new organizer writes event date into `when` and deadline into `due`; ignoring `due` places them outside the two-day window.
- **Six still missed** (count as reported in the original record):
  - Gas-inspection rescheduling: no `due` written;
  - Weekly report: timeline-data issue;
  - Preparing the party gift and Ajie's farewell: gathering four days away. The farewell relationship is linked; the card on the 21st evening includes “↳ Recall together: Ajie starts work in Shenzhen at month-end”;
  - Three moments where the message itself is the clue.
- **Card examples**:
  - “Thursday November 5: act before (event itself 11-07 15:00) | Confirm reserved personal-training trial slot… (Significance: slot released to someone else if not confirmed in time; trial voucher expires November 8)”
  - Suit pickup followed by “↳ Recall together: client meeting November 17 requires formal dress.”

### Bedtime reminders

Actual Broker calls to `gpt-6-luna` for 21 annotated evenings ([original reminders and changes visible that evening](evening-briefs.json)); mentions among 70 warranted evening reminders:

| | Moments mentioned |
| --- | --- |
| 0.10.0 (old graph, no fields or changes) | 59 |
| 0.11.0, two runs | **62, 62** |
| 0.11.0 same Broker, without organizer fields | 54 |

An intermediate version before the bedtime-prompt change also ran twice, scoring 63 and 62.

- **New in both runs**: November 11 hotpot gathering, Shanghai specialties to bring clients, Tingting's Japanese-dinner invitation. Suit pickup and checkup booking appeared once each.
- **Missing in both runs**: Beijing contracted-rate hotel (mentioned by 0.10.0).
- **Input per call**: about 2,170 characters; about 1,890 without fields.

**Things changed today**: present on 16 of 21 evenings, 31 items total:

- 16 completed;
- Eight rescheduled;
- Six canceled, including overdue parcel return and canceled meeting;
- One changed latest-action date.

Examples used in reminders:

- “Gas reinspection moved to November 8 morning; someone must stay home”;
- “Recommendation-algorithm requirements review… originally November 12, 14:00, now rescheduled”;
- “CNY 312 afternoon-tea reimbursement moved to December.”

### Message trigger

**Annotated key moments**: final Broker, each run three times ([originals](message-moments-3runs.json)).

| Moment | Reminded in three runs | Points mentioned |
| --- | --- | --- |
| Xiaoyu asks “What was that Japanese restaurant?” | 3/3 | Jiangsu Road, Nishimura Japanese restaurant in all three |
| Xiaoyu asks what Ajie wants lately | 3/3 | Aurora K75 in all three; party on the 22nd at 6 p.m. once (twice in 0.10.0) |
| Early-morning flight YC1203 canceled | 3/3 | All three mention impact on 11 a.m. meeting with Mr. Wang; still omit leaving at 2 p.m. and competitor interest |
| Xiaoyu invites for tomorrow evening (already promised Tingting 19:00 at Nishimura) | 3/3 | Phone marked a possible conflict all three times; all mention 19:00 and returning Tingting's umbrella from related context |
| Landlord asks whether Wednesday afternoon works for water-heater repair | 1/3 | Silent twice; once says data checking has no recorded time, so conflict cannot be confirmed. 0.10.0 called it a conflict once |

**Interruption rate in full replay**: 195 notifications over 22 days, offline replay with the same method/prompts/models as 0.10.0, then per-item grading by `gpt-6.1-sol` ([per-item results](replay-nudges-graded.json)).

| | 0.10.0 | 0.11.0 |
| --- | --- | --- |
| Local model wakeups | 32 | 37 (two marked possible conflict) |
| Reminders | 20 | 20 |
| Useful / Low value / Incorrect | 13 / 5 / 2 | **16 / 2 / 2** |

- **Two phone-marked conflicts**: Ms. Chen's 1:1 and promised proofreading for Lily both Friday 16:00; both graded useful.
- **Two incorrect reminders**:
  - Called “Review moved to the 13th, 10:00” and “Submit priorities before the 13th” a conflict. The phone had not flagged it; the model inferred it.
  - Conflated hotel availability from 14:00 with a meeting ending before 14:00; also present in 0.10.0.
- About 4,800 tokens per call.

### Hand off to Agent (end-to-end)

1. After restoring 22-day data, used the debug interface at “November 18, 20:00” to inject Xiaoyu's “Dinner together tomorrow evening?” as a notification.
2. Reminder appeared ([screenshot](nudge-action.png)): “Tomorrow evening you already have dinner at Nishimura at 19:00 and need to return Tingting's umbrella; ask Xiaoyu for another day?”
3. Clicked Hand off to Agent on the notification:
   - Notification changed to “Handed off to Agent, read-only handling” ([screenshot](nudge-action-sent.png));
   - Broker created a read-only task (`permissionMode: readOnly`, reminder ID included); Memory stored its task ID with the reminder.
4. Assistant v76 finished in about one minute ([screenshot](nudge-agent-result.png), showing an earlier result of the same workflow):
   - Checked memory;
   - Listed the conflict and several Thursday deadlines at 12:00, 18:00, and 21:00;
   - Offered two approaches such as keeping the original arrangement and asking Xiaoyu for another day;
   - Drafted a reply to Xiaoyu without sending.
5. Suggestions card changed to View Agent result ([screenshot](nudge-list-zh.png)).

The Agent answered in English because the test device's system language was English.

## Component tests

**142 StoreTests** ([output](store-tests.txt)); the other six groups passed as usual. Added checks:

- User card:
  - Items with latest-action dates enter schedules by those dates, including significance;
  - Related context follows the item;
  - Ablation switch exists only in debug builds;
- Things changed today:
  - Rescheduling includes old and new times;
  - Completed items listed;
  - No change within one second;
  - Visible to bedtime reminders;
- Hand off to Agent:
  - Reminder saves context shown to the model;
  - Handoff only once;
  - Task-ID format validation;
- Conflict detection:
  - Invitation overlapping an existing time period marked Possible conflict without duplicating it among other same-day plans;
  - Different time periods on the same date not flagged;
  - Time can be parsed from the message.

The debug build additionally passed the ablation-switch check. `npm run verify`, `npm --prefix tooling/app-kit/api test`, and `npm run build:android` passed.

## Reproduction

```bash
TAKU_DEBUG_HOOKS=1 bash system/memory/build.sh      # Debug: simulated time, replay, graph-only rebuild, field ablation
bash system/memory/build-tests.sh && bash system/assistant/build.sh
# Rebuild only the graph (root, debug build): call once per day; graphOnly leaves board and user card unchanged
adb shell content call --uri content://org.agentos.memory --method maintain --arg '{"now":<ms>,"graphOnly":true}'
# Use / omit organizer-written due, stake, and related in user cards (debug build)
adb shell content call --uri content://org.agentos.memory --method configure --arg '{"agendaFields":false}'
```

[rebuild-graph.sh](rebuild-graph.sh) rebuilds the graph from the 22-day backup; [card-ablation.sh](card-ablation.sh) compares cards at 80 moments; [replay-model.sh](replay-model.sh) runs message moments three times, bedtime reminders twice, and one field-omission run. Run only on the dedicated test AVD; do not rerun capture cases on a device containing real memories. After acceptance, the device held 0.11.0 debug (proactive reminders off), 22-day synthetic data with graph rebuilt by the current organizer, 0.11.0 Broker, and assistant v76.

## Known limits

- **Few latest-action dates**: only nine of 247 nodes ever had one; flexible deadlines such as rescheduling a gas inspection were omitted.
- **Incorrect-reminder rate unchanged**: notification replay still has two incorrect items. On-phone time comparison is coarse; broad periods such as tomorrow evening can flag an 18:00 approval deadline as a possible conflict, relying on model judgment. User feedback (useful, wrong, done) is not yet written back; that is step four of the plan.
- **Noise in Things changed today**: weekly meetings rolling to next week and continuing online-issue updates can count as rescheduling.
- **Agent verification limited by test data**: memory search uses the actual device date. Synthetic data is in the future, so the Agent cannot retrieve November 19's appointment and relies on reminder context, honestly stating it is unverified.
- **Handoff includes contemporaneous context**: user-card excerpts and the message, up to 4,000 characters, saved in the local reminder table; enters an Agent task only after the user clicks.
- **System surfaces** (Dynamic Island-style hints) lack action buttons; the test device also lacks that service.
- **Graph rebuild changed only the graph**; board and user card still come from 0.5 organization.
- Content already sent to model services cannot be retracted.

[Historical language evidence](../HISTORICAL-LANGUAGE.md) documents the intentionally retained Chinese-locale raw inputs, outputs, and reproduction scripts for this historical review.
