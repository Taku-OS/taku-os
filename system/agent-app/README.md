# Taku Agent — React Native UI

The Agent conversation UI uses React Native and Expo. It reads the existing
Codex sessions and sends requests through the native Taku assistant. The Android
runtime remains responsible for models, credentials, tasks and background screens.
There is no second conversation database and no simulated fallback history.

## What runs where

| Component | Responsibility |
| --- | --- |
| `org.agentos.agent` (this app, ordinary application UID) | Conversation history, Markdown, Activity disclosure, task view, draft input, question cards and background-screen previews |
| `org.agentos.assistant` | Signature-protected IPC gateway, native settings, screenshot context, approvals and screen controls |
| Existing Codex runtime and TaskBroker | Authoritative sessions, streamed execution, task delivery, cancellation and workspace ownership |

The gateway requires both the expected package identity and the matching Android
signature. The UI cannot call raw RPC methods, read model credentials or acquire
screen ownership directly. See [the gateway protocol](../assistant/agent-gateway.md)
for action schemas, error handling and large-response transport.

Chat and task views project the same native thread. In chat, each reply's Activity
appears below its text, during streaming and after completion. Expanding it keeps
the tools attached to that reply; expansion is scoped to the session and reply.
A background preview is read-only; opening it delegates to the
existing native screen controls. Closing the UI leaves the task running.

The chat is full screen (Figma 2-1). Only its window blurs what lies underneath, so the Feed,
Home and other apps look alike behind it: the system cross-window blur at σ 64 dp (Backdrop
Blur/3xl) under Surface/400, or an opaque #E3E3E3 where the device has blur off. Android 17
renders this radius smoothly; Android 16's Kawase blur shows a grid at it. The conversation blurs and fades progressively where it
scrolls under the title row and the composer (`EdgeBlurView`, Android 13+).

The chat opens on **TAKU**, the one persistent personal main conversation; every other
conversation is a **Task** (Single Chat). On TAKU the title row reads "TAKU" between the menu (top
left) and Collapse (top right). Under it, a row of capsules scrolls sideways, one for each Task at
work with its status: amber "Needs you" while the Task waits on a pending request, blue-grey "In
progress" while it has an active turn or a queued task. A capsule opens its Task; with no Task at
work there is no row. A Task that needs the user also has a card in TAKU's stream with what it
asked; a plain choice (`ask_user`, a single-choice runtime question) or a one-tap approval is
answered there with the gateway's `answer` for that Task's own request, after which the card and
the capsule both read "In progress" and only "View task" remains. A confirmation with details to
review, free text or secrets are answered on the Task's page. On a Task's page the menu becomes
Back to TAKU, the title is the Task's, the capsule row is hidden and the composer reads "Reply in
this task...". The statuses come from `readSession {statusOnly:true}` of each followed thread,
one read at a time and only while TAKU is on screen; nothing is inferred from message text. An
empty TAKU conversation greets the user with quick starts that put an opening line in the composer
and send nothing. When a request fails in TAKU's own conversation (or that conversation can no
longer be read), the failure card offers "Continue in a new TAKU conversation": the conversation it
leaves stays in Tasks, the unsent request comes along as a draft, and TAKU's next first message
creates and claims its new thread. Nothing is removed, so TAKU is never stuck on a thread the
runtime can no longer run. The same step is available on purpose, after a confirmation, as "New
TAKU conversation" under Taku Home's settings and tools.

The menu opens a drawer over four fifths of the screen (scrim, Close, Back or a swipe closes it):
TAKU, Panels with their count, Plugins with the number connected, then every Task with its status
and search. "New task" is pinned under that list at the foot of the drawer, above the personal space
row, which opens Settings. If tapped during a send, New task opens after that action finishes.
Panels opens Taku Home (2-5) over the chat on the same blurred window: tabs
All / Panels / Tasks ("See all" opens the section's tab), search, the real Panels directory, the
Tasks and the settings and tools (new task, chat/task view, settings, plugin market,
advanced controls). The Panels section pages the whole native directory and offers open, rename,
move and Unpin; an empty directory says to pin a panel from a chat. Opening a row brings the
desktop forward and selects that panel through the same `panels/open` operation as Agents, so the
list and the desktop show the same IDs and order. Panels render only on the desktop;
Back from Taku Home returns to the chat. The chat and Taku Home lists blur and
fade under the status bar and title row and along the bottom edge. Collapse (top right) hands the thread to the
home Dynamic Island (`assistant/resume`). Leaving the chat by Home, Back or Recents while a turn
runs hands it over silently instead (`assistant/handoff`): nothing shows while it works, and the
island card opens with the reply, question or error once the turn has one. It opens from the island's expand button, a double tap
on the gesture handle or the Taku icon, and swiping up on the handle leaves it like any app.
Long-pressing a finished reply opens the 0-3 style menu (dimmed, lightly blurred list, the
reply lifted on a card, Copy and Select pills); replies carry no copy button. The composer shows
the microphone while empty and the brand send button once there is text; several lines turn its
pill into a 24 dp rounded rectangle. The microphone dictates through the shared Audio Engine
(local, `TakuVoice` in modules/taku-home): the pill shows the 1-5 running transcript, waveform
and Stop; Stop waits for the last words, adds the text to the draft and Send takes over. Back
or leaving the conversation cancels the recording. Without the engine, or with voice turned
off in Taku AI, a toast says so and nothing records.

## Streaming body

The gateway carries native primary-reply snapshots as atomic `session/reply` events, alongside ordinary text/tool events, with one broker epoch and per-thread sequence. The Expo reader batches display notifications at 40ms. Hidden views unsubscribe without stopping the native task; reconnects replay from the last applied cursor or replace state with an authoritative snapshot. See [the relay contract](../assistant/SESSION_STREAMING.md).

Ordinary unframed messages keep frozen Markdown chunks and a mutable syntax-aware tail. Enriched Markdown 1.1.1 renders the tail; temporary syntax repair never changes the source used by Copy. Primary cards use the native semantic answer and its deterministic readable text for Copy and history. Noto Sans SC and JetBrains Mono are embedded by the Expo font plugin.

## Migration boundary

### Plugin market integration boundary

The conversation history menu opens `/plugin-market`. The
[route](src/app/plugin-market.tsx) uses
[`createNativePluginRepository`](src/plugins/nativeRepository.ts), which sends
the `pluginManager` action through the existing native gateway.
[`PluginManagerBridge`](../assistant/src/org/agentos/assistant/PluginManagerBridge.java)
forwards requests to the God Mode host's `taku-godmode` local socket. This is the
God Mode registry, separate from the Codex marketplace managed by the native
**Extension connections → Plugins and marketplace** page and the plain `taku-plugin` commands.

The app also declares the `taku-agent` scheme; the intended deep link is
`taku-agent://plugin-market` (Android intent:
`adb -s SERIAL shell am start -a android.intent.action.VIEW -d 'taku-agent://plugin-market' org.agentos.agent`).
The native extensions entry still uses its existing page. Deep-link resolution
and any future handoff from that entry require integrated-device verification.

[`contracts.ts`](src/plugins/contracts.ts) defines `PluginRepository` and its
identical `PluginNativeBridge` interface. The production route uses the native
repository, not the fixture or `unavailableRepository`. Its current scope is:

| Operation | Current implementation and remaining boundary |
| --- | --- |
| Installed list and details | Read the God Mode manager through `list` and `show`; display its enabled state, runtime state, manifest and registrations |
| Install, enable, disable, reload and remove | Forward to the same manager; installation takes a host-readable package path, while the UI's file picker remains unavailable |
| Catalog and updates | Catalog checks manager availability then returns an empty list; updates returns an empty list without checking remote versions. Neither is evidence that all community plugins are installed or up to date |
| Sources | Returns only `God Mode / local`; remote add/sync are unavailable. Non-HTTPS source URLs are rejected; the built-in local source cannot be removed |
| Import and rollback | Native directory/ZIP selection and this UI's rollback operation return `UNAVAILABLE`, even though the God Mode CLI supports rollback |

Each mutation returns `{ok:true,value}` or `{ok:false,code,message}`; after
success the UI rereads the manager. Connection failures remain explicit and do
not substitute fixture state. Future remote catalogs must carry real source
identity and installation state; verification must come from checked source
evidence, not a community repository name. `Taku-OS/taku-os-plugins` is not yet
configured as a source in this adapter, and its repository URL is not itself an
index URL.

Agent commands such as `taku-plugin godmode list`, `show ID`, `install PATH`,
`enable ID`, `disable ID`, `reload ID` and `remove ID` access this same manager.
Parameters, errors and further operations are documented in the
[God Mode guide](../../docs/plugins/README.md) and
[preinstalled Agent skill](../assistant/assets/skills/taku-open-access/SKILL.md).
The [engineering contract](../../AGENTS.md) requires GUI and Agent operations
to share authoritative data and validation. Source wiring and REA's separate
device results do not establish acceptance of every RN operation: verify each
read/write against the GUI on an isolated emulator with matching app, gateway
and host builds, and record unavailable operations explicitly. This documentation
update adds no device or ROM acceptance.

Ordinary conversation navigation can open this app when it is installed and its
signature matches the assistant. The native entry checks for pending drafts,
attachments and screenshot context before handing over. If an entry cannot be
transferred safely, it opens the existing native interface.

Power-key screenshot capture, Jev suggestions, attachments, model/account settings
and advanced screen controls retain their native implementations. The conversation
history menu includes an entry to the current conversation's native controls.
Screenshot input must remain an original image; this frontend does not convert it
to text or discard it during a handoff.

The source of truth for the appearance is the imported
[mobile design system](../assistant/design-system/README.md) and
[interaction contract](../assistant/design-system/interaction-contract.md).
The current handoff defines light appearance. Interface copy follows the Android
locale (English and Chinese). Font and icon licenses are retained under
[`src/theme`](src/theme/README.md).

## Local component gallery

The browser gallery renders all 37 catalog components through the same registry
as the phone UI, with clearly labelled sample data and independent preview state.
It runs without an emulator or a model connection.

```sh
cd system/agent-app
npm run gallery
```

Open **http://localhost:8083/components**. The browser home page also opens this
gallery. Search by component name or description, filter by category, or select
the 13 Jev candidate types used by inferred Matter layouts. Primary replies use
the current component catalog directly. Preview controls support local
interactions, Reset, Example JSON, and a live state inspector.

Slider dragging and numeric input update the same controlled value. SearchBar
writes edits back and submits the current text. Gallery data remains labelled
local preview data; these interactions do not prove a connected business service.

Chat GenUI and Home Feed use the same typography, values, surfaces, headers,
buttons and control drawing in [`src/feed/feedVisual.tsx`](src/feed/feedVisual.tsx),
with named tokens from [`src/feed/feedTokens.ts`](src/feed/feedTokens.ts).
The Chat catalog is `taku-chat-feed-1`. It accepts named presentation options
and rejects raw colors, gradients, arbitrary typography, padding and radii.
Selected and disabled controls, unset inputs, loading, review and media errors
use those shared implementations. React Native inner surfaces use translucent
fills and do not add backdrop blur.

Chat and Feed card bodies load supplied HTTP and HTTPS images, including local-network
addresses. Raster images and SVG share the same image-reference rule. Android release
builds permit HTTP traffic through the existing manifest config plugin. Images retain
their supplied URLs and report loading failures through the existing media presentation.

Saved and cached inferred layouts must pass the current catalog and binding
checks. Rebuilding an obsolete inferred layout preserves drafts, zero and false
values, stable selections, expansion and native card-view state. A primary reply retains
its supplied view and native source identity; a rejected or incompatible view
shows its readable answer and a rendering error without another layout model.
Historical V2 source records retain their original contents; their display
projection drops obsolete decoration and never replays a saved command.

The visual layer preserves state paths, stable selection IDs, emitted actions,
and native media ownership. Missing media sources and unavailable capabilities
remain visible as such. Gallery examples do not prove model generation or a
connected media service.

Money stays in decimal minor-unit strings. The formatter uses safe numeric
Intl samples for locale grouping and numeral glyphs, so large amounts retain
their digits on Hermes without passing BigInt into Intl.

## Build locally

The app targets Expo SDK 58 (React Native 0.88, React 19.3). Use Node 24.22.2
(CI uses this exact version; `engines` also accepts `^22.18.0` and `>=26`) with
its bundled npm 11.19 or newer, and JDK 17. The repository's
`../../scripts/android-env.sh` resolves the Android SDK and Java installation.
Expo Go cannot run this app's local native gateway module.

```sh
cd system/agent-app
npm ci                    # strict install-script policy, see below
npm run audit:deps        # full audit, scoped exception, signatures, Android bundle check
npm run doctor            # expo-doctor, pinned in devDependencies
node node_modules/expo/bin/cli install --check  # SDK version alignment
npm run typecheck
npm run lint              # ESLint over the whole package, zero warnings
npm test
npm run build:android
```

The `agent-app` job in `.github/workflows/ci.yml` installs with `npm ci --ignore-scripts`,
verifies vulnerabilities and registry signatures, then explicitly runs the repository's
Markdown patch and feed asset preparation scripts. It runs the checks above, then
`node node_modules/expo/bin/cli prebuild --platform android --no-install`. That runs the config plugins
(including the Gradle checksum pin in `plugins/withTakuBuildTools.js`) without a
Gradle build. CI does not build or run the APK. Upgrade Expo one SDK at a time with
`node node_modules/expo/bin/cli install expo@^<sdk>.0.0 --fix`, then run the checks above. Do not use
`npm audit fix --force` or `--legacy-peer-deps`.

The online Expo compatibility check follows the SDK's current recommended patch
versions. A new upstream recommendation can make an unchanged lockfile fail.
Use the installed CLI's `install --fix` to align the dependencies and commit both
`package.json` and `package-lock.json`; keep the compatibility check enabled.

### Dependency policy

- **Locked sources.** Before installation, CI runs `node scripts/check-dependency-lock.cjs`.
  Every dependency must have an HTTPS `registry.npmjs.org` tarball URL and SHA-512
  integrity pin; missing hashes, alternate hosts and local/Git dependencies fail.
  CI and the Android build invoke Expo through its local path, so a missing package
  fails instead of allowing npx to fetch an unpinned tool. Keep `npm audit` and
  `npm audit signatures` enabled. Signatures establish package origin, not that
  every line of dependency code is harmless.
- **Temporary picomatch exception.** The explicitly accepted policy in
  [`security/npm-audit-exception.json`](security/npm-audit-exception.json) covers
  only [GHSA-fc5q-39xc-vjpf](https://github.com/advisories/GHSA-fc5q-39xc-vjpf),
  expiring at **2026-12-18 00:00 UTC**. npm still reports 41 high-severity
  affected-package entries; accepting this tooling risk does not fix the advisory.
  CI runs the full official-registry audit at every severity and checks the exact
  lockfile digest and accepted findings graph. Changed dependencies, new or changed
  findings, an incomplete audit, or an expired policy fail the check. Normalization
  ignores ordering, duplicate edges, npm's computed transitive version ranges and
  suggested upgrades; it preserves every direct advisory field, severity, installed
  package path and dependency edge. Registry signatures remain mandatory.
  A fresh production Android JavaScript export must exclude picomatch, micromatch
  and tinyglobby from every bundle's recursively inspected source maps. Missing maps,
  DOM/executable assets and unsupported bundle layouts fail until reviewed. The
  exception assumes build tooling receives no untrusted external glob patterns.
  `npm run audit:deps` performs these checks locally, including the reviewed Markdown
  and feed preparation scripts after signature verification. CI retains the complete
  audit, decision and bundle evidence as `agent-app-dependency-audit` for 30 days;
  local copies are under ignored `out/security/`. The deadline is checked on each
  invocation; historical successful checks are not automatically rerun at expiry.
  Retire the policy in a reviewed change after a compatible fix or advisory withdrawal;
  never refresh its digests or extend its deadline automatically.
- **Install scripts.** `.npmrc` sets `strict-allow-scripts`, so `npm ci` fails
  when a dependency adds an install script that `allowScripts` in
  `package.json` does not cover. `react-native-enriched-markdown@1.1.1` is
  allowed and pinned to that version: its postinstall downloads the tree-sitter
  runtime (SHA-256 checked against its manifest) and grammar sources (exact
  versions from registry.npmjs.org, not hash-checked). The published package does
  not include them, and the Android build compiles them while
  `enableCodeHighlight` is on. `unrs-resolver` is denied. Its postinstall only
  downloads a native binding as a fallback, and npm already installs the binding
  as an optional dependency. Review the script source before approving a new or
  changed version. Source CI skips all dependency install hooks, including this
  allowed downloader, because it does not compile the native grammar sources.
  Local Android builds still need the reviewed native asset installation above.
- **Overrides.** `glob` (GHSA-3g3m-2fjr-6pw4) and `minimatch`
  (GHSA-g3hv-75wg-m249) have no fixed release in the majors that Expo SDK 58 and
  typescript-eslint request. The overrides are scoped to `@expo/cli`,
  `@expo/fingerprint` and `@typescript-eslint/typescript-estree`, and move them
  to glob 14 / minimatch 11. Before adding them we checked that the module
  format and exports are the same, that those packages use only APIs that still
  exist, and that `@expo/fingerprint` gives the same hash before and after.
  ESLint's own minimatch 3 is not affected and is not overridden. Remove the
  overrides once upstream depends on the fixed majors.
- **Navigation peers.** `react-native-reanimated`, `react-native-worklets` and
  `react-native-gesture-handler` are declared at the SDK-aligned versions,
  because `expo-router`'s drawer requires them as peers. Left undeclared, npm
  installs their newest releases, which may not match the SDK.
- **ESLint 9.** npm marks ESLint 9.39.5 as end-of-support. `eslint-plugin-react`
  and `eslint-plugin-import`, which `eslint-config-expo` 58 uses, do not support
  ESLint 10 yet.

The build generates Android sources through Expo config plugins, includes the JS
bundle in the release APK, and signs it with the same **development** certificate
used by the repository's native assistant. The output is `out/TakuAgent.apk`;
a running Metro server is not required. Generated `android/` and `ios/` folders
are not source files and should not be edited manually.

Build the companion assistant from the same checkout with
`bash system/assistant/build.sh`. An older assistant without the gateway cannot
serve this UI. Installing the frontend alone does not upgrade the runtime.
Do not uninstall either package to upgrade it: use an in-place APK update so
existing conversations, drafts, accounts and app data are preserved.

When explicitly testing an installed alternative toolchain, the build plugin
accepts `TAKU_NDK_VERSION` and `TAKU_CMAKE_VERSION`. They are optional overrides;
the normal SDK defaults remain unchanged when they are absent. A successful
compile with an override still requires device verification. The current local
candidate is being evaluated with NDK `28.0.13004108` and CMake `3.31.6`.

The development signing certificate is not a production release policy. A ROM or
public distribution must define and protect its own matching signing keys.

## Android canary delivery

The 0.2.0 canary uses Android `versionCode` 2 and the existing download channel:
`https://download.taku.ai/releases/0.2.0/TakuAgent-0.2.0-arm64-canary.zip`.
This is a ZIP archive containing `TakuAgent.apk` and `manifest.json`.

1. On Mac mini, build from a clean checkout of the exact landed `main` commit.
   Record its full SHA, verify it against `origin/main` and the passing CI run,
   then use the local checks and `npm run build:android` above. Record the SDK,
   JDK, NDK and CMake versions actually used. Use the repository's development
   signing certificate so the app can access the matching assistant gateway.
2. The system assistant gateway and TaskBroker must come from that same `main`
   commit, including its session-streaming protocol. Record the required runtime
   commit in the manifest. This frontend ZIP contains no system image or runtime
   upgrade; installing its APK cannot make an older gateway/broker compatible.
3. Package the signed arm64 APK in a real ZIP. Its manifest records the channel,
   version, versionCode, application ID, ABI, source/runtime commits, UTC build
   time, APK filename, byte size, SHA-256 and signing-certificate SHA-256. Keep
   signing keys, credentials and device data outside the archive. Record the
   final ZIP's SHA-256 and byte size separately alongside the release evidence.
4. Upload through the existing release storage to the versioned URL above as an
   immutable object. If that key already exists, reuse it only when its bytes
   match; publish changed bytes under a new version/path. Keep the existing
   Desktop release metadata and active pointers unchanged.
5. Download the published URL into a fresh directory, verify the ZIP hash and
   integrity, then verify the extracted APK against the manifest, its signature
   and Android package/version metadata. Install that downloaded APK in place
   with `adb -s <assigned-serial> install -r TakuAgent.apk`; preserve existing app
   data and confirm the installed version and real gateway/model streaming path.
   Development and GPU-emulator acceptance run on Mac mini. Air is reserved for
   separately assigned physical-device checks.

## Generated cards and panels

Normal conversation replies use one main-model component/data stream. The model starts `@taku.reply/1`, then emits strict JSONL `begin`, semantic `block`/`item`/`action`, append-only `element`, and `done` records. The native owner validates each complete record and publishes the first useful card prefix immediately. The app installs that supplied view synchronously; primary replies never enter a formatter, layout cache, Jev composition, `generateUi`, or inference retry.

Accepted snapshots live under `turn.mainReplies`, with native reply identity, monotonic revision, typed data, supplied view, field readiness, readable text and lifecycle. `streaming` and logical `sealed` remain pending until the native turn succeeds; only then can the presentation become `completed`. Malformed/truncated output, interruption and runtime loss retain the last valid content with an explicit failure/interruption. Ordinary unframed prose remains ordinary text. Wire records never become the visible answer body.

Component identities, parents and props remain fixed within a reply; new complete nodes append to existing parents. Later records update bound semantic values and stable-ID items without clearing known content. The existing renderer store retains drafts, selections, expansion and scroll. Only explicitly pending fields reserve loading regions; unannounced media/icons and tagged null do not create phantom skeletons.

The native owner derives one readable text projection from the same semantic data. Copy, search, history, the native conversation and chat result files use that projection. There is no separately generated prose answer or raw-wire disclosure. Reply identity derives from the native thread, turn and user-message occurrence, so canonical item-ID changes do not remount the card. Existing frozen threads receive the current confirmed reply contract before their next turn. Structured task schemas, MemoryGen and realtime keep their own formats.

Card inputs live in native card-view state: `card-view/read` returns `{matterId,uiRevision,ui,review?}` and `card-view/apply` commits semantic field and selection changes with the revision they were based on. A card only shows **Pin to Panels** when the native snapshot's `pin.canPin` is true, and only a committed `panels/pin` receipt reports Pinned; `false`, `0`, an empty text and a cleared selection are sent as real values. A stale revision produces a conflict, which rebases once on the canonical state and then becomes a visible error. Edits made during hydration win for their own fields, while untouched fields take native state.

Cards use the shared V2 contract: stable block/item/action IDs, typed values and generic metric, collection, input, media, route and other content roles. There is one outer Card surface. V1 scene blocks are historical data and cannot resume actions. Actual Matter/tool cards without a supplied primary view retain immediate templates and validated cached, Jev-composed or explicitly generated layouts. Catalog changes invalidate inferred layouts while preserving stable inputs and selections.

Jev candidates identify their stable element keys and required parents, so grouped details remain inside their expandable content. Hermes uses ordinary Promise constructors for composition deadlines, evaluator completion and photo export. Catalog version `taku-chat-feed-1` invalidates earlier layouts; reachability, binding and action checks still apply. Long collection descriptions render below visible item names, short comparable values remain aligned, and supplied source links remain accessible. Failed media collapses its unusable image area.

Primary content properties bind the semantic source instead of copying a second set of labels or facts into component literals. Native validation checks the current generated component schemas, typed values, declared bindings, tree reachability and final content coverage. Model-authored data cannot manufacture business success, live sources, confirmation receipts or action capabilities. Actual observations and continuing source ownership remain with native tools and Matters.

`config.size` controls the information budget. `full_size` leads a direct weather request with a large temperature, icon, high/low and five-day forecast. `medium_size` supports an explanation; `small_size` embeds a compact weather row in a larger plan. Related blocks share `config.group`, with detail children under their first Metric and expansion stored in `/ui/views/<group>/expanded`. All sizes use the same native weather resource. Values and freshness updates reuse the layout; changing the requested size selects its corresponding layout. See [the size contract](../../docs/generative-ui-composition-state-spec.md#431-choose-content-size-by-user-intent).

The native `matter` tool can create a source view from `{action:"present",key,source,size?}` without model-authored UI data. Custom requests use the strict V2 schema. Native tool cards remain distinct from a main reply that explains them. Native sources own `/data`, `/meta` and `/commands`; the shared Zustand store owns drafts, selections and view state. Source updates preserve edits, refresh untouched inputs, and require review after incompatible type/unit changes.

The signature-protected `readMatter`, multiplex `subscribeMatter` and `dispatchMatter` gateway serves an ordinary application UID. Buttons dispatch opaque native action references and stable request IDs; repeat delivery reuses the receipt. A completed read-only refresh may start a new intent after the native cooldown. Consequential followups retain the existing TaskBroker confirmation path. Native scheduling, source ownership, SQLite persistence and recovery do not depend on an RN view timer. Weather and Figma blog are real public reads; memory, task receipts and started audio sessions use their existing native sources. Figma is a bounded current feed, not a complete X/Figma incremental daily-brief service. Commerce, home devices, X, Slack and Linear remain unavailable without their actual adapters and authorization.

A definitive `notSent` receipt allows the next explicit attempt to use a new ID. Accepted, queued and uncertain outcomes keep their original IDs, so another press cannot duplicate execution. Null inputs stay unset until interaction; zero, a range minimum and `false` require the same explicit confirmation as other values. TimeDisplay preserves supplied item details through state resolution and progressive assembly.

A panel is one native `PanelRecord` keyed by a stable `panelId`, not a `threadId/key` record. Chat, the Home list and the desktop read the same directory: `panels/pin` resolves the source natively, `panels/continue` publishes the trusted target for the next turn, and Unpin removes the panel from both surfaces without cancelling a task or a rule. The UI reports failed actions and live-connection state, and offers retry.

On the desktop, long-press the panel card to open its **Unpin** menu; Unpin is not a persistent card button. Cancel closes the menu without changing the directory. Confirming Unpin uses the same native operation as chat and Agent callers. Each desktop root coalesces overlapping directory and foreground invalidations into one running read and one pending reread, so mounting adjacent pages does not flood the gateway. A failed matter read shows the page's retry action.

### Opening a panel conversation

`TakuPanel`'s **Continue editing** and `TakuAgent.openPanelConversation(panelId)` start the exported
conversation Activity with the panel's standard extras, so an ordinary application UID can navigate
the same way:

```sh
am start -n org.agentos.agent/.MainActivity \
  --es threadId <originThreadId> --es panelId <uuid> \
  --es sourceVersion <n> --es originThreadId <originThreadId>
```

The module validates the id through `panels/get` and accepts the target only when
`originThreadId == threadId` and `capabilities.canContinue`, then emits
`onOpenThread {threadId, panelTarget?}`. Navigation sends no task. The chat keeps the target as
navigation context only: its next send goes through `panels/continue` with the expected
`sourceVersion`, the same request identity and receipt UI as an ordinary message, and the target is
cleared on a thread change or after the accepted send. Failures such as `SOURCE_THREAD_NOT_FOUND`
or `PANEL_NOT_FOUND` are shown as real errors; nothing falls back to a sample or a second composer. Media surfaces use actual native playback/camera/capture resources; meeting summaries cite finalized recognition evidence and can seek near its recorded observation time, explicitly not word alignment. Playback translation does not claim video timeline alignment. Per-scenario verification and prerequisites are recorded in [the acceptance ledger](../../docs/reviews/generative-ui-v2-2026-11-02/README.md).

Native retention protects pinned panels, edited drafts, selections, pending review, active view/read leases, rules, live audio and unresolved commands. Unowned temporary projections and orphaned sources are reclaimed on demand. Terminal command receipts are archived before their bounded projection is removed, preserving duplicate-request lookup. When protected state fills storage, admission fails before replacing existing pinned state.

Agents can inspect the same native primary snapshot through the standard Android `reply/read` method documented in [the gateway protocol](../assistant/agent-gateway.md). The component/action schemas and primary prompt are generated from `genuiCatalog.ts` during both APK builds and packaged from ignored build output; no generated catalog copy is tracked in source.

## Source layout

- `src/app/`: Expo Router entry and root layout.
- `src/components/`: conversation, Activity, question, workspace and generated-card UI.
- `src/model/`: projections of native sessions (unknown history stays unknown) and the generated-card catalog, template and checks.
- `src/hooks/`: ordered session subscriptions, task/status polling, selected conversation, durable drafts and submission receipts.
- `src/native/`: typed gateway client, native Markdown renderer and the edge-blur view.
- `src/chat/`: Figma tokens, icons and copy for the chat (2-1) and the Taku Home page (2-5: the Panels directory, chat history, settings and tools), mounted by the chat's Home button. The chat passes the threads and actions through `TakuHomeProps`.
- `src/panels/`: the Panel wire client and product surfaces. `client.ts` calls `panels` and `cardView` on the Assistant gateway and checks the `taku.panels/1` contract; `PanelCatalogView.tsx` is the Home list; `PanelRoot.tsx` is the `TakuPanel` root the desktop mounts with only `{panelId}`; `matterDemand.ts` is the one ref-counted owner of native matter leases across chat and desktop; `cardViewSync.ts` writes shared inputs back with revision-conflict rebasing; `host.ts` reads Taku Home's directory invalidation and pager navigation. The desktop page chrome, pager keys and navigation stay Kotlin/Compose in modules/taku-home, which mounts `TakuPanel` through the shared ReactHost.
- `modules/taku-agent/`: local Expo module for Android IPC, navigation intents, the window backdrop and `EdgeBlurView`.
- `plugins/`: repeatable Android manifest, theme, branding and build configuration.

Before a send crosses IPC, its request ID and draft are stored locally. An unknown
delivery result retains that ID; a retry must not create a second task. Native
receipts also survive process restart. A lost IPC response is checked once again
with the same ID; reopening a conversation checks a stored pending receipt once.
The gateway can queue a next turn only after a definite rejection of a stale
steer. An older unknown result still needs reconciliation rather than replay.
The unresolved-request card also lets the user continue editing without
resending: it first saves the exact original pending record under its own ID,
then releases the send guard while retaining the current draft and native
receipt. A failed archive or a changed pending record leaves the guard in place.
The user is warned that sending the same request again may repeat its effects.
Preview reads retain the thread ID, screen
key and generation so an expired screen cannot silently become another preview.

A TaskBroker failure remains visible even when execution produced no chat turn.
The failure card keeps the original request and can restore it into an empty
draft. Restoring does not submit it; it also preserves a different draft already
being edited. A failed task is never assumed to be safe to replay automatically.

## Verification status

This is a candidate UI migration. Host tests, emulator rendering, Pixel behavior
and full-ROM integration are separate checks. The acceptance record is maintained
in this checkout's `out/agent-react-acceptance/`; it must identify the exact APKs,
device and tested actions. Neither a successful build nor an IPC probe proves
that the React Native screens have passed visual review.

The 2026-10-28 emulator run read existing native Clock conversation history and
its background-screen frame, created a real conversation, and preserved an unsent
draft across a frontend process restart. Repeated keyboard opening/closing, a
compact multiline composer, Chinese locale, 130% text size and returning from
the native background-screen viewer to the same React conversation were checked.
Native draft and pending-input preferences remained unchanged during that return.
A message submitted through the new UI
reached TaskBroker but failed during the existing runtime's network routing
discovery. A new successful model response and live streaming are therefore not
accepted by this run. The layout candidate still requires the user's visual
review before integration into `main`; Pixel and full-ROM acceptance remain open.

The subsequent 2026-10-29 streaming implementation and Air samples are recorded
in [the original Markdown report](../../docs/rn-markdown-streaming-2026-10-29.md).
The [Mac mini visual correction and performance follow-up](../../docs/rn-markdown-streaming-mini-2026-10-29.md)
tracks the newer candidate: pending-code highlighting, code/table overflow cues,
blockquote padding and the reply-copy spacing. Its Mini lint, typecheck, 90 tests,
repository verification and app-api tests have passed. Both Mini release APKs
built successfully, and the optimized candidate completed three real sessions
per version on the independent GPU AVD. Janky medians were 12.86% / 12.24% and
P50/P90/P95 medians were 20/38/48ms / 19/36/48ms. The last candidate run was worse
(16.39%, P95 57ms); it is retained alongside an additional adjacent A/B control.
That control did not reproduce the large text-phase delay; code/table UI and
frame tails remained comparable. Including all four runs per version gives
11.99% / 12.13% Janky and 20/36/44ms / 19.5/35/46ms. The small +2ms P95
difference is retained; the evidence supports no persistent, obvious regression,
not a causal speedup or zero overhead.
The three final Mini screenshots passed visual review. Main integration, its
exact-commit CI, and the 0.2.0 download/install receipt are recorded separately
in the PR and ignored release evidence; this note does not claim publication.
Development and emulator acceptance now run on Mac mini, with Air reserved for
separately scoped physical-device checks. Pixel and full-ROM status remain
independent of this follow-up.
