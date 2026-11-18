# Generative UI V2: implementation and acceptance record

2026-11-02. Corresponds to the [implementation SPEC](../../generative-ui-composition-state-spec.md) and [issue #2](https://github.com/QuantVela/taku-os-goose/issues/2).

**Emulator evidence exists for three weather sizes, real-source updates, native reminder controls, and the interactions below; all 21 business scenarios have not passed.** Generic rendering is not connected business functionality. Visual/functional status is separate; designs, catalog examples, and mock merchant data do not replace actual results.

## Environment and artifacts

- Independent AVD: `Taku_GUI_20261029`, serial `emulator-5590`, Android API 36, ARM64.
- Frontend `org.agentos.agent` uses ordinary UID `10150`; existing native runtime owns system execution/source state.
- Runtime `hardware-qemu.ini` actually reported `hw.gpu.enabled=true`, `hw.gpu.mode=host`; static AVD GPU defaults do not establish launch parameters.
- Default 1080×2400, density 420 (about 411dp), font scale 1.0; also tested 360dp/150% fonts, then restored defaults.
- Agent App `0.2.0` / versionCode 2, assistant versionCode 74; not a new ROM/hardware acceptance.
- Final frontend catalog version: `taku-ui-8-v2`; APK/assistant/Broker SHA-256 in [evidence.json](evidence.json).

## Paths executed

### Weather: one source, three densities

All three views use real Open-Meteo Beijing coordinates `39.9042,116.4074`, sharing source ID `5dd9d011-6903-38da-bb04-6452276ad64b`. Acceptance input explicitly supplied coordinates; they are not device location. Temperature actually changed during reads, so screenshots need not match earlier replies; each shows its own verification time.

| Size | Observed behavior | Device screenshot |
| --- | --- | --- |
| `full_size` | Blue-gradient outer frame, large temperature/icon, high/low, five-day forecast and checked time; all five days horizontally reachable | [Full weather](weather-full-size.png) |
| `medium_size` | White frame retaining current weather/high-low; collapsed details expand forecast/time on tap | [Explanatory weather](weather-medium-size.png) |
| `small_size` | Compact weather row within travel context sharing one outer frame; details expand on demand | [Embedded weather](weather-small-size.png) |

`small_size` context explicitly says trip dates/place arrangements are missing; this proves embedding, not a completed Kyoto itinerary or booking organization.

Actual interactions also covered:

- Expanded `medium_size` survived leaving/reopening and frontend force-stop/reopen, then was collapsed.
- At [360dp / 150% fonts](weather-360dp-150font.png), temperature/unit stayed on one line and high/low remained readable; horizontal scroll fully exposed final day `Fri`.
- Repeated refresh with one request ID returned the same receipt; subscription received revision 3 `matter/snapshot` after revision 2. Refresh was inside native rate limiting, correctly returning `deferred` / `source-refresh-requested`, not falsely claiming immediate completion.
- Original request meant “How is the weather today?”: missing location prompted a city question; explicit Beijing coordinates let native runtime create a real source card and return weather. Both operations updated `local-weather`, without extra Host auto-enhancement cards (`chatEnhancements` count 0). See [actual chat surface](weather-natural-main.png) and `naturalFlow` in `evidence.json`.

Before repair, main Agent hand-authored an invalid V2 `type/text` initial block and got `RUNTIME_UNAVAILABLE`. Native runtime now may create complete initial presentation from a real source; custom structures still validate strictly. Frontend projects a matching native snapshot returned by tools; saved views no longer overwrite existing native-source projections.

### Jev: actual compositions and fallback causes

Initial phone records exposed Hermes lacking `Promise.withResolvers`, followed by Jev placing compact-group details outside the group and another unreachable-element generation. Fixed with ordinary Promise construction and stable element IDs/required parents in candidate descriptions. Reachability, binding, action, and single-outer-frame validation remain.

These are final phone-recorded single layout-resolution times, **not first frame, TTFC, or stable-load performance benchmarks**:

| Size | Path | Jev steps | Layout resolution ms | Fallback |
| --- | --- | ---: | ---: | --- |
| `full_size` | Jev | 2 | 714 | None |
| `medium_size` | Jev | 2 | 774 | None |
| `small_size` | Jev | 2 | 734 | None |

`inputTokens`/`llmUsage` are null and recorded unknown, not zero or cost inferred from timing. Main Agent, reply formatting, and layout resolution are separate stages, not one combined model call. End-to-end first-frame/off-screen recovery metrics were not measured.

### Weekday rule

Under ordinary app UID, actually created 08:50 → edited 09:00 (revision 2) → skipped today → independently paused. Reinstall/reopen read back `09:00`, revision 2, paused=true, skipDate=`2026-11-02`. Final paused state retained.

This verifies native rule management, not Studio North coffee ordering: no products, prices, two-order history, or merchant receipts. No wait for the next weekday trigger; lock-screen/Doze punctual delivery unproven. Runtime explicitly notes Android JobScheduler may delay.

### Multi-turn vague taste suggestions

Real-model replies followed the three original item-16 requests in order: tired/unsure what to eat → hot/crisp/non-soup → refreshing/not oily. Final suggestion: lightly pan-fried shrimp/vegetable potstickers with cucumber salad. All three state cards reuse one key. Jev route: `typesafe/jev-latest`; formatter: `codex/gpt-6-luna`.

This proves multi-turn replies/same-matter presentation; main-food-image visual acceptance, merchants, prices, and orders are absent. Temporary selection is not an actual purchase.

### Camera: actual capture, filtering, saving

Authorized through UI, opened AVD `emulated` rear camera, captured one image, saved warm version, switched to monochrome, dragged strength to 100%, and saved again. UI displayed receipts; MediaStore returned two app-owned PNGs (910×1213). Actual decoded files: warm maximum RGB-channel difference 252; full monochrome 0. [Saved-result UI](camera-mono-saved-ui.png), `cameraExport` in `evidence.json`.

Saving uses actual SVG filter output/MediaLibrary, excluding separately drawn composition guides. Export `Promise.withResolvers` also changed to ordinary Promise. This uses emulator imagery, not portraits; natural skin quality, historic-building composition, and realtime sensor alignment unproven. Temporary camera Panel and two gallery test images were cleaned; local `out/genui-v2` retains export evidence.

## Real sources and capability scope

`SourceAdapters` actually registers five types: `memory`, `task-receipt`, `audio-session`, `weather`, `feed`. Others return unavailable; layout models cannot declare them connected.

| Source | Actual integration/read path | Test/limits |
| --- | --- | --- |
| Weather | Open-Meteo HTTPS; native shared owner, 10-minute polling, visible subscriptions/manual refresh | Current temperature/code, five-day highs/lows, timezone, checked time read; device location depends on permissions/valid cache; no merchant commands |
| Figma blog | Official Atom feed; 15-minute polling | Actually `available`, 769 items read, `coverageComplete=false`; bounded update window, not complete incremental daily briefing |
| Audio | Installed-engine AudioClient Messenger contract/native recording store | Real capability obtained; `fileInput=false`, `engineRecordingExport=false`, pause `finish-and-reopen`; `qualityVerified=false`, so no meeting/video-translation acceptance |
| Memory | Actual installed-provider records/ContentObserver | No authorized delivery/order/group-chat records used to accept those businesses this round |
| TaskBroker receipts | Same-conversation task files/FileObserver | Task completion alone does not mean paid, ordered, or booked |

## Status of all 21 scenarios

“Component path” means generic rendering/input exists; corresponding actual surfaces and business results need separate evidence before a scenario passes.

| Item | Scenario | Visual status | Functional status/missing conditions |
| --- | --- | --- | --- |
| 01 | Weather | Three sizes, 411dp, 360dp/enlarged-font screenshots | Real reads, same-matter updates, refresh, missing-location question, expansion restoration passed; dedicated GUI city selection untested |
| 02 | Ride home | Not run; Route/Field/ChoiceGroup are component paths | Missing authorized home address, location, real routes/quotes/ride connector/order receipt |
| 03 | Today deliveries | Not run; Collection/TimeDisplay component paths | Missing authorized actual delivery notifications/orders/pickup events; counts/deduplication/lock screen untested |
| 04 | Today flights | Not run; route/time components exist | Missing authorized itinerary, flight status, transport sources; delay/gate/cross-day untested |
| 05 | Music playback | Generic actual player implemented, scenario not run | Missing authorized playable tracks/music service and MediaSession receipts; track switching/state sync untested |
| 06 | Find coat/compare/watch prices | Not run; table/amount/rule entries exist | Missing recovery evidence, matching SKU, currency, four fee-inclusive quotes; sources do not declare typedPrice; low-price trigger untested |
| 07 | One-time Luckin coffee | Not run; confirmation/specification paths exist | Missing real Luckin product/store/stock/address/final quote/order reconciliation contract |
| 08 | Weekday Studio North coffee | Complete coffee scenario not run | Native time edit/skip/independent pause/restoration passed; no merchant/order integration or actual future trigger/order-history acceptance |
| 09 | Group dinner/order/split bill | Not run; collection/amount/choice components exist | Missing real participant arrangements/menu/bill/payment connector; deterministic split untested |
| 10 | Group-chat extraction/poll | Not run | Missing authorized source messages/calendar/real poll-reply source; local selection not submitted as external votes |
| 11 | Five-day Kyoto itinerary | Kyoto scenario not run | Missing authorized five-day itinerary/saved places/booking proof; small Beijing-weather embedding does not pass this item |
| 12 | Home controls/arrival reminder | Not run; controlled-input/single-frame paths exist | Missing actual device connector/reported state/control receipts/location rule; no false 23°C air-conditioner claim |
| 13 | Camera style | Actual emulator camera/filter/save surface run | Capture, monochrome, intensity, save passed; no authorized portrait, skin-quality/full original request untested |
| 14 | Historic-building portrait composition | Static-photo analysis/guidance layer exists, scenario not run | Missing authorized portrait; realtime frame/sensor alignment incomplete; static guidance is not realtime recognition |
| 15 | Delivery meal selection/order | Not run; fees/preferences/confirmation components exist | Missing real saleable candidates, delivery-inclusive prices, stock, authorized office address/order connector |
| 16 | Vague taste recommendations | Three real replies/same-key state cards; main food image untested | Original multi-turn suggestions worked; complete image/text selection/edit/reopen not fully accepted, no order claim |
| 17 | Video translation | Playback/Overlay/audio paths exist, scenario not run | Missing authorized video/media-timeline input contract; seek-to-ASR-cue alignment incomplete |
| 18 | Daily X/Figma briefing | Actual Figma source state readable; full-list scenario untested | Figma reads passed; X read/auth, dual-source cursor/dedup/daily publication incomplete; not a complete daily briefing |
| 19 | Meeting notes | Native audio session/state paths exist, scenario not run | Engine capability obtained; authorized recording/clips/recognition-quality evidence missing; recording/transcription/summary/replay untested as a whole |
| 20 | Slack/Linear follow-up | Not run | Neither source connection/auth/cursor contract exists; grouping/reconnect/cross-source dedup/external actions untested |
| 21 | Organize itinerary screenshots | Not run; gallery/source/timeline components exist | Three authorized screenshots not supplied; OCR regions/conflict checking/correction recovery chain incomplete |

Remaining prerequisites include actual source connections/accounts, verifiable merchant/device contracts, and authorized video, meeting, portrait, screenshot materials. Explicitly incomplete chains remain implementation work; adding a card/rendering example cannot close them.

## Checks and evidence

Final frontend commands succeeded:

```sh
cd system/agent-app
npm run typecheck
npm run lint
npm test
JAVA_HOME=/path/to/jdk-17 npm run build:android
```

All existing 116 checks passed; formal APK build/test-signature verification succeeded and installed on the independent emulator. No mock-business fixtures, extreme cases, or implementation-text-only tests were added. In addition, listed UI taps/process reopenings/source subscriptions/native receipts/real Jev composition/image saves actually ran.

One `uiautomator dump` exited 137 during Activity switching; Android crash buffer showed `UiAutomation` `Bad file descriptor`. Direct screenshot confirmed chat healthy, and UI/recovery verification passed after stabilization. This tool failure is not a scenario pass and caused no product-logic change.

Portable evidence: [evidence.json](evidence.json) and directory screenshots. Full local raw records remain in `out/genui-v2/`: before/after weather conversations, refresh/subscription receipts, rules, three-size snapshots, Jev structures before/after rejection, audio capability, Figma source, actual filter exports. That directory is not an additional business source.
