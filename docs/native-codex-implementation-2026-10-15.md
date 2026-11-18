# Pixel 9 native Codex client acceptance · 2026-10-15

v44 (20261015-native-codex) is deployed on a physical Pixel 9 using Codex App Server 0.161.0. Integration follows the protocol actually exported on the phone, covering 168 real client methods and excluding mock/experimentalMethod. Experimental interfaces are labeled, and Windows-only interfaces are disabled on Android.

## Presentation

- Conversation: Markdown responses, plan steps, tool progress, expandable terminal/MCP output and file diffs, and context usage; real questions, MCP information requests, and approvals appear as cards. Instructions sent during execution use native turn/steer; stopping uses turn/interrupt and pauses the active goal.
- Everyday settings: model and its supported reasoning efforts, multi-agent policy, quota and context compaction, conversation search/branch/archive, goals and budgets, queue, Apps/MCP, Harness/Skills.
- Developer pages: file browsing/read/write, terminal and code review, Hooks, plugins and marketplaces, remote pairing, memory/projects, and runtime events; remaining operations use workbench forms generated from native parameter schemas.
- Native phone tools: new conversations register the canonical taku_phone namespace, including device information, independent-screen observation, app launch, tapping, and Unicode text input. Existing conversations keep their creation-time tool sets.
- Voice: real 24kHz PCM recording, AudioTrack playback, native realtime text/audio protocol, and exit cleanup. Starting the microphone requires a tap on the phone.

The native queue uses App Server directly: submitting while idle calls turn/start; during execution it uses thread/queue/add, with native queue interfaces for modification/reordering/deletion. No separate business queue is created.

## Completed real acceptance

1. Native IPC: successfully read the 168-method catalog, 7 model entries, actual quotas, and MCP metadata.
2. Ordinary model tasks and Android read-only commands succeeded; dynamic device_info returned Android 16 and Pixel 9.
3. Sending instructions from the phone during execution actually changed the response in the same turn; a real request_user_input card appeared, and tapping/submitting coffee resulted in the model replying with coffee.
4. Goal set/read/clear, queue add/modify/reorder/delete, and file read/write passed in a dedicated acceptance conversation.
5. Physical-device PCM input produced 4800 bytes, and playback-device initialization succeeded; acceptance recordings were neither saved nor uploaded.
6. Wi-Fi, FlClash, and assistant services recovered after cold boot; sending Hi from the desktop automatically opened the drawer and received a response, and subsequent replies in the same conversation passed.
7. The latest version retained long-press power invocation and full-screen four-edge animation; stay-awake-while-plugged-in was restored to off.

Full tests and screenshots: out/native-client-2026-10-15/. Structured acceptance record: [2026-10-15-native-codex-client.json](../evidence/builds/2026-10-15-native-codex-client.json). Visual report: [device UI and acceptance](reviews/pixel9-native-codex-2026-10-15.html).

## Service boundaries still incomplete

Realtime cloud voice actually returned thread/realtime/error: `realtime conversation requires API key auth`. The current ChatGPT Pro login cannot enable this service. No paid API is configured or consumed, pending the personal API-use choice. Working recording and entry points do not establish available cloud realtime conversation.

The 168 methods cover protocol registration and UI routing. A model catalog does not mean the account can access every model; an MCP tool catalog does not mean every external business tool has passed acceptance. Experimental services, remote pairing, plugin installation, and vendor attestation have not each undergone production validation. The Unicode-input executor is deployed but lacks separate physical-device functional acceptance; new dynamicTools are not appended to old conversations. Internal model reasoning is not displayed.

This round used development-device component updates (APK and broker JAR), without rebuilding the complete ROM or publishing an OTA. Accounts and conversation history are retained, and source includes AOSP integration configuration; formal images still need a complete build and system-release acceptance. No account logout, credential changes, or quota resets were performed.

## Build and data

Native broker SHA-256: `c4b959610f4f402106104e0a5b01fe3cb23e1a02793fa4cf851cac256e6e7006`, matching the file installed on the phone.
Public source and the old broker backup are in out/native-client-2026-10-15/before/; account tokens, proxy-subscription keys, and login files are excluded from source, reports, and backups.

Protocol reference: [OpenAI App Server documentation](https://learn.chatgpt.com/docs/app-server); implementation is governed by the schema exported from phone version 0.161.0.
