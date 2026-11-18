# Taku OS: shared design-system adaptation acceptance

Source specification: the internal `reports/design-system/agentroid-os-v0.1/README.md`, `DEVELOPMENT-HANDOFF.md`, `tokens.json`, and `references/home.png`. The product name for this work remains Taku OS; the source specification was not renamed.

The 104 source tokens are the authoritative development values. `scripts/sync-design-system.py` generates Android resources; `--check` verifies read-only. Version is 0.1.0, SHA256 `6091f4312160910aedccbf4ab050e3f2425c0ad0134616267702ab0af4df9b65`, bound in `res/values/ag_design_tokens.xml`, with provenance in `evidence/design-system/source-provenance.json`. No second tokens JSON is maintained.

Implemented and device-reviewed within the scope below: semantic pastel/text colors, 12/16/24dp radii, 3dp main-card border, no default shadow, native static 4% microdots, fixed 5×7 DotNumberView (complete ordinary-digit fallback for large text/narrow cards), 14sp two-line entries, 48dp controls, one stationary 64dp companion, an eyes capsule within the header safe area (real task state, no fake recording), ordinary Chinese generation status, request-preserving failure and TaskRequests retry, no fixed inner-page dock, an addable native input widget, and mirror access.

Old screenshot-cropped microtexture was removed from production APK resources. Doto no longer renders the clock. The concept's 18:42, 78% battery, weather, and itinerary were not copied. No mixed real/fake receipts, SystemUI lock screen, system-wide cross-app capsule, or fake percentage was added.

## First hardware pass (APK 666127 bytes)

- Font scale 1.0: screenshots cover Home, generation, and capsule taps focusing real input. The generation page has no fixed dock; status comes from an existing real task, without a new model submission.
- Clock text is complete at 1.3, 2.0, and 320dp, with ordinary-digit fallback working.
- P2, pending fix/retest: fixed main-card height clipped secondary text and independent-screen headings at 1.3/2.0. Battery text wrapped at 320dp, and the default activity card's bottom edge clipped slightly. The source requires content to grow, not smaller text. Pre-fix screenshots remain in `evidence/design-system/first-pass/` as the original failure evidence.
- Fix source frozen: wrapping main card/metrics with token minimums, full-width primary context at 320dp, appropriately compact single-line entries/dock, explicit edge content and one inset handler, official Outlined camera/photo icons, and default Button elevation animation disabled.
- finally restored test configuration to font_scale 1.0, 1080×2400, 420dpi; record: `evidence/design-system/display-config-check.json`.

## Second-pass scope and boundaries

- Default text: Home hierarchy, card sizes, unobstructed companion, input/dock safe areas.
- Font scales 1.3 / 2.0: full clock, two-line entry names, generation steps, and nonoverlapping bottom input/main action.
- Native input, capsule focus, generation navigation, full app list, and actual foreground apps.
- Task state uses the latest real task. Failed-task retry had source review only; no fake failure was created for demonstration.
- Actual updated Home/generation screenshots were compared with the reference in the same image input. Rules take precedence over PNG deviations. A short real navigation recording was made.

Complete evidence for the earlier video reconstruction and bounded legacy UIAutomator timeouts remains in `evidence/video-ui/`. Its QA moved to `design-qa-first-video-version.md` and cannot establish this shared-design-system acceptance.

final result: passed

Second-pass fixes were installed and inspected image by image: the default activity card bottom is complete; main-card secondary copy and independent-screen headings are complete at 1.3/2.0. At 320dp, capture must wait for wm/config stabilization or a fresh Activity. Final `home-width-320-fresh.png` shows a full-width main card and complete 100% value. Original configuration was restored.

## Latest user override: replace fixed input with a Home widget

All pages lose fixed bottom bars by default. The only EditText becomes an optional, initially disabled Home module that can be added/removed. Capsule/companion and generation-page request editing reuse it. Earlier P2 default/large-text fixes were confirmed on-device. The 320dp fresh screenshot confirms full width and complete 100%. The widget was installed and passed real interaction acceptance. An old-style recording stopped when fixed coordinates after the redesign could not establish the task-board foreground; it is not a success video and submitted no model task.

## Final widget acceptance and scope

- No fixed input by default: `evidence/home-widget/default.png` / `removed-after-restart.png`.
- Adding and actual editing: `added.png` / `input.png`; the latter is a complete Chinese draft, not a fake model result.
- Management/removal: `manager.png` / `removed.png`.
- Real native automation passed in 13.254 seconds, `OK (1 test)`. Recording the same passed actions took 13.302 seconds and passed again. The repeat corrected a fixed-coordinate recording mis-tap, rather than repeatedly running the old 90-second test.
- Persistence: `persistence-check.json`, `added-after-restart.png` / `removed-after-restart.png`. This establishes app-process restart persistence, not full emulator reboot.
- Actual video: `widget-flow.mp4`, `widget-flow-metadata.json`, 14.763733 seconds, 720×1600 H.264. Extracted frames confirm addition/removal. Actual Chinese editing is evidenced by `input.png` and exact test readback, not a claim that every instant appears in sampled video frames.

### Five visual checks

The same image input included source reference home.png and the latest native Home screenshot without input. Comparison uses phone content areas: the source is 853×1848 with conceptual system bars, while hardware is 1080×2400, roughly 411×914dp. This is not physical-pixel 1:1 matching.

Typography uses source role sizes in sp, ordinary Chinese state text, and fixed 5×7 short times without shrinking text to fit. Spacing follows four columns, 8dp, and dynamic minimums; the user selected no fixed input, and the widget follows context cards. Colors share all 104 source tokens; microdots are static and low contrast. The independent transparent 64dp companion and official Material icons remain complete, without cropping the source PNG into a whole-page UI. Copy uses device time, battery, and real background/task state. The reference's weather, itinerary, receipts, and playback examples were not fabricated. The capsule stays in the app content safe area; third-party brand icons retain their identities.

This passed result covers this round's basic Home/generation design and native-widget addition, editing, removal, and saved selection. A real SystemUI lock screen is unimplemented; the older exported image was not repackaged. Standard cross-launcher AppWidget hosting, drag/multipage management, and real failure/retry scenarios are not claimed complete. Later independent pet animation is subsequent work and is not verified by these screenshots.
