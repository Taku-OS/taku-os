# Background workspaces — candidate acceptance

Recorded on 2026-10-27. This is a development candidate on `codex/multi-screen-case-20261026`, based on `bfbe6b9`. It has not been merged into main or deployed to a physical phone or full ROM.

This record establishes only this round's app discovery and workspace-management scope; it does not substitute for real cross-app business acceptance.

| Check | Result |
| --- | --- |
| App catalog host checks | 45 passed |
| Execution screens and admission fences | 136 passed |
| Native routing integration, using test doubles | 96 passed |
| Process identity and handoff proof, without signals | 11 passed |
| Dedicated Android emulator, actual IPC and native threads | 23 passed |
| Broker restart with an existing screen | Old lease remained unadmitted; verified same-generation host closed and binding cleared |
| UI | Actual APK; English/Chinese, light/dark, 150% font, portrait and landscape inspected |

## What the device checks establish

The dedicated `Taku_Workspaces_20261026` AVD runs Android 36 arm64. Clock, Calendar and WebView Shell are installed stock apps. The host prepares three real execution screens; the checks use the production broker, native thread API, PackageManager and screen host. No model response is simulated, and no inference request is sent.

Passed checks cover the real app catalog and current-user scope, three-screen leases, read-only input rejection, pause and takeover, rejection of new Agent turns during takeover, real managed input, no automatic replay after returning control, preservation of pending-continuation state on refresh, stale control-version and screen-generation rejection, app-access revocation, concurrency limit and master switch, and catalog refresh/launch rejection after disabling an app.

The app catalog does not infer login, background compatibility or booking capability. The UI displays real lease and control state. Screens share app accounts and data; managed phone-tool rules are not a global Android/root sandbox.

## Remaining acceptance

- Pixel 9: install this candidate and verify the same management flow on the physical phone.
- Live model turn: verify interruption and subsequent explicit continuation with a real provider. Idle native-thread management is already tested; it is not evidence for active inference cancellation.
- Cross-app business case: verify parent-task dispatch, concurrent app work, result aggregation and user confirmation separately.
- Full ROM: integrate and verify the Settings entry and runtime together after the UI is approved.

## Reproduce

Host checks: `system/assistant/test-app-catalog-host.sh`, `test-execution-screens-host.sh`, `test-native-routing-host.sh`, and `test-workspace-processes-host.sh`.

Build: `system/assistant/build.sh` and `system/assistant/build-workspace-tests.sh`. After deploying matching artifacts to the dedicated, clean AVD, run `python3 scripts/test-workspaces-device.py --serial emulator-5590`. The script guards the AVD identity and keeps the three final screens for UI inspection. It does not load user accounts or request model inference.

Local evidence is retained under `out/workspaces-20261026/`: `device-acceptance.json`, `restart-acceptance.json`, `device-test-final.log`, and actual APK screenshots. These local outputs are not website deployment evidence.

APK version: `72 / 20261026-workspaces-preview`.
APK SHA-256: `831469dedc39f809017aec2414e3300ce646306805f99a39ab165b194597ed5f`.
