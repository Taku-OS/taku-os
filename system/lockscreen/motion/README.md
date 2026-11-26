# Native clock height morph

The runtime is Kotlin `Path` / `Canvas` and Android `Transition` / `ValueAnimator`.
No JavaScript engine, WebView, SVG parser or per-frame font conversion is shipped.

Source: the designer's `taku-animation-handoff-10-15` handoff,
`effects/clock-height-morph/glyphs.ts` and `demos/13-LockScreenHero/timeline.ts`.
`designer-0930.json` records the original contour arrays and source SHA-256.
This source contains **09:30 only**, not a complete variable-height font.

`generate.py` preserves those 0/3/9/colon contour pairs and adds 1/2/4/5/6/7/8 from
the repository's Space Grotesk Regular. It converts the font's quadratic curves
to cubics and extends each digit's stems/diagonals at explicit vertical landmarks,
keeping caps, crossbars and counters. These seven tall endpoints are a derived
extension and still need visual design sign-off; they are not additional designer
artwork. Space Grotesk's OFL is in
`system/assistant/design-system/licenses/SpaceGrotesk-OFL.txt`.

Regenerate with Python and `fonttools==4.68.0`:

```sh
python3 system/lockscreen/motion/generate.py
python3 system/lockscreen/motion/test_geometry.py
python3 system/lockscreen/update-patch.py --fetch
```

Progress 0 is tall, 1 is compact. Every corresponding cubic control point is
interpolated; `scaleY` is not used. Uniform fitting is only for constrained screen
widths. Runtime uses the device time, time zone and 12/24-hour preference, not the
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
