# System Agent drawer visual and interaction QA

Date: 2026-10-13. Scope: portrait orientation at standard text size on a dedicated Android API 36 AVD, using selected light/dark system drawers. This is not Pixel ROM, model-task, or complete accessibility acceptance.

## Comparison inputs

- Source visual truth: `out/agent-design-20261013/selected/dark.png` and `light.png`, generated designs at 853×1844, interpreted as approximately 390×844 conceptual content areas. The source has no verifiable font/DPR metadata.
- Implementation screenshots: `out/agent-drawer-ui/visual-fixture/dark.png` and `light.png`, 1080×2424 at Android density 420 (2.625), approximately 411×923dp.
- Normalization: originals retained; comparison copies remove only native top 64px/bottom 126px bars, then normalize with the source to 390×844. Aspect-ratio normalization affects some dimensions and is not physical-pixel equality. The actual OS supplies system bars and background Home; conceptual fake bars are not reproduced.
- State: conversation title “Phone settings”, the same four sample dialogue segments, and two tool-card rows. The fixture uses the production view without starting a runtime or executing example commands. Send is disabled and tools are synthetic inProgress. The source's second pending row, percentages, and success promises are not runtime facts and must not be fabricated in production.
- Full-view images `out/agent-drawer-ui/comparison/dark-full.png` / `light-full.png` were inspected in the same image input.
- Focused `comparison/dark-detail.png` / `light-detail.png` were inspected for reply wrapping, avatars, tool cards, icons, and the bottom composer.

## Fix record

| Priority | Finding and impact | Fix and verification |
| --- | --- | --- |
| P1 | Entry shared the Home task stack, so collapse returned Home instead of the prior app | Separate transient-entry task affinity; real test confirmed return to Settings |
| P1 | VoiceSession wrap-content feedback destabilized drawer height and keyboard position | Root measures available window bounds and handles resize/IME overlap; real keyboard screenshots and input-bounds assertions passed |
| P1 | Submission dropped screenshot arguments; screenshot-only flag made AOSP return empty data | Pass the pre-invocation Android bitmap and request assist/screenshot together; request context.png SHA exactly matched the original Settings image |
| P1 | Corrupt task state caused a background restart loop and blocked new sessions | Preserve and isolate the original file, recover the queue; thread/list succeeded after actual service restart |
| P2 | Overwide replies produced isolated final-character wrapping; tool cards were too tall | Reply max 250dp, user bubble max 260dp, tighter card line spacing and footer divider; latest full/detail images reviewed |
| P2 | Background-screen entry lacked a capsule; input hierarchy differed from source | Standard Material 18dp icon and capsule; emphasized dark outline and softer light outline; final screenshots reviewed |
| P2 | Streaming redraw could interrupt reading position | Preserve scrollY; follow only at the bottom or on conversation switch. Actual long model streams were not accepted yet. |

Early screenshots/failure logs remain in ignored output. Final real interaction regression took 39.676 seconds, `OK (1 test)`. Only the light composer border color changed afterward; the final APK was rebuilt, installed, and visually captured. Visual fixtures were not treated as real-model evidence.

## Five visual surfaces

- **Typography:** native sans-serif with Chinese system fallback; title 18sp, messages 15sp with roughly 5dp line spacing. Source font metadata is absent, so identical font files are not claimed. Same-input two-line replies no longer leave isolated trailing characters. Long native titles truncate visually; settings can edit the full title. Default size is readable; large text and landscape were not accepted.
- **Spacing:** full-width top-rounded drawer at approximately 89% screen height, 42dp handle, 18dp horizontal padding, right user bubbles/left assistant replies, tool cards at the same hierarchy, and fixed bottom composer. Code does not fake OS bars. Actual keyboard layout and downward collapse passed.
- **Color:** shared dark green/mint and warm white/pale purple/orange semantic themes. Light-theme body text became dark indigo; icons/status use theme colors. The system provides blur against runtime backgrounds. Conceptual painted glows are not baked into a whole-page image.
- **Images:** independently generated transparent assistant mark, natively scaled/tinted through ImageView. Material icons preserve upstream paths and licenses. No whole-page PNG crop substitutes for interactive UI. Images are clear at actual size; subtle high-resolution transparent-edge noise is P3 polish.
- **Copy:** the main title comes from the real native thread, not a large brand masthead. Source sample dialogue is confined to the visual fixture. Real operation shows account/read sign-out state, local pending requests, and native tool terminal states. Turn completion is not described as successful device operation.

## Deliberate constraints and remaining boundaries

Tool titles use verifiable command categories. Without reliable semantic parsing, they do not claim “Set to 30%”. Collapsible cards were not implemented, so there is no fake chevron. Tool status comes from native state, without inventing a second pending plan item. The fixture lacks a native controller and disables Send. Real control connections enable it; actual screenshots and tests verify this separately.

P3 differences remain between flat semantic surfaces and the concept's painted lighting, bubble tails, tool connectors, and fine shadows. This is not pixel-perfect replication. Empty conversation, conversation list, settings, and signed-out state were undefined in the source; they follow the same theme and were actually captured. Sign-in UI was unfinished on the original report date; the October 14 addition below records settings/account pages. Plugin store, approval UI, long-history paging, landscape, large text, actual model streams/actions, and hardware cold start remain outside this passed scope.

## Implementation checks

- [x] Selected two revised-title designs; originals and historical selections retained.
- [x] Compared light/dark full views and focused areas in the same input.
- [x] Native thread create/rename/list, drafts, theme, IME, dragging, and foreground restoration.
- [x] Verified Android invocation screenshot against foreground content.
- [x] Real signed-out path, without simulated model success.
- [x] Corrupt-record recovery and real native control; documented source layering, license provenance, and verification boundaries.
- [ ] Later P3: material, bubble tails, tool connectors, and high-resolution mark edges.

final result: passed

## 2026-10-14: Agent settings and sign-in pages

The user found settings too close to system defaults. The revision adds a separate account card, segmented theme selection, context authorization, and conversation preferences, using the original drawer's semantic themes, generated mark, radii, and spacing. The account page offers actual authorization initiation, browser authorization, refresh, and cancellation. No separate generated design exists for these pages; the approved drawer theme constrained them, with UX checked against actual screenshots.

Inspected screenshots: `out/agent-drawer-ui/settings-dark.png`, `settings-light.png`, `account-idle.png`, and `account-cancelled.png`. Card hierarchy/colors are consistent, with clear Agent settings/account connection titles. Content scrolls within the drawer without launching Android Settings. The default blue radio/bare forms became themed drawer components; the sole Switch controls context authorization. Conversation title/response style are below and can be scrolled to and edited in actual regression.

No screenshots were taken during the native OAuth challenge. A real device-code challenge arrived, secure-window flags were checked, and cancellation cleared code/URL. Only pages without an authorization challenge were saved. Two UI regressions passed in 54.234 seconds, `OK (2 tests)`. Separate protocol tests covered success events not proving account/read sign-in, early events, wrong-session events, and challenge clearing. These are synthetic protocol evidence, not completed sign-in. Root could read state; UID 2000 was actually rejected.

The review page prioritizes Agent settings. Original Android Settings content moved into a clearly labeled collapsed restoration-acceptance section. The real account remains unauthorized, and real model execution unverified; sign-in success is not claimed. Default portrait text is readable; large text/landscape need later acceptance.

final result: passed

Latest peer-owner guard regression: 54.772 seconds, OK (2 tests). Final APK/JAR installed hashes match the locally built artifacts. Native device authorization challenge received and cancelled; account remains unconnected.
