# Agent chat UI: visual direction and selection record

## 2026-10-19: current implementation status

As of **2026-10-19**, the committed assistant is v54. The current visuals build on v51's orange-and-white Taku conversation UI and retained dark theme, with the real conversation title at the top, continuous body content, and execution details collapsed by default. v53 added Chinese streaming, stable views, and expansion preferences; v54 added native CommonMark + GFM rendering. The v50 and October 13 concepts below are selection history, no longer current item-by-item implementation targets. See [v51 conversation UI](reviews/native-conversation-2026-10-18/README.md), [v53 collapsed worklog](reviews/conversation-worklog-2026-10-19/README.md), and [v54 Markdown](reviews/markdown-rendering-2026-10-19/README.md).

v55/v56 model connections and return navigation from the administration web page are committed as `a547672` and accepted on the development emulator. Pixel 9 completed partial physical-device acceptance of the v44 native client on October 15, but that evidence does not cover the latest v54/v56 UI; the complete latest ROM has not yet been rebuilt successfully. See [Magpie UI acceptance](reviews/magpie-runtime-2026-10-19/README.md), [native device client](native-codex-implementation-2026-10-15.md), and [current status](current-status.md).

## 2026-10-18: Taku branding and navigation redesign (v50, historical version)

The user found the existing Agent UI difficult to use and visually unsatisfactory, choosing “a more distinctive system-assistant style with stronger branding.” That round retained the system drawer and changed themes to deep indigo and teal, and warm light colors with deep green; v51's orange-and-white conversation UI later replaced those colors and navigation.

The chat header retained the real conversation name and added a lightweight Taku mark. Responses used the full content width with an assistant marker above the body; user messages remained right-aligned. The composer displayed model and reasoning-effort text, using the same selection, send, steer, stop, and draft-saving logic. The top of the drawer added three action entries: new conversation, background displays, and capabilities; these buttons did not switch a parallel task state.

Settings were grouped by workflow, preferences, and current conversation, with model, tasks, and connection opening their respective native pages directly. Directly opened pages returned to settings; the capabilities page opened from chat returned to chat. Returning from the conversation list, changing theme, and navigating to/from background displays preserved drafts. Both themes, the keyboard, and real navigation were accepted with an independent test conversation; the test sent no model messages, restored the original conversation and theme, and archived the test conversation afterward.

Retest entry point: `bash system/assistant/test-agent-interface.sh`; results are in `out/agent-interface-20261018/`. Versioned screenshots and scope are in `evidence/builds/2026-10-18-agent-interface.json`. This round deployed only to the dedicated MacBook emulator and did not update the physical Pixel or complete ROM. Landscape and large-font support still require separate acceptance.

## 2026-10-13: system assistant drawer (historical selection)

The user clarified that it should not feel like a standalone app: it should feel like part of the image's system, with a floating component rising from the bottom, almost filling the screen, like a pull-up drawer. This superseded the earlier standalone chat-page container; the previously approved dark/light visual styles could still serve as references.

The new primary experience was a system assistant drawer floating over the current app: retain a small portion of the dimmed background app, provide a drag handle at the top, expand chat upward, and collapse downward to return to the original foreground. Chat remained central inside the drawer, supporting multiple conversations and a top-right settings icon, while preserving the requirements to omit Codex branding and top tabs. Desktop and system-assistant entry points were connected; downward collapse was handled only on the handle, without taking over SystemUI global navigation gestures. Static designs do not substitute for real interaction acceptance.

This round's three designs, numbered in actual display order separately from earlier rounds:

1. System drawer design 1 (local concept: `out/agent-design-20261013/system-drawer/option-1.png`)
2. System drawer design 2 (local concept: `out/agent-design-20261013/system-drawer/option-2.png`)
3. System drawer design 3 (local concept: `out/agent-design-20261013/system-drawer/option-3.png`)

The selection mapping is `out/agent-design-20261013/system-drawer/selection.json`. These are generated visual concepts; example conversations, blurred backgrounds, and execution progress are not runtime acceptance evidence.

The user ultimately selected system drawer images 1 and 2 and requested the real conversation title at the top. The generated and explicitly approved revised visuals were dark (local concept: `out/agent-design-20261013/selected/dark.png`) and warm light (local concept: `out/agent-design-20261013/selected/light.png`), with the selection in `selected/selection.json`. Those two were implementation targets at the time; see the version records above for subsequent visual evolution. The earlier standalone chat page remains only exploration history.

This phase implemented an `AssistantSession` system window, the same `AgentDrawerView` in dark/light themes, real thread titles/list, settings icon, drafts, keyboard adaptation, and downward handle dragging. `PanelActivity` was a transient entry point, with independent task affinity allowing drawer collapse to return to the original app; there was no second app UI or task queue. Interaction regression and invocation-screenshot acceptance passed at the time; the [module documentation](agent-module.md), drawer QA, and corresponding build records are authoritative.

## Early standalone chat-page exploration (selection history retained)

The user explicitly required the entire main UI to be chat, without top tabs; settings as a top-right icon; multiple conversations; no Codex name in the UI; and a common underlying model using Codex App Server threads / turns / items / events. Multiple design images were to be generated first, selected by the user, and then implemented faithfully.

The user judged the first round's two minimalist images too simple and lacking design. The second round generated three new designs with different composition, visual language, and execution-detail presentation; these formed the selection set at the time and were later superseded by the system drawer design.

Later feedback was that “2 and 3 are both good.” A shared chat structure with dark/light themes was proposed, combining design 2's conversation/execution hierarchy with design 3's branding and soft textures, and two revised designs using that structure were generated. Those revisions were shown but not implemented; the user did not explicitly select them as the final target before moving to the system drawer.

- Revised dark design (local concept: `out/agent-design-20261013/unified/dark.png`)
- Revised light design (local concept: `out/agent-design-20261013/unified/light.png`)

Revised images, original generation paths, and SHA-256 records are in `out/agent-design-20261013/unified/design.json`. Second-round options and these revised designs remain separate, without overwriting or confusing selection numbers.

Second-round designs in actual display order:

1. Design 1 (local concept: `out/agent-design-20261013/round2/option-1.png`)
2. Design 2 (local concept: `out/agent-design-20261013/round2/option-2.png`)
3. Design 3 (local concept: `out/agent-design-20261013/round2/option-3.png`)

Paths, original generation results, and SHA-256 mappings are stored in `out/agent-design-20261013/round2/selection.json`. Generated images are visual concepts, and conversations/device execution states shown in them are examples, not model or physical-device acceptance evidence. Numeric user selections must map to these three images' actual display order, not inferred from first-round images or request initiation order.

References: [OpenAI mobile product introduction](https://openai.com/index/work-with-codex-from-anywhere/), [MacStories mobile screenshots](https://www.macstories.net/club/macstories-weekly-issue-513/my-favorite-tips-and-tricks-for-working-with-codex-inside-chatgpt-on-ios/). The light conversation and dark conversation list were actually viewed through public image-browser pages; source screenshots served only as design references.

Concept images remain in ignored `out/` and are not distributed by source clone. The system drawer is implemented on the dedicated emulator; the early Activity page is no longer a delivery target. See [module documentation](agent-module.md) for device account authorization, real model responses, and read-only command acceptance on 2026-10-14. The older October 11 candidate was not repackaged as a result, but later Pixel 9 component updates passed [October 15 v44 physical-device acceptance](native-codex-implementation-2026-10-15.md). Do not confuse the old candidate's scope with subsequent component deployments. Latest v54/v56 still lack physical-device and complete-ROM acceptance for those versions.
