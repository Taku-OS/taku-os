# Security reporting and support scope

Taku OS currently provides a Pixel 9 development preview; the source repository remains private. Original materials use Apache-2.0. This does not mean every system component, device or plugin has passed security acceptance.

## Private reports

Report Taku OS core, ROM or plugin issues through [GitHub private vulnerability reporting](https://github.com/Taku-OS/taku-os-plugins/security/advisories/new), enabled on the public community repository. A GitHub account is required. Do not post exploit details, credentials or personal data in public issues or pull requests. @programmerguys handles initial triage and routes reports to module maintainers. There is currently no guaranteed response time or bug bounty.

Include the component, version/build, source SHA, caller's original UID and permissions, expected boundary, reproduction steps and observed impact. Use test data and provide the minimum redacted evidence needed to reproduce the issue. Distinguish unverified dependency scan findings from reproduced permission bypasses.

## Current trust model

- Users and their agents can access the system's exposed capabilities. The GUI and Agent use the same authoritative state.
- Skill/MCP and God Mode plugins are trusted code. Capability and permission fields are declarations, not isolation boundaries.
- The God Mode development service runs with elevated privileges. Android socket access depends on UID, SELinux and ROM configuration; the service does not currently validate the peer UID. Ordinary app reachability, malicious plugin isolation and default release policy require separate acceptance checks.
- Installing a trusted plugin can still cause irreversible external effects. Disabling or uninstalling it does not undo every action it has already performed.
- Do not disable SELinux to pass tests. An Enforcing runtime state does not demonstrate that every permission boundary has been verified.

## Versions and fixes

Prioritize reproducible issues on current main and the current development preview. Historical candidates are reproduction leads; maintenance of every historical version is not promised. Fix records should identify affected versions, permission boundaries, validation environments, untested scope and the corresponding new release. Do not overwrite released packages or historical evidence.

A separate reporting route may be enabled for the core repository when its source is made public. This source preparation does not change repository visibility.
