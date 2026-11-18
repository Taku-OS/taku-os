# Home gesture ownership — issue 54

Base: `vela/TAKU-new-UI`, `6e54ad9c8c9a44d830d67f29721ff57bd0cb17e4`.
Scope: launcher input handling; system navigation-handle assistant routing remains issue 55.

## Findings and implementation

The full-screen Pager had no launcher-side distinction between system-edge starts and
content starts. This is a confirmed code fact, not proof of the original intermittent
Pixel failure: the baseline emulator did not reproduce that complete failure.

Home now snapshots the current window's system and mandatory gesture insets on DOWN.
Edge-origin scrolling cannot move the Pager; content gestures lock their dominant axis
after platform touch slop. Crossing an edge later does not reclassify the gesture.
The nested-scroll boundary leaves Android touch dispatch, child taps/long presses,
Compose snapping and explicit icon edge paging in place. It does not install a gesture
exclusion region. The desktop assistant entry leaves system-region starts to Android.

A separate cancellation defect was reproduced during verification: after a long-press
icon drag to another page, injected ACTION_CANCEL committed the new icon position.
`dragOrMenu` treated every `pressed=false` event as release, including Compose's consumed
synthetic cancel. It now checks consumption before consuming its own release, aborts the
drop on cancellation, and does not launch an icon on a cancelled tap. The assistant's
second-tap cancellation also no longer opens text input.

This cancellation representation is defined by AndroidX's
[`SuspendingPointerInputFilter.onCancelPointerInput`](https://github.com/androidx/androidx/blob/androidx-main/compose/ui/ui/src/commonMain/kotlin/androidx/compose/ui/input/pointer/SuspendingPointerInputFilter.kt).

## Agent access

```sh
content call --uri content://org.agentos.agent.home-layout --method gestures/get --arg '{}'
```

The result is read directly on the UI thread from the gesture policy, Pager and drag
controller, not from a second store. No new setting or write operation is introduced.
Fields, unavailable state and errors are documented in the preinstalled
`taku-open-access` skill. Before the first DOWN, the last-gesture region is zero.

## Verification environment

- Only `emulator-5586`, AVD `Taku_Launcher_Verify`; launched with `-gpu host`,
  `-no-snapshot`, 1080 × 2424, default Home `org.agentos.agent`.
- Gesture navigation: reported window insets left/right 78 px, top 164 px, bottom 84 px.
  These are test observations, not product constants.
- APK installed with `adb -s emulator-5586 install -r`; no userdata reset.
- System Back on this image can recreate Home. A reset diagnostic sequence therefore
  does not establish that the edge swipe was never seen. Tests also sample Pager
  offset/page during injection and verify its settled state.
- Build load was elevated; these are functional checks, not latency benchmarks.

## Checks

- Six pure Kotlin/JUnit policy tests: edges, direction locking, touch slop, cancel/reset,
  changed insets and three-button insets. Reproducible with a JDK 17+ `JAVA_HOME`:
  `bash system/agent-app/modules/taku-home/scripts/test-gesture-policy.sh`.
  The Expo-generated project does not expose `:taku-home:testReleaseUnitTest`, so the
  host runner uses the shared Kotlin toolchain and cached JUnit dependencies.
- 22 input-injection cases: center paging; left/right fast and slow swipes repeated
  three times each; just inside both inset boundaries; content crossing an edge;
  vertical diagonals/direction changes; mid-swipe CANCEL; edge CANCEL; bottom swipe
  and hold. Checks include settled page/zero offset and sampled no-motion checks for
  edge/vertical starts.
- Three-button overlay: left/right insets became zero and a swipe starting at x=20
  paged normally. Original gestural overlay restored afterward.
- Sensitivity probe: writing secure left/right back scale 0.5 did **not** change this
  image's advertised window insets (still 78 px). An x=60 start stayed system-owned
  under the reported insets. The expected narrower-window test was not satisfied;
  do not claim sensitivity adaptation on this ROM. Original settings restored.

## Final build and commit checkpoint

The user requested committing and pushing at this checkpoint, before the remaining
regressions were complete. This is not full acceptance of issue 54.

- `npm run build:android`, `npm run lint`, TypeScript `tsc --noEmit`: passed.
- Host JUnit runner: 6/6 passed.
- Final TakuAgent APK SHA-256:
  `1718353b4d7e97d33f2540bd3a2f6863d0f439611a84d945fc0ed697678c5590`.
  Installed on emulator-5586. Assistant rebuilt/installed with the updated preinstalled
  documentation. Both cached APKs and their hashes in the ignored local startup
  configuration were updated.
- Final APK: icon long-press edge paging followed by ACTION_CANCEL passed; dragging
  ended and the layout matched its pre-test contents (excluding revision).
  A short cancelled icon tap also left Home in the foreground.
- **Regression at this checkpoint (resolved in continued acceptance below):** ordinary cross-page icon drop returned
  `currentPage=3`, `settledPage=3`, `scrolling=false`, but
  `pageOffset=-0.02037037`. The zero-offset assertion failed. The test's `finally`
  restored Phone to page 0 / index 6 through `layout/move` and returned Home.
  The cause and baseline behavior of this residual offset have not been established.
- The 22-case pass above was on the first build with the same boundary/policy logic,
  before the subsequent `dragOrMenu` cancellation fix. The chained rerun on the final
  APK and Feed long-press/pin suite did not run because the drop assertion stopped it.
  Do not report these final-APK regressions as passed.


## Continued acceptance: drag animation cleanup

The residual offset was reproduced on the committed APK with release times of
1,750 / 1,850 / 1,950 ms after DOWN. The 1,950 ms case remained at
`pageOffset=-0.0037037036`, `scrolling=false` two seconds after release. Earlier releases
settled at zero. The test held Phone at (150,1320) for 650 ms, moved through (400,1320)
at 850 ms to (1050,1320) at 1,200 ms, then released at the selected time.

Ending the drag cancels the `LaunchedEffect(dragging)` coroutine, including an active
`animateScrollToPage`. The existing cleanup corrected an out-of-range page only,
leaving an interrupted animation's offset intact when the page was still valid.
Cleanup now snaps the current page to offset zero and clamps it to the surviving
home pages. This applies to both UP and CANCEL and retains the existing drop target
and layout validation.

Post-fix acceptance on emulator-5586 passed:

- Local Android release build, lint, TypeScript check and 6 JUnit policy tests.
- Three drag checks: cross-page CANCEL preserves layout, cancelled tap keeps Home
  foreground, ordinary UP commits the cross-page drop. Original layout restored.
- Three release-time cases (1,750 / 1,850 / 1,950 ms): offset zero after release and
  still zero with no scrolling two seconds later. Each moved icon was restored.
- All 22 edge/content/direction/cancellation cases rerun on the final APK passed.
- Feed verification passed: publish/generate supplied cards, pin/reorder/unpin,
  hide/restore, process-restart persistence, GUI long-press menu and GUI Pin to top.
  The suite retracted its temporary cards; original cards and pin order were preserved.
- The final APK is installed and its cached local-startup copy/hash updated:
  `5053d02fc04e53fb9f2118a859a47bf3c80c94ab585f13fc768dc346d26ffb7e`.

Final logs: `/private/tmp/taku-g54-accept-{build,lint,types,unit,drag,offset,device,feed}.log`.
Raw post-fix offset traces: `out/local-setup/gesture-54/offset-after/device-results.json`;
Feed GUI/XML and before/after provider proof: `out/local-setup/gesture-54/feed-accept/`.
These remain local ignored artifacts. Functional acceptance covers this emulator;
the Pixel-specific limitations below still apply.

## Remaining device acceptance

The reported Pixel 9 / TakuOS Android 17 device is not connected. Its intermittent
conflict, OEM sensitivity/inset agreement, system Recents/quick-switch behavior and
third-party nested horizontal controls require device acceptance. Emulator results
are not a claim that every checkbox in issue 54 has been verified on Pixel.

Local raw traces and test fixtures are under ignored `out/local-setup/gesture-54/`;
build/test logs use `/private/tmp/taku-g54-*`. These artifacts are not checked in.
