# Taku Memory 0.18.0 acceptance: Taku conversations, and searches that reach further

Memory recorded what other apps showed, never what the user told Taku itself. An Agent's search also returned at most 10 records (the provider capped it at 20). On LongMemEval_S, 0.17 scored 86.0%, and most lost points came from one search with 20 records missing part of the evidence. This version makes three changes:

1. Taku conversations become memory.
2. Searches reach further.
3. The Agent is told how to search for counting and dated questions.

See the [module notes](../../../system/memory/README.en.md) for the summary.

## What changed

**Taku conversations** (`MemoryStore.chat`, `TakuChatMemory`). The new switch "Remember Taku conversations" (`takuChats`) is off by default and also needs capture on. While it is on, the Broker sends each primary chat turn to the Broker-only `chat` method:

- **The user's message**, when `turn/started` arrives. This is the turn's first text item; attachments, plugins and panel context follow it and are not sent.
- **Taku's reply**, when the turn completes. This is the readable text of the reply card (`MainReplyStream.readable`); a plain reply is kept as written.

Organizer threads, structured tasks (`outputSchema`) and realtime turns are never sent.

Memory then handles the two kinds of turn differently:

- **The user's messages** are ordinary evidence: searched, organized into the graph, and quoted on the user card.
- **Taku's replies** are stored with speaker `assistant`, but:
  - search reads them only when the query asks what Taku said (`Recall.ASKS_TAKU`, per language), or the caller passes `takuReplies`;
  - the organizer and the card's quotes never read them.

Some turns are not kept at all:

- what memory itself handed to Taku (the home-feed prompts in either language, or a line of a reminder from the last week), together with its reply;
- turns under 8 characters;
- turns with a password or one-time code.

Card-like numbers are masked. Conversation turns skip the analysis queue. Screen capture of the Taku app stays off. The library shows these records as "You: …" and "Taku: …".

**Searches reach further** (`Recall.search`, `Scope`):

- **More results.** `limit` goes up to 50 (20 before), and each ranked list is `max(30, limit)` deep.
- **Date window.** `from`/`to` (`YYYY-MM-DD`, inclusive) bound when records were first seen. Such a search reads evidence only, because graph nodes carry the organizer's dates.
- **Clearer items.** Items carry `seenAt`, a local date and time, since epoch milliseconds are easy to misread. Conversation turns also carry `speaker`.
- **Longer excerpts for the user's messages.** An excerpt of the user's own message holds up to 800 characters instead of 400. Cut at 400, one in eight evidence messages lost its point; at 800, one in two hundred does. Pages and Taku's own (long) replies keep 400. An item that continues is marked `cut`.
- **Phone tool.** `memory_search` takes `limit` (default 20, fixed at 10 before), `from`, `to` and `takuReplies`.

The keyword index gains the speaker column (`memory_kw2`), built by the maintenance job.

**Guidance.** The `memory_search` description and the harness policy ask the Agent to:

- search more than once, with the specific words for each kind of thing and a higher limit, when a question counts, totals or orders things, and list each item with its date before totalling;
- work out dates from today and pass `from`/`to` for a question tied to a time;
- read cut excerpts with `memory_get`;
- when records disagree about the same thing, take the latest as the current state and give that one answer;
- before giving advice or recommendations, search what the user said about their own tastes, situation and plans on the subject, and build on it;
- say so when memory holds nothing about the question, or contradicts what it assumes.

## How it was measured

LongMemEval_S (cleaned, 500 questions, 30 of them abstention) was run end to end. Each question's haystack stands for one phone whose memory holds those sessions as Taku conversations:

- each user turn is a record with speaker `user`, and each assistant turn one with speaker `assistant`;
- a record is first seen at the session's time plus one second per turn;
- turns go through the same filters as `MemoryStore.chat`.

Two things stand in for the phone:

- **Search.** It ran in a Python mirror of the new `Recall.search` ([memsim.py](memsim.py)). The mirror reads its word lists and patterns from the Java sources. Its English question words matched the Java `DumpWords` output on all 500 questions. No graph nodes were used, because no organizer ran.
- **Vectors.** Every turn was encoded with bekko-embedding-v1-a8m exactly as `embedNow` would encode it: title `Taku`, cut to 600 characters ([embed_chat.py](embed_chat.py)).

The phone's clock is the end of the question's day. Seventy-six haystacks hold sessions later on that day than the question's time stamp, and LongMemEval treats every session as history. A first run used the question's exact time, which hid those sessions. It was stopped after 85 questions and discarded ($4.00).

The reader:

- **Model.** Sonnet 5.5 (`claude-sonnet-5-5`, through the Claude CLI) answered as Taku. It called `memory_search` and `memory_get` itself, through an MCP stand-in ([mcp_taku.py](mcp_taku.py), [server.py](server.py)).
- **Tools.** The tool descriptions and schema are read from `PhoneNativeTools.java`.
- **Prompts.** The system prompt is the memory paragraph of `taku_harness.json` ([agent.py](agent.py)). The message is "Today is ‹question date›" followed by the question.

Judging:

- Sonnet 5.5 judged with LongMemEval's own prompts ([judge_prompts.py](judge_prompts.py)), the same judge as the 0.17 run.
- Metrics follow `print_qa_metrics.py` ([qa_metrics.py](qa_metrics.py)).
- No temperature was set.

The 0.17 run is the baseline. It used one search with the question as the query, kept the top 20 whole turns, and gave them to the same reader with LongMemEval's step-by-step template.

## Results

The final version was the second of three rounds:

- **Round 1** had the first guidance and 400-character excerpts.
- **Round 2** added the longer excerpts for the user's messages, the "latest record wins" and "build advice on what the user said" guidance, and the `cut` flag.
- **Round 3** added two more rules: check each counted item against every condition in the question, and search again with other words before saying memory holds nothing. It scored lower, so it was reverted.

| | 0.17: one search, 20 turns | 0.18 round 1 | **0.18 final (round 2)** | Round 3 (reverted) |
| --- | --- | --- | --- | --- |
| **Overall** | 86.0% (430/500) | 90.6% (453) | **93.8% (469)** | 92.8% (464) |
| Task-averaged | 88.6% | 90.3% | **94.3%** | 94.0% |
| Single-session, user said | 95.7% | 97.1% | 97.1% | 95.7% |
| Single-session, Taku said | 98.2% | 96.4% | 94.6% | 98.2% |
| Single-session, preference | 86.7% | 80.0% | 93.3% | 93.3% |
| Multi-session | 74.4% | 82.7% | 88.7% | 85.0% |
| Temporal reasoning | 82.7% | 94.7% | 94.7% | 93.2% |
| Knowledge update | 93.6% | 91.0% | 97.4% | 98.7% |
| Abstention (within the above) | 76.7% | 93.3% | 96.7% | 90.0% |

([final](longmemeval-agent.txt), [round 1](longmemeval-agent-round1.txt), [round 3](longmemeval-agent-round3.txt), [per question, all runs](longmemeval-per-question.tsv))

Published systems report 94.0–95.6% on the same set: Synap 95.6%, Mastra 94.87%, Mem0 94.4%, supermemory 94.0%. Those figures are self-reported and mostly judged by GPT-4o, so they compare only roughly.

**Read these as optimistic.** The round 2 and round 3 rules were written from round 1's failures on these same 500 questions. The rules are general, but they were chosen on this test set, which is the score being reported. Runs also vary: no temperature was set, and rounds 2 and 3 disagreed on 19 questions. That includes some judge noise, such as a correct "Alex became a parent first" judged wrong in round 3. A gap of about one point between two runs is within that noise.

**Where points are still lost** (final round, [longmemeval-evidence-shown.txt](longmemeval-evidence-shown.txt)). Every search the reader made was replayed to see which evidence turns it was shown:

- **When all evidence was shown** (451 of 470 non-abstention questions, 96.0%), the answer was right 94.9% of the time. In 0.17, all evidence was among the 20 records for 85.3% of questions.
- **When not**, it was right 63.2% of the time (19 questions).

The largest remaining loss is multi-session counting, right 89.7% of the time even with all evidence shown. Some of those gold answers are open to reading, for example whether an exchanged item still counts as one to pick up.

**How the reader used the tools** (final round, [longmemeval-tool-use.txt](longmemeval-tool-use.txt)):

- 1.71 searches per question.
- A limit above 20 in 178 questions.
- Dates passed in 84 questions.
- `takuReplies` set in 51 questions; Taku's replies were read in 50 questions' searches.
- 0.08 `memory_get` per question.

**One search with the question as the query**, as 0.17 was measured, shows what the retrieval changes alone do ([longmemeval-one-search.txt](longmemeval-one-search.txt)). The table gives the share of questions whose evidence was all returned:

| Limit | All | Multi-session | Temporal | Single-session, Taku said | Excerpt text |
| --- | --- | --- | --- | --- | --- |
| 0.17, 20 whole turns | 85.3% | 71.9% | 79.5% | 100% | 18.8k chars |
| 0.18, 10 | 82.3% | 68.6% | 78.7% | 85.7% | 2.6k |
| 0.18, 20 | 88.9% | 79.3% | 89.8% | 89.3% | 5.1k |
| 0.18, 40 | 94.0% | 90.9% | 95.3% | 89.3% | 9.7k |
| 0.18, 50 | 95.1% | 93.4% | 95.3% | 89.3% | 12.0k |

The 0.17 row hands over whole turns; 0.18 items are excerpts.

The question-word rule for Taku's replies matches 51 of the 56 single-session-assistant questions and none of the other 444. The rule was written before this measurement, but on the same benchmark's phrasing, so the figure is optimistic for other phrasings. The chat filters dropped an evidence turn in 2 questions.

**Cost** (list price):

| Run | Answers | Judge |
| --- | --- | --- |
| Round 1 | $25.68 | $3.88 |
| Round 2 | $27.61 | $3.87 |
| Round 3 | $27.87 | $3.85 |

A discarded partial run (wrong clock) cost $4.00.

## Checks

- **Builds.**
  - The memory release and debug APKs build, and so does the component test APK.
  - `test-host.sh` passes (native packaging, 13 fallback checks).
  - The Broker compiles with the Android API check clean.
  - The Broker host tests that compile the changed bridge pass: `test-native-turn-skills-host.sh` (122 checks) and `test-native-routing-host.sh` (119 + 54 + 13, plus 207 ownership assertions). Both use a stub of the new class.
- **Broker recorder on the JVM** ([TakuChatMemoryCheck.java](TakuChatMemoryCheck.java), [ChatStubs.java](ChatStubs.java), [broker-chat-checks.txt](broker-chat-checks.txt)): 8/8 checks, using the real `MainReplyStream`. They check:
  - only the first text item is sent, trimmed;
  - the reply is the turn's last message;
  - structured tasks, organizer threads and realtime turns are not sent;
  - an interrupted turn keeps the message but not the partial reply;
  - a framed reply that does not parse is dropped, and plain text is kept.
- **Component tests** (`StoreTests.chats`): these are compiled but not run. They cover:
  - the switch, and storing each turn once;
  - replies left out of ordinary searches and read when asked;
  - the organizer reading only the user's messages;
  - turns skipping the analysis queue;
  - a long message cut at 800 characters and marked `cut`, and a short one left whole;
  - dropping a home-feed hand-off and its reply, and dropping passwords;
  - date windows, and refusing bad dates.

## Not covered and risks

- **No device run.** Nothing ran on the emulator or a phone: not StoreTests, not the switch, not the Broker sending real turns. The recorder assumes that `turn/started` follows the native `turn/start` it saw, on the same thread, and that `item/completed` carries `turnId`, as `HarnessContext` already reads it.
- **The assistant APK was not rebuilt.** Its build runs a Node script from `system/agent-app`, which was not run here. The changed `taku_harness.json` was only checked to parse.
- **A well-formed reply card's readable text was not checked**; that needs the exported reply contract. Only plain and unparseable replies were checked.
- **The benchmark ran on a mirror, not the Java search.** The word lists and patterns come from the Java sources, and question words match. The vector ranking reproduces the Java code's tie order, but was not compared entry by entry with a device.
- **LongMemEval is not phone data.** It is long chats with an assistant, so it exercises the new conversation memory specifically. Phone captures and notifications are untested here. No graph nodes were used.
- **Judge and runs.** The judge is Sonnet, not GPT-4o. Each figure comes from one run with no temperature set, and one question moves the overall score by 0.2 points.
- **Storage.** Every kept turn is a record with a vector, so a heavy chat user adds records quickly. Retention settings apply as usual.
