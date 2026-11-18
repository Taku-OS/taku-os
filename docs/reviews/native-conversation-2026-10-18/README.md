# Taku conversation UI, v51

Implemented from the user-approved “Conversation UI 03,” retaining the native Android system-assistant drawer and existing Codex app-server. This version updates only assistant APK, not TaskBroker, runtime, system image, or physical phone.

## Rendering contract

| Data source | UI behavior |
| --- | --- |
| thread.name / preview | Current conversation title and list |
| userMessage.content | Original text/attachments in one bubble; file attachments support existing broker text envelopes |
| agentMessage.text | Selectable Markdown body and Copy, ordered by item |
| commandExecution, dynamicToolCall, mcpToolCall, etc. | Compact separate tool entries; type/status/command/output from native objects |
| item output deltas | Routed to matching tool details by item ID; expansion retained by turn/item ID |
| fileChange | Actual paths and diffs on expansion |
| plan item / turn/plan/updated | Only native plan text or actual step/status; no plan event means no plan section |
| native requests, goal, diff, error | Existing actual request/goal/change/error entries retained |

Missing/unknown tool status is not success; `success: false` is failure, `success: null` does not imply failure. `aggregatedOutput: null` is neither rendered as a string nor allowed to hide later output. No fixed workflows, percentages, fake timestamps, automatic summary cards, or preset tasks.

`NativeToolPresentation` is separate from Android Views for independent protocol checks. Tool output shows at most the last 16,000 characters. Image tool results show “Tool returned an image” (translated); existing background display remains in the menu, with no new image-result viewer. Body uses the existing lightweight Markdown parser, without new WebView/rendering dependencies.

## Visuals and interaction

- Actual Taku mark, warm-white background, orange key actions; existing dark preference retained, new installations default light.
- Body-first layout, on-demand tool details; upper-right menu groups settings, model, reasoning level, capabilities, and background display.
- Composer retains attachments, voice, Send, and same-turn additions. Stop remains available during execution even with a draft.
- Taku vector path comes from existing `taku-desktop-three-tier/src/renderer/src/assets/onboarding/taku-mark.svg`, frozen as an Android drawable without an external-repository build dependency.

## Verification

Device: MacBook `Taku_ImageIntegration_20261010`, `emulator-5634`, API 36. Tests restored original conversation/theme and archived test conversations; protocol tests use independent memory objects and do not write mock replies into user history.

- `npm run build:android`: assistant, background display, overlay, and app-kit development components compiled; only assistant APK installed.
- `bash system/assistant/test-native-conversation.sh`: 27 protocol-projection checks for missing/unknown states, failure, interruption, empty output, attachments, actual plans, and native IDs.
- `ComposerPreferencesTest`: 95 draft-state checks passed.
- `bash system/assistant/test-agent-interface.sh`: real Codex command status, retained output expansion, final body, themes, model menu, history, background display, Chinese drafts, and keyboard avoidance.
- `bash system/assistant/test-composer.sh`: missing-attachment failure, asynchronous-send failure, image/file send, same-turn attachment addition, and Stop with a draft all passed through real queue/runtime. Long-message attachment case additionally checks final-line character bounds against clipping.
- `npm run verify`: repository checks passed; existing Linux FIFO platform cases skipped on macOS.

Summary: [acceptance.json](acceptance.json). Images are actual emulator screens with real test requests/model answers, not design composites.

- [Light conversation](conversation-light.png)
- [Dark conversation](conversation-dark.png)
- [Tool running](tool-running.png)
- [Chinese keyboard](keyboard.png)
- [Attachment send](attachments.png)
- [Stop with draft](stopped.png)

This does not establish Pixel 9 or full-ROM acceptance; voice still depends on an existing device speech-recognition service.
