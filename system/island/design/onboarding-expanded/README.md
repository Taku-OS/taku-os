# Expanded onboarding progress card

Visual reference: Figma `MdImIDNqePpNZ62C0GLArW/1155:1965`.
The reference is the All set card. This implementation adopts its **chrome**, not
its sample user name, success message or unrelated success actions during login.
No static assets were returned: the eyes reuse the existing native assistant glyph.

- 40 dp outer radius; 16 dp padding; 32 dp eyes header; 4 dp header/body gap.
- Hanken Grotesk Regular 18 sp, 27.9 sp line box, tracking -0.01 em, white at 80%.
  Half-leading above/below the text compensates for Android baseline-only spacing.
- 16 dp action gap; two equal pill buttons separated by 8 dp. Medium 16 sp,
  24 sp line box, 12 dp horizontal / 8 dp vertical padding; 40 dp minimum height.
- Secondary background white 8%, label white 92%. Primary white, label #1B1B1B.
- Height is measured from content (approximately 180 dp for two lines). At the
  existing window/IME cap the message scrolls, keeping the actions visible.
- The background is black at 92% over a bounded 24 dp compositor blur. If Android
  disables cross-window blur, it becomes opaque #121212 rather than allowing
  browser text to remain readable through the card. Rounded touch regions and
  the non-focusable window remain in effect; outside taps reach the browser.

Only simple Taku progress notifications (package org.agentos.agent, category
progress, text template, at most one declared action, no rich surface / media /
steps / progress content) select this renderer. Other notification templates and
the activity chooser retain their existing implementations.

The body comes from the real notification. **Close** calls the same presenter
collapse operation as `cmd statusbar island collapse`; it does not cancel login.
The primary **Back to setup** button dispatches the existing `open` PendingIntent
through `cmd statusbar island action KEY '{"id":"open"}'`'s shared handler.
Swipe up and the accessibility dismiss action also collapse. Action removal on an
update removes the button. No fake account, completion or onboarding state is saved.

`cmd statusbar island state` and the matching content provider expose
`presentation.card.layout = native-progress`, `renderer = systemui-native`,
`nativeReady`, the actual focusable flag, and `progress.text` / `progress.buttonCount`.

Validation uses the existing `build-capsule-tests.sh` isolated instrumentation APK:
production Views, real Android measurement/Canvas, equal button widths, spacing,
content height, real action IDs, close-only collapse, scroll caps, and removal of
stale actions. A local component preview is not an installed SystemUI screenshot.
Full Soong build, compositor blur and browser/IME window acceptance still require
installation of the new SystemUI; the existing builder currently closes SSH
before authentication. No browser login or emulator user data was cleared.
