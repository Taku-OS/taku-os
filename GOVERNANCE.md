# Project governance

Taku OS is maintained by the Taku-OS organization. Original code, SDKs, examples and documentation use Apache-2.0. Repository publication, binary releases, third-party licensing and contribution review are managed separately.

The verified GitHub administrator and default reviewer is @programmerguys. CODEOWNERS records this contact. Module maintainers are listed only after they confirm their GitHub identity and responsibilities; authorship alone does not establish module ownership.

Changes to interfaces, permissions, data retention, licensing, signing or device support must record the problem, decision, user impact, validation and untested scope in an issue or pull request. Establish the use case and success criteria before choosing changes to the core, a plugin or documentation. Propose a design for substantial features; fixes and documentation corrections may be submitted directly as reviewable pull requests.

Contributions use the applicable repository license. Contributors must have the right to submit the included code and materials. A DCO or CLA is not currently required, and contributors must not sign on another person's behalf. Include the original license, provenance and a description of changes for third-party code.

Releases identify the full source commit and immutable versioned artifacts. Record source checks, component builds, device acceptance, publication and actual user upgrades separately. Passing CI does not substitute for device acceptance that has not been performed.

Use the [private reporting route](SECURITY.md) for security issues. See [open-source readiness](docs/open-source-readiness.md) for publication progress and remaining boundaries.
