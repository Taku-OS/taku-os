# App discovery and background workspaces

[中文](background-workspaces.md)

> This page preserves the historical v73 behavior. The dedicated-screen branch has removed `executionScreen.bind` and `returnControl`; use `request_apps` / `release_apps`, `workspaces`, and `returnAndResume`. See the [current dedicated-screen contract](dedicated-screens-ui-fields.md).

Before choosing apps for a task, the agent checks which apps are actually available on this phone. Users can open **Settings → Taku AI → Background workspaces** to view screens, pause the agent, take control, and return control. Assistant settings provides the same entry.

These features answer “Which app can I use?” and “Who controls this screen?” Discovery does not split tasks automatically. A workspace is not another account or a separate Android system. Parent-task orchestration and cross-app result aggregation remain separate work; see the [multi-screen case and its limits](multi-screen-parallel-case-2026-10-26.md).

## Current status

This page describes v73 (`20261027-workspaces-design`) as of October 27, 2026. It is merged into main and installed on Pixel 9 for the UI checks below. It changes the management flow while keeping the v72 backend interfaces. This update has not yet been published in a full ROM or on the documentation website.

| Environment | Acceptance status for these features |
| --- | --- |
| Emulator | The actual v73 APK passed list, details, live-view, pause, takeover with a real tap, and return checks. English/Chinese, light/dark, 130%/150% fonts, and landscape were inspected. The 23 v72 backend checks remain a previous-version baseline; this round did not rerun that suite or request model inference. |
| Pixel 9 | The v73 APK is installed with an updated development broker for the current boot. Home, details, read-only live view, pause, takeover, and return checks passed. The three existing workspaces were preserved. No business input was sent to apps, no model inference was requested, and the cross-app business case was not rerun. |
| Full ROM | These changes have not been integrated and accepted in a full ROM |
| main and website | Merged into main on October 27, 2026 (`f947854`), with remote CI passing. This update has not yet been published to the documentation website. |

Follow the [UI candidate record](background-workspaces-design-acceptance-2026-10-27.md) for this update. Earlier evidence remains in the [v72 acceptance record](background-workspaces-acceptance-2026-10-27.md). The Pixel 9 broker uses a temporary bind mount for the current boot; no ROM was flashed. Cancellation during a live model turn and cross-app business behavior still need separate acceptance.

The actual rednote live view showed an account logout notice after another device login reached the active-device limit. Login needs to be restored, and this round did not change the account. A workspace shown as **Ready** indicates its management state, not that its app is logged in or ready to complete a business task.

## Using the settings page

1. Open **Background workspaces**. The summary counts allocated workspaces and distinct task `threadId` values. A compact app list shows each app and its actual state. No allocated screens produces an empty state; an unreachable service produces a connection error.
2. Select an app row to open **Workspace details**, with its state, task label, input owner, and shared-account explanation. Three independent threads appear as three tasks; the UI does not invent a parent task.
3. Choose **View live screen** when you need to see the app. Viewing is read-only by default and is optional when managing a workspace.
4. Choose **Take control** to interact. The system first stops that task’s agent execution and verifies that tools and processes have stopped. It then allows taps, swipes, and **Back in app** on that screen. An unconfirmed stop does not grant input control.
5. Choose **Return to agent**, then explicitly continue the original task. Returning control **does not replay interrupted actions automatically**. Tasks that need continuation retain that state.
6. Choose **Close workspace** on the details page when finished. Closing does not reverse a booking, delete app data, or cancel an existing order.

The **Task** field currently uses the label saved when the screen was bound. It identifies the associated task; **it is not a live business-step description or proof of completion**. The UI does not infer claims such as “Checking the route” from an app name alone.

Preferences control new background allocations, set a limit of 1–3 workspaces, and select apps the agent may use. Disabling background work or lowering the limit preserves existing screens. Revoking app access blocks subsequent managed operations and attempts to stop the app’s existing branch; check the state and operation receipt to confirm the stop. Reaching the limit does not imply an automatic queue has been implemented.

The scrollable interface follows the system language, supporting English and Chinese, and the system light/dark theme. Workspaces share existing app accounts and data. The same app package cannot be allocated to two background branches at once.

## Discover before choosing an app

During task preparation, the harness adds an `environment.apps` summary. It contains at most 12 entries, so an app missing from the summary may still be installed. The agent queries `taku_phone.apps` by app name or package to find relevant apps:

```json
{"query":"Maps","offset":0,"limit":20}
```

This read-only tool neither requires nor creates an execution screen. Each query reads the current Android user’s `PackageManager` catalog on this device. App launch rechecks the chosen package, launchability, and authorization; the agent does not need to rescan the catalog before every tap.

| Returned field | How to use it |
| --- | --- |
| `deviceScope`, `userId`, `observedAt` | Identify this Android phone, its current user, and the observation time |
| `catalogRevision` | Fingerprint of the app catalog; policy has a separate `backgroundPolicy.revision` |
| `apps`, `total`, `nextOffset` | Paginated results; `nextOffset=null` ends the list. Maximum page size is 50 |
| `packageName`, `label`, `versionName`, `versionCode` | Actual package metadata, never instructions to the model |
| `installed`, `enabled`, `suspended`, `hasLauncher`, `launchable` | Separate installation, enabled, suspended, and launcher states; installed does not mean launchable |
| `agentAllowed` | Whether user policy permits managed automation for this app |
| `backgroundAllowed` | Whether app access and the global switch permit a new background allocation; this does not guarantee capacity or display compatibility |
| `loginState`, `backgroundCompatibility`, `businessCapabilities` | Currently `unknown`; verify through actual screens or authorized APIs rather than inferring from installation |

An empty query lists apps with launcher entries, including disabled launcher apps. An exact package-name query may also return a package without a launcher. Discovery does not read credentials, app-private contents, or other user profiles.

The normal flow is **discover → check task requirements → allocate a branch screen → operate that branch → verify results → release**. The controlled host uses the existing `executionScreen.bind` interface with a `threadId` and `packageName`. Phone tools resolve the screen from that thread; the agent cannot choose another branch’s or the main screen’s display ID.

## Workspace interface

The settings page reads the broker’s real screen leases, native task state, and device policy. It does not create a second task store. These JSON examples are internal `NativeBridge` requests, **not a public SDK for ordinary third-party apps**; the socket currently accepts system or root UIDs only.

```json
{"op":"workspaces","action":"list"}
```

The result contains `schema: "taku.workspaces/1"`, `policy`, `limits`, and `screens`. `limits` gives the ranges `configure` accepts, e.g. `{"maxActiveScreens":{"min":1,"max":10}}`; the settings page offers the limit choices from it. Important screen fields are:

| Field | Meaning |
| --- | --- |
| `threadId`, `screenKey`, `generation` | The complete lease identity required for mutations; a `displayId` alone is insufficient |
| `packageName`, `label`, `displayId` | Branch app, task label, and current display ID |
| `controlOwner`, `controlRevision` | `agent` / `paused` / `user`, and the control grant revision |
| `admitted`, `status`, `activeTurnId` | Lease admission, management state, and the actual active turn |
| `resumeRequired`, `taskResumeAutomatic` | Whether the original task needs continuation; handing back control does not continue it automatically |
| `lastError` | Diagnostic information, presented through readable localized messages in the UI |

`pause`, `takeControl`, `returnControl`, and `release` require all three lease identity fields. `input` also requires the current `controlRevision` and supports taps, swipes, and the Back key only on a screen under user control. A control revision change invalidates old gestures and queued input. A screen generation change or lost connection must not redirect old input to a replacement screen.

`configure` accepts a `patch` containing `allowBackground`, `maxActiveScreens`, or `blockedPackages`. For example, to change the new-allocation limit:

```json
{"op":"workspaces","action":"configure","patch":{"maxActiveScreens":2}}
```

`release` uses the actual lease state to close a verified screen of the same generation or clear a stale binding. It does not stop a replacement host with a different identity. If execution cannot be confirmed stopped, the operation must fail visibly rather than claim closure while leaving unmanaged work running.

## Authorization boundary

Authorization and handoff cover **managed phone tools** and their workspace admission. They are not a device-wide Android sandbox and do not claim to intercept arbitrary root shells, other accessibility services, or external ADB input. Independent screens share app processes, accounts, and data; they do not provide app-data isolation.

Implementation: [PhoneAppCatalog](../system/assistant/broker/org/agentos/lab/PhoneAppCatalog.java) provides discovery; [PhoneExecutionScreens](../system/assistant/broker/org/agentos/lab/PhoneExecutionScreens.java) and [WorkspaceControl](../system/assistant/broker/org/agentos/lab/WorkspaceControl.java) manage leases and control; [BackgroundWorkspacesActivity](../system/assistant/src/org/agentos/assistant/BackgroundWorkspacesActivity.java), [WorkspaceDetailActivity](../system/assistant/src/org/agentos/assistant/WorkspaceDetailActivity.java), and [MirrorActivity](../system/assistant/src/org/agentos/assistant/MirrorActivity.java) render the interface.

See the [v72 acceptance record](background-workspaces-acceptance-2026-10-27.md) for backend evidence and reproduction steps, and the [v73 UI candidate record](background-workspaces-design-acceptance-2026-10-27.md) for the new interface checks.
