# Continuous conversations and collapsible work logs, v53

Status: `emulator_verified`. Only assistant APK updated on local `Taku_ImageIntegration_20261010 / emulator-5634` (API 36). Installed 53 / `20261019-conversation-worklog`; device/local APK SHA-256 match. [Summary/checksums](acceptance.json).

## Behavior and implementation

- Native `thread → turn → item` is the sole conversation source. `commentary` and tools enter a default-collapsed work log; `final_answer` displays continuously, without fixed steps, estimated percentages, or inferred completion. For old records lacking `phase`, only phase-less text preceding tools is treated as process text; trailing body and unknown public phases remain visible.
- Same-turn additions split display segments at native user-message positions, retaining turn/message IDs. Tool completion is not turn completion; waiting appears until the model replies, and failure/Stop derive from native state.
- Stable IDs reuse turn, body, and tool nodes. Completed text is not rebuilt for new deltas; streaming retains one `TextView`. A single Chinese character can display immediately; only incomplete UTF-16 surrogate pairs wait. Blank Markdown paragraph lines become paragraph spacing.
- Follow output at the bottom; preserve line/offset when reading history. Expansion preferences persist per conversation, up to 100 conversations and 200 keys each, storing only UI preferences. Theme changes/reopening do not lose expansion choices.
- Commands/output stay with the corresponding native item; collapsed logs do not construct detailed rows. Native plans/goals/diffs expand on demand; approvals, questions, and errors remain outside collapsed areas.

`AgentConversation` / `NativeTurnPresentation` project data; `NativeConversationView` / `NativeTurnView` / `NativeActivityView` update incremental views; `ConversationExpansion` manages preferences. Existing Codex events, single queue, attachments, and drafts are reused without duplicating message storage or model runtime.

## References

Grouping, default collapse, and body layout reference Craft Agents commit [`22c9f041`](https://github.com/craft-ai-agents/craft-agents-oss/tree/22c9f0414ab63e0a22814c9a2ada740206e09091), specifically [`turn-utils.ts`](https://github.com/craft-ai-agents/craft-agents-oss/blob/22c9f0414ab63e0a22814c9a2ada740206e09091/packages/ui/src/components/chat/turn-utils.ts), [`WorkLog.tsx`](https://github.com/craft-ai-agents/craft-agents-oss/blob/22c9f0414ab63e0a22814c9a2ada740206e09091/packages/ui/src/components/chat/turn/WorkLog.tsx), and [`TurnCard.tsx`](https://github.com/craft-ai-agents/craft-agents-oss/blob/22c9f0414ab63e0a22814c9a2ada740206e09091/packages/ui/src/components/chat/TurnCard.tsx). Android components were independently implemented without copying React source, English word-count buffering, or backend turn-merging policy. Research cache stays in ignored `out/research/`, not a product dependency.

## Reproducible checks

```sh
npm run verify
npm run build:android
bash system/assistant/test-native-conversation.sh
bash system/assistant/capture-drawer-visual.sh --verify-conversation
bash system/assistant/test-agent-interface.sh
bash system/assistant/test-composer.sh
```

The first two device-fixture categories test protocol projection and real Android view reuse/Unicode boundaries, not model acceptance. The latter two UI suites run real Codex conversations, restoring original selection and archiving tests afterward. Physical phones are outside scope; Mac input-method bridging retains existing settings.

## Acceptance results

- `npm run verify`, Android build, and installation passed; existing Linux FIFO cases skipped on macOS.
- 46 native-protocol projection checks passed: message phases, same-turn additions, tool exit codes, missing-phase fallback, and Stop/failure before the first reply.
- 52 native Android view checks passed: nine Chinese/emoji/incomplete-Markdown frames reuse one `TextView`; selection in old messages stays unchanged; expansion restores across rebuilds while other conversations default collapsed; approvals/errors remain visible.
- 95 existing draft-state checks passed.
- All five real-input regressions passed: pre-queue attachment invalidation, asynchronous upstream failure, normal image/text sending, same-turn image/text additions, and Stop. Model actually read random image/document codes; keyboard hid after send/append, failed drafts/attachments remained, and Stop preserved unsent drafts with native terminal state.
- Three real UI acceptances passed: Chinese streaming, native tool execution/collapse, and navigation/draft/keyboard/theme. A roughly 350-character Chinese case displayed text while running, sampled multiple native-delta lengths, and finished normally.

Screenshots come from independent test conversations and real Codex events, without mocked model replies:

- [Light: collapsed process and continuous body](collapsed-light.png)
- [Dark: expanded work log](expanded-dark.png)
- [Running: expanded command and output](running-light.png)
- [Chinese streaming: body visible before completion](chinese-streaming.png)
- [Chinese completion: final text and single Copy entry](chinese-completed.png)
- [Asynchronous failure: draft/attachments retained](failure-draft.png)
- [Same-turn addition: actual image/document reads](steer-completed.png)
- [Stop before first reply: terminal state and unsent draft retained](stopped.png)

The append case hit an attachment-menu accessibility timeout and switched to existing bounded polling; another rerun hit local-load time limits and passed after environment recovery. Failures/reruns remain in acceptance JSON, with original conversation/settings restored in each case.

Existing history-read limits remain; no pagination or long-history frame-rate stress test. This verification does not establish complete Markdown/CommonMark support.
