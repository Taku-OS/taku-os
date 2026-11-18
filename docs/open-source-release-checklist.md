# Open-source documentation delivery and subsequent release checklist

This round consolidates documentation preparation on `codex/open-source-docs-prep`, with all work items entering one integration review. It modifies repository Markdown only; source code, tests, CI, package configuration, effective licenses, GitHub visibility, and release channels are handled separately. Current legal terms are governed by the root [LICENSE](../LICENSE).

This page defines completion criteria; finished documentation does not equal accepted functionality or a public release. See the [2026-11-06 report](reviews/open-source-readiness-2026-11-06/README.md) for the historical inventory.

## Documentation delivery in this round

| Work item | Documentation entry points | Integration checks |
| --- | --- | --- |
| Current engineering status and navigation | [Current status](current-status.md), [documentation home](README.md), [structure](project-structure.md), [architecture](../ARCHITECTURE.md) | Describe implementation, builds, emulator, physical-device, complete-ROM, and channel status separately; cite new progress without rewriting historical evidence |
| First run and first change | [Getting started](getting-started.md), [development](development.md), [first change](first-change.md) | Dependencies, commands, artifacts, success, and errors are verifiable; applicable offline examples actually run; untested device paths are explicit |
| Three-language README | [English](../README.md), [Simplified Chinese](../README.zh-CN.md), [Japanese](../README.ja.md) | Consistent positioning, actual scope, current licensing, and valid entry points; no nonexistent images or service links |
| Contribution and support | [Contributing](../CONTRIBUTING.md), [support](../SUPPORT.md), [security](../SECURITY.md), [governance](../GOVERNANCE.md), [code of conduct](../CODE_OF_CONDUCT.md) | Accurate maintainers and available channels; unconfigured channels are not presented as available; distinguish documentation and code validation requirements |
| Initial task drafts | [10 starter tasks](contributing-first-tasks.md) | Each identifies files, prerequisites, observable success criteria, and suggested ownership; no claim that tasks are already created or claimed |
| Public scope and material origins | [Scope](open-source-scope.md), [materials inventory](third-party-materials.md) | Separate original Apache-2.0 work from third-party terms, and on-device components from external services; make missing provenance/rights confirmations identifiable |
| Visual and Case deliverables | [Asset specifications](launch-assets.md) | Requirements for hero image, share image, real-device GIF, and three Case clips; historical screenshots are not passed off as launch testing |

The integrator verifies that all added and changed files are Markdown, runs `python3 scripts/check_structure.py` and `git diff --check`, and reviews offline examples and correspondence across the three languages. Record commands, environment, results, and uncovered scope for applicable checks in the integration PR; do not claim Android/ROM/physical-device acceptance for documentation changes.

## Separate work required before going public

- **Original-work licensing is complete**: #52 synchronized Apache-2.0, metadata, and validators. Branding, design system, generated assets, and third-party exceptions still require individual confirmation; licensing original work is not permission to publish the entire package.
- **Public-material review**: define the scope of source history, tags, Releases, Issues, and media to publish; complete applicable secret scanning and manual material checks. A few existing format checks cannot substitute for a full review.
- **Builds and external services**: verify anonymous dependency access, hashes, and build paths from pinned public versions; explain model-service/Taku-hosted-service dependencies and configuration. List proprietary complete-ROM dependencies and distribution rights separately.
- **Reporting and discussion channels**: configure and actually test private security reporting, conduct contacts, discussion entries, and responsible roles. Add labels and recruitment links after creating real claimable Issues.
- **Release identity**: define separate versions, source, artifacts, checksums, and compatibility requirements for TakuOS, Agent APK, and runtime. An existing component Release does not automatically become the first system release; historical archives must not be rewritten to standardize naming.

Actual progress is tracked in [open-source readiness](open-source-readiness.md). Source/documentation CI, the security entry point, and documentation CI/CD are established; stock-device first installation and complete-ROM reproduction remain independently recorded validations.

## Validation before GTM promotion

| Check | Required result | What current documentation provides |
| --- | --- | --- |
| Main Case reproducibility | At least one developer outside the team follows public instructions to run the same example shown in the video, then makes one concrete modification | Getting-started guidance and Case specifications; not evidence of external reproduction |
| Onboarding paths | At least two paths newcomers can actually complete, listing environment, time, and failure causes | SDK/plugin/component/ROM path descriptions; unverified paths cannot be advertised as ready to use |
| Media | Hero image, share image, English real-device GIF, and three Case clips approved for publication | Asset specifications and existing historical references |
| Navigation | Consistent versions and entry points across README, official OS page, docs, Demo, and GitHub; a separate phone waiting list | Repository navigation; external pages and waiting list tested separately |
| Sharing and attribution | Real share cards work, and test clicks and GitHub snapshots are traceable | Configuration requirements; if external tracking is already implemented, link results without rebuilding it |

If first flashing/recovery lacks tested instructions for the same candidate, provide verified development entry points first; do not publish unverified generic flashing commands. Production signing and support for every model/app need not be prerequisites for source publication, but every specific promotional claim still requires corresponding evidence.
