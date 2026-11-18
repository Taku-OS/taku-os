# Background workspace UI candidate

**Date:** 2026-10-27 (Asia/Shanghai)
**Candidate:** v73 · `20261027-workspaces-design`
**Status:** Merged into main; emulator UI checks and Pixel 9 installation, UI, and control handoff checks passed. Live-model interruption, cross-app business behavior, and full-ROM acceptance were not tested in this round.

## What changed


The management flow is now a compact app list → workspace details → optional live view. The UI uses the original design’s cool-gray background, white groups, and blue actions, with fewer repeated home-page buttons. English and Chinese follow the system language.


The summary counts distinct actual `threadId` values. **Task** uses the binding label, not a model-generated live business step. Pause, takeover, return, and close use the existing broker interfaces. Returning control does not replay actions automatically.

## Verification progress

| Item | Confirmed evidence |
| --- | --- |
| Resources and APK build | Full APK build and diff checks passed; the installed APK hash matches the local build. |
| Emulator | The UI matrix below used the actual broker and three stock-app workspaces, without model inference. |
| Pixel 9 | The v73 APK is installed and the development broker is updated for the current boot. The three existing workspaces were preserved; home, details, read-only live view, pause, takeover, and return checks passed. No business input or model inference was requested. |
| main, full ROM, website | `f947854` is merged into main; full repository verification, APK build, and remote CI passed. Full-ROM integration and website publication remain pending. |


The 23 v72 emulator device checks, broker-restart cleanup, and layout checks remain in the [previous record](background-workspaces-acceptance-2026-10-27.md). They do not become v73 passes. Emulator and Pixel 9 UI acceptance are recorded separately below. Cancellation during real model execution, cross-app business behavior, and the full ROM still require their own acceptance evidence.

## Emulator UI checks

| Scenario | Result |
| --- | --- |
| Home → details → live screen | Three workspaces and three actual threads are shown; details retain the original screen binding. |
| Pause, take control, tap | After control was granted, a tap in the main-screen preview changed the background Clock app from Clock to Timer. |
| Return control | The broker confirmed `controlOwner=agent`, `resumeRequired=true`, and no active turn; details retained the pending-continuation state. |
| Preferences | Limit and app-access dialogs opened and cancelled without changing the policy. |
| Layout and language | Inspected English/Chinese, light/dark, paused controls at 130%, scrolling details/settings at 150%, and landscape; no overlapping or overflowing text was observed. |
| Artifact identity | Installed APK and local build hashes match; the running broker DEX matches this build. |

Local evidence is retained in `out/workspaces-design-20261027/`: `01-home-en.png`, `02-detail-en.png`, `16-control-timer.png`, English/Chinese and large-font screenshots, `final-workspaces.json`, and `artifact-verification.json`. These are screenshots of the actual emulator APK, not physical-device or real-booking acceptance.

Local evidence in that directory contains actual emulator APK screenshots and broker receipts. It does not establish physical-phone or real-booking acceptance. The emulator was restored to English, light mode, normal font size, and portrait after inspection.

Emulator candidate APK SHA-256: `2934f00ba790214b8dc630fdc07b09c4d70f197b36fa210d9c686ac7e1f11c39`. This identifies the earlier emulator build; the phone used the v73 build rebuilt from main, recorded below.

## Pixel 9 installation and UI acceptance


After the user approved the merge and installation, the first guard check found an active turn and stopped before replacing the APK or broker, leaving v71 installed at that time. Old components and bindings were backed up. The three named display hosts were moved to separate cgroups while retaining their PIDs, process groups, and sessions. Installation completed after that turn ended and fresh checks confirmed an idle runtime. This earlier wait is retained as history, not the current installation state.


The v73 build came from `4c04499fe21d2a5e1b377b69f074bf0a314916f5`. Installing over the existing APK retained app data, and only `taku_broker` was restarted. The existing rednote, Amap, and Meituan workspaces retained their host processes, `screenKey`, `generation`, `displayId`, and original `threadId`. No screens or tasks were created. Magpie remained running and SELinux was Enforcing after installation.

| Device check | Result |
| --- | --- |
| Home and details | The actual APK showed rednote, Amap, and Meituan as three workspaces and three tasks; selecting rednote opened its existing workspace details. |
| Read-only live view | The original rednote screen was visible without sending input to the app in the preview. |
| Pause, takeover, return | Completed through the actual management UI on an idle task. Return left `controlOwner=agent`, `resumeRequired=true`, and `status=awaiting_resume`, with no active turn or automatic replay. Amap and Meituan remained `idle`. |
| Layout and system settings | Existing English and dark-mode settings were retained; no overlap or text overflow was observed on these pages. The emulator language, font-size, and landscape matrix was not rerun on the phone. |
| Component and screen identity | Installed APK and loaded broker hashes matched the build; final checks confirmed all three host process identities and original leases were preserved. |


The actual rednote screen showed a logout notice after another device login reached the active-device limit. Login needs to be restored; this round did not change the account or perform app business actions. **The checks establish workspace viewing and control handoff, not a valid rednote login, a completed cross-app route/booking case, or interruption of a live model turn.**


The broker was loaded through a development bind mount **for the current boot only; no full ROM was flashed or updated**. Persistence across reboot was not tested.

Local device evidence: `out/workspaces-pixel-20261027/`.

- Receipts: `deployment.json`, `ui-acceptance.json`, `verification-final.json`.
- Actual APK screenshots: `02-home-ready.png`, `03-rednote-details.png`, `04-rednote-live-readonly.png`, `05-rednote-paused.png`, `06-rednote-control.png`, `07-rednote-returned.png`, `08-rednote-details-returned.png`, `09-home-final.png`.
- Pixel APK SHA-256: `a2bfbd9f1ea547c71802c679400eb83d5ea574399b314d94069b17e76e7c9e11`.
- Pixel broker SHA-256: `a6cad00b8366eda915aad65d4d9870d3422bc603f816ed73a0348d946feef28d`.

Receipts and screenshots remain in local output; device-private data and generated artifacts were not added to the repository.

See the [usage and developer guide](background-workspaces.en.md) ([Chinese version](background-workspaces.md)) for interface and permission boundaries.

The interface and authorization boundaries are unchanged. This record does not claim completed automatic parent-task decomposition, cross-app aggregation, or automatic queueing.
