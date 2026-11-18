# Audio-engine page screenshots

Captured: 2026-10-28. Branch: `audio-engine`. Device: `Taku_Dev_API36`, Android 16, original screenshots 1080 × 2400.

## New feature pages

| Page | Purpose | Entry point | Screenshot |
| --- | --- | --- | --- |
| BYOK · Bring your own API key | Add, replace, or remove fal / OpenRouter keys | Taku AI → BYOK | [03-byok.png](03-byok.png) |
| Local reading | Select Chinese/English voices and speech rate | Taku AI → Local reading | [05-local-reading.png](05-local-reading.png) |
| Voice status | Inspect language, models, permissions, resource occupancy, volume, and echo-cancellation state | Taku AI → Voice status | [06-audio-status.png](06-audio-status.png) |
| Video translation · Experimental | Select target app, model, source/target languages, captions or translated audio, and original-audio replacement | Taku AI → Video translation · Experimental | [07-video-translation.png](07-video-translation.png) |
| Call translation · Experimental | Separately configure incoming/outgoing translation models and voices, plus both parties' languages | Taku AI → Call translation · Experimental | [08-call-translation.png](08-call-translation.png), [lower controls](08b-call-translation-controls.png) |

These count as **5 pages** by user-visible feature entry. Video and call translation share `MediaTranslationActivity`, distinguished by page parameters.

## Changed existing UI and new dialogs

| UI | Change | Screenshot |
| --- | --- | --- |
| Taku AI home, upper section | Voice AI toggle, BYOK entry, top-level fal voice-model options | [01-taku-ai-top.png](01-taku-ai-top.png) |
| Taku AI home, lower section | Call translation, video translation, local reading, and voice status entries | [02-taku-ai-audio-entries.png](02-taku-ai-audio-entries.png) |
| fal voice-model dialog | Kotoba STS, Grok Voice, Whisper segmented captions | [04-fal-models.png](04-fal-models.png) |
| Local voice dialog | Chinese female/male voice selection | [05b-local-voices.png](05b-local-voices.png) |
| Speech-rate dialog | 0.75×, 1.0×, 1.25×, 1.5× | [05c-local-speed.png](05c-local-speed.png) |
| Agent conversation composer | Voice dictation and continuous-conversation entries | [09-agent-composer.png](09-agent-composer.png) |
| Continuous-conversation menu | Long-press the continuous-conversation button to show turn-taking and experimental interruptible modes | [09b-agent-conversation-menu.png](09b-agent-conversation-menu.png) |

## Developer-only UI

The `AudioHostActivity` developer voice window supplies a visible host for authorized CLI sessions and is not included in the five ordinary feature pages above: [10-developer-audio-host.png](10-developer-audio-host.png).

## Problems observed and scope

- The default ActionBar obscures top content in local reading, video translation, and call translation; lower call-page buttons also overlap the navigation bar. Screenshots retain the actual appearance; these issues were not yet fixed.
- Taku AI status-bar text has low contrast; the developer window also has a top-inset problem.
- In this cold-boot environment, Taku AI reported the model service temporarily unavailable and the Agent page could not connect to the conversation service. These screenshots are for UI review, not login or model-connectivity acceptance.
- Translation target apps show fixture apps retained on the test device; no fixture or third-party call was started in this round.
- Only pages/dialogs were opened, without recording, continuous conversation, translation, or playback. No key, voice, rate, or model preferences were saved. A running translation-caption overlay was neither started nor captured.
- Key summaries show only the app's existing masks; secure key-entry windows were neither opened nor bypassed.
- The emulator shut down normally afterward, retaining existing virtual-machine data.

## Git tracking and publication scope

Checked at commit `8506bd2dfda09fd4087bda506badf798e1af9cc1` on 2026-11-06: this directory already contains **18 Git-tracked files (17 PNGs and this README)** and synchronizes with the repository; the [root .gitignore](../.gitignore) has no `/screenshots/` rule. Adding an ignore rule later would not automatically untrack existing files. Run `git ls-files -- screenshots` at the repository root for the actual inventory, which also includes [13-controller-overlay.png](13-controller-overlay.png).

These views retain their historical environments and acceptance scopes; they do not establish that current-version or launch materials passed public-release review. Before publication, inspect each image for accounts, test data, third-party content, and brand-use scope; see the [third-party and materials inventory](../docs/third-party-materials.md). This round corrects descriptions only, without deleting images, changing tracking, or claiming all views passed review.

## Supplementary screenshot after YouTube login

[11-youtube-live-translation.png](11-youtube-live-translation.png): after the user logged into YouTube, the same WebView browser actually played an English video while the Kotoba English→Chinese overlay continuously produced translations, with translated audio and original-audio replacement enabled. This observation is not complete latency or translation-quality acceptance.

## Home live-translation shortcut

[12-home-translation-shortcut.png](12-home-translation-shortcut.png): “Live translation” was added beside Taku Agent on Home. Tapping opens the overlay using the last media settings and returns to the source app; long-press opens settings. A paused video still requires pressing play in the player; the overlay retains the stop-translation button.
