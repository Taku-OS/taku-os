# Open-source scope and current boundaries

This page describes the public-release preparation scope for Taku OS original system code, on-device integration, development tools, and documentation. Hosted server implementations are excluded. Original work now uses Apache-2.0; see [open-source readiness](open-source-readiness.md) for actual status.

**Licensing for original work is effective; the repository currently remains Private.** [LICENSE](../LICENSE), [NOTICE](../NOTICE), package metadata, and REUSE declarations were aligned to Apache-2.0 in #52; third-party materials retain their original terms. Historical MIT licensing and earlier internal terms remain in [license-history](license-history/), without rewriting commit history. Distribution rights for assets, account data, proprietary binaries, and complete ROMs still require separate review.

## What is in preparation scope

| Category | Actual repository contents | Public-release preparation boundary |
| --- | --- | --- |
| Original Taku system and on-device services | Assistant, Broker, Memory, surfaces, display, AI interfaces, plugin integration, and Agent App in `system/`; original protocols and adapters in `core/`, `sdk/`, and `adapters/` | Original programs and documentation are in scope. These directories also contain third-party/imported materials and cannot be relabeled wholesale as Apache-2.0 |
| ROM integration | [image/](../image/), root [Android.bp](../Android.bp), product configuration, upstream patches, source locks, and build tools | Original sources and methods for assembling a ROM; complete Android/LineageOS/Linux and vendor inputs are obtained separately and retain their own licenses |
| Developer tools and examples | [tooling/](../tooling/), [examples/](../examples/), [scripts/](../scripts/), tests, preinstalled Agent skills, and usage documentation | Preserve provenance, commands, dependencies, and actual acceptance boundaries; example dependencies and third-party skills still need individual confirmation |
| Documentation and images | Root documentation, [docs/](README.md), [screenshots/](../screenshots/README.md), app assets | Original documentation is in scope. Screenshot content, branding, design deliveries, fonts, and generated images receive individual review under the [materials inventory](third-party-materials.md) |
| Hosted servers | External systems such as update channels, artifact downloads, model services, and account services | Their server implementations, operational credentials, databases, and deployment permissions are excluded. Referenced addresses or clients in the repository do not place server code in public scope |

“Servers excluded” means hosted backend services; it **does not exclude the phone's Broker, Magpie wrapper, Codex app-server adapter, or local development services**. These on-device/local programs are separate deliverables from remote inference or hosted services. Whether third-party runtime binaries themselves are in Git is also a separate distinction.

## Source, download dependencies, and complete ROMs

| Input or artifact | What is already in Git | What is additionally required at build/runtime |
| --- | --- | --- |
| Android, LineageOS, Linux | [Platform source lock](../image/source-lock.json), [kernel source lock](../image/kernel-source-lock.json), integration code and patches | Pinned upstream source trees; original project licenses and notices |
| Pixel vendor, Google components | Vendor source references; [Google services lock](../image/google-services.lock.json), [Google apps lock](../image/google-apps.lock.json), and integration configuration | Vendor/Google binaries. The Google Apps inventory explicitly states `internal-test-only`; source URLs, hashes, original signatures, and successful builds are not public redistribution permission |
| Codex, code-mode-host, Magpie, FlClash | Adapters, pinned [runtime asset lock](../image/runtime-assets.lock.json), Taku patches for Codex, and Magpie license | Downloaded ARM64 runtimes or APKs. The current Codex Taku archive points to Releases in this private repository and requires repository read access under the [adapter instructions](../adapters/codex-runtime/README.md); anonymous builds cannot be claimed available |
| Input method | [Source lock, patches, build integration, and licenses](../system/inputmethod/) | Obtain the pinned Fcitx5 Android/submodules and build the APK; the [ROM input lock](../system/inputmethod/rom-input-lock.json) records artifacts and does not mean the APK is in Git |
| RN, Markdown, vector models, and App Kit | Package locks, dependency descriptions, some license texts, and original integration | npm/Maven/model downloads and build outputs; some enter final APKs/ROMs, so distribution must be checked even when download caches are not tracked by Git |

The [root ignore rules](../.gitignore) exclude many downloads and build artifacts; this does not prove that all sensitive content or third-party materials are excluded. Locks record selected inputs; artifact provenance, actual content, and packaged notices must be reverified for the release version. ROM downloads containing proprietary components and public Taku source code are separate deliverables, and acceptance of one cannot replace the other.

## How external services affect use

| Purpose | Currently traceable connection | Usage/replacement boundary |
| --- | --- | --- |
| Agent inference | [Magpie documentation](../adapters/magpie-runtime/README.md): phone Codex app-server → local Magpie → user-selected model service; a native ChatGPT connection is also available | Local tool execution does not mean offline inference. First use requires your own account or API service; personal configuration is not supplied with source/installers. Replacements must match the actual Responses, Chat Completions, Decisions, or other protocol and modalities used |
| System AI, input method, Memory model calls | [System AI](../system/ai/README.md) shares Magpie/Broker; the [data contract](system-ai-data-contract.md) records Settings, scenarios, and model groups | Configure existing model connections and routes; arbitrary APIs cannot be promised as direct replacements. The data contract still lists provider/model synchronization and concurrent-write gaps |
| Local vector search | ONNX Runtime and bge-small-zh-v1.5 in the [Memory dependency lock](../system/memory/vector-dependencies.json) | The vector model runs on-device after download; this does not mean all Memory classification/suggestions are offline |
| OTA channel | The [product definition](../image/taku.mk) specifies `lineage.updater.uri=https://worker.taku.ai/os-update/v1/canary/{device}/{type}/{incr}`; [update documentation](pixel9-test-updates.md) records feed fields and exact source matching | The address is product integration configuration, not a supplied hosted backend. A self-hosted channel requires compatible feeds, artifacts, and signature/source-version verification; replacement channels were not validated in this batch, and no unaccepted flashing steps are supplied |
| Agent APK and runtime releases | [Agent APK release records](agent-app-releases.md) link to `download.taku.ai`; the runtime lock points to GitHub Releases | Existing download links do not mean the entire repository is public, all assets are anonymously accessible, or servers have been delivered. Before release, verify every promised endpoint in an environment without team privileges |

Agent operations still follow the [engineering contract](../AGENTS.md): GUI and Agent share authoritative data and validation; standard Android interfaces expose discoverable reads/writes; and preinstalled documentation records commands, parameters, and errors. Current implementation scope is defined by [Agent open access](agent-open-access.md) and component evidence. This round adds no settings, features, or emulator acceptance.

## Work remaining after this documentation batch

1. Original-work Apache-2.0 declarations are complete; continue checking provenance, exceptions, and packaged notices for new materials.
2. Use the [third-party and materials inventory](third-party-materials.md) to confirm public rights for design/branding, derivative-code licensing, final artifact notices, and distributable scope for proprietary components.
3. Select public commits, history, tags, and Release attachments; complete manual content/credential and image review. This page is not a complete historical secret scan or full SBOM.
4. In a clean environment without team GitHub permissions, actually download, verify, and build the promised developer paths; record available functionality without model accounts or external services. Historical device acceptance does not establish acceptance of the entire current HEAD package.
