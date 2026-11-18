# Taku Memory 0.3.1: branding and actual browsing scenarios

This round integrates the existing Taku logo into native pages and tests automatic memory/display using actual browsing of public information.

## Branding and reading

- Reuses `system/memory/res/drawable/taku_brand_mark.xml`, matching Assistant/system-surface branding. Home, Suggestions, and Settings show logo/name; Details and runtime-information bars show the mark.
- Uses the logo's `#FF5A1F` accent color consistently.
- Browser windows commonly use the application name. The app now uses a document title only when the accessibility tree actually supplies it and the existing protected collector already included it in captured text; otherwise it retains the window title. It neither fetches extra pages nor uses models to generate/rewrite titles.
- Cards prefer source paragraphs after the title, skipping meaningless long decimal progress values in navigation. Details retain complete captured text. Privacy/protected-window checks, input exclusion, deduplication, and model switches keep their original implementation.

## Scenarios and sources

| Actual operation | Public page |
| --- | --- |
| Read the project's Harness components/responsibilities | [Taku OS Harness](https://docs.taku.ai/harness/overview/) |
| Learn Android Activity tasks/back stacks | [Android Developers, Chinese page used in the original test](https://developer.android.com/guide/components/activities/tasks-and-back-stack?hl=zh-cn) |
| Look up museum opening/visit information | [The Metropolitan Museum of Art](https://www.metmuseum.org/plan-your-visit) |

Actual Firefox browsing on the designated development AVD triggered collection through `ObservationService`. Source app, body, title, and timestamps came from the running application. Acceptance did not inject body text into the production database or use user-correction interfaces to populate displays. Model analysis stayed disabled, producing no Jev requests or new Agent tasks.

Firefox 158.0.2 came from the [official Mozilla release archive](https://archive.mozilla.org/pub/fenix/releases/158.0.2/android/fenix-158.0.2-android-arm64-v8a/). Its APK remained in ignored development outputs. Firefox was neither made the default browser nor signed into an account. It is an acceptance-environment tool, not a Memory APK dependency.

## Acceptance boundaries

| Environment | Status |
| --- | --- |
| Dedicated Android 16 / API 36 ARM64 emulator | Actual installation, automatic public-page collection, logo/native-page checks; results in `acceptance.json` |
| Physical phone | Not tested in this round |
| Complete ROM | Not built or flashed in this round |

Existing records remained. Original observation configuration and emulator pause state were restored after testing. The [0.3.0 synthetic-page regression](../taku-memory-ui-2026-10-22/README.md) retains its original labeling; this directory separately stores actual public-page/native-UI screenshots.

## English overview

This update adds the existing Taku logo to the native app and aligns the accent color with the brand. The acceptance scenarios are actual Firefox visits to the public pages linked above, followed by production accessibility observation. Document titles are derived from accessible text already captured by the existing protected collector. Card excerpts prefer a source paragraph; full raw text and provenance remain available. Model analysis stays off. Acceptance covers the dedicated emulator only; physical devices and a full ROM are not validated here.

## Verification and screenshots

Installing 0.3.1 over the previous version retained 23 records. All three browsing scenarios were collected by the actual observation service; titles matched actual accessible document titles and no test API populated body text. The museum scenario used Firefox Reader Mode for real paragraphs. After confirming automatic creation, only one earlier navigation-state record from this round was deleted; its ID remains in acceptance JSON. The Home screenshot contained 27 records, with observation connected and automatic recording enabled; the original paused configuration was later restored.

Actual checks covered logos on Home/Suggestions/Settings/Details, opening real records, expanding provenance to verify IDs/Firefox package, and searching actual developer information using the ordinary keyword `Android`. `npm run verify` passed; Linux FIFO cases were skipped under existing macOS rules. Earlier 66 storage/69 queue regression checks link to 0.3.0 and are not relabeled as rerun here.

| Page | Original runtime screenshot |
| --- | --- |
| Official logo and actual Memory Home | [View](memory-real-home.png) |
| Actual record details | [View](memory-real-detail.png) |
| Source, time, and version | [View](memory-real-provenance.png) |
| Official logo and Settings | [View](memory-branded-settings.png) |
| Ordinary keyword search | [View](memory-real-search.png) |
| Actual Taku documentation browsing | [View](source-work.png) |
| Actual Android developer documentation browsing | [View](source-development.png) |
| Actual museum-site reading | [View](source-visit.png) |

[acceptance.json](acceptance.json) records actual APK/source/screenshot hashes, real record IDs, configuration restoration, and scope. The APK retains independent UID 10162, existing development platform signing, and permission boundaries. Model analysis was disabled; no new Jev requests or Agent tasks were created.

[Historical language evidence](../HISTORICAL-LANGUAGE.md) documents the intentionally retained Chinese-locale raw inputs, outputs, and reproduction scripts for this historical review.
