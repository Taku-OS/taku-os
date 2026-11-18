# Native Markdown rendering, v54

Status: `emulator_verified`. Local `Taku_ImageIntegration_20261010 / emulator-5634`, Android API 36 / ARM64. Installed version 54 / `20261019-markdown`; device APK checksum matches local build: [acceptance.json](acceptance.json).

## Comparison with Craft implementation

Research based on Craft Agents commit [`22c9f041`](https://github.com/craft-ai-agents/craft-agents-oss/tree/22c9f0414ab63e0a22814c9a2ada740206e09091):

- [`Markdown.tsx`](https://github.com/craft-ai-agents/craft-agents-oss/blob/22c9f0414ab63e0a22814c9a2ada740206e09091/packages/ui/src/components/markdown/Markdown.tsx): ReactMarkdown + GFM, with body, headings, lists, quotes, links, code, and tables, plus math/diagram/desktop-file previews.
- [`CodeBlock.tsx`](https://github.com/craft-ai-agents/craft-agents-oss/blob/22c9f0414ab63e0a22814c9a2ada740206e09091/packages/ui/src/components/markdown/CodeBlock.tsx): language labels, Shiki highlighting, copying.
- [`StreamingMarkdown.tsx`](https://github.com/craft-ai-agents/craft-agents-oss/blob/22c9f0414ab63e0a22814c9a2ada740206e09091/apps/electron/src/renderer/components/markdown/StreamingMarkdown.tsx): chunking/caching to reduce updates to completed blocks during streaming.

Taku independently implements native Android rendering without copying React, Shiki, or desktop-preview source. The same Codex native message string is parsed into a standard AST, with Android views reused by block; no new message protocol, task steps, or execution actions.

| Capability | Taku v54 |
| --- | --- |
| Headings, bold, italic, strikethrough, inline code, escaping, entities | CommonMark + GFM, native selectable text |
| Ordered/unordered/nested lists, read-only tasks, quotes, rules | Native layout; task lists are not fabricated runtime progress |
| Backtick/tilde fences, indented code | Source preserved; top-level code blocks have language labels, horizontal scroll, and separate Copy |
| Code highlighting | Lightweight lexing for common languages and diff additions/deletions; unknown languages displayed literally, without claiming Shiki-equivalent coverage |
| Tables | Shared column widths, alignment, inline formatting, horizontal scroll; unchanged cells retain selection |
| Web/email/file-path links | Standard links dispatched to Android; host-workspace paths retain copyable addresses, not treated as local files |
| Images | Standalone image paragraphs asynchronously load HTTPS/embedded bitmaps with bounded download/decode sizes, caching, preview, and visible errors |
| Streaming | Chinese displays immediately; full document reparsed for later reference definitions, only changed blocks updated; incomplete code fences preserve source |
| HTML, Mermaid, LaTeX, desktop preview fences | Readable source retained; HTML not executed and no Craft desktop editor/diagram/formula previews |

Code inside lists/quotes uses monospace text; standalone blocks have full Copy/horizontal-scroll controls. No claim of migrating the complete Craft desktop-preview suite or passing the entire CommonMark specification suite.

## Maintenance boundaries

- `NativeMarkdown`: standard AST, native styling, literal protection.
- `NativeMarkdownView`: stable block nodes/source cache; `NativeMarkdownTable`: stable table cells.
- `NativeCodeHighlight`: linear lexical coloring preserving original characters.
- `NativeMarkdownLinks` / `NativeMarkdownImage`: link dispatch, bitmap loading, failure fallback.
- Body and expanded process text share the renderer; tool output remains literal. Existing turn collapse, attachments, drafts, and input events retain their protocol.

Dependencies are pinned in `system/assistant/markdown-dependencies.json`, about 295 KiB of Maven Central JARs. Builds verify/cache SHA-256; runtime uses no CDN. Manual SDK builds include parser Java entity resources, and ROM `java_import` uses identical JARs. BSD-2-Clause / MIT licenses ship in APK assets. Existing source snapshots include these pinned dependencies.

## Verification

```sh
npm run verify
npm run build:android
bash system/assistant/capture-drawer-visual.sh --verify-markdown
bash system/assistant/capture-drawer-visual.sh --verify-conversation
bash system/assistant/test-agent-interface.sh --test testNativeMarkdown
```

- 81 actual Android fixture assertions in each light/dark theme passed: escaping, entities, links, lists, tables, code literals, unclosed fences, later references, copy, horizontal scrolling, Chinese/emoji, paragraph identity/selection, image decoding, and failures. Fixtures are not model acceptance.
- 52 existing conversation-rendering checks passed, including nine Chinese incremental frames, selection in old paragraphs, default-collapsed native work logs, and saved state.
- Real Codex Markdown acceptance passed: headings/inline formatting, literal code, tables, links, and theme switching. Actual replies: [light](light.png), [dark](dark.png). Test conversation archived and original conversation/theme restored.
- Full Android component build and `npm run verify` passed (existing Linux FIFO cases skipped on macOS). Device APK/build SHA-256 matched; entity resources and both dependency licenses checked.

Fixed manual DEX packaging omitting `entities.txt`. Clipboard checks need foreground focus; fixtures now wait for it and restore the original clipboard. No full-ROM rebuild, physical deployment, or long-conversation performance stress test.
