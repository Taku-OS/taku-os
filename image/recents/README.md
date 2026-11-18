# Taku overview on Quickstep

The new HOME stays `org.agentos.agent/.home.HomeActivity`. The system overview remains
`com.android.launcher3/com.android.quickstep.RecentsActivity`; its presentation is patched rather
than introducing a second task store or replacing the navigation service with an ordinary Activity.
The patch is independent of the existing nav-handle double-tap / voice-hold routing.

Phone portrait fallback overview uses up to 82% overlap between task cards (at least a 48dp page stride), 36dp thumbnail corners,
a neutral scrim and blurred backdrop from the same bundled Taku HOME wallpaper, and app headers above
the cards. Rear cards shrink smoothly toward 88% scale and receive a neutral veil (up to 42%) as they recede.
Layers retain their physical page order: right-hand cards always cover left-hand cards, even during
paging or a vertical dismiss drag. Centering a card never raises it. Cards to the right fan outward
continuously to expose the centered task; only the compressed left stack receives depth/tint.
The second card to the left is approximately 96% scale, with its top lower and bottom higher around a shared
center pivot. Depth uses continuous native scroll distance, not integer page steps. The same scale and outward translation
curves are applied to remote task surfaces and fade out through fullscreen progress, so side-card
launches do not jump from a smaller thumbnail to an unscaled window. App titles fade near the
page midpoint. The Screenshot footer is hidden in that layout. Tablet/grid and landscape keep upstream
layout behavior. Task launching/dismissal, screenshot privacy, cancellation and remote window
animations remain Quickstep operations. The backdrop reads `feed/home/wallpaper.jpg` from the installed Taku app's assets and applies
a hardware RenderEffect; it is never synthesized from captured user screens. If Taku is unavailable,
Quickstep retains its system wallpaper and native depth/blur fallback.

Visible side cards are interactive: explicit drawing and touch ordering agree **at the touch point**,
not a rule that only the center card can be manipulated. Tapping an exposed side card launches that
task; dragging upward there dismisses that task. All cards whose bounds intersect the screen load
preview data. Overlap uses actual page spacing, so scroll positions and the native live-task geometry
use the same positions; depth scaling is shared with the live-window simulator rather than applied to screenshots alone.

## Build

Both `image/build.sh` and `image/emulator/build.sh` apply `apply-recents-patches.py` after the existing
launcher entry patch. It requires upstream commit `818f72b74e6f914cbca2b465776d6d20f960ee87`, verifies
input/output file hashes and supports an already-applied patch. Unknown or partial source changes
stop without resetting the checkout. Its receipt proves source application only, never APK build or
device acceptance. `python3 tests/recents-patches.test.py` exercises real upstream fixtures.

## Agent interface

See the preinstalled `taku-open-access` documentation for `content://com.android.launcher3.taku.recents`.
The provider delegates to Android's task store and the launch/remove operations used by Quickstep.
No caller allow-list or Agent-specific shadow state is added. The platform still enforces task,
profile, lock and window policies. Calls return structured platform/admission errors. `show` uses
the native overview command queue; `queued:true` confirms admission, while the transition finishes
asynchronously. Calling `show` on an already-visible overview is a no-op.

## Acceptance

Build and test on the matching Lineage development image. Preserve application/userdata; install the
same-platform, same-certificate Launcher3QuickStep APK as an update. Verify actual task snapshots,
center and exposed-side launch/dismiss, drag cancellation, horizontal paging, entry from HOME and an
app, quick switch, Back/background return HOME, rotation, empty list, task headers, and agent reads /
writes against `dumpsys activity recents`. Keep secure snapshots redacted. Separately confirm existing
AI double-tap/hold behavior remains routed to Taku. Passing source tests alone is not visual acceptance.

### Dormant overview recovery

Leaving overview through its empty background transitions it to HOME/BG_LAUNCHER and unbinds
icons and previews. Android can later resurface that same Activity without an overview gesture.
Upstream `onStart()` restores content alpha but does not reactivate task loading in that state.
The Taku fallback Activity restores DEFAULT and the native data subscriptions once it is resumed,
focused and idle; it waits for active gesture, state and remote animations to finish. Pending
recovery callbacks are canceled on focus loss, stop and destruction. Uninitialized thumbnails
also suppress the depth veil, including subsequent scroll/depth updates, so reset views never
leave card-shaped gray silhouettes behind. Secure/locked snapshot handling is unchanged.

Reproduce and check this lifecycle path on the existing portrait emulator:

```sh
python3 image/recents/verify-resume.py --serial emulator-5590 --out out/recents-resume-check
```

This opens Clock, enters native overview, taps the background to return HOME, then brings the
existing Recents task forward with `am task focus`. It checks fresh preview binding on every
cycle and verifies that HOME layout data is unchanged. It never installs, wipes or removes tasks.
Inspect the saved HOME/restored screenshots for actual preview and icon visibility: accessibility
labels alone can remain present even when their drawable has been cleared. Also verify ordinary
gesture entry, paging and task launch, since the idle recovery must not interrupt those animations.
