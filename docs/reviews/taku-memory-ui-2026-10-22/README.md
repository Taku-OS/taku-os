# Taku Memory 0.3.0: interface and browsing

This is a native UI update to the independent Memory APK. It retains 0.2.0 local recording, Magpie batch analysis, Agent queries, and TaskBroker protocols without adding model-request paths.

## Product references

The following official materials were reviewed on 2026-10-22; the Breeno Memory collections page, Pixel Screenshots home page, and Fabric workspace were also viewed in a browser. Official promotional images describe the displayed product versions, not acceptance of their latest APKs obtained by us.

| Reference | Observation and adoption | Taku initial-release limits |
| --- | --- | --- |
| [Breeno Memory: official ColorOS feature introduction](https://www.coloros.com/article/A00000102/) | Collection names, counts, and content take priority; cards subordinate source/time, with actions appearing alongside content. Adopted clear card hierarchy and separate reading details. | No manual “Remember” entry; no collection Q&A or media thumbnails in the initial release. |
| [Pixel Screenshots: official Google interface introduction](https://blog.google/products-and-platforms/devices/pixel/google-pixel-screenshots-tips/) | Collections/categories support retrieval, search is prominent, and source cues allow tracing. Adopted categories, source filtering, and fixed search. | Taku stores readable text and still uses keyword search; no claim of image understanding or natural-language retrieval. |
| [mymind official mobile materials](https://mymind.com/mobile) | Emphasizes collecting first and retrieving later, reducing organization effort; reading centers on content. Adopted simple navigation and unobtrusive metadata. | Does not copy manual collection, smart tags, or multimedia capabilities. |
| [Fabric official product presentation](https://fabric.so/) | Content dominates the workspace; search and actions are compact. Adopted quieter supporting information and progressive disclosure. | Does not bring desktop masonry layouts or multi-user workspaces into the initial phone release. |

The interface uses native Android Views and custom-drawn vector icons; no third-party images, copied brand icons, WebView, or network UI dependencies were added.

## Completed work

1. **Retrieve memories**: fixed keyword search, horizontal categories, source filter, date-grouped cards; distinct explanations for empty search and no records.
2. **Read memories**: separate detail page, selectable body, collapsible record information, clear correction entry; edit history and confirmed deletion in More.
3. **Continue actions**: grouped pending suggestions and accepted tasks, Chinese task-status labels in this historical version, and original memories always available for readback.
4. **Manage limits**: settings grouped into automatic recording, analysis/use, and data/runtime; four independent switches; detailed queue and service information moved to runtime details.
5. **Basic experience**: bottom navigation, preserved list position on Back, new-record updates do not replace an actively edited search field, and clear dark system status/navigation icons.
6. **Reach history**: in-app pagination browses all retained records, fixing the old UI's latest-20-only view; signature-authorized Agent queries retain a 20-record cap. Accepted suggestions are queried independently so newer ordinary records cannot displace them.

## Verification scope

Current evidence is in `acceptance.json`, SQLite test output, and PNGs in this directory. All screenshots contain synthetic material on the dedicated development AVD, not personal user memories.

| Environment | Status this round |
| --- | --- |
| Dedicated Android 16 / API 36 ARM64 emulator | In-place upgrade, native-page interactions, automatic capture of synthetic pages, and storage regression; details in the acceptance record |
| Physical phone | Not accepted this round; emulator results do not establish a pass |
| Full ROM | No full ROM built/flashed this round; APK build and overwrite installation do not establish ROM acceptance |

Real Jev batch-analysis evidence is inherited from the [0.2.0 record](../taku-memory-batches-2026-10-22/README.md). Model analysis stayed disabled during this UI acceptance run; no repeated real model requests or newly accepted Agent suggestions.

## English

This release improves the native memory app's retrieval and reading experience: chronological cards, keyword search with category/source filters, complete paginated history, a dedicated reading page, clearer suggestion receipts, and grouped settings. The four independent controls and existing Magpie/TaskBroker integration remain intact. Design references are official Xiaobu Memory, Pixel Screenshots, mymind, and Fabric materials linked above; unsupported media or semantic retrieval features are not implied. Validation covers the dedicated emulator only. Physical devices and full ROM integration remain unverified for this UI release.

## Results and screenshots

The 0.2.0 → 0.3.0 overwrite installation retained 20 existing records and configuration, then the actual observation service added three synthetic records. Tested loading 23 records, source/category filters, no-result search, Back from details, canceled deletion, four independent switches, and runtime details. Actual Provider updates during search typing preserved input and focus. Toggling settings preserved scroll position; home/navigation were checked at 1.3× system font scale. Original configuration, observation service, font, and AVD paused state were restored after acceptance.

**66** storage tests and **69** batch-queue tests passed; `npm run verify` passed, with unsupported Linux FIFO checks skipped on macOS under the existing rule. Added storage tests cover stable pagination for equal timestamps, combined filters, literal wildcards, Agent count limits, and older accepted suggestions.

| Page | Original screenshot |
| --- | --- |
| Memory home | [View](memory-home.png) |
| Full reading details | [View](memory-detail.png) |
| Category filter | [View](memory-filter.png) |
| Load history and restore position | [View](memory-history.png) |
| Keyword search | [View](memory-search.png) |
| No matching results | [View](memory-empty-search.png) |
| Accepted suggestions | [View](memory-suggestions.png) |
| Grouped settings | [View](memory-settings.png) |
| Runtime details | [View](memory-diagnostics.png) |
| 1.3× font | [View](memory-large-font.png) |

Machine-readable record: [acceptance.json](acceptance.json), including package, source-file, and evidence hashes. APK retained independent UID 10162 and the existing development platform signature, without expanded or added network permissions. Two old real Agent receipts remained visible in Suggestions; this round created no task or new model request.

[Historical language evidence](../HISTORICAL-LANGUAGE.md) documents the intentionally retained Chinese-locale raw inputs, outputs, and reproduction scripts for this historical review.
