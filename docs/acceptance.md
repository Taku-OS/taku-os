# Foundation acceptance — 2026-10-10

Acceptance for this phase: the source SDK/CLI passed a closed set of real Android probes, and an external extension received actual inputs and outputs. This is not ROM or physical-device acceptance.

This is the phase record from 2026-10-10. An older Pixel 9 ROM was subsequently flashed and selected components passed physical-device acceptance. This page's exclusion of ROM/physical-device testing applies only to this phase; see [current engineering status](current-status.md) for the latest status by layer.

| Check | Status and scope |
| --- | --- |
| Offline source tests | All 4 Node tests passed; invalid requests, extra parameters, unknown methods, missing devices, and command-injection serials are rejected; unknown implementation errors do not leak internal exceptions |
| JSON protocol and registry | Parsing passed; the detailed capability catalog is accepted separately by the inventory script |
| `system.info` | Real ADB succeeded: Android16/API36/ARM64/userdebug, UID0, SELinuxEnforcing; no unique device identifiers read |
| `displays.status` | Real ADB succeeded: primary 0, background 11; ID11 still actually exists; no display started/restarted |
| `tasks.status` | Real ADB succeeded: 5 existing completed tasks; only ID/state read, without requesting prompts/result bodies |
| Extension end to end | `device-report` passed registry→scopedSDK→facade→ADB→Android; no mock or new model task |
| Failure path | An explicitly nonexistent development serial returned `TRANSPORT_ERROR/retryable:true`, exit1; no other connected device selected automatically |
| On-device shell CLI | The coordinating root agent authorized pushing only two source files to /data/local/tmp; all three methods ran successfully in actual Android shell without Node; task status under su UID2000 actually returned PERMISSION_DENIED, exit4 |
| GUI/installation/ROM/physical device | Not performed by this worker; outside this phase's acceptance |

Machine-readable real output is in `evidence/sdk-device-report.json`; generatedAt records the query time in UTC, while user dates use the Shanghai time zone. Core calls do not read personal Codex authentication; stdout contains only necessary system information and task status.

On-device CLI evidence is in `evidence/device-cli-smoke.json`; the verification script is `node scripts/verify-device-cli.mjs --serial emulator-5582` (requires the explicit deployment above to have completed; it does not install or restart). This round does not replace the scenario of an Agent on the phone generating/compiling/installing a standalone APK itself; it is only an on-device SDK call example.

Reproduction: the README commands can be run directly; failures must retain the protocol error and nonzero exit. The device is assumed connected and ADB-authorized. If adbd on an ordinary device runs as UID2000, task status returns PERMISSION_DENIED; success of system.info does not prove all privileged capabilities are available.
