# Native clock height morph

The runtime is Kotlin `Path` / `Canvas` and Android `Transition` / `ValueAnimator`.
No JavaScript engine, WebView, SVG parser or per-frame font conversion is shipped.

Source: the designer's `taku-animation-handoff-10-15` handoff,
`effects/clock-height-morph/glyphs.ts` and `demos/13-LockScreenHero/timeline.ts`.
`designer-0930.json` records the original contour arrays and source SHA-256.
This source contains **09:30 only**, not a complete variable-height font.

`generate.py` preserves the supplied 0/3/9/colon contour pairs exactly. Six is the
same authored nine rotated 180 degrees, retaining its bowl/stem junction. Digits
1/2/4/5/7/8 use paired cubic centre lines: Android offsets the interpolated line
at a uniform width (14 px tall, 10.08 px compact), so curves and diagonals cannot
swell or pinch as independently stretched inner/outer contours did. Eight's bowls
meet at one shared waist, with no inserted vertical bridge. Font advances remain
from Space Grotesk Regular; its OFL is in
`system/assistant/design-system/licenses/SpaceGrotesk-OFL.txt`. The additional
shapes are native extensions of the handoff, not additional designer assets.

Regenerate with Python and `fonttools==4.68.0`:

```sh
python3 system/lockscreen/motion/generate.py
python3 system/lockscreen/motion/test_geometry.py
python3 system/lockscreen/update-patch.py --fetch
```

Progress 0 is tall, 1 is compact. Every corresponding cubic control point is
interpolated; `scaleY` is not used. Uniform fitting is only for constrained screen
widths. Both 12- and 24-hour formats pad the hour to two digits (`09:51`).
Runtime uses the device time, time zone and 12/24-hour preference, not the
handoff demo's fixed time or looping notifications.

The native size transition replaces the stock two-face crossfade **for TAKU only**.
It renders one temporary native face while the real endpoints are hidden, and
removes it on completion, cancellation or detachment. A reversal starts from the
captured in-flight progress. Spring parameters are damping 0.78, frequency 9,
720 ms to compact and 1050 ms to tall, with the handoff's small overshoot and
opacity interpolation (0.5888 to 0.64). Android's global animator duration scale
applies; scale 0 goes straight to the endpoint. No new preference or API is added.

The trigger is the existing SystemUI large/small clock visibility transition.
This change does not implement a new authentication policy. In phase 1, notification
visibility affects that choice; authentication with no notifications still needs
checking in the full Keyguard build.

`tests/native` is a standalone Android test harness. It compiles the actual production
geometry, views and transition, substituting only SystemUI's two view IDs. It checks
all 1,440 24-hour times and runs forward, reverse and interrupted transitions. Passing
it verifies native rendering and transition cleanup, **not** Keyguard authentication,
AOD, split shade or the complete SystemUI build.

The harness also rasterizes each digit at 43 progress values (-0.025 through 1.025,
including spring overshoot). Each glyph must remain inside the canvas, have one connected
stroke region, and preserve its counters: one for 0/4/6/9, two for 8, none for the others.
This catches clipping, broken stems and closing counters that bounding-box checks miss.
Use `--ez digits true --ef progress 0` (tall) through `--ef progress 1` (compact) with
`am start -n org.taku.test.clockmotion/.ClockMotionTestActivity` to inspect all ten digits.
The harness also measures the rendered normal width of the 2 curve and 8 waist/stem
at 21 progress values (147 samples, tolerance 0.65 px), and checks two-digit hours
for all 24 hours in both 12- and 24-hour mode. Visual comparison remains necessary
alongside geometry and raster checks.
