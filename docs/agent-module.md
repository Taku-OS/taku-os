# Taku system Agent drawer

As of **2026-10-21**: Jev shares on-phone Magpie; see the v57 section below for validation scope. v54 conversation rendering and v55/v56 Magpie model connections/return navigation are committed, with the latter implementation at `a547672`; v55/v56 have development-emulator acceptance. Physical Pixel 9 passed v44 native-client, real-model, and selected system-capability acceptance on October 15, which does not cover the latest UI or Magpie. The complete latest ROM has not yet rebuilt successfully. See [current status](current-status.md), [Pixel 9 native client](native-codex-implementation-2026-10-15.md), [v54 Markdown](reviews/markdown-rendering-2026-10-19/README.md), and [v55/v56 Magpie](reviews/magpie-runtime-2026-10-19/README.md).

Android `VoiceInteractionSession` hosts the main UI, rising from the bottom while retaining the current app background. The top shows the native conversation title, with conversation switching on the left and settings on the right; there are no top tabs or underlying-runtime branding. Desktop, assistant, and notification entries use the same host. Pulling the handle down restores the previous foreground app; closing the drawer does not cancel background execution.

## Entry routing (2026-11-07)

The desktop “Taku system assistant” icon (`PanelActivity`), `android.intent.action.ASSIST`, assistant key/voice, and double-tap gesture bar (`SystemUiProxy.startAssistant`) share `AgentFrontendLauncher.openConversation`: ordinary invocation opens full-screen chat in `org.agentos.agent` on the last selected thread. `AssistantSession.onShow` first makes the same decision, so system assistant invocation no longer always opens the native drawer. `AgentFrontendRoute.ordinary` allows only `conversationId`, `invocation_type`, `invocation_phone_state`, `invocation_time_ms`, `org.agentos.assistant.extra.TRIGGER`, and `android.*` framework parameters. Unknown parameters such as `page`, `nativeControlsOnly`, `task_id`, or Jev triggers, enabled `includeScreenshot`, or native drafts/attachments/pending send acknowledgements/Home pending text on that thread retain the native conversation to preserve context. Missing chat app or signature mismatch also falls back to native. Code and host tests (`test-agent-gateway-host.sh`) are updated; device acceptance is pending.

## Layers and maintenance entry points

| Module | Single responsibility |
| --- | --- |
| `AssistantSession` | System window, prior foreground app and Android-provided screenshots, show/hide lifecycle |
| `PanelActivity` | Transient UI-less system-assistant entry; independent task affinity without raising the desktop task stack |
| `AgentDrawerView` / `DrawerTheme` / `DrawerDragBehavior` | Chat/conversation/settings views, dark/light themes and handle drag; no root commands |
| `AgentSettingsView` / `AgentSettingsUi` / `AgentAccountView` | Agent-specific settings cards/account authorization sharing drawer theme; no Android system-settings page |
| `AgentAccountController` | Account queries, login initiation/cancellation, and visible-lifetime refresh inside settings |
| `AgentAccount` / `AccountBridge` | In-memory native account projection and UID-controlled read-only socket; login codes/URLs never enter task files |
| `AgentDrawerController` | Native thread selection, creation, rename, and visible-lifetime projection subscription |
| `AgentConversation` / `NativeTurnPresentation` | Preserve native turns/message IDs/phases; group body/worklog, without private reasoning/authentication or a fixed workflow |
| `NativeConversationView` / `NativeTurnView` / `NativeActivityView` | Stable-ID view updates, collapsed worklog by default, continuous body, expandable tool details |
| `ConversationExpansion` | Bounded per-conversation expansion preferences without message content |
| `AgentRepository` / `TaskRequests` | Single-thread device IO, draft/appearance preferences, atomic submission/control RPC to the same queue |
| `TaskBroker` | Sole task queue, independent-screen allocation, recovery, notifications; retain/isolate bad records without replaying unconfirmed operations |
| `CodexAppServer` / `AgentViews` | One persistent App Server stdio connection, native event consumption, disposable UI projections |
| `NativeBridge` / `PhoneNativeTools` | UID-controlled native-protocol entry, device information, background-screen observation/operation tools |
| `ModelConnection` / `ModelConnectionView` | Codex ChatGPT/Magpie connection selection/catalog display; no Agent-runtime replacement |
| `system/display/` | Real independent Android background displays/viewer |

Release ROMs compile only `src/` and `broker/`. `tests/visual/` is a separate test APK rendering example content through production views, without starting runtimes or executing example actions; it is not model acceptance.

See [conversation-rendering acceptance](reviews/conversation-worklog-2026-10-19/README.md) for v53 continuous body, collapsed worklog, and Chinese streaming.

## Native runtime contract

The phone pins Codex **0.161.0**; fields follow schemas generated by that ARM64 binary. All Home and chat requests use App Server, without a separate exec path.

Initialize the connection once: `initialize` → `initialized`. UI uses `thread/start`, `thread/list`, `thread/read`, `thread/name/set`; execution uses `account/read`, `thread/resume`, `turn/start`, native streaming events, and `turn/completed`; stop uses `turn/interrupt`. Conversation IDs come directly from App Server without an invented UUID mapping.

Native thread/turn/item is the conversation authority; `runtime/agent-view/` is only a display projection. SharedPreferences stores selection, per-conversation drafts, expansion preferences, and settings. Local requests not yet in the model are labeled separately; after a native turn starts, native messages display them without duplicate bubbles. Tool completion means tool terminal state, not successful volume/app/other operations.

Home one-shot tasks retain structured-result schemas. Ordinary chat has the main model emit `@taku.reply/1` component/data streams; native code validates each and generates readable body text from the same source. Cards, copy, history, and native chat read the same answer, without a second formatting/layout model. Existing conversations receive the new contract through Harness acknowledgement receipts; structured tasks, Memory organization, and realtime retain their formats. The queue executes serially while conversation controls remain available during active turns. Stop waits for actual native terminal state and does not undo completed operations. See [assistant documentation](../system/assistant/README.md) and [session streaming](../system/assistant/SESSION_STREAMING.md) for execution budgets/stream protocol.

Background restart does not automatically replay running tasks. Corrupt records stay in their original directory, isolated through `state.invalid.json` and `done=invalid-record`, without clearing task directories. Writes use temporary files, fsync, and atomic rename. Long history currently has a 2 MiB display/read limit and conversation-list requests cap at 100; pagination is absent, so unlimited history is not claimed.

## Jev decision models and on-phone Magpie (v57, 2026-10-21)

Jev and the main chat model share on-phone Magpie services, credentials, and routing management. Jev makes a lightweight judgment about the current page; only after the user taps a suggestion does the existing Codex Agent create a task, receive the original screenshot, and continue. Decision-model selection is separate and does not alter the main model or ChatGPT login.

```text
This assistant invocation → current page text / screenshot → on-phone Magpie → structured Jev decision
                                                                                 ↓
                original Codex Agent ← user taps suggestion ← suggestion
```

### Configuration and use

1. Open Assistant settings → **Jev decision model** → **Manage models and routes**, then add a service supporting Decisions, such as OpenRouter, in local Magpie. Reuse existing services/credentials; the Jev Router chat model in the main catalog is not a decision model.
2. Return to Jev settings, select **Refresh decision models**, choose an actually listed decision model, then enable it. With no model, check service/catalog first; if a selected model disappears, report an error without silently switching.
3. Optionally select **Test decision connection**. It sends only fixed synthetic text and incurs a small model usage amount, without current-page content or unsent drafts.
4. The explicit development entry `org.agentos.assistant.TEST_JEV_SIMULATED_POWER` currently verifies one-shot invocation. The receiver requires `android.permission.DUMP`; tokens expire after 10 seconds. It neither intercepts the physical power key nor continuously watches the screen.
5. After a suggestion appears, tap “See the plan” to submit to the original Agent. No task exists before tapping. At acceptance, recheck enablement, model selection, suggestion expiry, and conversation busy state; one suggestion cannot submit twice.

Read the catalog through local `GET /v1/models` with `X-Magpie-Deciders: 1`, accepting only `kind: decision`; decisions use local `POST /v1/systemone`. UI neither directly requests OpenRouter nor saves another credential set. Decision requests have a separate IPC branch without occupying the main Codex RPC queue.

### Page input and credential migration

Send screenshots only when models declare image support. Text models read Android AssistStructure text for **this invocation**, skipping passwords/disallowed Assist nodes and bounding traversal; absence of readable text fails explicitly. HTTP success does not mean image understanding. Pinned Magpie 0.1.1290 omits input capabilities for some ordinary providers' decision catalogs; UI shows “Image capability unknown” and uses page text only, without guessing from model names. Acceptance still passes the original screenshot to the main Agent, whose model must support images too. Screenshots/Assist data belong only to this invocation, not background historical collection.

During upgrade, the Android Keystore-encrypted old Jev key imports to Magpie through restricted in-memory IPC: reuse an existing service with the same OpenRouter key, otherwise add a new one without overwriting services. Broker rereads Magpie private storage to confirm persistence, then the app persists a migration marker and deletes the old copy. Failure keeps the encrypted original for retry. Multiple-model catalogs require explicit selection, without guessing upstream Jev IDs. Keys never enter task files, receipts, logs, source, APKs, or ROMs.

Original-conversation unsent text/attachments are neither attached to suggestion tasks nor cleared. Structured receipts contain model, input mode, and decision result, excluding credentials/original page text.

### Validation scope

- See [Jev / Magpie acceptance](reviews/jev-magpie-2026-10-21/README.md) for v57 builds, protocol regression, and Android-emulator acceptance. Android tests use synthetic chats/explicitly labeled synthetic model responses while running real on-phone Magpie, Codex, Broker, and native UI; they do not establish real Jev quality. The subsequent [real flow](reviews/jev-magpie-live-2026-10-21/README.md) used existing credentials on the same-version emulator for Jev → Magpie → suggestion → original Agent; main model `gpt-6.1-sol` read dinner facts from the screenshot while drafts/attachments survived.
- The old direct-connection version previously verified a real Jev suggestion-to-Agent flow on an emulator; that does not substitute for real-model acceptance of this gateway path.
- v57 lacks same-version physical-device/full-ROM acceptance. Continuous listening, physical power key, automatic real-chat triggers, Memory, map launch, and restaurant booking are outside this delivery.

## Context and extensions

Screenshots are off by default. When enabled, Android supplies the original app view at assistant invocation and sending reuses it; it is neither a send-time live capture nor a background-screen view. Missing system screenshots fail explicitly and retain drafts instead of capturing the assistant itself. Screenshots are used only in local task context/authorized model requests, never source/ROM. Main-display foreground app and independent display ID are different context fields. Prefer stable system commands; GUI operations target independent displays only.

Model/reasoning options come from the native Codex catalog. v55 adds “Model connection,” switching ChatGPT/on-phone Magpie and selecting Magpie models/routing groups. Codex still owns conversations, tools, approvals, and stopping; DeepSeek inference calls an online API. v56 adds management-page return navigation to the current conversation. Both were committed as `a547672` by 2026-10-19 and accepted on the emulator; see [device adapter](../adapters/magpie-runtime/README.md) and [acceptance](reviews/magpie-runtime-2026-10-19/README.md).

On-phone account connection and native Harness/Skills panels provide context provenance, public-community skill installation, enable/disable, and conversation loading; see [Harness and Skills](agent-harness.md). Native questions, approvals, and MCP information requests are connected; v44 physical-device testing verified same-turn steering, question cards, goals, queues, and selected flows. The 168 methods cover protocol registration/UI routing, not execution acceptance of every service. Plugin-service installation, remote pairing, vendor attestation, and other services need separate validation; see [native-client boundaries](native-codex-implementation-2026-10-15.md). Extensions continue using `spec/extensions.json` and versioned contracts, without copying registries or adding schedulers. Trusted local extensions are not a third-party sandbox.

## Validation and boundaries

```sh
bash system/assistant/build.sh
python3 system/assistant/scripts/sync-design-system.py --check
# Preinstall these components; only dedicated AVD Taku_ImageIntegration_20261010 / emulator-5634 is accepted.
bash system/assistant/test-system-drawer.sh
# Independent synthetic event guards and actual IPC permissions, without OAuth completion/model tasks.
bash system/assistant/test-account-state.sh
# Run separately; do not share the emulator with interaction regression above.
bash system/assistant/capture-drawer-visual.sh
npm run verify
```

Interaction tests check real thread titles/list, themes/drafts, keyboard layout, logged-out path, collapse dragging, previous-app restoration, and pre-invocation screenshots. Screenshots/logs go to ignored `out/agent-drawer-ui/`; redacted artifact summaries go to `evidence/builds/`.

Historical preauthorization acceptance covered only real `account/read`, device-code/browser `account/login/start`, and this attempt's `account/login/cancel`: the emulator obtained a native authorization challenge, then cancellation cleared code/URL while it remained disconnected. That round neither authorized for the user nor accepted model generation/model-driven actions. After user authorization, real models/system calls/stopping have records below and linked separately; Pixel 9 also has later component-update device/cold-boot acceptance. Component builds, visual fixtures, and early logged-out interactions do not replace later execution evidence. The older October 11 candidate lacks latest components, and the latest complete ROM has not rebuilt successfully.

Screenshot requests set `SHOW_WITH_ASSIST | SHOW_WITH_SCREENSHOT` together: AOSP [AssistDataRequester](https://android.googlesource.com/platform/frameworks/base/+/master/services/core/java/com/android/server/am/AssistDataRequester.java) disables screenshots when Assist data was not requested; screenshot-only flags return an empty image. Assist structure is not this module's persisted context.

See [design selection](agent-design-direction-2026-10-13.md) and [drawer visual QA](../system/assistant/drawer-design-qa.md) for selected visuals/comparison. Official interface: [App Server](https://learn.chatgpt.com/docs/app-server). Regenerate schemas from the phone-pinned version and revalidate protocol/device boundaries on upgrade.

## Agent settings and account connection (2026-10-14)

The top-right entry opens independent `AgentSettingsView`, containing only account connection, appearance, screenshot context, and conversation title/response style. Dark/light themes use the same cards/controls; long content scrolls inside the drawer, and theme changes/return to chat retain drafts. Android Settings serves only as the foreground-restoration test's background app; its review screenshots belong in collapse acceptance, not product settings.

Runtime manages official OAuth on the account page. Device-code flow displays a one-time code and opens the official verification page; browser flow opens the native auth URL in the phone browser, with the phone runtime handling local callbacks. Both start through the same App Server without borrowing host login caches or external organization credentials.

Login requests use the existing control queue, but result files contain only an empty acknowledgement object. Account state containing code/URL is read only from memory over `taku-agent-account` LocalSocket. The server verifies peer UID, allowing only root/system; the client also requires server UID root, rejecting same-name impersonation. This socket adds no scheduling, model calls, or conversation authority. UI reads no auth files and saves no authorization code/URL to SharedPreferences, task directories, projections, or this round's reports. This adapter no longer persists runtime stderr.

The authorization waiting page uses FLAG_SECURE; cancel/failure/success events clear temporary challenges. ChatGPT connected status requires actual `account/read`; a lone successful login/completed event cannot make UI declare login. Independent protocol tests cover events arriving before RPC responses. This round verified connection and cancellation of incomplete login, without revoking existing global sessions or covering logout/account switching.

Actual emulator regression includes login entry, real native device-code challenge, cancellation/temporary-data clearing, and prior chat/draft/theme/keyboard/collapse/screenshot checks. One-time codes were neither captured nor logged; snapshots were saved only while logged out after cancellation with no challenge. Protocol tests use synthetic events in an independent process, not real OAuth-completion evidence. UID2000 was actually denied reading the memory endpoint.

Basis: [official App Server account interface](https://learn.chatgpt.com/docs/app-server), with fields governed by phone-pinned 0.161.0 schemas.

## Real account and model acceptance (2026-10-14)

The user completed device-account authorization. The controlled memory endpoint confirmed connected=true, challengePresent=false without reading/exporting auth caches. `test-live-agent.sh` used the existing account and real chat UI to create a separate acceptance conversation: the model replied “Taku is connected” (in Chinese) and executed two read-only getprop commands. Android version/model in the reply matched independent device reads; native commandExecution records were completed with exitCode=0. Actual model: gpt-6.1-sol; test passed in 40.807 seconds. Original conversation/screenshot selection was restored afterward, without login/logout or replaying old failed requests.

Real acceptance found turn/completed items may be partial: replacing the whole projection array lost tool history. Completion events now merge by native item ID, then terminal-state thread/read retrieves complete authoritative history before publishing task completion. History-refresh failure does not relabel completed device execution as failed. The first missing-tool-record failure log is retained; the real flow passed after repair.

```sh
# Real model and read-only command acceptance on the logged-in dedicated AVD.
bash system/assistant/test-live-agent.sh
```

The logged-out case in `test-system-drawer.sh` applies only to disconnected dedicated devices; do not log out an existing account to satisfy it. Real-model screenshots/redacted evidence are in `out/agent-live-ui/` and `evidence/builds/2026-10-14-agent-live-model.json`. Original logged-out/canceled-authorization records remain historical evidence.

This October 14 emulator acceptance covers ongoing conversation and Android read-only commands, not volume, wallpaper, app GUI, interruption, or physical hardware/cold boot. Later [system calls/native app acceptance](agent-system-app-acceptance-2026-10-15.md) adds emulator settings, app creation/update, and volume; [cancellation acceptance](agent-cancellation-2026-10-15.md) verifies real command-process cleanup; [v44 Pixel 9 acceptance](native-codex-implementation-2026-10-15.md) adds physical-device models, selected tools, and cold boot. Each keeps its own scope; these do not repackage the complete latest Pixel 9 ROM.

## Cleaning actual command processes on stop (2026-10-15)

Runtime ownership and turn-process identity tracking are added. After interruption, clean up observed child processes created in this turn and confirm results, preserving other conversations/prior-turn processes; late stop requests do not reach new turns. The dedicated emulator accepted the real button, nested processes, concurrent conversations, and subsequent model replies. See [cancellation acceptance](agent-cancellation-2026-10-15.md) for scope/reproduction; this is not rollback, a third-party process sandbox, or physical-device acceptance.

## Native device client and voice boundaries (2026-10-15)

v44 deployed to physical Pixel 9 through component updates and verified real models/read-only commands, dynamic device information, same-turn steering, question cards, goals/queues/files, and network/assistant recovery after cold boot. New conversations register five `taku_phone` tools: device information, background-screen observation, app launch, tap, and Unicode input. Registration does not establish device behavior acceptance of every tool; Unicode still lacks independent device acceptance.

The physical device verified 24kHz PCM input/playback initialization and native realtime text/audio protocol integration. Current ChatGPT Pro login actually returned `realtime conversation requires API key auth`; cloud realtime voice remains unavailable, with no paid API configured/consumed. See [Pixel 9 native-client acceptance](native-codex-implementation-2026-10-15.md) for complete evidence/unverified services. These conclusions belong to v44; latest v54 UI and v55/v56 Magpie retain their separate emulator scopes.
