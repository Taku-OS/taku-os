# Verification evidence

This directory contains manually selected historical evidence. It preserves the actual execution dates, devices, permissions, scope and anomalies. Research examples and build results do not count as successful physical-device testing.

- `sdk/`: historical SDK device-report and on-device CLI JSON results (moved without changing their contents).
- `api-inventory/`: capability audits, read-only probes, APK verification, historical SHA indexes and reports from each verification session.
- `scenarios/`: records of engineering candidates that generated and installed APKs on a phone or operated existing apps in the background.
- `builds/`: component, image and device results dated by file. The [October 10 clean-checkout component build](builds/2026-10-10-clean-components.json) establishes compilation only. The [October 11 complete Pixel 9 candidate](builds/2026-10-11-pixel9-image-candidate.json), [October 14 installation](builds/2026-10-14-pixel9-install.json) and [October 15 physical-device native client](builds/2026-10-15-native-codex-client.json) each establish their own scope; together they do not establish that all versions passed.
- Other directories preserve native UI and independent-display evidence for individual modules.

Write new results to `out/checks/` or dated `out/scenarios/` directories by default. Do not overwrite these historical files. Before committing evidence, remove personal authentication, private keys, conversations, user materials and raw model events, and confirm that the evidence supports the stated conclusion. Changing status wording alone cannot turn a research item into a passing result.

[Current progress (2026-10-19)](../docs/current-status.md) · [Capability guide](../docs/android-api-guide.md) · [Scenario acceptance](../docs/scenario-acceptance.md). Records exist for an older complete ROM and partial Pixel 9 acceptance. The last recorded attempt at the then-latest complete ROM was a failed rebuild, and the then-latest assistant/gateway results primarily covered emulators. Compatibility anomalies and original failure records remain unchanged.
