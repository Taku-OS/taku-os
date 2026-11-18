# Taku OS Android permissions / API inventory

Version 0.1 · 2026-10-10 historical capability snapshot · for product evaluation and reproduction.

This inventory contains 57 entries: 20 with specific emulator evidence, 37 research entry points, and 0 verified on a physical device. These counts are the **per-entry acceptance statistics for this inventory on 2026-10-10**, not the current project's total physical-device progress. Entries retain the status, versions and evidence scope recorded then.
“Verified on an emulator” covers only the entry's verificationScope; it does not establish that related research variants, all apps or all versions passed.

**Later progress (as of 2026-10-19):** A complete Pixel 9 development candidate was built on October 11. A package based on it was installed and booted on a physical phone on October 14. October 15 component acceptance covered real models, read-only Android tools and some recovery after cold boot; see [native client acceptance](native-codex-implementation-2026-10-15.md). That day also included [on-device native Java app generation, compilation, upgrades and business API acceptance](agent-system-app-acceptance-2026-10-15.md) on a dedicated emulator. This later evidence was not backfilled as per-item physical-device coverage for the 57 entries here and does not establish a successful build of the latest complete ROM. See [current progress](current-status.md) for consolidated status.

## How to use this inventory

Choose specific inputs and success criteria, then inspect the permission tier, entry point and limitations. Rerun verified entries against actual results using their examples. Research entries provide source locations and method-level snippets, not delivered general adapters. Kotlin snippets for ordinary APIs belong in a complete Android project with Manifest declarations, permission callbacks and lifecycle handling; they cannot be pasted into a phone shell. Source-location examples reproduce code locations, not feature calls.

Phone-shell examples run on authorized test devices; host examples must explicitly specify emulator-5582. An experimental entry point requiring root/system privileges does not become available to ordinary apps because it is wrapped in a CLI.

The SDK currently exposes a closed set of read-only system/display/task-state and discovery methods. Run `node sdk/cli/taku.mjs call methods.list` to inspect the actual set. Research capabilities in this inventory do not automatically become callable SDK methods. Phone-app business APIs are also distinct from low-level OS APIs.

## Flashing and permission tiers

|Tier|How to obtain it|Main boundaries|
|---|---|---|
|Ordinary app|Runtime permissions, special access, default roles, URI grants|Camera, notifications and accessibility can work without flashing; there is no single grant for all permissions|
|ADB / Shizuku|Developer debugging authorization; Shizuku shell service and app authorization|No custom ROM needed; shell is not root and OEMs may restrict it|
|Root / system signing|Unlock/root or OEM platform signing and preinstallation; same-partition privapp allowlist|Does not necessarily require Framework changes and does not bypass SELinux/FBE/hardware trust boundaries|
|ROM Framework / HAL|AOSP branch, device/vendor, service/policy/HAL integration|System lifecycle and global pipelines require builds and verification for each device/version|

The emulator used here has uid 0 and system test signing. System-signed apps and root services are distinct calling surfaces. Root deployment on stock systems, Shizuku integration and complete-image installation each require independent evidence; they cannot be inferred from these emulator records. The later Pixel 9 installation has separate [device evidence](../evidence/builds/2026-10-14-pixel9-install.json).

## Reproduction entry points

Environment verification without side effects: `python3 examples/capabilities/probe_readonly.py`. It has an AVD/serial allowlist and reads only versions, services, resources and explicitly named packages. It does not start/stop apps, retrieve account data or inject input.

Choose commands for emulator-verified capabilities from [examples/capabilities/README.md](../examples/capabilities/README.md). The probe does not automatically run examples that write, notify, launch or install. Serialize GUI tests that would contend with another worker for the main screen.

Standalone APK verification: `evidence/api-inventory/apk-verification.json` contains apksigner results, Manifests, SHA-256 and installed-file hashes for both packages. Matching results establish actual APKs rather than web previews.

## Failures and gaps at the time of this inventory

- WeChat 8.0.79: historical pre-login welcome/phone-number pages and targeted taps passed; no login, messaging, calls or payments were tested.
- Xiaohongshu 9.49.1: guest image feed and Mirror scrolling/hide/resume passed historically; video, posting and accounts were untested.
- Douyin 40.7.0: installation, native controls and switching worked; video showed green screens/colored artifacts, including some main-screen failures. Video compatibility did not pass. Standard HEVC raw decoding/Surface/GLES experiments passed, but the root cause remains unresolved.
- Independent displays share user/data/audio resources; there is no guarantee of automatic pausing, takeover arbitration, multi-touch/keyboard forwarding or two instances of the same app.
- This October 10 inventory did not accept a complete ROM or physical device. Later older-candidate builds, Pixel 9 installation and component acceptance are described above. A complete ROM corresponding to current source still needs building and acceptance; partial results cannot establish that the entire system is releasable.

This section preserves the compatibility scope at that time. Later individual features were verified separately, including [emulator background Unicode input](emulator-chinese-input-2026-10-14.md) and [child-process cleanup when the Agent stops](agent-cancellation-2026-10-15.md). They do not establish generic takeover arbitration, two instances of one app or complete app compatibility.

## All capabilities

### System version and execution identity · `system.info`

**Purpose:** Help the Agent choose compatible commands and determine UID, ABI, SELinux and system-service prerequisites
**Tier:** Ordinary app; no flashing required. The basic capability does not require flashing; the current experimental implementation may use higher privileges
**Status:** Verified on an emulator (within the cited evidence). This review read the system environment only; the core device-report SDK also read corresponding fields; no system writes were tested.
**Version/device:** Minimum declared API 1; First public SDK version of the interface class (not the minimum for every behavior described here). This example starts at API 1. An API declaration is not a device guarantee; check targetSdk and the OEM branch.; Public APIs require corresponding hardware / OEM support; this inventory covers only AgentOS_ImageCheck ARM64, with no verified physical device
**Entry point:** Build.VERSION / getprop / Taku SDK system.info
**Permissions/authorization:** None: Ordinary apps can read Build; id/getprop/getenforce examples require an authorized shell; the Taku SDK adb transport requires ADB

**Example (Host terminal, explicitly targeting emulator-5582): **

```sh
node sdk/cli/taku.mjs call system.info --transport adb --serial emulator-5582
```

**Expected result / success criteria:** Returns platform version; direct probes read API36, Android16, arm64-v8a, uid0 and Enforcing

**Limitations:**

- Root and platform signing are distinct qualifications; UID 0 does not establish that all data is readable.
- This is a read-only SDK wrapper, not evidence that the entire OS API is exposed.

**Evidence:** [current-readonly.json](../evidence/api-inventory/current-readonly.json); [sdk-device-report.json](../evidence/sdk/sdk-device-report.json)
**Primary sources:** [android.os.Build](https://developer.android.com/reference/android/os/Build); [AOSP SELinux](https://source.android.com/docs/security/features/selinux). Access/registration date 2026-10-10.

### Memory and disk overview · `system.resources`

**Purpose:** Determine whether there are resources to build APKs, run models or save screen images
**Tier:** Ordinary app; no flashing required. The basic capability does not require flashing; the current experimental implementation may use higher privileges
**Status:** Verified on an emulator (within the cited evidence). This review read /proc/meminfo and df /data only; low-memory process-killing policy was not tested.
**Version/device:** Minimum declared API 1; First public SDK version of the interface class (not the minimum for every behavior described here). This example starts at API 1. An API declaration is not a device guarantee; check targetSdk and the OEM branch.; Public APIs require corresponding hardware / OEM support; this inventory covers only AgentOS_ImageCheck ARM64, with no verified physical device
**Entry point:** ActivityManager.getMemoryInfo / StatFs / df
**Permissions/authorization:** None: Ordinary apps use ActivityManager.MemoryInfo / StatFs; the example uses an authorized shell

**Example (Root shell on the phone): **

```sh
cat /proc/meminfo
df -h /data
```

**Expected result / success criteria:** Returns MemTotal/MemAvailable and data-partition capacity; this review's probe is saved

**Limitations:**

- Storage capacity does not authorize access to other apps' private directories; emulator memory differs from host RAM.

**Evidence:** [current-readonly.json](../evidence/api-inventory/current-readonly.json)
**Primary sources:** [android.app.ActivityManager](https://developer.android.com/reference/android/app/ActivityManager). Access/registration date 2026-10-10.

### Package name, version and installation location · `packages.metadata`

**Purpose:** Determine whether traditional/generated apps are installed with the expected ABI and version
**Tier:** ADB / Shizuku; no custom ROM required. No custom ROM required; authorized ADB/shell access is needed; this project has not tested Shizuku integration
**Status:** Verified on an emulator (within the cited evidence). Recorded package versions: WeChat 8.0.79, Xiaohongshu 9.49.1, Douyin 40.7.0; both standalone APK hashes match the installed files.
**Version/device:** Minimum declared API 1; First public SDK version of the interface class (not the minimum for every behavior described here). This example starts at API 1. An API declaration is not a device guarantee; check targetSdk and the OEM branch.; Public APIs require corresponding hardware / OEM support; this inventory covers only AgentOS_ImageCheck ARM64, with no verified physical device
**Entry point:** pm path / dumpsys package
**Permissions/authorization:** ADB shell: Authorized ADB; ordinary apps need queries or legitimate visibility; Shizuku authorization does not expand shell privileges; ordinary apps are subject to package visibility restrictions: Authorized ADB; ordinary apps need queries or legitimate visibility; Shizuku authorization does not expand shell privileges

**Example (Root shell on the phone): **

```sh
pm path org.agentos.apiboard
dumpsys package org.agentos.apiboard
```

**Expected result / success criteria:** Returns the actual base.apk path and version/min/target; inspect only the intended package

**Limitations:**

- This review read seven specified related packages, not the user's complete app inventory.
- Installing three apps does not establish compatibility for messaging, payments or live streaming after login.

**Evidence:** [current-readonly.json](../evidence/api-inventory/current-readonly.json); [apk-verification.json](../evidence/api-inventory/apk-verification.json)
**Primary sources:** [android.content.pm.PackageManager](https://developer.android.com/reference/android/content/pm/PackageManager); [Official Shizuku introduction](https://shizuku.rikka.app/introduction/). Access/registration date 2026-10-10.

### Post an app's own system notifications · `notifications.post`

**Purpose:** Keep task completion, generated-app progress and actual statistics visible outside the app
**Tier:** Ordinary app; no flashing required. The basic capability does not require flashing; the current experimental implementation may use higher privileges
**Status:** Verified on an emulator (within the cited evidence). Historical converter notification UI / system records passed; no notification was sent again in this review.
**Version/device:** Minimum declared API 1; First public SDK version of the interface class (not the minimum for every behavior described here). This example starts at API 1. NotificationManager base API1; Notification.Builder API11; channels API26; POST_NOTIFICATIONS API33.; Public APIs require corresponding hardware / OEM support; this inventory covers only AgentOS_ImageCheck ARM64, with no verified physical device
**Entry point:** NotificationManager.notify / mini-app tasks.notify
**Permissions/authorization:** POST_NOTIFICATIONS(API33+): The ordinary app has runtime notification consent and its channel enabled; the experimental shell can already call the mini-app service; NotificationChannel(API26+): The ordinary app has runtime notification consent and its channel enabled; the experimental shell can already call the mini-app service

**Example (Root shell on the phone): **

```sh
/data/local/agentos/light-app-lab/free-app-kit/mini-app call board tasks.notify '{}'
```

**Expected result / success criteria:** Returns actual statistics and posts a system notification; verify its content through the notification UI/system record

**Limitations:**

- Authorization differs before/after API33; disabling the app or channel prevents notification display.
- Posting a notification does not mean another app's message has been answered.

**Evidence:** [converter-notification-test.txt](../evidence/api-inventory/historical/light-app-lab/free-app-kit/evidence/converter-notification-test.txt); [converter-notification.txt](../evidence/api-inventory/historical/light-app-lab/free-app-kit/evidence/converter-notification.txt)
**Primary sources:** [android.app.NotificationManager](https://developer.android.com/reference/android/app/NotificationManager); [Android Manifest permission reference](https://developer.android.com/reference/android/Manifest.permission). Access/registration date 2026-10-10.

### Observe notification changes · `notifications.listen`

**Purpose:** Receive text, timestamps, updates and removal events from posted app notifications to create tasks
**Tier:** Ordinary app; no flashing required. The basic capability does not require flashing; the current experimental implementation may use higher privileges
**Status:** Research proposal / not yet tested. Not tested; the example is a research entry point, not an installed Taku API
**Version/device:** Minimum declared API 18; Key method used by the example / project target version. This example starts at API 18. An API declaration is not a device guarantee; check targetSdk and the OEM branch.; Public APIs require corresponding hardware / OEM support; this inventory covers only AgentOS_ImageCheck ARM64, with no verified physical device
**Entry point:** NotificationListenerService.onNotificationPosted
**Permissions/authorization:** BIND_NOTIFICATION_LISTENER_SERVICE (service binding protection): Declare the Service and intent-filter in the Manifest; the user enables notification access in Settings; wait for onListenerConnected; special notification-access authorization: Declare the Service and intent-filter in the Manifest; the user enables notification access in Settings; wait for onListenerConnected

**Example (Kotlin method snippet in an ordinary Android project; complete Manifest, permissions and lifecycle as documented; not a directly executable CLI): **

```kotlin
override fun onNotificationPosted(sbn: StatusBarNotification) {
  Log.i("TakuProbe", "posted pkg=${sbn.packageName} id=${sbn.id}")
}
```

**Expected result / success criteria:** A newly posted test notification triggers a posted callback; this project has no verified listener adapter yet

**Limitations:**

- Receives actual notifications only; cannot reconstruct complete conversation history.
- The system may redact sensitive notifications; declaring a permission does not enable access automatically.

**Evidence:** No execution evidence yet.
**Primary sources:** [android.service.notification.NotificationListenerService](https://developer.android.com/reference/android/service/notification/NotificationListenerService); [NotificationListenerService](https://developer.android.com/reference/android/service/notification/NotificationListenerService). Access/registration date 2026-10-10.

### Quick replies through existing notifications · `notifications.reply`

**Purpose:** Reply to an existing notification through the app's exposed RemoteInput action
**Tier:** Ordinary app; no flashing required. The basic capability does not require flashing; the current experimental implementation may use higher privileges
**Status:** Research proposal / not yet tested. Not tested; the example is a research entry point, not an installed Taku API
**Version/device:** Minimum declared API 20; Key method used by the example / project target version. This example starts at API 20. An API declaration is not a device guarantee; check targetSdk and the OEM branch.; Public APIs require corresponding hardware / OEM support; this inventory covers only AgentOS_ImageCheck ARM64, with no verified physical device
**Entry point:** RemoteInput.addResultsToIntent / PendingIntent.send
**Permissions/authorization:** Notification access: Obtain the action through an authorized NotificationListenerService; the target app must expose a reply action; an available PendingIntent and RemoteInput: Obtain the action through an authorized NotificationListenerService; the target app must expose a reply action

**Example (Kotlin method snippet in an ordinary Android project; complete Manifest, permissions and lifecycle as documented; not a directly executable CLI): **

```kotlin
val reply = Intent()
RemoteInput.addResultsToIntent(action.remoteInputs, reply, Bundle().apply { putCharSequence(action.remoteInputs[0].resultKey, "Received") })
action.actionIntent.send(this, 0, reply)
```

**Expected result / success criteria:** Triggers the target app's own reply action; verify business success through its UI/service

**Limitations:**

- Not a general chat API; some apps lack quick replies or require unlocking.
- This project has not verified WeChat notification replies or sent a message through this path.

**Evidence:** No execution evidence yet.
**Primary sources:** [android.app.RemoteInput](https://developer.android.com/reference/android/app/RemoteInput); [NotificationListenerService](https://developer.android.com/reference/android/service/notification/NotificationListenerService). Access/registration date 2026-10-10.

### ADB UI-tree and control testing · `ui.tree.shell`

**Purpose:** Debug real pages, locate buttons and check results
**Tier:** ADB / Shizuku; no custom ROM required. No custom ROM required; authorized ADB/shell access is needed; this project has not tested Shizuku integration
**Status:** Verified on an emulator (within the cited evidence). Historical native Mirror and standalone APK UI Automator checks passed; no current GUI was captured in this review.
**Version/device:** Minimum declared API 18; First public SDK version of the interface class (not the minimum for every behavior described here). This example starts at API 18. An API declaration is not a device guarantee; check targetSdk and the OEM branch.; Public APIs require corresponding hardware / OEM support; this inventory covers only AgentOS_ImageCheck ARM64, with no verified physical device
**Entry point:** uiautomator dump / UI Automator
**Permissions/authorization:** shell / instrumentation: Developer enables ADB and authorizes the host; UI Automator starts through shell instrumentation

**Example (Root shell on the phone): **

```sh
uiautomator dump /sdcard/taku-ui.xml
cat /sdcard/taku-ui.xml
```

**Expected result / success criteria:** Returns the accessible node tree; the command writes the target test XML without clicking

**Limitations:**

- Not the entirety of app content; custom drawing and Surface video expose few nodes.
- Output may contain user page content; reproduce only in test scenarios.

**Evidence:** [native-ui-test.txt](../evidence/api-inventory/historical/mirror-screen/evidence/native-ui-test.txt); [standalone-open-test.txt](../evidence/api-inventory/historical/light-app-lab/free-app-kit/evidence/standalone-open-test.txt)
**Primary sources:** [android.app.UiAutomation](https://developer.android.com/reference/android/app/UiAutomation); [AccessibilityService](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService). Access/registration date 2026-10-10.

### Read the accessible UI tree from an ordinary app · `ui.tree.accessibility`

**Purpose:** Let an Agent understand the current page through text/nodes without flashing
**Tier:** Ordinary app; no flashing required. The basic capability does not require flashing; the current experimental implementation may use higher privileges
**Status:** Research proposal / not yet tested. Not tested; the example is a research entry point, not an installed Taku API
**Version/device:** Minimum declared API 4; First public SDK version of the interface class (not the minimum for every behavior described here). This example starts at API 4. An API declaration is not a device guarantee; check targetSdk and the OEM branch.; Public APIs require corresponding hardware / OEM support; this inventory covers only AgentOS_ImageCheck ARM64, with no verified physical device
**Entry point:** AccessibilityService.rootInActiveWindow
**Permissions/authorization:** BIND_ACCESSIBILITY_SERVICE (service binding protection): Complete accessibility Service and XML capability; user enables it in system Settings; root is unnecessary; canRetrieveWindowContent: Complete accessibility Service and XML capability; user enables it in system Settings; root is unnecessary

**Example (Kotlin method snippet in an ordinary Android project; complete Manifest, permissions and lifecycle as documented; not a directly executable CLI): **

```kotlin
val root = rootInActiveWindow ?: return
Log.i("TakuProbe", "nodes=${root.childCount}")
```

**Expected result / success criteria:** Returns currently accessible window nodes; custom-drawn content may be empty

**Limitations:**

- This project has not implemented an accessibility-based Agent.
- A disabled service, missing window nodes and custom-drawn content require explicit failure handling.

**Evidence:** No execution evidence yet.
**Primary sources:** [android.accessibilityservice.AccessibilityService](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService); [AccessibilityService](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService). Access/registration date 2026-10-10.

### Targeted touch, swipe and key input · `input.shell`

**Purpose:** Operate across apps while ensuring input reaches the background display
**Tier:** ADB / Shizuku; no custom ROM required. No custom ROM required; authorized ADB/shell access is needed; this project has not tested Shizuku integration
**Status:** Verified on an emulator (within the cited evidence). Historical native preview taps paused DeskClock, and timing continued after hiding the main-screen view; this does not guarantee all apps or multi-touch.
**Version/device:** Minimum declared API 36; Key method used by the example / project target version. This example starts at API 36. The input -d and host commands here were tested on API36; earlier CLI parameters depend on the system branch, with no universal minimum Android version promised.; Public APIs require corresponding hardware / OEM support; this inventory covers only AgentOS_ImageCheck ARM64, with no verified physical device
**Entry point:** input -d <displayId> tap/swipe/keyevent
**Permissions/authorization:** Shell or the protected INJECT_EVENTS permission: Authorized ADB/shell; the current root host sends events with input -d; ordinary JavaScript cannot call it directly

**Example (Root shell on the phone): **

```sh
agent_display_id=$(phone-display id)
input -d "$agent_display_id" tap 360 1210
input -d "$agent_display_id" keyevent 4
```

**Expected result / success criteria:** Input reaches that logical display; verify through its image/target state

**Limitations:**

- Successful injection is not business success; do not hard-code dynamic DisplayIDs.
- Same-app task migration, rotation, focus and touch regions all affect results.

**Evidence:** [native-ui-test.txt](../evidence/api-inventory/historical/mirror-screen/evidence/native-ui-test.txt); [native-video-test.txt](../evidence/api-inventory/historical/mirror-screen/evidence/native-video-test.txt)
**Primary sources:** [Android 16 InputShellCommand source](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/services/core/java/com/android/server/input/InputShellCommand.java); [Android 16 DisplayManagerService](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/services/core/java/com/android/server/display/DisplayManagerService.java). Access/registration date 2026-10-10.

### Inject semantic actions and gestures without flashing · `input.accessibility`

**Purpose:** Use accessibility to click nodes, scroll and perform single-pointer gestures
**Tier:** Ordinary app; no flashing required. The basic capability does not require flashing; the current experimental implementation may use higher privileges
**Status:** Research proposal / not yet tested. Not tested; the example is a research entry point, not an installed Taku API
**Version/device:** Minimum declared API 24; Key method used by the example / project target version. This example starts at API 24. An API declaration is not a device guarantee; check targetSdk and the OEM branch.; Public APIs require corresponding hardware / OEM support; this inventory covers only AgentOS_ImageCheck ARM64, with no verified physical device
**Entry point:** performAction / dispatchGesture
**Permissions/authorization:** Accessibility authorization: User enables the Service; XML android:canPerformGestures=true; call within the Service lifecycle; canPerformGestures: User enables the Service; XML android:canPerformGestures=true; call within the Service lifecycle

**Example (Kotlin method snippet in an ordinary Android project; complete Manifest, permissions and lifecycle as documented; not a directly executable CLI): **

```kotlin
val p = Path().apply { moveTo(300f, 600f) }
val g = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p, 0, 80)).build()
dispatchGesture(g, null, null)
```

**Expected result / success criteria:** Injects a single-pointer tap; verify application state even after the completion callback

**Limitations:**

- Gestures require API24; node-click success does not guarantee target handling.
- This path was not tested in this experiment and is not a substitute for a trusted background-display API.

**Evidence:** No execution evidence yet.
**Primary sources:** [android.accessibilityservice.AccessibilityService](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService); [AccessibilityService](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService). Access/registration date 2026-10-10.

### Text input and input-method bridge · `input.text`

**Purpose:** Chinese forms, generated-app input and draft restoration
**Tier:** Ordinary app; no flashing required. The basic capability does not require flashing; the current experimental implementation may use higher privileges
**Status:** Verified on an emulator (within the cited evidence). Only historical Chinese drafts in Taku native text fields and restart readback were verified; a general IME remains a research proposal.
**Version/device:** Minimum declared API 3; First public SDK version of the interface class (not the minimum for every behavior described here). This example starts at API 3. An API declaration is not a device guarantee; check targetSdk and the OEM branch.; Public APIs require corresponding hardware / OEM support; this inventory covers only AgentOS_ImageCheck ARM64, with no verified physical device
**Entry point:** InputConnection.commitText / ACTION_SET_TEXT
**Permissions/authorization:** A default input method requires BIND_INPUT_METHOD: Obtain the current editor's InputConnection in InputMethodService, or set node text through authorized accessibility; accessibility ACTION_SET_TEXT requires accessibility authorization: Obtain the current editor's InputConnection in InputMethodService, or set node text through authorized accessibility

**Example (Kotlin method snippet in an ordinary Android project; complete Manifest, permissions and lifecycle as documented; not a directly executable CLI): **

```kotlin
currentInputConnection?.commitText("桌面输入测试", 1)
```

**Expected result / success criteria:** The current editor receives Chinese text; verify the UI/storage before treating the write as successful

**Limitations:**

- Ordinary input text is not a general Unicode solution; current native tests use AccessibilityNodeInfo.ACTION_SET_TEXT.
- A general cross-app input-method bridge and background-preview keyboard forwarding are not implemented.

**Evidence:** [reboot-ui-test.txt](../evidence/api-inventory/historical/assistant-panel/evidence/desktop/reboot-ui-test.txt)
**Primary sources:** [android.view.inputmethod.InputConnection](https://developer.android.com/reference/android/view/inputmethod/InputConnection); [AccessibilityService](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService). Access/registration date 2026-10-10.

### ADB / root screenshots · `screen.capture.shell`

**Purpose:** Save main-screen or background-display images for visual agents and acceptance
**Tier:** ADB / Shizuku; no custom ROM required. No custom ROM required; authorized ADB/shell access is needed; this project has not tested Shizuku integration
**Status:** Verified on an emulator (within the cited evidence). Historical ImageReader images and native-preview PNGs were checked; this review took no screenshot.
**Version/device:** Minimum declared API 36; Key method used by the example / project target version. This example starts at API 36. Target commands were verified on API36; screencap and exact -d behavior depend on the AOSP/OEM branch.; Public APIs require corresponding hardware / OEM support; this inventory covers only AgentOS_ImageCheck ARM64, with no verified physical device
**Entry point:** screencap / phone-display capture
**Permissions/authorization:** Shell / root: Authorized ADB/shell; the virtual display's ImageReader belongs to the root host; secure-window restrictions: Authorized ADB/shell; the virtual display's ImageReader belongs to the root host

**Example (Root shell on the phone): **

```sh
phone-display capture
# /data/local/agentos/virtual-display/screen.png contains the background display
```

**Expected result / success criteria:** Saves an actual PNG; confirm its timestamp and DisplayID before giving it to a model

**Limitations:**

- screencap -d uses a SurfaceFlinger display ID, distinct from am/input logical IDs.
- Secure windows and protected content are not guaranteed to be capturable; a black image is not success.

**Evidence:** [mirror-return-still-running.png](../evidence/api-inventory/historical/mirror-screen/evidence/mirror-return-still-running.png)
**Primary sources:** [MediaProjection](https://developer.android.com/media/grow/media-projection); [Android 16 DisplayManagerService](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/services/core/java/com/android/server/display/DisplayManagerService.java). Access/registration date 2026-10-10.

### Screen capture without flashing · `screen.capture.public`

**Purpose:** Screen recording, OCR and current-page understanding
**Tier:** Ordinary app; no flashing required. The basic capability does not require flashing; the current experimental implementation may use higher privileges
**Status:** Research proposal / not yet tested. Not tested; the example is a research entry point, not an installed Taku API
**Version/device:** Minimum declared API 21; Key method used by the example / project target version. This example starts at API 23. An API declaration is not a device guarantee; check targetSdk and the OEM branch.; Public APIs require corresponding hardware / OEM support; this inventory covers only AgentOS_ImageCheck ARM64, with no verified physical device
**Entry point:** MediaProjectionManager.createScreenCaptureIntent
**Permissions/authorization:** MediaProjection user consent: Activity obtains the createScreenCaptureIntent result, then creates a capture session in a valid foreground service; target34+ foregroundServiceType=mediaProjection and FGS permissions: Activity obtains the createScreenCaptureIntent result, then creates a capture session in a valid foreground service

**Example (Kotlin method snippet in an ordinary Android project; complete Manifest, permissions and lifecycle as documented; not a directly executable CLI): **

```kotlin
startActivityForResult(getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent(), 100)
```

**Expected result / success criteria:** Shows the system capture-consent UI; the result is not a screenshot; Surface/VirtualDisplay and a stop callback are still required

**Limitations:**

- target34+ requires fresh consent per session; a token cannot be reused repeatedly.
- This project has not completed an ordinary-app MediaProjection adapter; secure content capture remains unpromised.

**Evidence:** No execution evidence yet.
**Primary sources:** [android.media.projection.MediaProjectionManager](https://developer.android.com/reference/android/media/projection/MediaProjectionManager); [MediaProjection](https://developer.android.com/media/grow/media-projection). Access/registration date 2026-10-10.

### Usage history and foreground-app clues · `context.foreground`

**Purpose:** Identify the user's recent apps and choose when to act
**Tier:** Ordinary app; no flashing required. The basic capability does not require flashing; the current experimental implementation may use higher privileges
**Status:** Research proposal / not yet tested. Not tested; the example is a research entry point, not an installed Taku API
**Version/device:** Minimum declared API 21; Key method used by the example / project target version. This example starts at API 23. An API declaration is not a device guarantee; check targetSdk and the OEM branch.; Public APIs require corresponding hardware / OEM support; this inventory covers only AgentOS_ImageCheck ARM64, with no verified physical device
**Entry point:** UsageStatsManager.queryEvents
**Permissions/authorization:** PACKAGE_USAGE_STATS / usage-access AppOp: Declare in the Manifest and let the user enable usage access in Settings; queryEvents uses a time window

**Example (Kotlin method snippet in an ordinary Android project; complete Manifest, permissions and lifecycle as documented; not a directly executable CLI): **

```kotlin
val now = System.currentTimeMillis()
val events = getSystemService(UsageStatsManager::class.java).queryEvents(now - 60_000, now)
```

**Expected result / success criteria:** Returns activity-usage events; first check AppOps and actual event availability

**Limitations:**

- Usage statistics may be delayed; they are not a complete real-time window tree.
- Real-time cross-app task management requires a separate privileged interface, not exposed here.

**Evidence:** No execution evidence yet.
**Primary sources:** [android.app.usage.UsageStatsManager](https://developer.android.com/reference/android/app/usage/UsageStatsManager). Access/registration date 2026-10-10.

### Foreground clipboard / share entry point · `context.clipboard`

**Purpose:** Add copied or shared material to the current Agent task
**Tier:** Ordinary app; no flashing required. The basic capability does not require flashing; the current experimental implementation may use higher privileges
**Status:** Research proposal / not yet tested. Not tested; the example is a research entry point, not an installed Taku API
**Version/device:** Minimum declared API 11; First public SDK version of the interface class (not the minimum for every behavior described here). This example starts at API 23. An API declaration is not a device guarantee; check targetSdk and the OEM branch.; Public APIs require corresponding hardware / OEM support; this inventory covers only AgentOS_ImageCheck ARM64, with no verified physical device
**Entry point:** ClipboardManager.getPrimaryClip / ACTION_SEND
**Permissions/authorization:** Foreground focus: Read primaryClip in a focused Activity; register ACTION_SEND for a share target; a share Intent does not require global background clipboard access: Read primaryClip in a focused Activity; register ACTION_SEND for a share target

**Example (Kotlin method snippet in an ordinary Android project; complete Manifest, permissions and lifecycle as documented; not a directly executable CLI): **

```kotlin
val clip = getSystemService(ClipboardManager::class.java).primaryClip
Log.i("TakuProbe", "items=${clip?.itemCount ?: 0}")
```

**Expected result / success criteria:** A foreground app can obtain clipboard items; handle missing access or empty content as empty

**Limitations:**

- Ordinary background apps are restricted from API29; continuous system-level subscription needs a separate design.
- Does not read clipboard history; this capability has not been tested in the project.

**Evidence:** No execution evidence yet.
**Primary sources:** [android.content.ClipboardManager](https://developer.android.com/reference/android/content/ClipboardManager); [Android clipboard access restrictions](https://developer.android.com/privacy-and-security/risks/secure-clipboard-handling). Access/registration date 2026-10-10.

### Media-session state and playback control · `context.media`

**Purpose:** Music/podcast titles and pause, resume and skip controls
**Tier:** Ordinary app; no flashing required. The basic capability does not require flashing; the current experimental implementation may use higher privileges
**Status:** Research proposal / not yet tested. Not tested; the example is a research entry point, not an installed Taku API
**Version/device:** Minimum declared API 21; Key method used by the example / project target version. This example starts at API 23. An API declaration is not a device guarantee; check targetSdk and the OEM branch.; Public APIs require corresponding hardware / OEM support; this inventory covers only AgentOS_ImageCheck ARM64, with no verified physical device
**Entry point:** getActiveSessions / MediaController.transportControls
**Permissions/authorization:** Notification access or protected MEDIA_CONTENT_CONTROL: After notification-listener authorization, specify its listener component; only sessions exposed by apps are controllable

**Example (Kotlin method snippet in an ordinary Android project; complete Manifest, permissions and lifecycle as documented; not a directly executable CLI): **

```kotlin
val sessions = getSystemService(MediaSessionManager::class.java).getActiveSessions(listenerComponent)
sessions.firstOrNull()?.transportControls?.pause()
```

**Expected result / success criteria:** Pauses an available controllable session; verify the player's state

**Limitations:**

- Not a general audio transcription interface; players may omit metadata.
- Untested; audio routing and multiple displays of one app still share resources.

**Evidence:** No execution evidence yet.
**Primary sources:** [android.media.session.MediaSessionManager](https://developer.android.com/reference/android/media/session/MediaSessionManager); [NotificationListenerService](https://developer.android.com/reference/android/service/notification/NotificationListenerService). Access/registration date 2026-10-10.

### System SMS and call logs · `data.sms.calllog`

**Purpose:** Delivery, flight and contact events in system Providers
**Tier:** Ordinary app; no flashing required. The basic capability does not require flashing; the current experimental implementation may use higher privileges
**Status:** Research proposal / not yet tested. Not tested; the example is a research entry point, not an installed Taku API
**Version/device:** Minimum declared API 19; First public SDK version of the interface class (not the minimum for every behavior described here). This example starts at API 19. An API declaration is not a device guarantee; check targetSdk and the OEM branch.; Public APIs require corresponding hardware / OEM support; this inventory covers only AgentOS_ImageCheck ARM64, with no verified physical device
**Entry point:** ContentResolver.query(Telephony.Sms.CONTENT_URI)
**Permissions/authorization:** READ_SMS: Runtime consent alone is insufficient; check installer allowlists, default SMS/phone roles and OEM policy; use dedicated test data only; READ_CALL_LOG: Runtime consent alone is insufficient; check installer allowlists, default SMS/phone roles and OEM policy; use dedicated test data only; installer allowlist for hard-restricted permissions / relevant roles: Runtime consent alone is insufficient; check installer allowlists, default SMS/phone roles and OEM policy; use dedicated test data only

**Example (Kotlin method snippet in an ordinary Android project; complete Manifest, permissions and lifecycle as documented; not a directly executable CLI): **

```kotlin
contentResolver.query(Telephony.Sms.CONTENT_URI, arrayOf("_id", "date"), null, null, "date DESC")?.use { Log.i("TakuProbe", "count=${it.count}") }
```

**Expected result / success criteria:** Returns test SMS IDs/timestamps when properly authorized; denial raises SecurityException, so universal access must not be claimed

**Limitations:**

- A single pm grant does not grant a hard-restricted permission.
- WeChat/Douyin history is not in the SMS Provider; this project has not read or tested this personal data.

**Evidence:** No execution evidence yet.
**Primary sources:** [android.provider.Telephony](https://developer.android.com/reference/android/provider/Telephony); [Android Manifest permission reference](https://developer.android.com/reference/android/Manifest.permission). Access/registration date 2026-10-10.

### Read and write contacts · `data.contacts`

**Purpose:** Associate contacts and create entries owned by the app in the address book
**Tier:** Ordinary app; no flashing required. The basic capability does not require flashing; the current experimental implementation may use higher privileges
**Status:** Research proposal / not yet tested. Not tested; the example is a research entry point, not an installed Taku API
**Version/device:** Minimum declared API 5; First public SDK version of the interface class (not the minimum for every behavior described here). This example starts at API 5. An API declaration is not a device guarantee; check targetSdk and the OEM branch.; Public APIs require corresponding hardware / OEM support; this inventory covers only AgentOS_ImageCheck ARM64, with no verified physical device
**Entry point:** ContentResolver.query(ContactsContract.Contacts.CONTENT_URI)
**Permissions/authorization:** READ_CONTACTS: Request each runtime permission separately; use an isolated account/test contacts; writes separately require WRITE_CONTACTS: Request each runtime permission separately; use an isolated account/test contacts

**Example (Kotlin method snippet in an ordinary Android project; complete Manifest, permissions and lifecycle as documented; not a directly executable CLI): **

```kotlin
contentResolver.query(ContactsContract.Contacts.CONTENT_URI, arrayOf(ContactsContract.Contacts._ID), null, null, null)?.use { Log.i("TakuProbe", "count=${it.count}") }
```

**Expected result / success criteria:** Returns test contact IDs; this project has not called it

**Limitations:**

- The system address book is not every app's cloud friend list.
- Read access does not include write access or cross-organization account access.

**Evidence:** No execution evidence yet.
**Primary sources:** [android.provider.ContactsContract](https://developer.android.com/reference/android/provider/ContactsContract). Access/registration date 2026-10-10.

### Query and create system calendar events · `data.calendar`

**Purpose:** Schedule events and associate reminders and local appointments
**Tier:** Ordinary app; no flashing required. The basic capability does not require flashing; the current experimental implementation may use higher privileges
**Status:** Research proposal / not yet tested. Not tested; the example is a research entry point, not an installed Taku API
**Version/device:** Minimum declared API 14; Key method used by the example / project target version. This example starts at API 14. An API declaration is not a device guarantee; check targetSdk and the OEM branch.; Public APIs require corresponding hardware / OEM support; this inventory covers only AgentOS_ImageCheck ARM64, with no verified physical device
**Entry point:** CalendarContract / ACTION_INSERT
**Permissions/authorization:** Authorize READ_CALENDAR and WRITE_CALENDAR separately: Runtime authorization; ACTION_INSERT lets the calendar app request confirmation without the caller holding database-write permission

**Example (Kotlin method snippet in an ordinary Android project; complete Manifest, permissions and lifecycle as documented; not a directly executable CLI): **

```kotlin
startActivity(Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI).putExtra(CalendarContract.Events.TITLE, "Taku test event"))
```

**Expected result / success criteria:** Opens the calendar creation page; read back the event to confirm it was saved

**Limitations:**

- A writable system calendar account may be absent; cloud Feishu/WeChat schedules do not automatically exist in the Provider.
- Opening the confirmation page does not mean an event is saved; this project has not tested the example.

**Evidence:** No execution evidence yet.
**Primary sources:** [android.provider.CalendarContract](https://developer.android.com/reference/android/provider/CalendarContract). Access/registration date 2026-10-10.

### Photo library and file location · `data.photos`

**Purpose:** Image indexing, turning photos into tasks, and optional Exif location
**Tier:** Ordinary app; no flashing required. The basic capability does not require flashing; the current experimental implementation may use higher privileges
**Status:** Research proposal / not yet tested. Not tested; the example is a research entry point, not an installed Taku API
**Version/device:** Minimum declared API 1; First public SDK version of the interface class (not the minimum for every behavior described here). This example starts at API 1. MediaStore exists in early APIs; the example uses ACTION_GET_CONTENT; grouped media-read permissions require API33; Photo Picker is API33 with some system backports.; Public APIs require corresponding hardware / OEM support; this inventory covers only AgentOS_ImageCheck ARM64, with no verified physical device
**Entry point:** ACTION_GET_CONTENT / MediaStore / ExifInterface
**Permissions/authorization:** Photo Picker / READ_MEDIA_IMAGES、READ_MEDIA_VIDEO(API33+): Prefer Photo Picker grants for selected URIs; full media reads need version-appropriate permissions; location needs separate authorization; ACCESS_MEDIA_LOCATION(API29+): Prefer Photo Picker grants for selected URIs; full media reads need version-appropriate permissions; location needs separate authorization

**Example (Kotlin method snippet in an ordinary Android project; complete Manifest, permissions and lifecycle as documented; not a directly executable CLI): **

```kotlin
startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).setType("image/*"), 101)
```

**Expected result / success criteria:** The user selects an image and grants URI read access; check whether Exif contains GPS

**Limitations:**

- GPS may be absent or removed; it is not proof of a visit or preference.
- API34 supports partial photo access; photo-database indexing is untested.

**Evidence:** No execution evidence yet.
**Primary sources:** [android.provider.MediaStore](https://developer.android.com/reference/android/provider/MediaStore); [Android Manifest permission reference](https://developer.android.com/reference/android/Manifest.permission). Access/registration date 2026-10-10.

### Select, search and organize files · `data.files`

**Purpose:** Import documents and archive user-selected directories
**Tier:** Ordinary app; no flashing required. The basic capability does not require flashing; the current experimental implementation may use higher privileges
**Status:** Research proposal / not yet tested. Not tested; the example is a research entry point, not an installed Taku API
**Version/device:** Minimum declared API 21; Key method used by the example / project target version. This example starts at API 21. An API declaration is not a device guarantee; check targetSdk and the OEM branch.; Public APIs require corresponding hardware / OEM support; this inventory covers only AgentOS_ImageCheck ARM64, with no verified physical device
**Entry point:** Storage Access Framework
**Permissions/authorization:** SAF URI grant: Select a directory with ACTION_OPEN_DOCUMENT_TREE and persist URI read/write grants; this does not grant access to private directories; MANAGE_EXTERNAL_STORAGE special access if necessary: Select a directory with ACTION_OPEN_DOCUMENT_TREE and persist URI read/write grants; this does not grant access to private directories

**Example (Kotlin method snippet in an ordinary Android project; complete Manifest, permissions and lifecycle as documented; not a directly executable CLI): **

```kotlin
startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), 102)
```

**Expected result / success criteria:** Select a directory and obtain its authorized URI before traversing DocumentFile

**Limitations:**

- Some directories cannot be selected through the tree picker from API30; all-files access still excludes other apps' private directories.
- This project has no file full-text index.

**Evidence:** No execution evidence yet.
**Primary sources:** [android.provider.DocumentsContract](https://developer.android.com/reference/android/provider/DocumentsContract). Access/registration date 2026-10-10.

### Research: export other apps' private data · `data.private-files`

**Purpose:** Custom exports for test-device debugging and user data migration
**Tier:** Root / system signing; no Framework changes required. Requires appropriate root access or system signing/preinstallation; deployment on a rooted stock system may be possible, but this distribution path is unverified
**Status:** Research proposal / not yet tested. Not tested; the example is a research entry point, not an installed Taku API
**Version/device:** Minimum declared API No universal declaration; Non-public SDK, custom or device interface; no universal minimum API declaration. This example starts at API No universal declaration. An API declaration is not a device guarantee; check targetSdk and the OEM branch.; Public APIs require corresponding hardware / OEM support; this inventory covers only AgentOS_ImageCheck ARM64, with no verified physical device
**Entry point:** Custom native export service / filesystem + app export
**Permissions/authorization:** Root DAC + SELinux + FBE / app-level encryption: Verify unlock state, sepolicy, the app's export support and consistent snapshots; there is no universal read-everything API

**Example (Read-only source-location command in an AOSP checkout; not evidence of implemented functionality): **

```sh
rg --threads 2 "neverallow|app_data_file" system/sepolicy -g "*.te"
```

**Expected result / success criteria:** Locate private-directory policy in an AOSP checkout; research only, without collecting real personal databases

**Limitations:**

- Root cannot decrypt app-level encryption or hardware keys; copying a live database blindly is unsafe.
- No exposed export service, verified execution or credential-data reading is provided.

**Evidence:** No execution evidence yet.
**Primary sources:** [AOSP file-based encryption](https://source.android.com/docs/security/features/encryption/file-based); [AOSP SELinux](https://source.android.com/docs/security/features/selinux). Access/registration date 2026-10-10.

### Research: backup, restore and migration · `data.backup`

**Purpose:** Preserve application state before upgrades and restore apps you own
**Tier:** Ordinary app; no flashing required. The basic capability does not require flashing; the current experimental implementation may use higher privileges
**Status:** Research proposal / not yet tested. Not tested; the example is a research entry point, not an installed Taku API
**Version/device:** Minimum declared API 8; First public SDK version of the interface class (not the minimum for every behavior described here). This example starts at API 8. An API declaration is not a device guarantee; check targetSdk and the OEM branch.; Public APIs require corresponding hardware / OEM support; this inventory covers only AgentOS_ImageCheck ARM64, with no verified physical device
**Entry point:** BackupManager.dataChanged / app export
**Permissions/authorization:** App-specific backup configuration: Implement BackupAgent / an export format for your own app; copying complete third-party login state is not promised; privileged cross-app backup additionally requires BACKUP: Implement BackupAgent / an export format for your own app; copying complete third-party login state is not promised

**Example (Kotlin method snippet in an ordinary Android project; complete Manifest, permissions and lifecycle as documented; not a directly executable CLI): **

```kotlin
BackupManager(this).dataChanged()
```

**Expected result / success criteria:** Marks the app's data as awaiting backup; does not establish immediate backup completion or restoration

**Limitations:**

- allowBackup, the transport, encryption and OEM behavior determine scope.
- Successful JSON persistence across restart is not full system backup.

**Evidence:** No execution evidence yet.
**Primary sources:** [android.app.backup.BackupManager](https://developer.android.com/reference/android/app/backup/BackupManager); [AOSP file-based encryption](https://source.android.com/docs/security/features/encryption/file-based). Access/registration date 2026-10-10.

### Launch existing apps or Intent actions · `apps.launch`

**Purpose:** Open traditional entry points such as Clock, WeChat and Maps
**Tier:** Ordinary app; no flashing required. The basic capability does not require flashing; the current experimental implementation may use higher privileges
**Status:** Verified on an emulator (within the cited evidence). Historical DeskClock and specific pages in three apps; the current probe confirms those packages remain installed.
**Version/device:** Minimum declared API 26; Key method used by the example / project target version. This example starts at API 26. Intent base API1; ActivityOptions.setLaunchDisplayId API26; project commands verified on API36.; Public APIs require corresponding hardware / OEM support; this inventory covers only AgentOS_ImageCheck ARM64, with no verified physical device
**Entry point:** startActivity / am start --display
**Permissions/authorization:** Exported target Activity / public Intent: Foreground apps can send public Intents; background starts have version-specific restrictions; display targeting needs authorized shell/system policy; package visibility: Foreground apps can send public Intents; background starts have version-specific restrictions; display targeting needs authorized shell/system policy

**Example (Root shell on the phone): **

```sh
agent_display_id=$(phone-display id)
am start --display "$agent_display_id" -n com.android.deskclock/.DeskClock
```

**Expected result / success criteria:** The actual DeskClock task appears on that display; verify using a screenshot or dumpsys

**Limitations:**

- An ordinary Intent cannot arbitrarily launch non-exported components.
- WeChat was checked only before login and Xiaohongshu in its guest image feed; Douyin video failed; account workflows are not verified.

**Evidence:** [native-ui-test.txt](../evidence/api-inventory/historical/mirror-screen/evidence/native-ui-test.txt); [compatibility-report.json](../evidence/api-inventory/historical/app-compat/compatibility-report.json)
**Primary sources:** [android.content.Intent](https://developer.android.com/reference/android/content/Intent); [Restrictions on background Activity starts](https://developer.android.com/guide/components/activities/secure-bal); [Android 16 DisplayManagerService](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/services/core/java/com/android/server/display/DisplayManagerService.java). Access/registration date 2026-10-10.

### Install, update and uninstall standalone APKs · `apps.install`

**Purpose:** Make a phone-generated app a real package visible in the launcher
**Tier:** ADB / Shizuku; no custom ROM required. No custom ROM required; authorized ADB/shell access is needed; this project has not tested Shizuku integration
**Status:** Verified on an emulator (within the cited evidence). This review verified two historical APKs with apksigner and matched installed base.apk hashes; no reinstall occurred.
**Version/device:** Minimum declared API 21; Key method used by the example / project target version. This example starts at API 21. An API declaration is not a device guarantee; check targetSdk and the OEM branch.; Public APIs require corresponding hardware / OEM support; this inventory covers only AgentOS_ImageCheck ARM64, with no verified physical device
**Entry point:** pm install / PackageInstaller Session
**Permissions/authorization:** ADB shell / INSTALL_PACKAGES: Shell pm install; an ordinary installer needs unknown-source authorization and user action; updates must keep the signing identity; ordinary apps need REQUEST_INSTALL_PACKAGES and user consent: Shell pm install; an ordinary installer needs unknown-source authorization and user action; updates must keep the signing identity

**Example (Root shell on the phone): **

```sh
pm install -r /data/local/tmp/test-generated.apk
pm path org.agentos.apiboard
```

**Expected result / success criteria:** Returns Success and base.apk; verify signing, version and launch separately

**Limitations:**

- Supply an actual test APK at the example path first; no automatic download or build occurs.
- Cannot update by downgrading or changing signatures; a different platform signature fails; installation does not establish application compatibility.

**Evidence:** [apk-verification.json](../evidence/api-inventory/apk-verification.json); [current-readonly.json](../evidence/api-inventory/current-readonly.json); [standalone-open-test.txt](../evidence/api-inventory/historical/light-app-lab/free-app-kit/evidence/standalone-open-test.txt)
**Primary sources:** [android.content.pm.PackageInstaller](https://developer.android.com/reference/android/content/pm/PackageInstaller); [PackageInstaller SessionParams](https://developer.android.com/reference/android/content/pm/PackageInstaller.SessionParams). Access/registration date 2026-10-10.

### Configure runtime permissions, roles and AppOps · `permissions.manage`

**Purpose:** Device initialization and capability diagnostics
**Tier:** ADB / Shizuku; no custom ROM required. No custom ROM required; authorized ADB/shell access is needed; this project has not tested Shizuku integration
**Status:** Research proposal / not yet tested. Not tested; the example is a research entry point, not an installed Taku API
**Version/device:** Minimum declared API 23; Key method used by the example / project target version. This example starts at API 23. An API declaration is not a device guarantee; check targetSdk and the OEM branch.; Public APIs require corresponding hardware / OEM support; this inventory covers only AgentOS_ImageCheck ARM64, with no verified physical device
**Entry point:** pm grant / appops / RoleManager
**Permissions/authorization:** Shell / GRANT_RUNTIME_PERMISSIONS / role-specific authorization: pm grant applies only to valid runtime permissions; notification access, accessibility, roles and AppOps each have their own entry points; undeclared/signature-only permissions cannot be granted this way

**Example (Root shell on the phone): **

```sh
pm grant org.agentos.apiboard android.permission.POST_NOTIFICATIONS
appops get org.agentos.apiboard POST_NOTIFICATION
```

**Expected result / success criteria:** Enables notification permission where supported; preserve the original error and cause if refused

**Limitations:**

- Not executed in this review; existing packaging scripts contain grants but do not establish access to all permissions.
- Verify authorization actions separately from successful API behavior.

**Evidence:** No execution evidence yet.
**Primary sources:** [android.app.AppOpsManager](https://developer.android.com/reference/android/app/AppOpsManager); [Android Manifest permission reference](https://developer.android.com/reference/android/Manifest.permission); [AOSP privileged permission allowlist](https://source.android.com/docs/core/permissions/perms-allowlist); [Official Shizuku introduction](https://shizuku.rikka.app/introduction/). Access/registration date 2026-10-10.

### System settings, brightness and Do Not Disturb · `settings.control`

**Purpose:** Adjust available settings for a task
**Tier:** ADB / Shizuku; no custom ROM required. No custom ROM required; authorized ADB/shell access is needed; this project has not tested Shizuku integration
**Status:** Research proposal / not yet tested. Not tested; the example is a research entry point, not an installed Taku API
**Version/device:** Minimum declared API 1; First public SDK version of the interface class (not the minimum for every behavior described here). This example starts at API 1. An API declaration is not a device guarantee; check targetSdk and the OEM branch.; Public APIs require corresponding hardware / OEM support; this inventory covers only AgentOS_ImageCheck ARM64, with no verified physical device
**Entry point:** Settings.System / Secure / cmd service
**Permissions/authorization:** WRITE_SETTINGS special access: Ordinary apps have specific settings-write grants; shell can call settings; prefer the owning service over direct database writes; protected WRITE_SECURE_SETTINGS: Ordinary apps have specific settings-write grants; shell can call settings; prefer the owning service over direct database writes; DND separately requires policy access: Ordinary apps have specific settings-write grants; shell can call settings; prefer the owning service over direct database writes

**Example (Root shell on the phone): **

```sh
settings get system screen_brightness
```

**Expected result / success criteria:** Read brightness as a probe without side effects; changing it requires setting a value and observing the effect

**Limitations:**

- A read example establishes readability only, not that every toggle is writable.
- Wi-Fi/Bluetooth and DND policy vary by Android/OEM; this research item has not passed acceptance.

**Evidence:** No execution evidence yet.
**Primary sources:** [android.provider.Settings](https://developer.android.com/reference/android/provider/Settings); [Android Manifest permission reference](https://developer.android.com/reference/android/Manifest.permission). Access/registration date 2026-10-10.

### Custom home screen, task cards and traditional app entry points · `home.launcher`

**Purpose:** Show task state and launch traditional apps directly from Home
**Tier:** Ordinary app; no flashing required. The basic capability does not require flashing; the current experimental implementation may use higher privileges
**Status:** Verified on an emulator (within the cited evidence). Historical native HOME Chinese drafts and restart readback; current APK minSdk36.
**Version/device:** Minimum declared API 36; Key method used by the example / project target version. This example starts at API 36. HOME exists in early APIs; this project's assistant APK explicitly requires minSdk36, without promising installation on older versions.; Public APIs require corresponding hardware / OEM support; this inventory covers only AgentOS_ImageCheck ARM64, with no verified physical device
**Entry point:** HOME Activity / TaskRequests / launcher intents
**Permissions/authorization:** HOME Intent: Ordinary apps declare HOME; the current Taku assistant uses system UID1000 and a platform test signature, with minSdk36; user selects the default launcher: Ordinary apps declare HOME; the current Taku assistant uses system UID1000 and a platform test signature, with minSdk36; system preinstallation can set the default: Ordinary apps declare HOME; the current Taku assistant uses system UID1000 and a platform test signature, with minSdk36

**Example (Root shell on the phone): **

```sh
am start -a android.intent.action.MAIN -c android.intent.category.HOME
```

**Expected result / success criteria:** Opens the actual Taku HOME, not a simulated web interface

**Limitations:**

- An ordinary launcher can provide most cards; it does not automatically gain global SystemUI behavior.
- Input widgets and animated desktop companions remain separate UI iterations; this item does not replace current design acceptance.

**Evidence:** [reboot-ui-test.txt](../evidence/api-inventory/historical/assistant-panel/evidence/desktop/reboot-ui-test.txt)
**Primary sources:** [android.app.role.RoleManager](https://developer.android.com/reference/android/app/role/RoleManager); [Restrictions on background Activity starts](https://developer.android.com/guide/components/activities/secure-bal). Access/registration date 2026-10-10.

### Global assistive layer / overlay · `home.overlay`

**Purpose:** Translation, task state and tools for the current page
**Tier:** Ordinary app; no flashing required. The basic capability does not require flashing; the current experimental implementation may use higher privileges
**Status:** Research proposal / not yet tested. Not tested; the example is a research entry point, not an installed Taku API
**Version/device:** Minimum declared API 26; Key method used by the example / project target version. This example starts at API 26. An API declaration is not a device guarantee; check targetSdk and the OEM branch.; Public APIs require corresponding hardware / OEM support; this inventory covers only AgentOS_ImageCheck ARM64, with no verified physical device
**Entry point:** WindowManager.addView
**Permissions/authorization:** SYSTEM_ALERT_WINDOW special access: User enables overlay permission; WindowManager adds a non-blocking view and handles focus/input; TYPE_APPLICATION_OVERLAY API26: User enables overlay permission; WindowManager adds a non-blocking view and handles focus/input

**Example (Kotlin method snippet in an ordinary Android project; complete Manifest, permissions and lifecycle as documented; not a directly executable CLI): **

```kotlin
val p = WindowManager.LayoutParams(200, 100, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT)
getSystemService(WindowManager::class.java).addView(TextView(this).apply { text = "Taku status" }, p)
```

**Expected result / success criteria:** Displays a separate overlay; touch passthrough/layout require separate tests

**Limitations:**

- Not a SystemUI permission; system policy constrains overlays on sensitive windows and touch delivery.
- This project has a NativeHOME pet only, with no verified cross-app pet overlay.

**Evidence:** No execution evidence yet.
**Primary sources:** [android.view.WindowManager](https://developer.android.com/reference/android/view/WindowManager); [Android Manifest permission reference](https://developer.android.com/reference/android/Manifest.permission). Access/registration date 2026-10-10.

### Default digital assistant and global invocation · `assistant.entry`

**Purpose:** Receive context and tasks from the system ASSIST entry point
**Tier:** Ordinary app; no flashing required. The basic capability does not require flashing; the current experimental implementation may use higher privileges
**Status:** Research proposal / not yet tested. Source and installed identity were checked, but this inventory lacks complete independent acceptance for global invocation/context capture; HOME tests do not make this item pass.
**Version/device:** Minimum declared API 21; Key method used by the example / project target version. This example starts at API 21. An API declaration is not a device guarantee; check targetSdk and the OEM branch.; Public APIs require corresponding hardware / OEM support; this inventory covers only AgentOS_ImageCheck ARM64, with no verified physical device
**Entry point:** VoiceInteractionService / android.intent.action.ASSIST
**Permissions/authorization:** BIND_VOICE_INTERACTION (service binding): Implement VoiceInteractionService/SessionService and select the default assistant; the current test-signed system app integrates these; default assistant role: Implement VoiceInteractionService/SessionService and select the default assistant; the current test-signed system app integrates these

**Example (Root shell on the phone): **

```sh
am start -a android.intent.action.ASSIST
```

**Expected result / success criteria:** Opens the current default assistant entry point; not speech recognition or low-power wake-up

**Limitations:**

- RecognitionUnavailableService in the Manifest indicates that speech transcription is not implemented.
- The system may redact background/main-screen context; secure content is not promised.

**Evidence:** No execution evidence yet.
**Primary sources:** [android.service.voice.VoiceInteractionService](https://developer.android.com/reference/android/service/voice/VoiceInteractionService); [VoiceInteractionService](https://developer.android.com/reference/android/service/voice/VoiceInteractionService). Access/registration date 2026-10-10.

### A truly independent background display · `display.independent`

**Purpose:** Let the Agent operate another display while the user continues on the main screen
**Tier:** Root / system signing; no Framework changes required. Requires appropriate root access or system signing/preinstallation; deployment on a rooted stock system may be possible, but this distribution path is unverified
**Status:** Verified on an emulator (within the cited evidence). Historical independent creation, targeted operations, concurrent main-screen HOME and host stop/start lifecycle passed; Douyin video did not pass.
**Version/device:** Minimum declared API 36; Key method used by the example / project target version. This example starts at API 36. Base createVirtualDisplay API19; this protected flag combination and host were tested only on API36, which does not establish compatibility from API19.; Public APIs require corresponding hardware / OEM support; this inventory covers only AgentOS_ImageCheck ARM64, with no verified physical device
**Entry point:** phone-display / createVirtualDisplay
**Permissions/authorization:** Current root app_process: The phone-display host owns an ImageReader Surface; current root + SELinux Enforcing operation, not merely ordinary MediaProjection mirroring; flags such as TRUSTED/OWN_FOCUS require protected system permissions: The phone-display host owns an ImageReader Surface; current root + SELinux Enforcing operation, not merely ordinary MediaProjection mirroring

**Example (Root shell on the phone): **

```sh
phone-display start
phone-display id
phone-display status
```

**Expected result / success criteria:** Obtains a dynamic nonzero displayId and an independent 720x1280 Surface, not a copy of the main screen

**Limitations:**

- Shares the Android user, data and audio resources; not a separate VM or account sandbox.
- Single-instance apps may move windows; user/Agent conflict scheduling is absent; reboot requires starting the task, rather than an independently autostarting host init service.

**Evidence:** [native-ui-test.txt](../evidence/api-inventory/historical/mirror-screen/evidence/native-ui-test.txt); [native-video-test.txt](../evidence/api-inventory/historical/mirror-screen/evidence/native-video-test.txt); [helper-synchronous-stop-test.json](../evidence/api-inventory/historical/virtual-display/evidence/video-diagnostics/helper-synchronous-stop-test.json)
**Primary sources:** [android.hardware.display.DisplayManager](https://developer.android.com/reference/android/hardware/display/DisplayManager); [DisplayManager](https://developer.android.com/reference/android/hardware/display/DisplayManager); [Android 16 DisplayManagerService](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/services/core/java/com/android/server/display/DisplayManagerService.java). Access/registration date 2026-10-10.

### View and manually control the background display from the main screen · `display.preview`

**Purpose:** Let the user inspect the Agent's work and touch the display when needed
**Tier:** Root / system signing; no Framework changes required. Requires appropriate root access or system signing/preinstallation; deployment on a rooted stock system may be possible, but this distribution path is unverified
**Status:** Verified on an emulator (within the cited evidence). Historical native Mirror paused at 26:04.89 to 26:11.57, then resumed from 1.71 to 7.66 seconds after hiding; the GUI was not rerun in this review.
**Version/device:** Minimum declared API 36; Key method used by the example / project target version. This example starts at API 36. An API declaration is not a device guarantee; check targetSdk and the OEM branch.; Public APIs require corresponding hardware / OEM support; this inventory covers only AgentOS_ImageCheck ARM64, with no verified physical device
**Entry point:** MirrorActivity / taku-mirror frame|tap|swipe|key
**Permissions/authorization:** Socket peer UID 0/1000/2000: Taku Native MirrorActivity connects to the abstract Unix socket taku-mirror; no network socket is exposed; touch targets the root host; current system_app UID1000: Taku Native MirrorActivity connects to the abstract Unix socket taku-mirror; no network socket is exposed; touch targets the root host

**Example (Root shell on the phone): **

```sh
am start -n org.agentos.assistant/.MirrorActivity
```

**Expected result / success criteria:** Shows the actual background image; taps/swipes reach only its logical display

**Limitations:**

- Target rate is approximately 5fps maximum; software rendering introduces latency and is not a 60fps video stream.
- Only single-pointer taps/swipes/long presses and limited keys; no automatic Agent pausing, cancellation, keyboard-text forwarding or multi-touch.

**Evidence:** [native-ui-test.txt](../evidence/api-inventory/historical/mirror-screen/evidence/native-ui-test.txt); [native-video-test.txt](../evidence/api-inventory/historical/mirror-screen/evidence/native-video-test.txt); [mirror-native-after-touch.png](../evidence/api-inventory/historical/mirror-screen/evidence/mirror-native-after-touch.png); [mirror-home-concurrent.png](../evidence/api-inventory/historical/mirror-screen/evidence/mirror-home-concurrent.png); [mirror-return-still-running.png](../evidence/api-inventory/historical/mirror-screen/evidence/mirror-return-still-running.png)
**Primary sources:** [Android 16 DisplayManagerService](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/services/core/java/com/android/server/display/DisplayManagerService.java). Access/registration date 2026-10-10.

### Task and multi-window policy changes · `display.window-policy`

**Purpose:** Control app placement, focus, user/Agent handoff and same-app window conflicts
**Tier:** ROM Framework / HAL changes. The intended behavior requires Framework, system-policy or HAL integration; each device requires its own build and verification
**Status:** Research proposal / not yet tested. Not tested; the example is a research entry point, not an installed Taku API
**Version/device:** Minimum declared API No universal declaration; Non-public SDK, custom or device interface; no universal minimum API declaration. This example starts at API No universal declaration. An API declaration is not a device guarantee; check targetSdk and the OEM branch.; Public APIs require corresponding hardware / OEM support; this inventory covers only AgentOS_ImageCheck ARM64, with no verified physical device
**Entry point:** ActivityTaskManagerService / WindowManager
**Permissions/authorization:** ActivityTaskManager / WindowManager / display-policy permissions and code: Pin an AOSP branch and design task preemption/pause events and window policy; existing CLI flags alone are insufficient

**Example (Read-only source-location command in an AOSP checkout; not evidence of implemented functionality): **

```sh
rg --threads 2 "setLaunchDisplayId|isActivityStartAllowedOnDisplay" frameworks/base -g "*.java"
```

**Expected result / success criteria:** Read-only location of display-launch restrictions; requires separate implementation and app regression testing

**Limitations:**

- App multi-window, resizeable and singleTask behavior affect results; two simultaneous instances of every app are not promised.
- No implemented pause/cancel/handover event specification yet.

**Evidence:** No execution evidence yet.
**Primary sources:** [Android multi-window support](https://developer.android.com/develop/ui/views/layout/support-multi-window-mode); [Android 16 DisplayManagerService](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/services/core/java/com/android/server/display/DisplayManagerService.java). Access/registration date 2026-10-10.

### Microphone and speech capture · `audio.microphone`

**Purpose:** Meeting notes and spoken Agent input
**Tier:** Ordinary app; no flashing required. The basic capability does not require flashing; the current experimental implementation may use higher privileges
**Status:** Research proposal / not yet tested. Not tested; the example is a research entry point, not an installed Taku API
**Version/device:** Minimum declared API 3; First public SDK version of the interface class (not the minimum for every behavior described here). This example starts at API 23. An API declaration is not a device guarantee; check targetSdk and the OEM branch.; Public APIs require corresponding hardware / OEM support; this inventory covers only AgentOS_ImageCheck ARM64, with no verified physical device
**Entry point:** AudioRecord.Builder
**Permissions/authorization:** Runtime RECORD_AUDIO: Authorize in the foreground and configure recording, routing and lifecycle; newer Android versions restrict background starts; background use needs a valid microphone FGS type: Authorize in the foreground and configure recording, routing and lifecycle; newer Android versions restrict background starts

**Example (Kotlin method snippet in an ordinary Android project; complete Manifest, permissions and lifecycle as documented; not a directly executable CLI): **

```kotlin
val record = AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.MIC).setAudioFormat(AudioFormat.Builder().setSampleRate(16000).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build()).setBufferSizeInBytes(4096).build()
record.startRecording()
```

**Expected result / success criteria:** Only non-silent PCM establishes input; the example needs a complete read and stop/release lifecycle

**Limitations:**

- Permission does not establish correct concurrency, lock-screen or Bluetooth routing; this does not include both sides of a call.
- This project has no acceptance using real microphone samples.

**Evidence:** No execution evidence yet.
**Primary sources:** [android.media.AudioRecord](https://developer.android.com/reference/android/media/AudioRecord); [Restrictions on starting foreground services from the background](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start). Access/registration date 2026-10-10.

### Capture another app's playback with public APIs · `audio.playback`

**Purpose:** Captions, study notes and understanding playback content
**Tier:** Ordinary app; no flashing required. The basic capability does not require flashing; the current experimental implementation may use higher privileges
**Status:** Research proposal / not yet tested. Not tested; the example is a research entry point, not an installed Taku API
**Version/device:** Minimum declared API 29; Key method used by the example / project target version. This example starts at API 29. An API declaration is not a device guarantee; check targetSdk and the OEM branch.; Public APIs require corresponding hardware / OEM support; this inventory covers only AgentOS_ImageCheck ARM64, with no verified physical device
**Entry point:** AudioPlaybackCaptureConfiguration.Builder
**Permissions/authorization:** RECORD_AUDIO + MediaProjection session: Same user; playback app permits capture and uses an eligible usage; create AudioRecord after user authorizes projection

**Example (Kotlin method snippet in an ordinary Android project; complete Manifest, permissions and lifecycle as documented; not a directly executable CLI): **

```kotlin
val config = AudioPlaybackCaptureConfiguration.Builder(projection).addMatchingUsage(AudioAttributes.USAGE_MEDIA).build()
val capture = AudioRecord.Builder().setAudioFormat(format).setAudioPlaybackCaptureConfig(config).build()
```

**Expected result / success criteria:** An eligible player produces PCM; record denial/silence boundaries

**Limitations:**

- API29; results cannot be generalized to apps that disallow capture, call usages or other excluded sources.
- Not yet tested; root does not imply arbitrary access to protected audio tracks.

**Evidence:** No execution evidence yet.
**Primary sources:** [android.media.AudioPlaybackCaptureConfiguration](https://developer.android.com/reference/android/media/AudioPlaybackCaptureConfiguration); [Audio playback capture](https://developer.android.com/media/platform/av-capture). Access/registration date 2026-10-10.

### Research: both sides of cellular / VoIP calls · `audio.calls`

**Purpose:** Device research for explicitly requested call assistance and summaries
**Tier:** ROM Framework / HAL changes. The intended behavior requires Framework, system-policy or HAL integration; each device requires its own build and verification
**Status:** Research proposal / not yet tested. Not tested; the example is a research entry point, not an installed Taku API
**Version/device:** Minimum declared API No universal declaration; Non-public SDK, custom or device interface; no universal minimum API declaration. This example starts at API No universal declaration. An API declaration is not a device guarantee; check targetSdk and the OEM branch.; Public APIs require corresponding hardware / OEM support; this inventory covers only AgentOS_ImageCheck ARM64, with no verified physical device
**Entry point:** AudioPolicy / Audio HAL
**Permissions/authorization:** AudioPolicy / Audio HAL / routing: Select the device and routing first, then prove non-silent RX/TX in test calls without reading real calls; may require protected permissions such as CAPTURE_AUDIO_OUTPUT: Select the device and routing first, then prove non-silent RX/TX in test calls without reading real calls

**Example (Read-only source-location command in an AOSP checkout; not evidence of implemented functionality): **

```sh
rg --threads 2 "VOICE_CALL|VOICE_UPLINK|VOICE_DOWNLINK" frameworks/av -g "*.cpp" -g "*.h"
```

**Expected result / success criteria:** Locates call/routing definitions; source research only, producing no audio tracks

**Limitations:**

- Cellular and VoIP use different paths; verify carrier, vendor HAL and headset modes separately.
- No universal all-phone support promise or implemented interface.

**Evidence:** No execution evidence yet.
**Primary sources:** [AOSP Audio HAL](https://source.android.com/docs/core/audio/implement). Access/registration date 2026-10-10.

### Own-track effects / research into system input effects · `audio.effects`

**Purpose:** Noise reduction, clarity and entertainment effects
**Tier:** ROM Framework / HAL changes. The intended behavior requires Framework, system-policy or HAL integration; each device requires its own build and verification
**Status:** Research proposal / not yet tested. Not tested; the example is a research entry point, not an installed Taku API
**Version/device:** Minimum declared API No universal declaration; Non-public SDK, custom or device interface; no universal minimum API declaration. This example starts at API No universal declaration. An API declaration is not a device guarantee; check targetSdk and the OEM branch.; Public APIs require corresponding hardware / OEM support; this inventory covers only AgentOS_ImageCheck ARM64, with no verified physical device
**Entry point:** AudioEffect / AudioFlinger / Effects HAL
**Permissions/authorization:** Public effects apply to owned audio sessions: First test AEC/NS on an owned AudioRecord session; cross-app/Bluetooth/cellular paths require device integration; cross-app paths require AudioPolicy/HAL: First test AEC/NS on an owned AudioRecord session; cross-app/Bluetooth/cellular paths require device integration

**Example (Read-only source-location command in an AOSP checkout; not evidence of implemented functionality): **

```sh
rg --threads 2 "EFFECT_TYPE_NS|EFFECT_TYPE_AEC" frameworks/av -g "*.h" -g "*.cpp"
```

**Expected result / success criteria:** Locates effect types/services; system-wide unified effects remain research

**Limitations:**

- Effects on an owned session are not a system-wide microphone modification.
- Latency, echo, offload and low-power routing are untested.

**Evidence:** No execution evidence yet.
**Primary sources:** [AOSP Audio HAL](https://source.android.com/docs/core/audio/implement). Access/registration date 2026-10-10.

### Ordinary camera preview / real-time understanding · `camera.read`

**Purpose:** Object recognition, reading paper documents and visual agents
**Tier:** Ordinary app; no flashing required. The basic capability does not require flashing; the current experimental implementation may use higher privileges
**Status:** Research proposal / not yet tested. Not tested; the example is a research entry point, not an installed Taku API
**Version/device:** Minimum declared API 21; Key method used by the example / project target version. This example starts at API 23. An API declaration is not a device guarantee; check targetSdk and the OEM branch.; Public APIs require corresponding hardware / OEM support; this inventory covers only AgentOS_ImageCheck ARM64, with no verified physical device
**Entry point:** CameraManager.cameraIdList / openCamera
**Permissions/authorization:** Runtime CAMERA: Authorize CAMERA, enumerate cameraId, and close sessions/devices correctly; background camera FGS follows version-specific rules: Authorize CAMERA, enumerate cameraId, and close sessions/devices correctly

**Example (Kotlin method snippet in an ordinary Android project; complete Manifest, permissions and lifecycle as documented; not a directly executable CLI): **

```kotlin
val manager = getSystemService(CameraManager::class.java)
Log.i("TakuProbe", "cameras=${manager.cameraIdList.toList()}")
```

**Expected result / success criteria:** Enumerates camera IDs; preview additionally requires callbacks, a Surface and working hardware

**Limitations:**

- Enumeration does not capture a frame; an emulator's virtual camera is not a physical device.
- Concurrency, heat, power consumption and background restrictions are untested.

**Evidence:** No execution evidence yet.
**Primary sources:** [android.hardware.camera2.CameraManager](https://developer.android.com/reference/android/hardware/camera2/CameraManager); [AOSP camera architecture](https://source.android.com/docs/core/camera); [Restrictions on starting foreground services from the background](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start). Access/registration date 2026-10-10.

### Research: cross-app camera enhancement / replacement image pipeline · `camera.global-filter`

**Purpose:** Let third-party video apps consume system-processed images
**Tier:** ROM Framework / HAL changes. The intended behavior requires Framework, system-policy or HAL integration; each device requires its own build and verification
**Status:** Research proposal / not yet tested. Not tested; the example is a research entry point, not an installed Taku API
**Version/device:** Minimum declared API No universal declaration; Non-public SDK, custom or device interface; no universal minimum API declaration. This example starts at API No universal declaration. An API declaration is not a device guarantee; check targetSdk and the OEM branch.; Public APIs require corresponding hardware / OEM support; this inventory covers only AgentOS_ImageCheck ARM64, with no verified physical device
**Entry point:** CameraService / Camera HAL
**Permissions/authorization:** CameraService / Camera HAL / GPU buffer pipeline: True cross-app processing requires device HAL or system-service integration; CAMERA permission alone cannot change other apps' output

**Example (Read-only source-location command in an AOSP checkout; not evidence of implemented functionality): **

```sh
rg --threads 2 "processCaptureRequest|configureStreams" hardware/interfaces/camera -g "*.aidl" -g "*.hal"
```

**Expected result / success criteria:** Locates the HAL stream contract; not a completed enhancement module

**Limitations:**

- Formats, color, protected buffers, concurrency and vendor implementation determine difficulty.
- Discussed only as an extension example, not initial product scope or implemented functionality.

**Evidence:** No execution evidence yet.
**Primary sources:** [AOSP camera architecture](https://source.android.com/docs/core/camera). Access/registration date 2026-10-10.

### Research: low-power voice wake-up · `voice.hotword`

**Purpose:** Invoke the Agent without touch
**Tier:** ROM Framework / HAL changes. The intended behavior requires Framework, system-policy or HAL integration; each device requires its own build and verification
**Status:** Research proposal / not yet tested. Not tested; the example is a research entry point, not an installed Taku API
**Version/device:** Minimum declared API No universal declaration; Non-public SDK, custom or device interface; no universal minimum API declaration. This example starts at API No universal declaration. An API declaration is not a device guarantee; check targetSdk and the OEM branch.; Public APIs require corresponding hardware / OEM support; this inventory covers only AgentOS_ImageCheck ARM64, with no verified physical device
**Entry point:** SoundTrigger / HotwordDetectionService
**Permissions/authorization:** SoundTrigger HAL / DSP support: The target chip must support soundtrigger and a deployable model; implement HotwordDetectionService or a vendor path; default VoiceInteraction role: The target chip must support soundtrigger and a deployable model; implement HotwordDetectionService or a vendor path

**Example (Read-only source-location command in an AOSP checkout; not evidence of implemented functionality): **

```sh
rg --threads 2 "SoundTrigger" hardware/interfaces/soundtrigger -g "*.aidl" -g "*.hal"
```

**Expected result / success criteria:** Locates hardware wake-up services; an emulator cannot establish a low-power DSP path

**Limitations:**

- Continuous AudioRecord capture is not low-power wake-up.
- Speech recognition is not currently implemented; no physical-chip/model acceptance exists.

**Evidence:** No execution evidence yet.
**Primary sources:** [AOSP SoundTrigger](https://source.android.com/docs/core/audio/sound-trigger); [VoiceInteractionService](https://developer.android.com/reference/android/service/voice/VoiceInteractionService). Access/registration date 2026-10-10.

### Location and location context · `environment.location`

**Purpose:** Arrival reminders and related travel tasks
**Tier:** Ordinary app; no flashing required. The basic capability does not require flashing; the current experimental implementation may use higher privileges
**Status:** Research proposal / not yet tested. Not tested; the example is a research entry point, not an installed Taku API
**Version/device:** Minimum declared API 1; First public SDK version of the interface class (not the minimum for every behavior described here). This example starts at API 23. An API declaration is not a device guarantee; check targetSdk and the OEM branch.; Public APIs require corresponding hardware / OEM support; this inventory covers only AgentOS_ImageCheck ARM64, with no verified physical device
**Entry point:** LocationManager.getLastKnownLocation
**Permissions/authorization:** ACCESS_COARSE/FINE_LOCATION: Authorize runtime permissions separately; background location is not covered by the foreground grant; Google Play Services can provide geofencing but is unverified in this image; background use separately requires ACCESS_BACKGROUND_LOCATION: Authorize runtime permissions separately; background location is not covered by the foreground grant; Google Play Services can provide geofencing but is unverified in this image

**Example (Kotlin method snippet in an ordinary Android project; complete Manifest, permissions and lifecycle as documented; not a directly executable CLI): **

```kotlin
val location = getSystemService(LocationManager::class.java).getLastKnownLocation(LocationManager.GPS_PROVIDER)
Log.i("TakuProbe", "hasFix=${location != null}")
```

**Expected result / success criteria:** Reads only whether a historical fix exists, without collecting a personal route; real-time location requires separate testing

**Limitations:**

- An old fix is not current location; indoor behavior, background frequency, OEM policy and battery use need testing.
- Injected emulator locations do not establish real-world accuracy; untested.

**Evidence:** No execution evidence yet.
**Primary sources:** [android.location.LocationManager](https://developer.android.com/reference/android/location/LocationManager); [Android Manifest permission reference](https://developer.android.com/reference/android/Manifest.permission). Access/registration date 2026-10-10.

### Sensors and activity recognition · `environment.sensors`

**Purpose:** Motion/posture assistance and context-based interaction
**Tier:** Ordinary app; no flashing required. The basic capability does not require flashing; the current experimental implementation may use higher privileges
**Status:** Research proposal / not yet tested. Not tested; the example is a research entry point, not an installed Taku API
**Version/device:** Minimum declared API 1; First public SDK version of the interface class (not the minimum for every behavior described here). This example starts at API 23. An API declaration is not a device guarantee; check targetSdk and the OEM branch.; Public APIs require corresponding hardware / OEM support; this inventory covers only AgentOS_ImageCheck ARM64, with no verified physical device
**Entry point:** SensorManager.getSensorList
**Permissions/authorization:** Most ordinary sensors need no runtime permission: Enumerate device support first; register listeners as the algorithm requires; sensor values alone are not behavioral facts; steps/activity need ACTIVITY_RECOGNITION: Enumerate device support first; register listeners as the algorithm requires; sensor values alone are not behavioral facts; high rates need HIGH_SAMPLING_RATE_SENSORS: Enumerate device support first; register listeners as the algorithm requires; sensor values alone are not behavioral facts

**Example (Kotlin method snippet in an ordinary Android project; complete Manifest, permissions and lifecycle as documented; not a directly executable CLI): **

```kotlin
val sensors = getSystemService(SensorManager::class.java).getSensorList(Sensor.TYPE_ALL)
Log.i("TakuProbe", "types=${sensors.map { it.type }}")
```

**Expected result / success criteria:** Returns supported types; semantic activity recognition needs an algorithm or another API

**Limitations:**

- Emulator sensors differ from physical devices; newer versions restrict high sampling rates/background operation.
- Walking/driving recognition has not passed acceptance.

**Evidence:** No execution evidence yet.
**Primary sources:** [android.hardware.SensorManager](https://developer.android.com/reference/android/hardware/SensorManager); [Android Manifest permission reference](https://developer.android.com/reference/android/Manifest.permission). Access/registration date 2026-10-10.

### Bluetooth / local-network device discovery · `environment.nearby`

**Purpose:** Nearby agents, accessories or collaboration nodes
**Tier:** Ordinary app; no flashing required. The basic capability does not require flashing; the current experimental implementation may use higher privileges
**Status:** Research proposal / not yet tested. Not tested; the example is a research entry point, not an installed Taku API
**Version/device:** Minimum declared API 16; Key method used by the example / project target version. This example starts at API 23. An API declaration is not a device guarantee; check targetSdk and the OEM branch.; Public APIs require corresponding hardware / OEM support; this inventory covers only AgentOS_ImageCheck ARM64, with no verified physical device
**Entry point:** NsdManager.discoverServices
**Permissions/authorization:** INTERNET: Use NSD to discover a test service on an authorized LAN; pairing/connection need separate authorization; discovery does not mean connected; Bluetooth API31+ SCAN/CONNECT: Use NSD to discover a test service on an authorized LAN; pairing/connection need separate authorization; discovery does not mean connected; version-appropriate Wi-Fi discovery permissions: Use NSD to discover a test service on an authorized LAN; pairing/connection need separate authorization; discovery does not mean connected

**Example (Kotlin method snippet in an ordinary Android project; complete Manifest, permissions and lifecycle as documented; not a directly executable CLI): **

```kotlin
getSystemService(NsdManager::class.java).discoverServices("_taku._tcp.", NsdManager.PROTOCOL_DNS_SD, discoveryListener)
```

**Expected result / success criteria:** Discovers your published test service; implement the listener and stopDiscovery

**Limitations:**

- API16 NSD does not cover all Bluetooth/Wi-Fi permission requirements.
- No cross-device collaboration protocol or wireless-pairing acceptance exists in this project.

**Evidence:** No execution evidence yet.
**Primary sources:** [android.net.nsd.NsdManager](https://developer.android.com/reference/android/net/nsd/NsdManager); [Android Manifest permission reference](https://developer.android.com/reference/android/Manifest.permission). Access/registration date 2026-10-10.

### Research: USB peripherals and NFC / HCE · `environment.usb-nfc`

**Purpose:** Card readers, sensors and tap-triggered tasks
**Tier:** Ordinary app; no flashing required. The basic capability does not require flashing; the current experimental implementation may use higher privileges
**Status:** Research proposal / not yet tested. Not tested; the example is a research entry point, not an installed Taku API
**Version/device:** Minimum declared API 12; Key method used by the example / project target version. This example starts at API 23. An API declaration is not a device guarantee; check targetSdk and the OEM branch.; Public APIs require corresponding hardware / OEM support; this inventory covers only AgentOS_ImageCheck ARM64, with no verified physical device
**Entry point:** UsbManager.deviceList / NfcAdapter
**Permissions/authorization:** USB user authorization: Check FEATURE_USB_HOST/FEATURE_NFC first; USB requestPermission names the target device; use test peripherals only; NFC Manifest and hardware support: Check FEATURE_USB_HOST/FEATURE_NFC first; USB requestPermission names the target device; use test peripherals only; HCE service binding: Check FEATURE_USB_HOST/FEATURE_NFC first; USB requestPermission names the target device; use test peripherals only

**Example (Kotlin method snippet in an ordinary Android project; complete Manifest, permissions and lifecycle as documented; not a directly executable CLI): **

```kotlin
val devices = getSystemService(UsbManager::class.java).deviceList
Log.i("TakuProbe", "usbCount=${devices.size}")
```

**Expected result / success criteria:** Enumerates peripherals; does not establish connection or protocol read/write success

**Limitations:**

- NFC/HCE does not imply the ability to copy payment secure-element credentials.
- The emulator has no actual USB/NFC peripheral path; HCE API19 and device support need separate tests.

**Evidence:** No execution evidence yet.
**Primary sources:** [android.hardware.usb.UsbManager](https://developer.android.com/reference/android/hardware/usb/UsbManager); [Android Manifest permission reference](https://developer.android.com/reference/android/Manifest.permission). Access/registration date 2026-10-10.

### On-device task execution and state queue · `runtime.broker`

**Purpose:** User request -> actual Agent execution -> state and results
**Tier:** Root / system signing; no Framework changes required. Requires appropriate root access or system signing/preinstallation; deployment on a rooted stock system may be possible, but this distribution path is unverified
**Status:** Verified on an emulator (within the cited evidence). The init service was running in this review; source confirmed a single queue and historical task UI passed; no model task was submitted in this review.
**Version/device:** Minimum declared API 36; Key method used by the example / project target version. This example starts at API 36. An API declaration is not a device guarantee; check targetSdk and the OEM branch.; Public APIs require corresponding hardware / OEM support; this inventory covers only AgentOS_ImageCheck ARM64, with no verified physical device
**Entry point:** TaskRequests / root TaskBroker / tasks.status
**Permissions/authorization:** init root service: Use the existing single TaskRequests queue with init agentos_codex running; do not create a second task queue; system UID TaskRequests: Use the existing single TaskRequests queue with init agentos_codex running; do not create a second task queue; SELinux domain: Use the existing single TaskRequests queue with init agentos_codex running; do not create a second task queue

**Example (Host terminal, explicitly targeting emulator-5582): **

```sh
node sdk/cli/taku.mjs call tasks.status --transport adb --serial emulator-5582
```

**Expected result / success criteria:** The SDK reads the latest task state; reading state does not submit a new AI task

**Limitations:**

- The current broker has a six-minute hard timeout; complete cancellation/preemption/idempotent task recovery after reboot is absent.
- Main/background displays cannot share input arbitrarily; a queued task's success requires actual-result verification.

**Evidence:** [current-readonly.json](../evidence/api-inventory/current-readonly.json); [reboot-ui-test.txt](../evidence/api-inventory/historical/assistant-panel/evidence/desktop/reboot-ui-test.txt)
**Primary sources:** [AOSP SELinux](https://source.android.com/docs/security/features/selinux). Access/registration date 2026-10-10.

### Ordinary background tasks, boot notifications and recovery · `runtime.background-public`

**Purpose:** Periodic organization, synchronization and recovery of owned tasks
**Tier:** Ordinary app; no flashing required. The basic capability does not require flashing; the current experimental implementation may use higher privileges
**Status:** Research proposal / not yet tested. Not tested; the example is a research entry point, not an installed Taku API
**Version/device:** Minimum declared API 21; Key method used by the example / project target version. This example starts at API 23. An API declaration is not a device guarantee; check targetSdk and the OEM branch.; Public APIs require corresponding hardware / OEM support; this inventory covers only AgentOS_ImageCheck ARM64, with no verified physical device
**Entry point:** JobScheduler / BOOT_COMPLETED
**Permissions/authorization:** RECEIVE_BOOT_COMPLETED: Implement JobService/BootReceiver; persistent tasks remain subject to Doze/quotas/OEM limits; design Direct Boot data separately; BIND_JOB_SERVICE binding: Implement JobService/BootReceiver; persistent tasks remain subject to Doze/quotas/OEM limits; design Direct Boot data separately; FGS types and permissions: Implement JobService/BootReceiver; persistent tasks remain subject to Doze/quotas/OEM limits; design Direct Boot data separately

**Example (Kotlin method snippet in an ordinary Android project; complete Manifest, permissions and lifecycle as documented; not a directly executable CLI): **

```kotlin
val info = JobInfo.Builder(42, ComponentName(this, ProbeJobService::class.java)).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).build()
val result = getSystemService(JobScheduler::class.java).schedule(info)
```

**Expected result / success criteria:** RESULT_SUCCESS means the system accepted the job, not that it runs immediately

**Limitations:**

- Ordinary background operation guarantees neither continuous residency nor exact timing; FGS background starts are restricted.
- A running broker and mini-app JSON surviving reboot do not establish automatic recovery of all tasks.

**Evidence:** No execution evidence yet.
**Primary sources:** [android.app.job.JobScheduler](https://developer.android.com/reference/android/app/job/JobScheduler); [Restrictions on starting foreground services from the background](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start); [AOSP file-based encryption](https://source.android.com/docs/security/features/encryption/file-based). Access/registration date 2026-10-10.

### Power, WakeLocks and task scheduling · `runtime.power`

**Purpose:** Background synchronization or short periods of retained computation
**Tier:** Ordinary app; no flashing required. The basic capability does not require flashing; the current experimental implementation may use higher privileges
**Status:** Research proposal / not yet tested. Not tested; the example is a research entry point, not an installed Taku API
**Version/device:** Minimum declared API 1; First public SDK version of the interface class (not the minimum for every behavior described here). This example starts at API 23. An API declaration is not a device guarantee; check targetSdk and the OEM branch.; Public APIs require corresponding hardware / OEM support; this inventory covers only AgentOS_ImageCheck ARM64, with no verified physical device
**Entry point:** PowerManager.newWakeLock
**Permissions/authorization:** Normal WAKE_LOCK permission: Set a timeout and release the lock; long-term battery use needs physical testing; do not rely on an unlimited WakeLock for residency; exemption from battery optimization needs separate special access: Set a timeout and release the lock; long-term battery use needs physical testing; do not rely on an unlimited WakeLock for residency

**Example (Kotlin method snippet in an ordinary Android project; complete Manifest, permissions and lifecycle as documented; not a directly executable CLI): **

```kotlin
val lock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Taku:probe")
lock.acquire(5000)
lock.release()
```

**Expected result / success criteria:** A short WakeLock with a timeout does not remove network/foreground-service restrictions

**Limitations:**

- Battery drain, heat and OEM policy are untested; an emulator cannot demonstrate a day of standby.
- Holding a lock grants no access to another app's content.

**Evidence:** No execution evidence yet.
**Primary sources:** [android.os.PowerManager](https://developer.android.com/reference/android/os/PowerManager). Access/registration date 2026-10-10.

### Research: CPU/GPU/thermal management · `runtime.performance`

**Purpose:** Allocate resources among local inference, interaction and screen recording
**Tier:** ROM Framework / HAL changes. The intended behavior requires Framework, system-policy or HAL integration; each device requires its own build and verification
**Status:** Research proposal / not yet tested. Not tested; the example is a research entry point, not an installed Taku API
**Version/device:** Minimum declared API No universal declaration; Non-public SDK, custom or device interface; no universal minimum API declaration. This example starts at API No universal declaration. An API declaration is not a device guarantee; check targetSdk and the OEM branch.; Public APIs require corresponding hardware / OEM support; this inventory covers only AgentOS_ImageCheck ARM64, with no verified physical device
**Entry point:** task_profiles / Power HAL / Thermal HAL
**Permissions/authorization:** task profiles / cgroup / Power & Thermal HAL: Pin device HAL capabilities and kernel interfaces; optimize only within thermal-protection limits

**Example (Read-only source-location command in an AOSP checkout; not evidence of implemented functionality): **

```sh
rg --threads 2 "SetTaskProfiles|Thermal" system/core -g "*.cpp" -g "*.json"
```

**Expected result / success criteria:** Locates system scheduling interfaces; not an existing runtime-tuning capability

**Limitations:**

- Disabling thermal protection is not sustainable optimization; permissions cannot substitute for proprietary vendor capabilities.
- No physical-device performance, battery or thermal conclusions are available.

**Evidence:** No execution evidence yet.
**Primary sources:** [AOSP Thermal HAL](https://source.android.com/docs/core/power/thermal-mitigation). Access/registration date 2026-10-10.

### Research: network state, VPN and per-app networking · `network.vpn`

**Purpose:** Connection recovery, routing and diagnostics
**Tier:** Ordinary app; no flashing required. The basic capability does not require flashing; the current experimental implementation may use higher privileges
**Status:** Research proposal / not yet tested. Not tested; the example is a research entry point, not an installed Taku API
**Version/device:** Minimum declared API 14; Key method used by the example / project target version. This example starts at API 14. An API declaration is not a device guarantee; check targetSdk and the OEM branch.; Public APIs require corresponding hardware / OEM support; this inventory covers only AgentOS_ImageCheck ARM64, with no verified physical device
**Entry point:** ConnectivityManager / VpnService.prepare
**Permissions/authorization:** INTERNET: Obtain consent after VpnService.prepare; establish tun and forward traffic correctly; concurrent configurations remain limited by the system; BIND_VPN_SERVICE binding and user VPN consent: Obtain consent after VpnService.prepare; establish tun and forward traffic correctly; concurrent configurations remain limited by the system

**Example (Kotlin method snippet in an ordinary Android project; complete Manifest, permissions and lifecycle as documented; not a directly executable CLI): **

```kotlin
val consent = VpnService.prepare(this)
if (consent != null) startActivityForResult(consent, 103)
```

**Expected result / success criteria:** Shows VPN authorization; without a tun interface this does not establish traffic proxying

**Limitations:**

- Seeing VPN packets does not reveal HTTPS plaintext; TLS and server authorization still apply.
- Complete per-app routing policy and network measurements are not implemented.

**Evidence:** No execution evidence yet.
**Primary sources:** [android.net.VpnService](https://developer.android.com/reference/android/net/VpnService); [Android Manifest permission reference](https://developer.android.com/reference/android/Manifest.permission). Access/registration date 2026-10-10.

### Research: cross-device Agent collaboration · `runtime.cross-device`

**Purpose:** Continue work between computers and phones; remote developer calls
**Tier:** Ordinary app; no flashing required. The basic capability does not require flashing; the current experimental implementation may use higher privileges
**Status:** Research proposal / not yet tested. Not tested; the example is a research entry point, not an installed Taku API
**Version/device:** Minimum declared API 23; Key method used by the example / project target version. This example starts at API 23. An API declaration is not a device guarantee; check targetSdk and the OEM branch.; Public APIs require corresponding hardware / OEM support; this inventory covers only AgentOS_ImageCheck ARM64, with no verified physical device
**Entry point:** Custom protocol / HTTP / event stream
**Permissions/authorization:** INTERNET: Network transport is available; remote app interfaces need authentication/reconnection/conflict handling; a localhost development API is not a public Internet API; custom identity/authorization/synchronization protocol: Network transport is available; remote app interfaces need authentication/reconnection/conflict handling; a localhost development API is not a public Internet API

**Example (Kotlin method snippet in an ordinary Android project; complete Manifest, permissions and lifecycle as documented; not a directly executable CLI): **

```kotlin
// Transport probe only, not a synchronization/authorization protocol
val connected = getSystemService(ConnectivityManager::class.java).activeNetwork != null
```

**Expected result / success criteria:** Reads whether an active network exists; cross-device tasks still require a protocol

**Limitations:**

- Android has no one-click maximum privilege grant; account isolation and protocol conflict handling remain unimplemented.
- This project's experimental 127.0.0.1 API does not mean an OS remote-developer API is exposed.

**Evidence:** No execution evidence yet.
**Primary sources:** [android.net.ConnectivityManager](https://developer.android.com/reference/android/net/ConnectivityManager). Access/registration date 2026-10-10.

### System diagnostics and performance tracing · `diagnostics.system`

**Purpose:** Diagnose execution failures, app video anomalies and system state
**Tier:** ADB / Shizuku; no custom ROM required. No custom ROM required; authorized ADB/shell access is needed; this project has not tested Shizuku integration
**Status:** Verified on an emulator (within the cited evidence). Package/service diagnostics were actually read in this review; Perfetto was not run and the Douyin root cause remains unknown.
**Version/device:** Minimum declared API 36; Key method used by the example / project target version. This example starts at API 36. An API declaration is not a device guarantee; check targetSdk and the OEM branch.; Public APIs require corresponding hardware / OEM support; this inventory covers only AgentOS_ImageCheck ARM64, with no verified physical device
**Entry point:** dumpsys / getprop / Perfetto
**Permissions/authorization:** Shell DUMP / log access: Developer-authorized ADB; limit target services and test logs rather than collecting personal content in bulk; ordinary apps can access their own logs: Developer-authorized ADB; limit target services and test logs rather than collecting personal content in bulk

**Example (Root shell on the phone): **

```sh
dumpsys package org.agentos.apiboard
getprop init.svc.agentos_codex
```

**Expected result / success criteria:** Reads the target package and broker service; Perfetto needs separate trace configuration

**Limitations:**

- Logs may contain user content and must be scoped; they are not a complete record of the user's day.
- Standard HEVC paths passed; Douyin rendering remains unexplained and must not be mistaken for general codec failure.

**Evidence:** [current-readonly.json](../evidence/api-inventory/current-readonly.json); [compatibility-report.json](../evidence/api-inventory/historical/app-compat/compatibility-report.json)
**Primary sources:** [Official Perfetto documentation](https://perfetto.dev/docs/quickstart/android-tracing). Access/registration date 2026-10-10.

### Research: ROM builds, OTA and recovery · `release.ota`

**Purpose:** Let users flash images and upgrade reliably
**Tier:** ROM Framework / HAL changes. The intended behavior requires Framework, system-policy or HAL integration; each device requires its own build and verification
**Status:** Research proposal / not yet tested. Not tested; the example is a research entry point, not an installed Taku API
**Version/device:** Minimum declared API No universal declaration; Non-public SDK, custom or device interface; no universal minimum API declaration. This example starts at API No universal declaration. An API declaration is not a device guarantee; check targetSdk and the OEM branch.; Public APIs require corresponding hardware / OEM support; this inventory covers only AgentOS_ImageCheck ARM64, with no verified physical device
**Entry point:** AOSP build / update_engine / AVB
**Permissions/authorization:** Device signing / AVB / bootloader / update_engine / recovery: Choose an unlockable model and device/vendor trees; establish reproducible build, signing, upgrade and rollback experiments; never distribute userdata login state

**Example (Read-only source-location command in an AOSP checkout; not evidence of implemented functionality): **

```sh
rg --threads 2 "PRODUCT_PACKAGES|PRODUCT_COPY_FILES" device -g "*.mk"
```

**Expected result / success criteria:** Inspect packaging configuration in the pinned device source; this does not establish an available ROM release

**Limitations:**

- The latest assistant/host/mini-app changes had not been exported into a complete ROM; no physical-device verification in this inventory.
- Verify bootloader relocking with custom signatures per device; system rollback and data rollback are distinct.

**Evidence:** No execution evidence yet.
**Primary sources:** [AOSP OTA](https://source.android.com/docs/core/ota); [AOSP file-based encryption](https://source.android.com/docs/security/features/encryption/file-based). Access/registration date 2026-10-10.

### Generate, preview and export standalone APKs on the phone · `apps.generate`

**Purpose:** Have Codex write arbitrary application core code and turn it into a new tool
**Tier:** Root / system signing; no Framework changes required. Requires appropriate root access or system signing/preinstallation; deployment on a rooted stock system may be possible, but this distribution path is unverified
**Status:** Verified on an emulator (within the cited evidence). Historical on-device generation/preview of two apps and launch of a standalone game APK; this review matched both APK signatures/hashes to installed files.
**Version/device:** Minimum declared API 30; Key method used by the example / project target version. This example starts at API 30. Exported shell minSdk30/target35; preview host minSdk26; the complete toolchain was tested only on API36 ARM64.; Public APIs require corresponding hardware / OEM support; this inventory covers only AgentOS_ImageCheck ARM64, with no verified physical device
**Entry point:** generate.sh / build.sh / package-app.sh
**Permissions/authorization:** Current root build/install scripts: React, esbuild, aapt, the DEX shell and test-signing tools are present on the phone; App.jsx/api.mjs contain unrestricted application code; the user signs in to the Agent for code generation; ordinary web pages do not gain root themselves: React, esbuild, aapt, the DEX shell and test-signing tools are present on the phone; App.jsx/api.mjs contain unrestricted application code; the user signs in to the Agent for code generation; requires a preinstalled toolchain: React, esbuild, aapt, the DEX shell and test-signing tools are present on the phone; App.jsx/api.mjs contain unrestricted application code; the user signs in to the Agent for code generation

**Example (Root shell on the phone): **

```sh
cd /data/local/agentos/light-app-lab/free-app-kit
# apps/myapp requires main.jsx/ui.jsx and actual App.jsx; APIs additionally require api.mjs
/system/bin/sh build.sh myapp --no-open
/system/bin/sh package-app.sh myapp org.agentos.myapp MyApp
```

**Expected result / success criteria:** The generated app.apk passes signature verification, installs with pm and starts a real Activity; preview does not replace standalone APK acceptance

**Limitations:**

- The initial host/DEX shell is precompiled on a computer; subsequent core source and JSX/APK packaging occur on the phone.
- App source and toolchain/runtime initialization are still required; a packaged shell does not support arbitrary native SDK functionality.
- Figures such as 760ms describe one packaging run, excluding AI generation, installation and first frame; they are not performance guarantees.

**Evidence:** [apk-verification.json](../evidence/api-inventory/apk-verification.json); [standalone-build.txt](../evidence/api-inventory/historical/light-app-lab/free-app-kit/evidence/standalone-build.txt); [standalone-open-test.txt](../evidence/api-inventory/historical/light-app-lab/free-app-kit/evidence/standalone-open-test.txt)
**Primary sources:** [PackageInstaller SessionParams](https://developer.android.com/reference/android/content/pm/PackageInstaller.SessionParams). Access/registration date 2026-10-10.

### Expose generated-app business functions / external calls · `apps.business-api`

**Purpose:** Let the Agent invoke functions/state directly and synchronize the UI after external calls
**Tier:** Root / system signing; no Framework changes required. Requires appropriate root access or system signing/preinstallation; deployment on a rooted stock system may be possible, but this distribution path is unverified
**Status:** Verified on an emulator (within the cited evidence). Historical HTTP parameter/error/idempotency checks, API-to-UI and standalone board tap-to-API readback passed; this review did not invoke business state.
**Version/device:** Minimum declared API 36; Key method used by the example / project target version. This example starts at API 36. An API declaration is not a device guarantee; check targetSdk and the OEM branch.; Public APIs require corresponding hardware / OEM support; this inventory covers only AgentOS_ImageCheck ARM64, with no verified physical device
**Entry point:** mini-app / loopback HTTP / OpenAPI
**Permissions/authorization:** Experimental root Node service: api.mjs implements arbitrary methods and parameter/result schemas; service at 127.0.0.1:8765; UI/CLI share state; start the service first; INTERNET for loopback: api.mjs implements arbitrary methods and parameter/result schemas; service at 127.0.0.1:8765; UI/CLI share state; start the service first; app-specific business permissions: api.mjs implements arbitrary methods and parameter/result schemas; service at 127.0.0.1:8765; UI/CLI share state; start the service first

**Example (Root shell on the phone): **

```sh
/data/local/agentos/light-app-lab/free-app-kit/mini-app describe converter
/data/local/agentos/light-app-lab/free-app-kit/mini-app call converter length.convert '{"value":100,"from":"cm","to":"m"}'
```

**Expected result / success criteria:** ok=true and value=1 m; invalid params/methods return structured errors; HTTP200 alone is not success

**Limitations:**

- An application business-API experiment, not every low-level Android API.
- The service has no independent boot autostart; loopback development only, not public Internet deployment; interface scoping still needs productization.

**Evidence:** [external-http-verification.json](../evidence/api-inventory/historical/light-app-lab/free-app-kit/evidence/api/external-http-verification.json); [standalone-ui-to-api.json](../evidence/api-inventory/historical/light-app-lab/free-app-kit/evidence/api/standalone-ui-to-api.json); [live-ui-update-test.txt](../evidence/api-inventory/historical/light-app-lab/free-app-kit/evidence/api/live-ui-update-test.txt)
**Primary sources:** [Android Manifest permission reference](https://developer.android.com/reference/android/Manifest.permission). Access/registration date 2026-10-10.

### On-phone Codex CLI and low-level shell · `agent.cli`

**Purpose:** Execute code, generate apps and call exposed capabilities on the device
**Tier:** Root / system signing; no Framework changes required. Requires appropriate root access or system signing/preinstallation; deployment on a rooted stock system may be possible, but this distribution path is unverified
**Status:** Verified on an emulator (within the cited evidence). CLI version read in this review; historical device logs of JSX source writes/build commands were checked; authentication directories were not read.
**Version/device:** Minimum declared API 36; Key method used by the example / project target version. This example starts at API 36. An API declaration is not a device guarantee; check targetSdk and the OEM branch.; Public APIs require corresponding hardware / OEM support; this inventory covers only AgentOS_ImageCheck ARM64, with no verified physical device
**Entry point:** /system_ext/bin/codex exec / Android shell
**Permissions/authorization:** Current root CLI: CLI is preinstalled; authentication resides only in device data; Android /system/bin/sh is mksh, not GNU Bash; never package authentication into the image; users sign in to their own Agent account: CLI is preinstalled; authentication resides only in device data; Android /system/bin/sh is mksh, not GNU Bash; never package authentication into the image; SELinux remains Enforcing: CLI is preinstalled; authentication resides only in device data; Android /system/bin/sh is mksh, not GNU Bash; never package authentication into the image

**Example (Root shell on the phone): **

```sh
/system_ext/bin/codex --version
# This new-task example calls a model; choose an explicit test scenario
/system_ext/bin/codex exec --skip-git-repo-check -C /data/local/agentos/work "Read and report the Android system version without operating apps" </dev/null
```

**Expected result / success criteria:** Outputs the version; model success requires actual task and command results, not just a process exit code

**Limitations:**

- This review ran only --version; historical model calls are not fresh acceptance.
- A version, login or executable program does not establish availability of all Codex features, phone hardware or app permissions.

**Evidence:** [current-readonly.json](../evidence/api-inventory/current-readonly.json); [generation-command-audit.json](../evidence/api-inventory/generation-command-audit.json)
**Primary sources:** [AOSP SELinux](https://source.android.com/docs/security/features/selinux). Access/registration date 2026-10-10.

### Query main/background displays through the SDK · `displays.status`

**Purpose:** Let external developers discover actual logical display IDs and whether a background display is active
**Tier:** ADB / Shizuku; no custom ROM required. No custom ROM required; authorized ADB/shell access is needed; this project has not tested Shizuku integration
**Status:** Verified on an emulator (within the cited evidence). This review's end-to-end SDK call read displayIds [0,11] and active background display 11; that ID must not be hard-coded.
**Version/device:** Minimum declared API 36; Key method used by the example / project target version. This example starts at API 36. An API declaration is not a device guarantee; check targetSdk and the OEM branch.; Public APIs require corresponding hardware / OEM support; this inventory covers only AgentOS_ImageCheck ARM64, with no verified physical device
**Entry point:** Taku SDK displays.status
**Permissions/authorization:** Authorized ADB: SDK adb transport reads display service/existing host state only, with an AVD/serial allowlist; root experimental phone-display state file: SDK adb transport reads display service/existing host state only, with an AVD/serial allowlist

**Example (Host terminal, explicitly targeting emulator-5582): **

```sh
node sdk/cli/taku.mjs call displays.status --transport adb --serial emulator-5582
```

**Expected result / success criteria:** Returns actual displayIds and background.active; IDs are assigned by the system

**Limitations:**

- Queries do not create displays, capture frames, inject input or expose arbitrary shell execution.
- The current SDK device-report example was verified only on an API36 emulator.

**Evidence:** [sdk-device-report.json](../evidence/sdk/sdk-device-report.json)
**Primary sources:** [Android 16 DisplayManagerService](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/services/core/java/com/android/server/display/DisplayManagerService.java). Access/registration date 2026-10-10.

### Query existing task state through the SDK · `tasks.status`

**Purpose:** Let third-party extensions show live status without inventing another task system
**Tier:** Root / system signing; no Framework changes required. Requires appropriate root access or system signing/preinstallation; deployment on a rooted stock system may be possible, but this distribution path is unverified
**Status:** Verified on an emulator (within the cited evidence). This review's device-report actually read five existing id/state records; includesPrompts=false and includesResults=false.
**Version/device:** Minimum declared API 36; Key method used by the example / project target version. This example starts at API 36. An API declaration is not a device guarantee; check targetSdk and the OEM branch.; Public APIs require corresponding hardware / OEM support; this inventory covers only AgentOS_ImageCheck ARM64, with no verified physical device
**Entry point:** Taku SDK tasks.status
**Permissions/authorization:** Current ADB/root access reads existing TaskBroker data only: Explicitly target and query serially; return id/state only, excluding prompts/results, authentication and personal information; a new transport is not implemented: Explicitly target and query serially; return id/state only, excluding prompts/results, authentication and personal information

**Example (Host terminal, explicitly targeting emulator-5582): **

```sh
node sdk/cli/taku.mjs call tasks.status --transport adb --serial emulator-5582
```

**Expected result / success criteria:** Returns existing TaskBroker task id/state and source; errors must be structured

**Limitations:**

- Five historical completed IDs were read; this is not evidence of new task execution.
- No generic SDK for event subscription or task submit/cancel/handover currently exists.

**Evidence:** [sdk-device-report.json](../evidence/sdk/sdk-device-report.json)
**Primary sources:** [AOSP SELinux](https://source.android.com/docs/security/features/selinux). Access/registration date 2026-10-10.
