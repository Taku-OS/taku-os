# Taku OS RN Markdown streaming implementation and measurements (2026-10-29)

The report's preferred approach is implemented in the independent MacBook Air worktree `taku-os-rn-md-streaming-20261029`, branch `codex/rn-md-streaming-20261029`, rebased onto UI branch `7a206b8`. Commits are local only, without pushing or merging main; the UI worktree and its emulator-5590 were untouched.

## Measured results

| Sample | Frames / janky | P50 / P90 / P95 | Response characters / full capture time |
| --- | --- | --- | --- |
| Before changes 0.5.0 / body polling | 1460 / **6.03%** | 17 / 22 / 22 ms | 4192 / 66.9s |
| First iteration 1.1.1 / before visual correction | 3986 / **4.34%** | 16 / 18 / 20 ms | 4523 / 85.1s |
| Final 1.1.1 / 40ms streaming | 3826 / **3.76%** | 16 / 17 / 18 ms | 4427 / 80.4s |

The final sample had 2.27 percentage points fewer janky frames and 4ms lower P95 than baseline. Continuous incremental presentation was observed, with some overdue frames remaining.

All three were real conversations through RN input → system gateway → root broker → official Codex app-server → OpenAI model. After changes, body text returned to RN through a native pipe; baseline used the original polling. The fixed prompt requested Chinese and English, 8 nested-list items, 45 TypeScript lines, a 12-row four-column table, quotes, and inline code. Model: `gpt-6.1-sol`, reasoning effort low; no mock, prerecorded tokens, or manually assembled model body. Only list indentation and light-background syntax highlighting changed between candidate runs.

Load average at baseline start was 17.91/12.52/10.19; the final round was 11.13/14.39/15.05.

Each run first reset `dumpsys gfxinfo org.agentos.agent`, automatically followed the response after sending, and exported `framestats` at terminal state; identical recording and roughly four-second screenshots accompanied each. The table uses Android's current janky-frame measure, not legacy; percentiles come from gfxinfo histograms and do not establish a stable 60fps lock. Response lengths and times depend on the model and are not rendering throughput.

Environment: independent `Taku_MDStreaming_GPU_20261029` / `emulator-5594`, API 36 arm64, 1080×2400, density 420, font_scale 1.0, 60Hz. AVD: `hw.gpu.enabled=yes`, `hw.gpu.mode=host`, launch option `-gpu host`. SurfaceFlinger reported `Android Emulator OpenGL ES Translator (Apple M3)`; app gfxinfo reported `Pipeline=Skia (OpenGL)`, confirming the hardware GPU path.

Testing used an isolated development runtime within a standard AVD: copy that device's original system_ext contents into its private directory, bind mount, then install assistant/broker, official pinned Codex 0.161.0, and virtual display host. No shared system image or physical device was modified/flashed. Credentials served only authorized model requests from an existing local account and were excluded from reports, logs, and the repository.

Other Air workloads continued. No builds/tests ran during sampling, which waited for builds to finish and load to decrease. Host load and model output nevertheless differed between rounds; these are real interaction samples, not proof attributing every difference independently or a long-term stress test. No available Pixel was found, so no physical Pixel validation occurred.

## Implementation and main files

| Area | Files and behavior |
| --- | --- |
| broker reducer / relay | `system/assistant/broker/org/agentos/lab/{AgentSessionReducer,AgentViews,SessionEvents}.java`: retains raw content by thread/turn/item; appends official `item/agentMessage/delta`; replaces with full text on `item/completed`; snapshot/subscription registration share a lock; bounded epoch/seq replay with resync on gaps/overflow. Delta disk checkpoints coalesce to 250ms; completion persists immediately. |
| socket / gateway | `NativeBridge.java`, `NativeClient.java`, `AgentGatewayProtocol.java`, `AgentGatewayProvider.java`: separate persistent connection, ordinary UID/package/signature checks, single-use UID-bound token, nonblocking read-only pipe, bounded queue/subscription count/lease. Status polling supports `statusOnly` without retransmitting full text. |
| Expo bridge / RN state | `SessionEventReader.kt`, `TakuAgentModule.kt`, `src/native/agent.ts`, `src/model/sessionStream.ts`, `src/hooks/{useAgentController,sessionRead}.ts`: first delta emits immediately, later deltas coalesce at 40ms, terminal state flushes; reconnects at applied cursor; body stream and status polling separate; old polls cannot overwrite newer streams; a new broker epoch reacquires history-completeness proof. |
| Markdown | `src/native/{Markdown,MarkdownStream}.tsx/ts`: Enriched Markdown 0.5.0 → 1.1.1; freezes completed blocks at syntax boundaries and retains active tail; preserves context until lists/fences/tables stabilize; temporarily repairs incomplete syntax only for display, leaving source text unchanged. Completion merges to a full item, restoring whole-response selection/copy and avoiding trailing-table remnants. |
| Fonts and layout | `app.json`, `src/theme/useTakuFonts.android.ts`, fonts/licenses: expo-font config plugin embeds Noto Sans SC and JetBrains Mono; fixed fonts from the first Android frame, with no content-based switching. Body 16/26, code 13/21, tables 14/22; nested indentation 24dp, code/table padding 12 and 10×8dp. Explicit light-background highlighting, with colored tokens at least 5.36:1 contrast against white. |
| Documentation/tests | `SESSION_STREAMING.md`, `agent-gateway.md`, app README; added relay/reducer, RN stream-reducer, Markdown-boundary, and cold-restart tests; extended ordinary-UID GatewayProbe. |

The final APK contains each of six project fonts exactly once. Existing 14 Material Symbols copies come from Expo Router/Expo Symbols dependencies and were already in baseline, not introduced by the Markdown upgrade. Project fonts are not repackaged by Metro. Compressed font payload shrank about 1.02MiB; total APK grew from 41,858,864 to 44,152,192 bytes.

Final RN APK SHA-256: `3ecb4860879d0ed08e18e4704e49398c42cab1eb3a09b861608a48a2ed2bb44a`. Assistant APK: `6adcbddcf887befd5116e96bd709c6991017ae5cf9a66e671e5932549f989fa2`; broker jar: `7d2a14ccca9912aec0132444ac6cd3f520bf0ec9f4d4c42f7524bf3d27420b07`.

## Validation

- Final RN lint, typecheck, and all 86 tests passed; final release APK built successfully. Build environment: JDK 17, Node 22.23.3, NDK 28.0.13004108, CMake 3.31.6.
- All 32 repository check commands exited 0: root tests 4, App API 9, OTA 177, 15 assistant host scripts, plus inventory, raw-interfaces, structure, surfaces, proactivity, and script syntax checks.
- Image checks: 254 total, 249 passed and 5 environment skips: macOS lacks Linux `/proc/self/fdinfo`; Go, real repo launcher, repo-installation integration fixture, and standalone host kotlinc were missing. Skips were not counted as passes.
- New relay 116 assertions, reducer 13, and gateway protocol 39 passed; the old native-routing fixture was updated with the actual SessionEvents interface, passing 96 checks.
- Actual ordinary device UID 10150 passed complete snapshot, seq-cursor ready, single-use token, unsubscribe closing pipe, closed-token nonreuse, statusOnly without body, and arbitrary-RPC/unknown-field rejection; subscription/open by another package UID 10152 was rejected.
- After actually killing/restarting the dedicated device's broker, RN automatically initialized a new app-server and recovered the original conversation's complete body, without external thread RPC to start it or resending tasks. The new epoch no longer inherited old history-completeness markers.
- Screenshot self-review led to nested-bullet indentation and light-background code-color fixes. Completed body text, paragraph spacing, code blocks, and table margins were readable; wide code/four-column tables permit horizontal scrolling.

## Evidence and reproduction

Full changed-file inventory: `docs/validation/rn-md-streaming-20261029/source-files.txt`.

Repository `docs/validation/rn-md-streaming-20261029/` stores the prompt, structured metrics, original gfxinfo, gateway-probe results, and validation summary. Complete Air logs are at `/tmp/mdimpl-evidence/` and `/tmp/mdimpl-validation-host-20261029/`; the Mac mini copy is in `implementation-evidence/` beside this report.

Screenshots and short videos are on both machines in `/tmp/rn_shots/`:

- `mdimpl-stream-code.png`: streaming an unclosed code block.
- `mdimpl-stream-table.png`: an unfinished table.
- `mdimpl-complete.png`: final complete response.
- `mdimpl-stream.mp4`: final-version recording from code block to table.
- `mdimpl-baseline2.mp4`, `mdimpl-final.mp4`: full comparison recordings.

Capture script `/tmp/mdimpl-run.py`, UI driver `/tmp/mdimpl-ui.py`, and cold-restart driver `/tmp/mdimpl-restart.py` target only emulator-5594. Final APK is in the independent worktree at `system/agent-app/out/TakuAgent.apk`; assistant APK/broker jar are in `system/assistant/out/`. These artifacts use development test signatures.

## Remaining boundaries

- The seq journal is in memory; broker restart recovers through a new epoch + full snapshot, without a cross-process persistent token log.
- After idle subscription cancellation, broker cleanup waits at most until the next roughly 10-second heartbeat; switching conversations at least 8 times in quick succession may briefly return STREAM_BUSY before automatic recovery.
- New conversations with empty titles may show “New conversation” until the official title event or final canonical snapshot arrives, without affecting streaming body text.
- There is no Pixel sample; conclusions are limited to this independent hardware-GPU emulator and these actual responses.
