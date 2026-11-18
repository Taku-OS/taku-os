# Open-source readiness remediation (updated 2026-11-13)

This round preserves both repositories' private visibility and all Git history. New commits fix only current source and documentation, without rewriting historical evidence or released artifacts. Original code, SDK, examples, and documentation use Apache-2.0 as confirmed by the project owner; third-party components retain their original licenses.

| Work item | Delivered this round | Still to verify or decide |
| --- | --- | --- |
| OS-01 Licensing | Apache-2.0 text, current SPDX/package metadata, NOTICE; historical MIT and internal terms preserved | Per-item distribution review of third-party binaries, models, and visual materials |
| OS-02 Content review | `python3 scripts/release-inventory.py` generates per-file SHA-256 and manual-review categories; CI scans newly introduced credentials | Item-by-item review of historical evidence, screenshots, and internal links; publication of the entire repository not approved |
| OS-03 Clean build inputs | Reviewable Dockerfile, environment doctor, anonymous input probes, and preparation steps | Public distribution or source rebuild of private runtime inputs; a cold build of the complete ROM |
| OS-04 Security entry point | Public community repository handles private reports; documentation explains the actual trust model | Acceptance of ordinary-app reachability to the root socket and release policy |
| OS-05 Project facts | Current version inventory, English/Chinese README, updated installation and status entry points | Keep provenance and acceptance scope synchronized with new versions |
| OS-06 First plugin | All public community Hello World files, ZIP, checksums, and complete bilingual tutorial | Independent Agent/UI acceptance on the release ROM; host MCP tests must not be treated as physical-device acceptance |
| OS-07 Development environment | macOS Bash 3.2 compatibility fixes, JDK/SDK/Node/Python doctor | A new developer completing the entire flow in their own environment |
| OS-08 Automated checks | Linux/macOS plugin host tests, Android plugin component compilation, builder tool checks, new secret scanning; documentation dependency audit | Complete ROM build and device acceptance remain separate tasks; this round's remote CI result must be checked |
| OS-09 First installation | Accurate first-install/OTA/emulator validation scope retained | Stock-device first installation, clean AVD distribution, failure recovery, and independent newcomer acceptance |
| OS-10 Release and maintenance | Source and artifact inventories, contribution/security guidance | Default technical integration reviewer and confirmed Memory owner registered; other modules temporarily use the default reviewer, without inferring ownership from a single commit. OIDC migration for the new public repository is deferred |

## Review commands

```sh
python3 scripts/doctor.py
npm run verify
JAVA_HOME=/path/to/jdk17 bash system/plugins/test-host.sh
python3 scripts/release-inventory.py
```

The inventory records current bytes of Git-tracked files, source commit, and worktree state, excluding credential values. Files labeled `historical-evidence`, `media-content-and-rights`, `internal-link-review`, or `license-and-provenance` require individual review. Output goes to ignored `out/open-source/inventory.json`. This does not export files, overwrite history, generate a new public repository, or approve publication.

Changing source license declarations also changes hashes. Older ROMs and their input locks remain unchanged; a new ROM candidate must rebuild affected components and pass existing lock/receipt checks, not merely refresh hashes as though rebuilt.

Private-repository GitHub Actions checks can remain available, but public contributors require legal access to all necessary inputs to reproduce them. `doctor --rom --network` deliberately uses no internal identity to report this gap from an external developer's perspective.

## Progress verified on November 13

The [component input and license-packaging receipt](reviews/open-source-inputs-2026-11-13/README.md) records the Codex public-source build, independent API 37 emulator initialization, eight font notices across two APKs, archive notices, and overwrite prevention. The source inventory adds media types, and two current design documents remove internal-chat references. The confirmed Memory owner is added to CODEOWNERS; other modules retain the default reviewer.

These results still do not count as a complete ROM cold build, an anonymous new developer completing installation end to end, full third-party distribution licensing, or passed historical privacy review. Physical-device work is paused at the user's request; repository visibility and history remain unchanged.
