# Launch visual assets and Case specifications

This page prepares assets corresponding to actual versions; it does not mean the assets are already produced or the product publicly released. Use **TakuOS** as the project name and **Your world, on your terms.** as the brand line. Verify versions and download entry points separately for Taku Desktop, Taku mobile, and TakuOS.

## Deliverables

Place final files approved for publication in `docs/media/launch/`, with accessible archives of source files or original recordings. Create directories/files only when assets are actually ready; do not link the README to nonexistent files.

| Deliverable | Content and format | Acceptance |
| --- | --- | --- |
| README hero image | Project name, brand line, one accurate positioning sentence, and a real product view; editable source and README-suitable export | Check desktop/narrow-screen readability, text clarity, and asset rights; retain a caption and alt text |
| GitHub social preview | 1280×640, PNG/JPG/GIF, under 1 MB; keep primary content clear of edges | After committing the image, configure GitHub Social preview and verify actual share cards separately; having a file alone does not mean it is live |
| Opening loop GIF | At most 10 seconds, recorded on a real physical device, with English UI/captions and a clean background; show one complete, verified short task | Input, execution, and result are understandable; prefer original speed and label acceleration, such as 2×; retain the original recording |
| Three Case clips | One reproducible task per clip, with same-version steps, permissions, successful results, and known failure boundaries | Video matches the guide; at least one developer outside the team reproduces the main Case using publicly available instructions, with actual scope recorded |
| Case guide | Scenario purpose, source/component versions, device, steps, Agent commands, GUI results, and one small modification | Demo links to a Run entry; the modification has concrete files and observable results; link real evidence |

See [GitHub's official instructions](https://docs.github.com/en/repositories/managing-your-repositorys-settings-and-features/customizing-your-repository/customizing-your-repositorys-social-media-preview) for share-image dimensions and limits. Existing brand resources may be reused only after checking [third-party and brand origins](third-party-materials.md); internal-use permission does not automatically authorize public distribution.

## Recording a Case

1. Choose a specific verified feature and pin source SHA, component/ROM version, and device. Check [current status](current-status.md) first; do not substitute a component version for the whole package version.
2. Complete the steps using publishable test data, recording prerequisite apps, accounts/model services, networking, permissions, and any headset or physical-device requirement. Show service failures honestly or rerecord successful steps; do not replace real results with demo data.
3. Record the Agent's actual commands/results and verify that the GUI shows the same state. New settings/features must follow [AGENTS.md](../AGENTS.md); explicitly state when data is unavailable upstream.
4. Record the entire flow from intent entry to visible result. If it exceeds 10 seconds, choose another complete short task for the opening, or clearly label editing/speed changes and provide the full original. Do not present edited waiting time as actual performance.
5. Check whether notifications, account names, keys, chats, location, and third-party screens may be published. Use test data and remove private content outside the Case, while retaining the real evidence needed for results.
6. Align versions and entry points across README, Case documentation, and the official site; save originals, exports, captions, covers, and necessary licensing information.

## Using existing material

| Existing material | What it can demonstrate | What it must not represent |
| --- | --- | --- |
| [Historical home](media/home.png), [generated app](media/generated-app.png), [background display](media/background-display.png) | Android 16 emulator views from the original historical scenarios; see [scenario records](scenario-acceptance.md) | Current-version English physical-device launch footage |
| [Desktop pet video](media/desktop-pet.mp4) | A retained animation demonstration; provenance still needs confirmation | End-to-end validation of an Agent completing a task |
| [Audio screenshots](../screenshots/README.md) | Observations of corresponding pages and specified supplementary scenarios, including documented problems | A guarantee of all audio features, performance, or model connectivity |
| [Generative UI V2 record](reviews/generative-ui-v2-2026-11-02/README.md) | Real connections, results, and blockers for specified emulator scenarios | Connectivity for arbitrary workflows or completed same-version physical-device acceptance |
| [Physical-device OTA record](reviews/takuos-1.0-ota-2026-10-25/README.md) | Native updating and data retention for the specified I→J candidates | The complete current HEAD package, first installation on a stock phone, or every update path |

Each asset description must retain at least its purpose, source, production/validation date, language, device/version, editing/acceleration status, rights basis, corresponding guide, and evidence. This can be recorded in Markdown alongside the assets; documentation preparation does not require a new program or data format.

## Current delivery boundary

This round delivers only specifications and references. It creates no new hero/share images, GIFs, or clips, and changes no GitHub/official-site settings. Record versions and results in the [preparation checklist](open-source-release-checklist.md) after image production, physical-device recording, external reproduction, and share-preview testing are each complete.
