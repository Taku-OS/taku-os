# RN Markdown: Mac mini visual fixes and performance retest (2026-10-29)

Status: **Two batches of six real GPU comparison runs are complete, with an additional adjacent A/B comparison for the B3 anomaly. Complete evidence shows no sustained, material rendering regression. Three original Mini screenshots passed review, meeting this canary merge criterion. The single-run anomaly and small tail-percentile differences are preserved.** This continues the [initial streaming implementation](rn-markdown-streaming-2026-10-29.md), without overwriting historical reports or data.

The user authorized moving development and emulator acceptance to Mac mini, then merging and pushing main through repository procedures after performance acceptance. At 11:30, authorization was added to build and publish signed APKs on Mini through the existing channel. Air is subsequently reserved for independent physical-device validation. This round did not flash or wipe physical devices and did not validate Pixel or a complete ROM.

## Implementation and versions

Workspace: `/Users/haipro/experiments/android-agent-os/taku-os-rn-md-streaming-20261029`, branch `codex/rn-md-streaming-20261029`. Air visual-fix commit `b643a35` was synced to Mini; `3d380fe` merged then-current main `8319d1a`, and `6351458` subsequently merged main `0a1bb31` containing memory 0.8.0. `76da44a` completed the host-test fixture for memory event forwarding. Current `3b876e190720a03fcd853ec113d88fba62ffc477` contains the performance optimizations below. Air's UI worktree and emulator were not touched.

| Visual-review issue | Implementation |
| --- | --- |
| Unclosed code blocks entirely black | Native container highlights the completed-line prefix and caches the prefix and language: red keywords, dark-blue strings, gray comments. Closing the fence corrects the whole block; source text remains unchanged. |
| Long code appears hard-clipped with an incomplete container | Horizontally scrolling code retains a light background, 12dp corners, padding, and 1dp border. Fixed-viewport fades and small arrows disappear on a side when that end is reached. |
| Last table column touches the edge | Wide tables have fixed rounded viewport boundaries and bidirectional overflow hints; cells consistently use 12dp horizontal and 8dp vertical padding. Short tables retain content width. |
| Quotes, paragraph spacing, and copy action feel inconsistent | Quotes use 10dp padding, 8dp rule-to-text spacing, and 8dp corners; body text 16/26, paragraph spacing 12dp. Without Activity, the copy icon is left-aligned and moved up 8dp; visible button 32dp, actual touch target 48dp. The Activity action row retains its layout. |

Main files: `system/agent-app/src/native/Markdown.tsx`, `src/components/AgentChatScreen.tsx`, `patches/react-native-enriched-markdown+1.1.1.{patch,json}`, and `scripts/patch-enriched-markdown.cjs`. Native patches cover `CodeBlockContainerView.kt`, `TableContainerView.kt`, and `HorizontalOverflowIndicator.kt`. Installation and builds verify exact dependency versions and before/after SHA-256, rejecting drift or partial application. Four installer tests cover application, idempotency, source changes, and partial application.

After the first batch showed regression, inspection found active code updates repeatedly creating and enumerating color spans, while tables rebuilt clipping geometry during drawing. `3b876e1` removes default-text-color spans, caching start/end positions and immutable spans for nondefault colors. Table Path and stroke Rect update only in `onSizeChanged`. These changes reduce allocation and recomputation. The complete second batch follows, preserving single-run anomalies and not directly attributing median differences to optimization.

## Mac mini environment and isolation

| Item | Actual configuration |
| --- | --- |
| Host | Apple M4 Pro, 14-core CPU, 64GB RAM |
| Isolated AVD | `Taku_MDStreaming_Mini_GPU_20261029` / `emulator-5664`，API36 default arm64，4 vCPU、4096MB RAM |
| Display | 1080×2400、density 420、font scale 1.0、60Hz |
| GPU | `hw.gpu.enabled=yes`, `hw.gpu.mode=host`; SurfaceFlinger: `Android Emulator OpenGL ES Translator (Apple M4 Pro)` / `OpenGL ES 3.0 (4.1 Metal - 91.7)`; app gfxinfo: `Skia (OpenGL)` |
| Tools | Emulator 37.2.12; Node 22.23.3; Python 3.14.6; RN builds: JDK 17.0.20.1, NDK 28.0.13004108, CMake 3.31.6, Gradle 9.0.0; assistant/host checks: Homebrew JDK 21.0.12.1 |
| Network isolation | Separate netsimd instance 64, gRPC 62560, TCP 62561, HCI 62562; new AVD explicitly sets the packet streamer endpoint |
| Model network | After direct guest connectivity failed, used localhost-only CONNECT proxy 7898 with restricted destination domains, accessed through this serial's `adb reverse` |
| Runtime | Both versions share development-signed assistant/broker, virtual displays, and official app-server; account credentials are excluded from the repository and evidence archive |

Two early QEMU disappearances were caused by a hang watchdog during netsim initialization waits, with blocking stack `GetChannel → NetsimWifiForwarder::init`, not GPU-driver crashes. Disabling Vulkan did not fix it; a separate netsimd and explicit endpoint resolved default-discovery conflicts. Existing `emulator-5634` and its daemon remained separate and were neither restarted nor changed for this work. Every install, screenshot, and sample must explicitly target `emulator-5664` and verify the AVD name.

## Method and first-batch results

Baseline A is pre-visual-fix `09adacd`, already containing Enriched Markdown 1.1.1, subscription channels, and 40ms coalescing; **it is not the original 0.5.0 polling version**. First-batch B is `76da44a`; optimized B is `3b876e1`. The baseline APK was also built on Mini, without visual native patches and with upstream-source hashes verified. Both batches use the same baseline APK and system runtime.

| Artifact | SHA-256 |
| --- | --- |
| Mini baseline A | `30168ee08be83eb3a2f72ae32454b585b4aa57639c71523524969fc2b5891b2e` |
| First-batch candidate B | `2aceed16c3219efffd7fd633e0486141ef830b485b4420dedfafb0c4946922db` |
| Optimized candidate B | `3b8f6de5ef8ce4dfb95d4e6c424fd4f1ed8c08480d6a3911b5b18b5abbc53b29` |
| Shared Assistant APK | `c8c581f87d17e48f3bc11a3b16e0da2dc371c424c4f0248911866199c2276cb3` |
| Shared broker jar | `792a25178ed4d1ce58527f799d8fc1870fabaa86c7302c666cf8dab4f8d243d2` |

Each batch alternates **A1, B1, A2, B2, A3, B3**, using the [same prompt](validation/rn-md-streaming-20261029/prompt.txt) and `gpt-6.1-sol` / low, requesting Chinese and English, nested lists, 45 lines of TypeScript, a 12-row four-column table, quotes, and inline code. Each run starts a new session through real UI → system gateway → broker → official app-server → model → native pipe → RN; tokens are not replayed and text is not assembled artificially.

Wait for this task's builds and checks to exit before starting. The first batch and first five runs of the second batch required two consecutive samples of CPU idle ≥65% and 1-minute load ≤12; gate records and load/swap samples every 5 seconds during execution are retained. This is a relatively quiet window on a shared Mini, not an idle host. Before the second batch's B3 request, a threshold adjustment was recorded: after about 13 minutes waiting and an external load peak around 70 subsided, sustained host load was around 6 and idle around 60%, so the gate became three consecutive idle ≥60%, load ≤8 samples at 15-second intervals. The original five runs were not recalculated; `after-3-initial-host-gate.json` and pre-request `after-3-gate-adjustment.json` are retained. B3 has different starting conditions and must be reviewed separately; do not retrospectively claim all six used one gate. Prompt entry time is excluded. Reset gfxinfo before sending, wait for actual `completed`, then another 1.5 seconds to include terminal-text correction and UI settling. Both versions record video and capture screenshots roughly every 4 seconds with identical collection actions.

Primary metrics directly use Android's **original whole-run gfxinfo** janky%, P50/P90/P95. Medians are taken across three independent runs, without pooling frames from different replies. The second batch adds per-second framestats to both versions to identify prose, code, and table phases; phase results do not replace whole-run metrics. GPU histogram showed anomalous tails around 4950ms and a few raw timestamps were negative, so GPU tail percentiles are not used for conclusions. User-requested overall gfxinfo values remain unfiltered, without selecting more favorable results.

All six first-batch `mdimpl3` runs reached `completed` and are included:

| Run | Frames / janky frames | Janky | P50 / P90 / P95(ms) | Starting load1 / idle | Body characters |
| --- | --- | --- | --- | --- | --- |
| A1 | 3471 / 408 | 11.75% | 20 / 34 / 44 | 7.31 / 68.22% | 3690 |
| B1 | 4146 / 569 | 13.72% | 19 / 34 / 46 | 5.68 / 66.41% | 4368 |
| A2 | 2944 / 337 | 11.45% | 20 / 34 / 40 | 7.03 / 65.57% | 3619 |
| B2 | 3931 / 457 | 11.63% | 20 / 34 / 42 | 6.32 / 70.40% | 4196 |
| A3 | 4286 / 494 | 11.53% | 19 / 32 / 38 | 6.61 / 68.28% | 4285 |
| B3 | 3529 / 421 | 11.93% | 20 / 32 / 40 | 6.50 / 68.51% | 3773 |

| First-batch three-run median | Janky | P50 | P90 | P95 |
| --- | --- | --- | --- | --- |
| A | 11.53% | 20ms | 34ms | 40ms |
| B | 11.93% | 20ms | 34ms | 42ms |
| B − A | +0.40 percentage points | 0ms | 0ms | +2ms |

P95 increased by 2ms in all three first-batch pairs, a small regression signal requiring action rather than a pass. Real model output lengths and generation durations differ, so the data alone cannot attribute every difference to visual changes. This batch is retained; after the optimizations above, the full comparison was rerun without selecting favorable samples.

### Second batch after optimization

All six `mdimpl4` runs are valid real sessions reaching `completed`; B3 was not discarded:

| Run | Frames / janky frames | Janky | P50 / P90 / P95(ms) | Starting load1 / idle | Duration / characters |
| --- | --- | --- | --- | --- | --- |
| A1 | 3739 / 481 | 12.86% | 20 / 38 / 48 | 6.73 / 66.50% | 92.33s / 4050 |
| B1 | 3961 / 485 | 12.24% | 19 / 36 / 48 | 6.70 / 67.54% | 94.87s / 3800 |
| A2 | 3380 / 472 | 13.96% | 20 / 38 / 48 | 7.45 / 66.97% | 83.60s / 4368 |
| B2 | 3827 / 455 | 11.89% | 19 / 34 / 44 | 7.62 / 68.65% | 96.85s / 4549 |
| A3 | 3692 / 369 | 9.99% | 19 / 31 / 36 | 6.92 / 70.36% | 95.71s / 3909 |
| B3 | 3373 / 553 | 16.39% | 20 / 40 / 57 | 7.53 / 61.81% | 85.78s / 4075 |

| This batch's three-run median | Janky | P50 | P90 | P95 |
| --- | --- | --- | --- | --- |
| A | 12.86% | 20ms | 38ms | 48ms |
| B | 12.24% | 19ms | 36ms | 48ms |
| B − A | −0.62 percentage points | −1ms | −2ms | 0ms |

| Sequentially paired B − A | Janky (percentage points) | P50 / P90 / P95(ms) |
| --- | --- | --- |
| Pair 1 | -0.62 | -1 / -2 / +0 |
| Pair 2 | -2.07 | -1 / -4 / -4 |
| Pair 3 | +6.40 | +1 / +9 / +21 |

**Anomaly at the end of six runs:** A/B Janky medians were 12.86% / 12.24%, with P50/P90/P95 20/38/48ms / 19/36/48ms. However, B3 reached 16.39%, 20/40/57ms versus A3's 9.99%, 19/31/36ms: Janky +6.40 percentage points, P95 +21ms. The first two pairs did not deteriorate this way. The third pair cannot be hidden by medians or wholly attributed to the shared host without evidence. Approval was therefore withheld after the six runs, followed by the diagnosis and adjacent comparison below.

All replies meet the long-form sampling structure: A1/B1/A2/B2/A3/B3 contain **45/46/45/45/45/46** code lines respectively. Each four-column table has **14 source lines (header, separator, 12 data rows)**, and all replies include Chinese, English, and quotes. Model output is not fixed token replay; character-count and duration differences are listed.

Shared-host limitations: B3 runtime load1 was 7.53–10.00 versus A3's 6.63–9.28; B3 swap use was about 28,899.50–28,915.50MB versus A3's 27,860.44MB. The threshold change, earlier background-load peak, memory/swap changes, and real-output differences weaken strict pairing and causal interpretation. Original whole-run gfxinfo retains all frames; phase diagnosis only assists investigation and does not replace acceptance metrics. Even a later finding of no material regression cannot turn this shared-GPU emulator result into a stable-60fps guarantee.

### B3 anomaly diagnosis and adjacent comparison

At least one second before B3's first code fence appeared, cumulative gfxinfo was already **2469 frames / 424 janky (17.17%), 20/48/57ms**; A3 at the same stage was 2515/246 (9.78%), 19/32/36ms. The anomaly already existed during plain prose, so it cannot be attributed to code highlighting or tables not yet reached. B3 code/table UI P95 was 1.223/1.268ms; synchronization and buffer waits grew most. Host load alone cannot prove an environmental cause.

Before sampling, `diagnostic-control-plan.json` recorded the plan to add an immediately adjacent baseline Acontrol. After it returned to normal, Bcontrol followed immediately as planned, creating a new adjacent A/B pair. Both use the same prompt, model, APK, whole-run gfxinfo, and collection actions, requiring three consecutive idle≥60%, load1≤8 samples 15 seconds apart; actual starting idle exceeded 68% for both. Neither the original six runs nor anomalous B3 was deleted or replaced.

| Additional comparison | Frames / janky frames | Janky | P50 / P90 / P95(ms) | Starting load1 / idle | Duration / characters |
| --- | --- | --- | --- | --- | --- |
| Acontrol | 3844 / 427 | 11.11% | 20 / 34 / 40 | 6.63 / 68.11% | 97.75s / 4285 |
| Bcontrol | 4101 / 493 | 12.02% | 20 / 34 / 42 | 6.29 / 68.90% | 104.41s / 4418 |
| B − A | — | +0.91 percentage points | 0 / 0 / +2 | — | — |

Auxiliary phase P95 values (not whole-run acceptance metrics) follow. This adjacent comparison did not reproduce B3's large plain-prose waits. Code-phase tail latency was nearly identical, and tables showed no sustained deterioration.

| Generation phase | A / B frame-time P95(ms) | A / B UI P95(ms) |
| --- | --- | --- |
| Plain prose | 54.998 / 53.737 | 2.758 / 2.691 |
| Code | 40.940 / 40.971 | 1.343 / 1.378 |
| Table | 44.673 / 38.245 | 1.147 / 0.825 |

Alongside original three-run medians, sensitivity medians include **all four runs**, avoiding selective presentation of favorable statistics:

| Four-run medians | Janky | P50 | P90 | P95 |
| --- | --- | --- | --- | --- |
| A | 11.99% | 20ms | 36ms | 44ms |
| B | 12.13% | 19.5ms | 35ms | 46ms |
| B − A (before rounding) | +0.145 percentage points | −0.5ms | −1ms | +2ms |

**Final judgment: no sustained, material rendering regression attributable to this patch was found; merging main and publishing canary can proceed under user authorization.** This does not prove zero overhead or a definite speedup: the additional comparison still has +0.91 percentage points Janky and +2ms P95, and four-run statistics still show +0.145 percentage points and +2ms, all explicitly retained. Source review and phase evidence support avoiding blind code changes for one anomaly. Shared GPU, real model output, and few runs still limit causal interpretation. `control-metrics.json`, `phase-analysis-with-controls.json`, and all eight valid sessions and samples are archived.

Historical single-run Air A was 4.27%, 16/17/17ms and B 4.59%, 17/17/18ms under different loads; these are excluded from Mini medians. Air retests interrupted by offline status and failed/incomplete sessions during Mini network repairs are not completed samples; failure records are preserved.

## Checks, screenshots, and self-review

| Check | Known result |
| --- | --- |
| RN lint / typecheck / tests | Passed after latest optimization, 90/90; `/tmp/mdimpl4-{lint,typecheck,test}.log` |
| Strict patches and native-source replay | Exact SHA verification, first application, and idempotent replay passed; 4/4 installer tests, logs `/tmp/mdimpl4-{patch-check,native-replay,installer-test}.log` |
| Repository `npm run verify` | Passed after merging main; environment skips follow the logs and do not count as passes |
| assistant host tests | 15/15 passed; native-routing 104 checks after memory fixture repair |
| app-api tests | Passed |
| Mini release APK | Baseline, first candidate, and latest optimized candidate all built successfully; SHAs above |
| Second-batch six-run performance / Mini native screenshots | All six plus two additional runs valid and completed; B3 investigated with additional evidence as described, without reproducing sustained material regression. Two reviewers approved three real original screenshots; see below |
| PR, exact main SHA, and CI | This records pre-merge acceptance; merge results and CI for the exact SHA go in the PR body and ignored `out/` release receipts |
| Pixel / complete ROM | Not validated |

Complete merged-main checks are in `/tmp/mdimpl3-host-checks-merged-main/results.json`, validating `6351458` plus the fixture repair later committed as `76da44a`; production assistant/broker built successfully. Latest `3b876e1` only optimizes the Markdown native patch; relevant RN checks and release builds were rerun.

Historical Air visual review confirmed coloring of completed lines inside unclosed fences, switching overflow hints at both horizontal ends, code/last-column padding, continuous quote rule and background, and copy-button proximity to text. A tap about `(40dp,40dp)` outside the visible target triggered `Copied`. These are historical acceptance only, not substitutes for final Mini captures.

Paragraph hierarchy and action-button spacing reference [official ChatGPT App Store images](https://apps.apple.com/us/app/chatgpt/id6448311069) and [official Claude App Store images](https://apps.apple.com/us/app/claude-by-anthropic/id6473753684). The inspected images had no code blocks, tables, or quotes and are not direct specifications for those containers, nor a claim of pixel identity with their current Android apps.

All three final named images come from real Mini sampling of optimized candidate B2; originals were viewed and approved by two reviewers. `selected-screenshots.json` stores source files and SHA-256. The code image comes from `after-2-066`, whose source contains only one fence and still-unclosed code. The table image comes from `after-2-078`, whose source has only six table lines with the last unfinished while the reply is still generating. The completed image is the same run's full terminal state. Air images were not substituted.

| Final Mini evidence | Local path | Self-review conclusion |
| --- | --- | --- |
| Streaming code | `/tmp/rn_shots/mdimpl4-mini-stream-code.png` | Completed lines show keyword, string, and comment colors; light rounded container, padding, and right fade/arrow are clear, with horizontal-overflow hints for long lines. |
| Streaming table | `/tmp/rn_shots/mdimpl4-mini-stream-table.png` | Header/data rows remain stable in an unfinished reply; right viewport edge, corners, and fade/arrow are clear, with consistent cell spacing. Narrow screens scroll horizontally to later columns. |
| Completed state | `/tmp/rn_shots/mdimpl4-mini-complete.png` | Noto Sans SC Chinese/English body text and JetBrains Mono code have clear hierarchy; quote rule connects to background, paragraph spacing is balanced, and the left-aligned copy button stays near the text. |
| Real-speed recording | `/tmp/rn_shots/mdimpl4-after-2.mp4` | Complete sampling recording from the same run retained; no separately edited short clip. |

The first batch is archived in the worktree's ignored `out/validation/rn-md-streaming-mini-20261029/first-pass/`; the second batch and extra controls are in `final-pass/` under the same root. Original evidence is also in `/tmp/mdimpl4-evidence/`, screenshots/recordings in `/tmp/rn_shots/`, and latest APK in `/tmp/mdimpl4-kit/`. Evidence includes original gfxinfo, environment/load, model projections, sequential screenshots, recordings, collection scripts, check logs, and build/screenshot hashes. Archives retain all six original runs plus controls, without overwriting original records with additional samples. Release outcomes follow PR and release receipts. The report commits only summaries, methods, and local paths, excluding credentials, private chats, complete event streams, caches, APKs, and build artifacts. Historical `docs/validation/rn-md-streaming-20261029/` remains unchanged.

Acceptance of a pre-merge candidate APK does not mean main has merged or artifacts have shipped. App 0.2.0 / versionCode 2 metadata and the existing canary workflow are documented in README. Builds at the exact main SHA, ZIP/manifest, immutable uploads, re-download verification, and installation acceptance are recorded separately in the PR body and ignored `out/` release receipts. This report does not claim anticipated download URLs are available.

## Subsequent development and acceptance on Mini

Run in the isolated worktree above. RN uses JDK17; assistant/host checks use JDK21. Prefer `/opt/homebrew/bin` for Python to avoid missing required modules in system Python3.9. Commands below are for reproduction only. Before running, confirm the previous acceptance round has ended and designated ports are free, preserving existing instances belonging to other tasks.

```sh
export PATH=/Users/haipro/.local/share/mise/installs/node/22.23.3/bin:/opt/homebrew/bin:$PATH
export JAVA_HOME=/Users/haipro/.local/share/taku-tools/jdk17
export TAKU_NDK_VERSION=28.0.13004108 TAKU_CMAKE_VERSION=3.31.6
export GRADLE_OPTS='-Dorg.gradle.workers.max=2 -Dorg.gradle.parallel=false'
export CMAKE_BUILD_PARALLEL_LEVEL=2
npm --prefix system/agent-app run lint
npm --prefix system/agent-app run typecheck
npm --prefix system/agent-app test
npm --prefix system/agent-app run build:android
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home npm run verify
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home bash system/assistant/build.sh
```

Launch arguments for the separate network daemon and AVD follow. Run each as a managed background process with retained PID and logs, cleaning up only processes created by this task. Passing only the netsim instance while still relying on global default discovery is insufficient.

```sh
/Users/haipro/Library/Android/sdk/emulator/netsimd --instance 64 \
  --grpc-port 62560 --tcp-port 62561 --hci-port 62562 \
  --startup-timeout 120000 --no-web-ui
/Users/haipro/Library/Android/sdk/emulator/emulator \
  -avd Taku_MDStreaming_Mini_GPU_20261029 -port 5664 -gpu host \
  -packet-streamer-endpoint 127.0.0.1:62560 \
  -no-window -no-audio -no-boot-anim -no-snapshot
/Users/haipro/Library/Android/sdk/platform-tools/adb -s emulator-5664 emu avd name
/Users/haipro/Library/Android/sdk/platform-tools/adb -s emulator-5664 shell dumpsys SurfaceFlinger
/Users/haipro/Library/Android/sdk/platform-tools/adb -s emulator-5664 shell dumpsys gfxinfo org.agentos.agent
```

After reboot, restore this AVD's private `/system_ext` bind, virtual displays, and broker using archived preparation steps and runtime hashes. Accounts remain only in device-private directories. If a proxy is needed, restore only this serial's reverse and restricted CONNECT proxy. Performance suite `/tmp/mdimpl4-suite.py` invokes `/tmp/mdimpl4-run.py`; scripts are archived in `final-pass/`. After temporary directories are cleared, restore scripts and their explicitly specified APK/evidence paths. Alternate three runs per version after stopping this task's builds; do not change source, install packages, or add screenshot actions during sampling.

## Retained boundaries

- Patches apply only to Android Enriched Markdown 1.1.1. Upgrades require renewed source/hash review without relaxing checks.
- The latest incomplete code line is not highlighted yet. Upstream strips trailing newlines, so a newly completed line may wait for the next line's first character before highlighting; closing the fence corrects the whole block. Copy uses original text.
- Scrolling vertically into a long block may move its top arrow outside the viewport; full-height edge fades still indicate horizontal scrolling.
- See the initial report for epoch/seq in-memory replay and full-snapshot recovery after broker restart. Visual changes do not alter those protocols.
- Emulator performance does not replace physical-device, complete-ROM, or production-release validation. Later physical-device validation runs independently on Air as arranged by the user.
