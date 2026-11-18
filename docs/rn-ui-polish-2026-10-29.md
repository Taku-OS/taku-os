# RN Agent UI layout polish — 2026-10-29

## Scope and source

This pass builds on `codex/agent-react-native-20261028` at
`06316c2b3c713e183927cb32e39470b782231889`. A successful `git fetch --no-tags
origin` and `ls-remote` check found no published remote RN branch; the original
local branch remained the latest source. Work is isolated in
`codex/rn-ui-polish-20261029`, without modifying the original checkout, pushing,
or integrating main.

The references are the PRD §4 Agent interaction Chat image (`sess-chat.png`)
and mobile DS v0.8 at `355e8c2d4edc436b6664e30c3edb396f732ad76c`, including its
Chat, composition, mobile and themes contracts. The original image and Skill
are retained in the Air `taku-os-chat-ui-20261027/out/design-reference/` and
`out/design-system-source/skill/taku-design-system/` directories. The app's
imported resources remain the source of truth; none are regenerated here.

Later user refinements take precedence over the illustration: the header keeps
its explicit conversation history and new-conversation buttons, and Copy stays
small and left-aligned. The requested scope is spacing, hierarchy and density,
not a new visual style. The supplied design supports light appearance only;
system dark preference must not introduce an unreviewed palette.

## Changes

- Activity and the 16dp Copy glyph share one left-aligned row below the reply,
  removing a separate 48dp row. The disclosure defaults and session/reply keys
  are unchanged; Copy remains available while details are expanded.
- Activity names and Copy use secondary color. Speaker/body spacing is 8dp;
  the single screen gutter and thread gap remain 16dp and 12dp. Message list
  top/bottom padding is normalized, and the sheet handle is easier to see.
- Type tracking is −1% of each role's own size rather than a fixed −0.16dp.
  Chinese tracking stays zero, with existing fonts and type sizes retained.
- The user bubble uses 88% maximum width and a 4dp trailing corner. The existing
  pale green treatment is retained. Card insets and failure-card grouping are
  made consistent; workspace previews use a broad 1.6 aspect ratio capped at
  244dp rather than a fixed height.
- Composer controls have 48dp touch targets and retain a small 32dp send face.
  The input grows from 48dp to 144dp, or 96dp below 500dp allocated sheet height.
  Footer spacing follows the keyboard/safe-area contract, with a quiet divider.

## Stability fixes

- Conversation restoration no longer waits for an optional history index. Local
  draft and unresolved-delivery recovery are independent of native selection.
  History errors belong to the history panel; action/delivery errors retain their
  own lifetime and cannot be erased by a successful background read.
- Session and workspace discovery have independent serialized read loops. A
  slow/failed preview lookup does not stall chat updates or imply that Agent
  itself disconnected. Cached native projection is shown before full-history
  hydration, preserving native completeness proof and generation guards.
- Gateway frame reads reuse a deep-copied, verified native workspace catalog,
  matching the existing floating preview approach. This eliminates two expensive
  full catalog calls per frame. Native discovery also probes only the requested
  thread, avoiding unrelated dead screen hosts. Exact thread/key/generation/display, mirror peer
  identity, JPEG validation, five-second catalog expiry and original-frame
  expiry remain enforced. Opening a workspace still uses a fresh validation.
- Read-only workspace probes no longer persistently revoke an admitted lease on
  transport failure. A confirmed identity mismatch still revokes it; successful
  reads never automatically re-admit a revoked lease. Execution validation still
  revokes admission on any validation exception.
- Android 30+ sheet sizing now receives a complete window/root/IME geometry
  snapshot from a non-interactive observer owned by the existing native module.
  It does not replace React's Insets listeners or change `adjustResize`. The
  actual remaining overlap and sheet height are calculated together; an already
  resized Android window receives no duplicate padding. This removes reliance
  on React Native's visibility-only keyboard event and stale visible-frame
  coordinates. Insets callbacks use their new value immediately, and later layout
  callbacks read fresh root Insets instead of retaining a cached keyboard state.
  Animation progress is not mixed into React's discrete layout: its previous
  position could overwrite the already delivered target and cover the composer.
  iOS and older Android retain their existing KAV path.
- Expanding Activity pauses automatic bottom following so the newly expanded
  details do not jump out of the viewport. Scrolling back to the bottom restores
  the existing follow behavior.
- A preview retry returns to its loading state instead of retaining the previous
  “unavailable” flag. Old frame callbacks cannot win over the current request.

No new model requests, dependencies, credential changes or simulated fallback
were introduced. Native controls and authoritative execution remain in the
existing runtime.

## Validation and device evidence

The final RN release (`system/agent-app/out/TakuAgent.apk`) is SHA-256
`9fe79a42a55bfb2af577438f6289b425fb2caedf85f9a105b20b653e1151a1bd`.
The companion APK (`system/assistant/out/AgentOSAssistant.apk`) is
`707b564157ba9c7d1e4759e847385d9f77d980fb904bc9a579039905f6ed4732`.
The broker JAR (`system/assistant/out/task-broker.jar`) is
`6d4e6939d736163782ebf8211b2db7e54a0eb8dd8c239c747e0cee03f8cb3dbe`.

- RN lint, typecheck and all 51 tests pass.
- Native execution-screen tests: 189 checks; workspace-execution: 86 checks;
  preview protocol: 70 checks; gateway protocol: 25 assertions; frontend route:
  24 checks; durable submission/receipt tests pass.
- Full companion/broker and RN Android release builds pass with the existing
  documented NDK/CMake overrides. No generated Android source edits.
- The real directory I/O failure regression fails against the original broker
  and passes after the fix. Per-thread discovery tests also fail against the
  original full-list implementation and pass after filtering.
- Independent review covered navigation races, draft/pending isolation, catalog
  lifetime, old reads/frames and the execution admission boundary.

The intermediate shared-catalog candidate (before thread filtering) passed five
ordinary-UID instrumentation samples: native directory 1080–1126 ms, JPEG 3–5 ms,
1 matching workspace, complete five-turn history. These measurements run with
the RN process under instrumentation and must not be compared directly with
foreground UI plus concurrent directory probes. Under the running UI, full-list
reads still took 3.68–4.71 seconds. The final filter targets that remaining load.

Final device measurements and paired screenshots are recorded below.

Confirmed before-device timings: five native workspace directory reads took
7.514, 12.200, 8.156, 4.660 and 7.930 seconds; ten direct mirror status/frame
reads succeeded in 12–127 ms. This isolates directory discovery, not JPEG
transfer, as the principal preview delay. The persistent admission-revocation
failure was source-confirmed and fault-injected in host tests; it was not
reproduced on the live device before the fix.

The live baseline is the original installed APK (also matching the original
worktree's output), SHA-256
`8f1d70b5d37cdc1b888366bfd40b2f5097b5995caaaa325945cd8be95bdb5144`.
Its build timestamp precedes the 06316c2 commit by three minutes. The screenshot
contains the requested activity placement, but no exact build-to-commit claim
is made. The isolated source checkout itself is based on 06316c2. A final source-branch
check still found that commit and a clean original worktree.

The first UI-only phase also captured four pairs on an isolated emulator using
three whitelisted turns from an existing acceptance record. Those
`polish-before-*.png` / `polish-after-*.png` images remain supplementary layout
records; final `polish-final-*.png` acceptance uses the original emulator and its
real native runtime. The isolated emulator has been closed to release resources.

## Live native acceptance

The assigned original emulator is `emulator-5590`, AVD
`Taku_Workspaces_20261026`, Android API 36 arm64, 1080×2400 at 420 dpi.
The connected Pixel is untouched. The original broker was a manually started
process in adbd's cgroup, not an init service. After confirming an empty durable
queue and no active model turn, only its exact PID and Codex child PID were
terminated. No process group, display host or device reboot was used.

The new broker runs from
`/data/local/agentos/components/rn-ui-polish-20261029-0120/task-broker.jar`.
Clock host PID 11556 and default host PID 12453 retained their original start
identities. Clock was explicitly rebound after a real thread read; its key,
generation and display 2 remained unchanged, with control owner `paused` and
admission true. The other two bindings were already unavailable with no live
hosts; neither was recreated or readmitted. This stage-path broker launch is a
runtime-only deployment, not a full-ROM or reboot-persistent system update.

With the final companion/broker, all five ordinary-UID probe samples passed:
per-thread directory 627–706 ms, JPEG 6–10 ms, one matching real workspace.
The same probe before filtering measured 1080–1126 ms directory reads. These are
small, controlled samples, not a performance benchmark.

With the RN UI actually polling, four captures at 1.077, 12.985, 24.130 and
35.831 seconds all show the real Clock timer preview with no loading/unavailable
state. Raw PNGs and timing JSON are saved as
`polish-final-preview-stability-01.png` through `-04.png` and
`polish-final-preview-stability.json` in the caller's `/tmp/rn_shots/`.

The old native list also contained two empty Calendar/Browser conversations.
After restart, `thread/read` reported not loaded and one explicit
`thread/resume` for each reported no rollout. Their original projection files
remain intact with zero turns. They cannot be honestly restored as live threads
without creating replacement conversations, which this task does not do. Clock
and another existing empty conversation were restored successfully. This is not
loss of conversation text, but the two unavailable empty entries no longer
appear in the live history list.

The original historical `Hi` task's network-failure card remains visible when
scrolled into view. It is not a new request, not a current connection failure,
and was not cleared or retried for a cleaner screenshot. No messages, failed-task
restoration actions or new model turns were submitted during acceptance.

## Remaining scope boundaries

- The explicit History and New Conversation controls reflect later user feedback
  and intentionally remain more detailed than the PRD header. Long titles
  ellipsize to preserve all control hit targets.
- The supplied DS supports light appearance only. System night preference is
  tested for consistent light presentation; a new dark theme is not invented.
- New model execution, Pixel hardware, full-ROM deployment, full accessibility
  and arbitrary third-party application behavior are not claimed by this pass.
- Freshness expiry always clears the preview. Any remaining directory delay
  beyond five seconds must remain visible; stale images are never kept to make
  the UI appear successful.

## Keyboard diagnosis references

The installed RN 0.83.10 `ReactRootView.checkForKeyboardEvents` only emits the
show/hide event when IME visibility changes, while its `screenY` is derived from
`getWindowVisibleDisplayFrame`. The original KAV inner-layout measurement added
a second, asynchronous height source. On the assigned emulator this produced a
composer hidden after 5.748 seconds and a half sheet shrunk to roughly one
quarter of the screen after hiding for 6.811 seconds. Those failed candidate
captures are retained; they are not treated as successful acceptance.

The fix follows Android's [IME Insets guidance](https://developer.android.com/develop/ui/views/layout/sw-keyboard)
and [window animation callback contract](https://developer.android.com/reference/android/view/WindowInsetsAnimation.Callback).
Seven pure geometry cases cover native-resized versus edge-to-edge windows,
stale JS measurements, repeated show/hide and changing keyboard height; device
verification remains essential and is recorded with the final captures.

The first fresh-Inset candidate removed the hidden-keyboard gap but failed two
subsequent show captures. Temporary, geometry-only native/JS tracing proved that
all events reached JS and committed: an animation progress value of zero followed
the applied 901px target and temporarily restored the hidden layout. The final
observer publishes applied/root/end targets only. Temporary JS tracing and its
native method were removed before the final build. The optional native geometry
log remains disabled unless Android's `TakuIme` tag is set to DEBUG.

## Final target-Inset device pass

The final 9fe79a42 release passed three ordinary half-sheet keyboard show/hide
cycles on the original emulator. All six first-capture PNGs retain a visible
composer while shown and return it to the bottom while hidden; a later settled
capture was not substituted. Each capture has native IME flags and timestamps.
Evidence: `polish-ime-9fe-cycle-{1,2,3}-{show,hide}.png` and associated JSON/logs
in the caller's `/tmp/rn_shots/`.

A further three cycles ran inside one ADB shell, with a minimum 0.7-second wait
and 0.5-second state polling. Each show was confirmed before Back, and each hide
was confirmed before the next tap. The final hidden frame is correct. The run
took 33.34 seconds because emulator state queries were slow; this is continuous
state validation, not a claim of one-second stress throughput. Device-side awk
was unavailable for optional event timestamp text; outer start/end timestamps
and native geometry logs remain available. Evidence: `polish-ime-9fe-rapid-device*`.

The full sheet was verified at its actual 128px safe-area top before show/hide
acceptance. A short swipe under emulator load only partly expanded it and was
not counted as full-screen evidence; a 1800ms drag reached the true endpoint.
Task/chat switching and history open/close passed. The existing unsent draft
`UI` survived close/reopen exactly and was then cleared. Bulk ADB text injection
entered only those two characters, so this is draft-persistence verification,
not a claim of fast-typing coverage. No Send or failed-task restore was invoked.

## Final paired captures and known transition limitation

The four original-emulator before/after pairs are in the caller's
`/tmp/rn_shots/`, each at 1080×2400. The final after images use the installed
9fe79a42 release and the real existing Clock session:

| State | Before | After |
| --- | --- | --- |
| Collapsed Activity and live preview | `polish-final-before-01-collapsed-preview.png` | `polish-final-after-01-collapsed-preview.png` |
| Expanded Activity | `polish-final-before-02-expanded.png` | `polish-final-after-02-expanded.png` |
| Half sheet and visible keyboard | `polish-final-before-03-half-keyboard.png` | `polish-final-after-03-half-keyboard.png` |
| System night preference | `polish-final-before-04-night-preview.png` | `polish-final-after-04-night-preview.png` |

The root reviewer inspected all four final after originals. The expanded capture
shows all five Activity rows without losing the reading position. The half-sheet
capture shows the full empty composer and keyboard without occlusion. These are
state comparisons, not identical scroll-position or pixel-difference tests.

Switching the system preference from night back to day produced one actual black
Home/preview Surface capture after a two-second wait. It did not display the
preview-unavailable label. The raw evidence is retained as
`polish-night-switch-transition-black.png`. Subsequent half-sheet/IME interaction
restored the Home and preview without restarting or reopening RN or the runtime;
the final day image is from 03:19. Because interaction occurred before the next
capture, pure idle self-recovery is **not** established. This theme-switch
transition remains an unresolved rendering limitation; its cause is not assigned
to RN, Surface reconstruction or emulator load without further evidence. The
settled day and night-preference images both pass visual review.

Final readback at 03:19:37 +08:00 confirms the exact installed RN hash, system
night preference `no`, `TakuIme` logging restored to INFO, hidden IME and empty
draft. Runtime is ready with zero queued requests. The original Clock thread
still has five terminal turns (four completed, one interrupted), no active turn,
and its original paused/admitted lease with an empty last error. Broker PID 4315
and display-host PIDs 11556/12453 retain their recorded start identities. The
state endpoint does not expose an `activeTurnId`; the no-active-work conclusion
uses thread/read and queue evidence. The preexisting/probe package ownership was
not conclusively recorded, so `org.agentos.gatewayprobe` was retained instead of
removing a potentially shared test package. Device operations have stopped.
Evidence: `polish-final-safe-state.json` and `polish-live-verification.json`.

## Follow-up: compact Activity list

The follow-up keeps Activity and Copy on their existing shared action row. Each
single-line detail row now has a 34dp minimum height instead of 48dp, with 4dp
rather than 8dp vertical padding and no additional inter-item gap. The existing
continuous left rule and status dots are retained, forming one list block.
Names stay at the 14sp meta size (body is 16sp); status text stays 12sp. Both use
a 20dp text line box, and a reserved 16dp chevron column keeps right-side status
and disclosure alignment consistent. Multiline names can still grow the row.

Lint, typecheck and all 51 RN tests pass. The release APK builds successfully:
SHA-256 `846dc4d23e15bf91274df0543ccd5b3e369d14089a1e23ef50139490882e602c`.
No new dependencies or interaction/business-state changes are included.

The app uses Expo SDK 55 with a prebuild workflow: `system/agent-app/package.json`
declares `expo: ~55.0.32`, `react-native: 0.83.10`, and the `prebuild:android`
script. `scripts/build-android.sh` invokes `expo prebuild --platform android
--no-install` before the local Gradle release build; the generated Android tree
is not checked into Git. This is an Expo prebuild/native-module project, rather
than an Expo Go-only managed app or a bare React Native CLI project.

The original emulator's real Clock conversation was captured before and after
as `/tmp/rn_shots/compact-before.png` and `compact-after.png` on the caller's
machine. All five detail rows are visible in both. UIAutomator bounds measure
126px per row before (48dp at 2.625 density), and 89–90px afterward (34dp with
pixel rounding). The five-row block shrinks from 630px to 447px, about 29%, with
contiguous row bounds and the continuous left rule preserved. The shared
Activity/Copy row remains 126px/48dp high; its screenshot position differs by
11px because of the sheet viewport, not a change to that control.

On-device single-detail open/close and group collapse/expand both pass. Final
readback retains the original five terminal Clock turns, ready runtime, zero
requests and the same paused/admitted workspace identity. Keyboard is hidden
and draft is empty. Companion, broker/runtime and Pixel were untouched during
this follow-up. Evidence is `compact-verification.json` and
`compact-final-state.json` beside the two PNGs.

## Follow-up: grouped Activity panel and close heading

The expanded details now share a 12dp-radius panel using the existing 4% neutral
surface token. Its continuous left rule and status dots are retained. Detail
rows shrink from 34dp to 32dp, with 2dp vertical padding and no inter-item gap.

The previously measured heading-to-first-label gap was about 29dp: approximately
14dp below the heading inside its 48dp control, 8dp between sibling components,
and 7dp above the first label. The heading and panel now form one zero-gap group.
In the expanded state, the action controls are 32dp high with their 20dp content
aligned at the bottom using positive padding; no negative margins or overlapping
hit targets are used. The panel's 2dp top inset plus the first row's 6dp text
inset produce an 8dp heading-to-first-label target. Copy stays on the same row
with its existing handler. Collapsed action sizing remains unchanged.

Lint, typecheck, all 51 RN tests and the release build pass. APK SHA-256:
`6bec52f98dad73ca1074cd4e25f0bb53502df68182ca8d217a4170d48da98333`.

On the original emulator's real Clock session, UIAutomator measures the Activity
heading text at y1266–1319 and Update at y1340–1393: 21px / 2.625 = exactly 8dp
between text line boxes, down from 76px / 2.625 = 28.95dp. All five detail rows
are 84px / 2.625 = 32dp, totaling 420px. The original PNG was visually reviewed
for the pale rounded panel, continuous rule, dots, readable status and same-row
Copy. Evidence: `/tmp/rn_shots/compact2-after.png` and its UIAutomator XML on the
caller's machine. The existing historical Stopped label visible above the final
reply was not cleared for the screenshot.

Single-detail open/close and whole-group collapse/expand pass. Final readback
shows the exact installed APK hash, ready runtime, zero requests, five unchanged
terminal Clock turns and the original paused/admitted workspace identity. IME
is hidden and draft is empty. Companion/runtime and Pixel were untouched.
Evidence: `compact2-verification.json` and `compact2-final-state.json` beside
the screenshot. Device operations have stopped.

## Follow-up: space around the tinted Activity panel

The heading-to-panel gap is now 8dp. The panel's extra 2dp vertical padding is
removed, leaving the first 32dp row's centered 20dp text box 6dp below the panel
edge. The pale background, 12dp radius, continuous rule, 32dp rows and same-row
Copy remain unchanged. Lint, typecheck, all 51 RN tests and the release build
pass; APK SHA-256 is
`f8423b0d725eb37ed0f676c3c60019cfe455916487f8cfba13687b4f90193545`.

The Stopped label above the final request was also investigated. It belongs to
the fourth historical turn, whose native status is `interrupted` and which has
only a user message. The fifth turn is `completed` with the final Clock answer
and Activity 5. The session projection and translations have identical Git
blobs at 06316c2 and 56e8493, and the stopped-state rendering condition is unchanged
across all UI commits. The 03:14 original screenshot already shows this footer;
recent before XMLs contain it clipped above the viewport. Compact2 brings it
back into the visible scroll area. It is not a new stop or connection error,
and the truthful historical status has not been removed. Evidence:
`compact2-stopped-verification.json` and `polish-final-pre-capture-state.png` in
the caller's `/tmp/rn_shots/`.

## Final Activity self-review: spacing and status ownership

References reviewed on 2026-10-29:
- [OpenAI's Codex on mobile public demonstration](https://learn.chatgpt.com/docs/mobile): muted execution disclosure, grouped execution rows and a distinct reply/action group.
- [Introducing the Codex app](https://openai.com/index/introducing-the-codex-app/), including its Vercel skill demonstration: compact execution text with chevrons and locally associated progress.

These are public product examples, not a measured specification or an interactive
review of a signed-in official app. They support quiet hierarchy and local state
ownership; the exact dp values below are Taku decisions. Neither reference showed
a stopped state, so the stopped treatment is our own UX correction.

Following the user's final explicit choice, the tinted panel keeps its status
dots and removes the vertical rail and the rail's extra 13dp indentation. The
background now supplies the group boundary, dots supply per-step status, and the
chevrons indicate details. This removes redundant decoration and gives long
labels more room. The panel retains 12dp horizontal padding and radius, 8dp of
space below the Activity heading, about 6dp above its first label, contiguous
32dp minimum rows and 14sp/12sp name/status hierarchy. Copy remains on the same
row with the same clipboard handler. Text can wrap instead of being clipped.

A real historical empty interruption is now labelled `Request stopped` inside
its original user bubble, without a redundant empty Tako reply and isolated
Stopped footer. Partial replies and tool history remain visible, with a local
`Response stopped` label. The presentation helper folds only an adjacent empty,
inactive stopped assistant block into a matching stopped user block in the same
native turn. It does not mutate native history or Task view; late-arriving output
is displayed on the next projection. Chinese equivalents are included. This is
a presentation change, not a change to task status, execution or model requests.

Five regression tests cover native-history preservation, partial output/tools/
attachments, unrelated or missing requests, live/failed/inconsistent states and
late history recovery. Lint, typecheck and all 56 RN tests pass. Release build
passes; final APK SHA-256:
`265db0b03c32b9e8250d00b5c48b3b58d478b7a18c19d13d32f588003ce54dfe`.
The Gradle daemon was stopped after building to release Air memory.

The final release was installed on the original emulator and checked in its real
Clock conversation. Final caller-side images are `compact3-after.png`,
`compact3-audit-collapsed.png` and `compact3-audit-detail.png`; all show a working
Clock preview. The root agent reviewed all three original PNGs, including the
expanded Update content. Measured heading-to-panel space is 21px / 2.625 = 8dp;
panel-to-first-label is 15px / 2.625, approximately 6dp; each of the five rows is
84px / 2.625 = 32dp. Dot, name, status and chevron columns align without the rail.
Copy remains on the heading row, and the compact list reads as a single group.
`compact3-audit-stopped-context.png` shows the historical interruption inside its
request bubble; `compact3-audit-tasks-stopped.png` confirms the unmodified task
history. Tasks-to-Chat navigation keeps the same conversation and completed reply.

The comparison `compact3-before.png` is a byte-for-byte copy of the accepted
compact2 screenshot from 56e8493, with its original viewport position; it is not
a reconstructed image. Transitional captures are retained separately and are
not final acceptance pictures.

During the shared-emulator investigation, a Markdown research app was visible
behind the sheet and an old capture genuinely showed Preview unavailable. The
Clock process/lease identity stayed intact; mirror JPEG decoding and same-UID
provider reads succeeded. After returning to the final RN package, three direct
screenshots across 24.4 seconds showed the real Clock image; a separate 10-second
background stay followed by approximately 10- and 21-second foreground captures
also showed it. This follow-up does not change preview/provider code and does
not claim that installing a new APK fixes the underlying transient. A foreground
catalog-refresh versus first-image-load race remains a diagnostic candidate,
not a confirmed root cause. UIAutomator and PNG capture times must not be treated
as simultaneous.

The old failed `Hi` task (id 1793195455353) remains in the conversation footer.
Its native request and failed state predate this work; it was neither cleared nor
retried to improve the screenshots. Evidence: `compact3-audit-history-failure.png`.
The earlier theme-transition black-frame observation and runtime-only broker
deployment limitation remain documented above. This pass does not claim enlarged
font or screen-reader coverage.

An additional attempted first-five-second check did not pass as a precise timing
test. Six screenshots requested after foregrounding spanned roughly 25 seconds
and showed Connecting to screen; the first screencap call itself took 9.41s.
A device-side 8s recording took 15.86s and contained only one Home frame, so it
cannot establish the first-frame sequence. A later screenshot recovered to a
normal Clock preview without a runtime restart. At that point the Air load was
11.24 with the original and a new headless emulator running concurrently. These
observations show a remaining foreground-preview recovery delay under the tested
load; they do not prove the load or catalog race is its root cause. The final
normal screenshots establish visible layout, not the absence of this delay.
No speculative timeout/TTL/lease changes were made. Evidence includes
`compact3-audit-foreground-first.json`, its six PNGs and
`compact3-audit-after-record.png`.

## Detail-only refinement after review

The Activity detail body now uses the same panel background, with no nested gray
surface or rounded box. It retains the existing 12sp caption and secondary text
color, and its 14dp left inset matches the 6dp status dot plus 8dp row gap, aligning
the detail text with the activity name. The old 10dp all-round padding is removed;
extra space after the detail is reduced from 12dp to 4dp. Heading, compact rows,
collapsed/expanded group appearance and Copy behavior are unchanged.

Lint, typecheck, all 56 RN tests and the release build pass. APK SHA-256:
`adcbfa56ebd0ffe8e5da8aba315aa33e7007566b8c943f947b01c3e9a127bf0b`.
The only requested new visual artifact is `/tmp/rn_shots/compact4-detail.png` on
the caller's machine; the accepted compact3 screenshots remain untouched.

The final original PNG was reviewed by the root and independent visual reviewer
with a normal Clock preview. Both the Update title and detail's first glyph begin
at x110. Detail bottom to next row top is 11px / 2.625 = 4.19dp; sampled detail
background equals the shared panel, with no inner surface. The original five
terminal turns, zero requests and original workspace identity remain intact.
No runtime/host restart or new model request was made; all compact3 screenshot
hashes are unchanged. Evidence: `compact4-geometry.json`,
`compact4-verification.json` and `compact4-final-state.json`.
