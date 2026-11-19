# Onboarding connecting capsule

Design: Taku Mobile OS, Figma `1155:1323`, compact group `1155:1436`.
The three SVGs here are unmodified exports of `1155:1437`, `1155:1451` and
`1155:1452`. The right neck is the same artwork mirrored horizontally.

SystemUI renders the native equivalent: 32 dp icon disc (centre 16), 24 dp
camera (centre 52), text pill starting at 72, 8 dp text insets, Hanken Grotesk
Regular 12 sp, tracking -0.12 dp, white at 92%. The neck Path uses the SVG's
original coordinates. It is unioned with the discs and text pill once per size,
then drawn in one pass, so shared antialiased edges cannot create seams.

`IslandActivity.statusLabel` selects this layout for progress notifications with
a picture leading slot and a text trailing slot, and preserves assistant Building.
The existing notification, tap/long-press, timeout and `cmd statusbar island`
interfaces remain authoritative. No package-name or title matching, new setting,
extra window, or fake sign-in state is introduced. The compact shape has one
primary activity; long-press still opens the activity chooser.

The presenter anchors the camera to DisplayCutout's visual path, not the padded
exclusion rectangle or the View's midpoint. If the physical hole exceeds 24 dp,
the complete shape scales uniformly to cover it. Actual left/right/top/bottom
bounds are checked against the clock, system icons and bar; insufficient space
hides the compact surface while leaving the notification and Agent access intact.

The login publisher now sends **Signing...**, then **Connected**, with its real
account state and existing actions. `animated: working` uses the existing native
`DotMatrixLoader.thinking` implementation, ported from the designer handoff
`effects/dot-matrix-loader/pose.ts`. The handoff and checked-in source are byte
identical. The fixed 3x3 positions, opacity, scale, 1000 ms loop, 125 ms clockwise
steps and attack/hold/decay are preserved. Reduced motion shows the Figma rest pose.
Figma returned no additional keyframe tracks for this compact group.

## Validation

- `npm run check:island`: pinned patch checks, store/policy checks and 33 frames
  compared with the designer TypeScript golden data (including reduced motion).
- `system/island/typecheck.py`: Android 17 + narrow SystemUI doubles; not Soong.
- `system/island/build-capsule-tests.sh`: builds a separate instrumentation APK
  from the production capsule View and Activity model. Supply `KOTLINC` and
  `KOTLIN_STDLIB` for the installed Kotlin distribution (plus the usual Android env).
- On the assigned emulator:

```sh
adb -s SERIAL install --no-incremental -r out/island-capsule-tests/CapsuleRenderTests.apk
adb -s SERIAL shell am instrument -w org.agentos.island.capsule.tests/com.android.systemui.taku.island.CapsuleRenderTests
adb -s SERIAL pull /sdcard/Android/data/org.agentos.island.capsule.tests/files/frames out/island-capsule-tests/frames
```

The test renders offscreen with Android Canvas, covers login/connected/Building
selection and media isolation, and checks the opaque neck, circular left slot,
camera centre and changing loader poses at reference/physical-hole scales.
It never publishes a login notification or replaces SystemUI. A full platform
SystemUI build and real status-bar sign-in acceptance remain separate requirements.
