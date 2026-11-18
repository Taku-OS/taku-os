# Engineering standards and release gates

These requirements are based on official maintained documentation and do not mean the project has earned an OpenSSF Badge, REUSE certification, or any SLSA level. The repository currently remains Private, with original materials under Apache-2.0; external distribution still requires content and artifact review.

| Area | Currently implemented | Still required before release |
| --- | --- | --- |
| Engineering hygiene | README, docs index, contribution/support/security/conduct/governance files, actual CODEOWNERS, Issue/PR templates | External-contribution handling SLA, maintainer rotation, and public discussion channels |
| CI | Minimal contents:read, timeouts, Actions pinned to full commits, offline validation | Clean-environment Android builds, device/ROM integration acceptance, branch protection |
| Licensing | Apache-2.0, historical MIT retained, original third-party licenses and NOTICE | Third-party provenance/file-level license inventory, asset distribution review, full REUSE checks |
| Versions | package 0.1.0 is the SDK/CLI development version; [system versioning rules](versioning.md) define bump criteria; source supports three-part product versions, historical-source reads, and product downgrade prevention | New target images and actual upgrade acceptance, automatic release registration/bump classification, formal artifacts and migration guidance |
| Supply chain | Public development signing revision/hash pinned; artifacts ignored | SBOM, independent production signing, checksums, immutable artifacts, build provenance; no claim of SLSA compliance |
| Real testing | Research/emulator/physical-device results separated; older Pixel 9 ROM flashing and subsequent component/device/startup-recovery records exist, with historical evidence frozen | First-device recovery-path acceptance, latest ROM and corresponding device regression, OTA, complete hardware and critical-app compatibility |

## Rules

- Interface definitions and the capability catalog use spec as the source of truth; synchronize code/examples/site. Acceptance conclusions state record date, version, device, and scope. Later targeted testing must not be described as unimplemented because older catalogs are not updated. See [current status](current-status.md) for overall progress; historical catalog counts are not project-wide acceptance totals.
- Record research, component builds, emulator execution, physical-device component execution, complete-ROM build/flashing, and production release separately. Partial success cannot imply universal success; absence of formal release cannot negate completed builds, flashes, or scoped physical-device tests.
- Original code currently uses `Apache-2.0`; npm `private:true` prevents accidental npm publication, and `license:Apache-2.0` states licensing. Do not rewrite third-party SPDX or font/icon licenses. Rights publicly granted earlier are not revoked by changing visibility to Private.
- The ROM product name is TakuOS; record version, build number, channel, and upstream Android/API separately. Select MAJOR/MINOR/PATCH under the [version and release rules](versioning.md); SDK/protocol SemVer is maintained independently, and breaking changes in 0.x still require documentation. Product display version 1.x does not mean all component interfaces are stable; a package development tag does not mean the ROM is formally released.
- DCO sign-off is a rights declaration for future open contributions. It is not enforced in the current internal phase, and historical authors must not be automatically signed off.
- New APIs and contributions must at least document permissions, device/API, input/output/errors, real examples, and failure boundaries. Extension declaration checks are not a process-isolation sandbox.
- Issues contain only nonsensitive summaries; vulnerabilities/credentials must not enter PRs, logs, or screenshots. See SECURITY.md for private reporting.

## Official references (2026-10-10)

- [GitHub repository community health files](https://docs.github.com/en/communities/setting-up-your-project-for-healthy-contributions/creating-a-default-community-health-file)
- [GitHub CODEOWNERS](https://docs.github.com/en/repositories/managing-your-repositorys-settings-and-features/customizing-your-repository/about-code-owners)
- [GitHub Actions security and commit-SHA pinning](https://docs.github.com/en/actions/security-for-github-actions/security-guides/security-hardening-for-github-actions)
- [GitHub private vulnerability reporting](https://docs.github.com/en/code-security/security-advisories/working-with-repository-security-advisories-for-a-repository/configuring-private-vulnerability-reporting-for-a-repository) (handled by the existing public plugin community repository while the main source repository remains private)
- [OpenSSF Best Practices Badge criteria](https://www.bestpractices.dev/en/criteria)
- [REUSE file-licensing specification](https://reuse.software/spec-3.3/)
- [SemVer 2.0.0](https://semver.org/spec/v2.0.0.html)
- [Developer Certificate of Origin 1.1](https://developercertificate.org/)
- [OpenSSF Scorecard checks](https://github.com/ossf/scorecard/blob/main/docs/checks.md) (agent review does not equal a second human reviewer)
- [SLSA 1.2 build levels](https://slsa.dev/spec/v1.2/build-track-basics)
- [Android licensing boundaries](https://source.android.com/docs/setup/contribute/licenses)

References are the basis for requirements, not third-party certification of this project. Every release gate needs actual results and reproducible records.
