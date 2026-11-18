# Raw Android image interface reference

**Exploring what a phone can do and possible Cases? Start with the [one-page capability map](https://docs.taku.build/capabilities/overview/).** Use this page for specific interfaces, permissions, and sources.

This document answers one question: **Before adding Taku Agents, wrappers, and plugins, what entry points does Android itself provide?** Use it to explore products, then decide what needs wrapping or extension support.

Unlike the site's [57-capability inventory](https://docs.taku.build/reference/generated/), organized by tasks and tested evidence, this page follows **raw interface declarations, permissions, and source**. The explorer indexes individual declarations instead of presenting a handwritten table as all Android APIs.

Status scope updated 2026-10-19: this is an interface index from pinned sources and candidate artifacts, not live project acceptance. The older candidate built fully on October 11, installed on Pixel 9 on October 14, and gained partial physical-component/cold-boot recovery acceptance on October 15. These do not turn all indexed interfaces into tested passes. Latest complete-ROM build still records failure; see [current progress](current-status.md) for current source/emulator/physical-device/full-package scope.

## Pinned baseline and completeness

Baseline: **LineageOS 23.2 / Android 16 / API 36**, used for the Pixel 9 `tokay` development candidate. Raw interfaces are extracted from pinned upstream `HEAD` Git blobs, excluding uncommitted Taku changes. This is neither Google's stock Pixel system nor the common superset of every Android brand.

Distinguish two evidence categories:

- **Upstream declarations**: public/system/module/test API signatures, permission XML, Binder AIDL, HAL AIDL/HIDL, native symbols, Bionic syscall mappings, UAPI headers, system properties, and command-help source.
- **Candidate build outputs**: non-SDK descriptors in `hiddenapi-flags.csv` and program names in partition `bin` directories. These come from integrated-candidate generated artifacts, separately marked, and are not runtime validation of an unmodified original image.

Separately marked **Android SDK 36 revision 2 annotation supplements** come from installed local SDK `annotations.zip`, preserving precise `RequiresPermission`, `RequiresFeature`, `RequiresApi`, and parameter annotations. They differ in revision from Lineage source and only help locate permissions/conditions; they cannot replace service-implementation inspection.

**“The API is declared,” “the process may call it,” “the device implements it,” and “the result is correct” are four different claims.** Batch extraction proves only the first or artifact-file existence, adding no emulator/device passes.

“Complete” here means an auditable scope: every matched file has path, revision, SHA256; every managed API declaration line remains, including unclassified lines. Nothing is removed for pagination, high privilege, or deprecated methods. Counts include classes, constructors, methods, fields, enum items, source files, and version copies, **not unique callable APIs**.

Not claimed complete across all devices/implementations: vendor-private ABIs, closed HAL implementations, actually registered Binder services, runtime command help, device-specific ioctls, Google/GMS/Play, AndroidX, third-party private interfaces, and private Java declarations outside candidate hiddenapi artifacts. `removed.txt` describes historical interfaces, not current declarations; historical removed inventories are excluded.

## Finding all declarations

**Public denotes source export level, not universal app-SDK availability.** Aggregated platform/module `current.txt` includes module-build exported types; not every entry exists in ordinary APK `android.jar`. Check source file, dependency/build entry, feature flags, and execution identity. System, Module-lib, and Test names likewise do not alone establish eligibility.

Select a level, then search class, method, permission, or path, such as `WallpaperManager`, `setBitmap`, `CameraManager`, `INJECT_EVENTS`, or `IInputManager`. Expanding entries shows original signatures, source files, and pinned links. File-level entries preserve complete text including methods, parameters, callbacks, constants, and structs. Public class-reference links assist reading only; current Android websites may change, while **pinned source defines this page's version**.

<!-- RAW_ANDROID_EXPLORER -->

## What ordinary apps, Shell, and system processes can access

| Identity / entry | Existing raw-system capabilities | How obtained | What is not automatic |
| --- | --- | --- | --- |
| Ordinary APK / public SDK | UI, Intents, files/network, own data, authorized camera/microphone/location/sensors | APK installation, Manifest/runtime grants, compliant foreground/background lifecycle | Other apps' private data, arbitrary touch injection, screen capture without consent |
| Special access / system role | Accessibility, notification access, usage access, default IME/assistant/SMS roles | Service/XML capability declaration; user enables/selects in system settings | Not all enabled by one `pm grant`; callbacks are not complete historical databases |
| ADB / shell UID 2000 | Development entry points such as `am`, `pm`, `cmd`, `dumpsys`, `input`, `screencap` | Debugging and authorized connection, or an eligible on-device shell service | Shell is not Root; SELinux, AppOps, and service checks remain |
| Platform signature / privileged APK | Declared protected SDK/Binder capabilities and cross-app system integration | Correct signature, system-partition installation, `privapp-permissions`, and individual permission requirements | Platform signature, priv-app, and system UID are distinct, not universal grants |
| System Server / native daemon | Internal services, specific service capabilities, display/input/power scheduling | Framework/native-service integration, startup order, Binder policy, dedicated SELinux domain | Ordinary JS cannot become an arbitrary system process without a bridge/adapter |
| Root / UID 0 | Linux file/process/mount/debug privileges and policy-allowed low-level entries | Development Root on `userdebug/eng` or specially configured elevation | Does not automatically decrypt app ciphertext/hardware keys, implement HALs, or pass remote app checks |
| HAL / driver / firmware | Camera, audio, sensor, haptic, USB, radio, power hardware interfaces | Drivers + vendor HAL + VINTF + permissions/SELinux; device implementation | ROM changes cannot create absent hardware, unavailable closed implementations, or unsupported feature bits |

References: [ADB](https://developer.android.com/tools/adb), [ADB Root build identity](https://android.googlesource.com/platform/packages/modules/adb/+/refs/heads/main/docs/dev/root.md), [privileged permission allowlists](https://source.android.com/docs/core/permissions/perms-allowlist), [non-SDK restrictions](https://developer.android.com/guide/app-compatibility/restrictions-non-sdk-interfaces), [SELinux](https://source.android.com/docs/security/features/selinux). These explain mechanisms; specific interfaces follow this page's pinned source.

## System capability map: from product ideas to raw entry points

This table navigates searches; **it is not every method**. Use declaration search above for constructors, overloads, fields, and callbacks. Check permissions by method, targetSdk, device, and foreground/background state; one permission row is not a whole-class pass.

| Area / purpose | Raw API or service | Typical eligibility / entry | Key boundary |
| --- | --- | --- | --- |
| System version, ABI, build, memory | `Build`, `ActivityManager.MemoryInfo`, `Process`, `SystemClock` | Public SDK; `getprop`, `id` | Build properties do not establish hardware availability |
| App lists, components, signatures, permissions | `PackageManager`, `PackageInstaller`, `PermissionManager` | Package visibility; install confirmation or installer/system eligibility; `pm`, `cmd package` | Enumeration, installation, and permission grants have distinct conditions |
| Launch apps/specific functions | `Intent`, `Context.startActivity`, `PendingIntent`, `ActivityOptions` | Exported components/public Intents; `am start` | Background launch, lock screen, and target displays have independent restrictions |
| Tasks, windows, Recents | `ActivityManager`, `ActivityTaskManager`, `WindowManager`, `IActivityTaskManager` | Public APIs for own tasks; cross-app tasks typically system/shell | No complete cross-app window control by default |
| Accessible UI tree/semantic clicks | `AccessibilityService`, `AccessibilityNodeInfo` | Enabled accessibility service, window-retrieval declaration | Custom-drawn/video/secure windows may lack semantic nodes |
| Touch, swipe, keyboard, Back | `GestureDescription`, `InputManager`, `IInputManager` | Accessibility gestures; system `INJECT_EVENTS`; `input` | Read back target display, focus, and business outcome |
| IME, Chinese input, editors | `InputMethodService`, `InputConnection`, `InputMethodManager` | User-selected IME; `BIND_INPUT_METHOD` service | Shell `input text` is not general Chinese input |
| Screenshots/recording | `MediaProjectionManager`, `MediaProjection`, `ImageReader` | System capture consent; foreground service; shell capture | Session, secure content, virtual-display IDs, and physical-display IDs differ |
| New/external/background displays | `DisplayManager`, `VirtualDisplay`, `SurfaceControl`, `IDisplayManager` | Ordinary private displays; inspect trusted/mirror/capture privileges separately | VirtualDisplay existence does not mean arbitrary apps run there |
| Notification reads/replies/actions | `NotificationListenerService`, `Notification`, `RemoteInput` | Notification access, exposed actions, protected service binding | Missing notification content/history does not appear automatically |
| Posting notifications/live task state | `NotificationManager`, `NotificationChannel` | `POST_NOTIFICATIONS` for ordinary apps on Android 13+ | Posting does not guarantee display; DND/user preferences apply |
| Clipboard, sharing, drag/drop | `ClipboardManager`, `ClipData`, `ACTION_SEND`, `DragEvent` | Focus/IME eligibility; shared-URI grants | Ordinary background apps cannot read the global clipboard indefinitely |
| Foreground app/usage history | `UsageStatsManager`, `UsageEvents`, `AppOpsManager` | Usage access; inspect protected operations separately | Usage statistics are not a complete millisecond UI-event stream |
| Camera frames/photos/exposure/focus | `CameraManager`, `CameraDevice`, `CaptureRequest`, `ImageReader` | `CAMERA`, foreground constraints, Camera HAL | Inspect `CameraCharacteristics`; concurrency/RAW/high frame rates depend on hardware |
| Image processing/GPU | `Bitmap`, `Canvas`, `HardwareBuffer`, OpenGL ES, Vulkan | Own buffers usually public; inspect sharing separately | Pixels do not imply access to an app's original file |
| Microphone/recording/encoding | `AudioRecord`, `MediaRecorder`, `MediaCodec` | `RECORD_AUDIO`, foreground-service/background limits | Call recording, internal playback capture, and microphone capture differ |
| Audio playback/media buttons | `AudioTrack`, `MediaPlayer`, `MediaSession`, `MediaController` | Own playback; others' sessions require notification/system eligibility | Only sessions exposed by the target player are controllable |
| Audio routing/volume/Bluetooth headsets | `AudioManager`, `AudioDeviceInfo`, `BluetoothProfile` | Ordinary volume public; global routing/audio policy need higher eligibility | Multiple displays may still share audio devices |
| Recognition/TTS/assistant | `SpeechRecognizer`, `TextToSpeech`, `VoiceInteractionService` | Microphone, system roles, installed implementations | Android interfaces do not guarantee offline models or all languages |
| GNSS/network location/geofences | `LocationManager`, `GnssStatus`, `Geocoder` | Separate coarse/fine/background location grants | Geocoder service, hardware, and network independently affect results |
| Acceleration/gyro/light/pressure | `SensorManager`, `Sensor`, `SensorEventListener` | Basic sensors often public; high-frequency/health types have extra permissions | Inspect actual sensors; emulator cannot prove physical sampling |
| Steps/motion/health | `Sensor.TYPE_STEP_COUNTER`, `HealthConnectManager` | Activity recognition and corresponding sensor/health permissions | Raw steps, system health records, and third-party health clouds differ |
| Vibration/haptics/lights/torch | `Vibrator`, `VibratorManager`, `VibrationEffect`, `CameraManager.setTorchMode` | `VIBRATE`, camera-service/hardware eligibility | Effects, strength, multiple vibrators, and torch occupancy depend on device |
| Wi-Fi/connectivity/hotspots/network selection | `WifiManager`, `ConnectivityManager`, `NetworkCapabilities`, `TetheringManager` | Nearby-device/location/system-settings roles; hotspot privileges separately | Ordinary apps cannot freely change all Wi-Fi/hotspot settings |
| Bluetooth/BLE/pairing/scanning | `BluetoothAdapter`, `BluetoothLeScanner`, `BluetoothGatt` | `BLUETOOTH_SCAN/CONNECT/ADVERTISE`, system pairing flow | ROM does not automatically bypass peripheral protocols/authentication |
| NFC/HCE/UWB/infrared | `NfcAdapter`, `HostApduService`, `UwbManager`, `ConsumerIrManager` | `NFC`, UWB eligibility, `TRANSMIT_IR`, corresponding hardware | No chip/vendor implementation means no usable capability |
| USB/peripherals/serial/OTG | `UsbManager`, `UsbDeviceConnection`, `UsbAccessory`, native USB/tty | USB grants; tty DAC/SELinux | USB host/accessory/network/debug modes are distinct |
| Cellular/calls/SMS/SIM/eSIM | `TelephonyManager`, `TelecomManager`, `SubscriptionManager`, `SmsManager`, `EuiccManager` | Phone/SMS roles, hard-restricted permissions, carrier privileges | Carriers/basebands/hardware keys are not determined by ordinary system permissions |
| Contacts/calendar/SMS providers | `ContactsContract`, `CalendarContract`, `Telephony`, `ContentResolver` | Corresponding read/write permissions; provider exported/URI grants | Not WeChat contacts, Feishu cloud calendars, or private app history |
| Photos/video/EXIF/documents | `MediaStore`, Photo Picker, `DocumentsContract`, SAF | Grouped media permissions, selected URIs, persistent grants | “All files” does not mean other apps' private directories |
| Files/volumes/encryption/backup | `StorageManager`, `StorageStatsManager`, `BackupManager`, `IVold` | Own files; separate statistics/backup/mount eligibility | FBE unlock, app encryption, and consistent snapshots are distinct |
| Wallpaper/live wallpaper/themes | `WallpaperManager`, `WallpaperService`, `OverlayManager` | `SET_WALLPAPER`, live-wallpaper selection, system overlay policy | Image wallpaper has a public API; video wallpaper needs playback service implementation |
| Launcher/widgets/shortcuts | `LauncherApps`, `AppWidgetManager`, `AppWidgetHost`, `ShortcutManager`, `TileService` | Launcher/widget host, user placement/binding, Quick Settings tiles | Android has host protocols; Taku's unified generation/extension experience still needs implementation |
| Lock screen/authentication/biometrics/keys | `KeyguardManager`, `BiometricPrompt`, Android Keystore, `IKeystoreService` | User authentication/protected roles, hardware-key policies | Root is not a general hardware-key/biometric-template export API |
| Background jobs/alarms/boot/scheduling | `JobScheduler`, `AlarmManager`, `Service`, `BroadcastReceiver` | Foreground-service types, exact-alarm special eligibility, system scheduling | Ordinary services are not permanent; Doze/battery policies depend on identity |
| Battery/charging/temperature/energy | `BatteryManager`, `PowerManager`, `ThermalService`, Health/Power HAL | Public state; global control/performance hints need separate eligibility | Sensors/control depend on HAL; disabling policy is not a substitute for thermal protection |
| Users/work profiles/device management | `UserManager`, `DevicePolicyManager`, `RoleManager` | Device/profile owner, system permissions/roles | Device Owner, Root, and platform signatures do not substitute for each other |
| Permissions/AppOps/accounts | `PermissionManager`, `AppOpsManager`, `AccountManager` | Distinguish self-inspection, shell, and system changes by method | Account providers constrain tokens; this is not a global token list |
| Package updates/OTA/reboot/recovery | `PackageInstaller`, `RecoverySystem`, `UpdateEngine`, BootControl HAL | Installer/signature/system permissions; AVB/rollback/vendor compatibility | APK updates differ from whole-device OTA; artifact passes do not mean physical flashing |
| Performance/processes/logs/tracing | `Debug`, `Trace`, `Process`, Perfetto, `logcat`, `dumpsys` | Own process; global logs/debug governed by shell/root/system policy | Do not bulk-export user logs or describe debug-only entries as production support |
| Network/IPC/native execution | Java sockets, `Binder`, `IBinder`, JNI, Bionic, epoll, Unix socket | `INTERNET`, process/file/SELinux eligibility | Android sh/toybox does not imply preinstalled GNU Bash, Node, Codex, or compilers |
| General UI/application code | Activity, Service, View, Resources, WebView, SQLite, Java base libraries | Public SDK, own app sandbox | APK installation does not imply built-in app generation/build/sign/preview pipelines |

## Command entry points: Agents can call directly without an SDK

Run examples in an **explicitly authorized development-device shell**. On a computer, confirm the target with `adb devices -l`, then enter `adb -s <target-serial> shell`; omitting the target risks another phone. This page executes no examples automatically.

### Identity and discoverable entry points

```sh
id
getenforce
getprop ro.build.version.release
getprop ro.build.version.sdk
getprop ro.build.type
getprop ro.product.cpu.abilist
cmd -l
service list
dumpsys -l
toybox --long
```

Expected outputs include UID, SELinux, API/build identity, and current command/service names. `cmd -l`, `service list`, and `dumpsys -l` enumerate different sets. A source-declared service may not be registered on-device. This page performs static extraction only; **these runtime outputs are not yet included in original-image validation**.

### Common system Shell commands and prerequisites

| Goal | Command / entry | Outputs, mutations, and failures |
| --- | --- | --- |
| Installed apps | `pm list packages`; `cmd package help` | Package names do not grant private-data access |
| Components/package eligibility | `dumpsys package <package>` | Potentially large/device-specific output; inspect permissions, components, signatures on test apps |
| Launch test app | `am start -n <package>/<Activity>` | Activity must exist/be launchable; read current task back afterward |
| Install test APK | `pm install -r /data/local/tmp/test.apk` | Valid file, matching signature, compatible SDK/ABI/storage; use only your generated test package |
| Uninstall test APK | `pm uninstall <test-package>` | Actually removes app/data; not a read-only probe |
| Windows/displays | `dumpsys window`; `dumpsys display` | Permissions/build policies may restrict; output is not pixels |
| Targeted input | `input --help`; `input -d <logical-DisplayId> tap 100 200` | Check current syntax, target display, and actual business result |
| Main-display screenshot | `screencap -p /data/local/tmp/test-screen.png` | Writes a test file; secure/protected capture not guaranteed |
| Test main-display recording | `screenrecord --help`; `screenrecord --time-limit 5 /data/local/tmp/test-screen.mp4` | Codec/size/display parameters follow actual help; audio/protected content may be absent |
| UI tree | `uiautomator dump /data/local/tmp/test-ui.xml` | Not every window exposes nodes; export isolated test content only |
| In-app tests | `am instrument -w <test-package>/<Runner>` | Requires correct installation/runner, not automatic system capabilities |
| Notifications | `cmd notification help`; `dumpsys notification` | Help/data-read eligibility differs; dumps are not chat history |
| Permissions/operation policy | `pm grant <test-package> <grantable-permission>`; `cmd appops help` | Actually changes grants; protection level/install allowlist/roles may reject |
| Read settings | `settings get system screen_brightness`; `settings help` | Namespace writes need eligibility; values are configuration, not hardware brightness feedback |
| Jobs/power-saving | `cmd jobscheduler help`; `dumpsys deviceidle` | Scheduling and exiting idle require different permissions; no continuous-background promise |
| Bluetooth/Wi-Fi/connectivity | `cmd wifi help`; `cmd bluetooth_manager help`; `dumpsys connectivity` | Service names/commands vary by branch; discover existence first |
| Camera/audio/sensors | `dumpsys media.camera`; `dumpsys audio`; `dumpsys sensorservice` | Dumps are not frame-capture/recording/sensor-subscription APIs |
| Hardware service registration | `lshal --help`; VINTF manifests | HIDL/AIDL HAL discovery differs; `lshal` is not all AIDL HALs |
| Network/native processes | `ip help`; `ps -A`; `ls /system/bin` | Arguments/visibility/control depend on privileges; file listing does not guarantee execution |
| Performance tracing | `perfetto --help`; `cmd activity help` | Trace configuration, data sources, and files have conditions; tracing is not completion of a product task |

In the explorer, select **System command help source / Toybox help / System programs** for baseline source help/program names. `sh` in `system/bin` does not establish `bash`, `npm`, or `clang`; installed runtimes/toolchains come later from Taku or users.

## Direct SDK calls: no plugin required for every Case

These are minimal Kotlin snippets inside Android app processes, not phone Shell commands. Place them in eligible Activity/Service code with imports, lifecycle, permission requests, and exception handling. **API existence does not mean these examples have been compiled or run.**

### Camera and torch

```kotlin
val manager = getSystemService(android.hardware.camera2.CameraManager::class.java)
val ids = manager.cameraIdList
val info = manager.getCameraCharacteristics(ids.first())
val hasFlash = info.get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE)
// Changes hardware state: call only with camera-service eligibility and hasFlash == true.
// manager.setTorchMode(ids.first(), true)
```

Discover camera capabilities before frames, recognition, photos, or triggers. Opening requires `CAMERA` and a valid lifecycle; another client may occupy it. Emulator cameras do not establish Pixel RAW/concurrency/resolution support. Frames require `openCamera → CameraCaptureSession → CaptureRequest → Surface/ImageReader`, not a `dumpsys`.

### Sensors and hardware subscriptions

```kotlin
val manager = getSystemService(android.hardware.SensorManager::class.java)
val sensors = manager.getSensorList(android.hardware.Sensor.TYPE_ALL)
val accelerometer = manager.getDefaultSensor(android.hardware.Sensor.TYPE_ACCELEROMETER)
// If the sensor exists, registerListener within the lifecycle and unregisterListener on exit.
```

Uses include motion, pickup, lighting, orientation, and sensor discovery. null is a legitimate failure boundary; check sampling rate, background policy, and motion/health permissions by type. Raw sensor events do not automatically mean “the user is running”; scripts, models, or extensions can supply semantics.

### Image wallpaper

```kotlin
// Manifest: <uses-permission android:name="android.permission.SET_WALLPAPER" />
val wm = android.app.WallpaperManager.getInstance(this)
wm.setBitmap(bitmap) // Caller supplies bitmap; it is not automatically obtained from Douyin.
```

Setting image wallpaper is already public. Taku can add a thin CLI/service bridge, or Agents can use eligible existing tools. **Identifying which Douyin video was just liked is a separate event/context problem**, requiring app adaptation, observers, or explicit user-provided context. A wallpaper API does not supply it. Video wallpaper needs `WallpaperService`/playback implementation and user/system selection.

### Notifications and media sessions

```kotlin
// Inside a user-enabled NotificationListenerService.
override fun onNotificationPosted(sbn: android.service.notification.StatusBarNotification) {
    val extras = sbn.notification.extras
    val text = extras.getCharSequence(android.app.Notification.EXTRA_TEXT)
    // Pass the event to your handler; text may be null and does not mean app history was read.
}
```

Uses include triggers, delivery/meeting reminders, and exposed notification actions. Notification access is special service enablement, distinct from ordinary runtime grants. Android delivers events; stable streams, deduplication, and task presentation are general platform work for Taku. Personal WeChat history, desktop WeChat content, and Douyin likes are not built-in data sources here.

### Screen capture and accessibility

```kotlin
val projectionManager = getSystemService(android.media.projection.MediaProjectionManager::class.java)
val consentIntent = projectionManager.createScreenCaptureIntent()
// Obtain user consent through ActivityResult, then create FGS, projection, Surface/VirtualDisplay.

// Inside user-enabled AccessibilityService declaring canRetrieveWindowContent:
val root = rootInActiveWindow
val nodes = root?.findAccessibilityNodeInfosByText("Search")
// ACTION_CLICK attempts an operation; read back target state afterward.
```

MediaProjection, accessibility trees, arbitrary input injection, and background trusted displays are different mechanisms. Android 14+ projection sessions/tokens/foreground-service rules must match targetSdk. Secure windows are not guaranteed readable. Sparse accessibility nodes do not equal a complete app data interface.

### Files, calendar, and app installation

```kotlin
val pick = android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT)
    .setType("image/*")
    .addCategory(android.content.Intent.CATEGORY_OPENABLE)
// Launch selection; persistent URI grants require subsequent takePersistableUriPermission.

val event = android.content.Intent(android.content.Intent.ACTION_INSERT,
    android.provider.CalendarContract.Events.CONTENT_URI)
    .putExtra(android.provider.CalendarContract.Events.TITLE, "Test itinerary")
// Opening calendar entry does not mean it was saved; verify the result.

val installer = packageManager.packageInstaller
// Create session, write APK, commit; read completion through IntentSender.
```

Uses include user-provided materials, itineraries, and Agent-generated APKs. Android supplies URI/calendar/installer protocols; **code generation, preview servers, compilation/signing, and app capability APIs are not an automatic end-to-end Android pipeline**. Distinguish user-confirmed installation from silent installation with system-installer eligibility.

## Using the full permission inventory

The explorer retains every `<permission>` attribute in `frameworks/base/core/res/AndroidManifest.xml`: name, protection level, and actually declared group, flags, label, description, knownCerts, etc. Conditional/version declarations with duplicate names remain; counts are not unique permissions.

| Protection / eligibility | Grant mechanism | Common misconception |
| --- | --- | --- |
| `normal` | Correct Manifest declaration, granted at install; call policies may remain | normal does not eliminate other method checks |
| `dangerous` | Runtime request plus user/role/install-allowlist conditions | `pm grant` cannot replace every special access or hard restriction |
| `signature` | Caller/definer meet signature rules | Copying APK into system does not give a matching signature |
| `signature\|privileged` | Signature or privileged grant path; latter needs partition/allowlist rules | priv-app does not automatically receive all privileged permissions |
| `internal` / `role` / `knownSigner` | Corresponding platform role/internal eligibility/recognized certificate flow | Ordinary user dialogs cannot grant these |
| AppOps / special access | Operation mode/settings enablement beyond Manifest | Permission grant differs from allowed AppOp |
| Service `BIND_*` | Protects system binding to correctly declared services | Requesting BIND does not grant access to everyone else's services |
| Linux / SELinux / capabilities | UID/GID, file mode, domain, Binder/ioctl policies | UID 0, signature, and system UID differ |

For real calls, record at least package/UID, domain, build type, targetSdk, permission grants, AppOps/roles, unlock state, service/hardware existence, input, and output/exception. Where method-level permissions were not extracted, display “Check implementation,” **never “No permission needed.”**

## Reading Binder, HAL, Native, and kernel declarations

### Binder / AIDL

Search names such as `IActivityManager`, `IInputManager`, or `ICameraService`. Files include interfaces, parcelables, enums, callbacks, and parameters. AIDL existence does not guarantee any process can obtain a callable service through `ServiceManager`. Public Managers are typical entry points; Framework/System SDK, process eligibility, SELinux, and server checks govern internal calls.

**Do not treat `service call <service> <number>` as a stable contract.** Transaction IDs, parcel layouts, AIDL versions, users, and caller UIDs vary. Prefer formal SDK/generated AIDL clients or validated system commands.

### HAL / AIDL / HIDL

Search camera, audio, sensors, power, vibrator, usb, radio, bluetooth, wifi, nfc, biometrics, graphics, health, thermal, GNSS, UWB, and related directories. Frozen versions remain; counts include version copies and data-type files. Confirm implemented versions through VINTF and actual registered instances. Default AIDL/HIDL text does not prove a particular Pixel hardware instance exists.

### Native / Bionic

Native records include symbol-map exports; some are match patterns, **not resolved function signatures**. Interface headers from `include/android`, `ndk/include`, and Bionic libc/libm/libdl preserve complete prototypes, macros, structs, and version annotations. Bionic `SYSCALLS.TXT` shows wrappers, names, and architecture mappings, neither every Linux syscall nor all syscalls allowed to Android apps. Read declarations/source for introduction versions, ABI, and linkage; exported names do not establish eligibility.

Stable entries include native camera, AAudio/OpenSL ES, AMediaCodec, AImageReader, AHardwareBuffer/ANativeWindow, ASensorManager, JNI, Binder NDK, OpenGL ES/EGL, Vulkan, and libc/libm/libdl. [NDK native API guide](https://developer.android.com/ndk/guides/stable_apis) and [NDK reference](https://developer.android.com/ndk/reference) help explain them. Symbol extraction does not mean every NDK prototype has individual manual annotations.

### UAPI / drivers / sysprop

These are Linux UAPI headers included by Bionic, containing ioctl constants, structs, flags, and ABI declarations, not all vendor kernel headers. Calls still need actual device nodes, driver support, correct architecture/sizes, and DAC/SELinux/seccomp permission. sysprop retains property access types/names; a definition does not permit every identity to read/write it.

## Coverage checks and reproducible extraction

Collector: system repository `scripts/collect_raw_android_interfaces.py`. Input is a read-only pinned source tree:

```sh
python3 scripts/collect_raw_android_interfaces.py \
  --source /path/to/pinned/android/source \
  --sdk /path/to/sdk/platforms/android-36 \
  --output spec/raw-android-interfaces.json.gz
```

Project scope comes from `.repo/project.list`: `frameworks/`, `packages/modules/`, `system/`, `hardware/`, `libcore`, `art`, `bionic`, `external/icu`, `external/toybox`. Read commit blobs, not device user data or closed vendor-binary contents. Signatures cover `/api/*current.txt`, excluding removed history and lint baselines. AIDL/HIDL includes frozen declarations but explicitly excludes test/sample/benchmark directories, which are not callable product capabilities.

Generation verifies source indices, layered counts, declaration coverage, and unclassified lines. Downloaded complete JSON supports all records, independent of the UI's 10 entries/page or 50 declarations/expansion batch:

```python
import gzip, json
data = json.load(gzip.open("raw-android-interfaces.json.gz", "rt"))
for record in data["records"]:
    if "setBitmap" in record["declaration"]:
        print(record["kind"], record["owner"], record["declaration"])
```

Verify SHA256 using coverage manifest `snapshotSha256`. Collection time is not device-test time. Sources retain upstream licenses and pinned public-source links; license markings/declarations do not change under this site's private-project license.

## Deciding what Taku should provide

The raw system already sets wallpaper, opens apps, captures photos, vibrates, posts notifications, and installs APKs. **Taku need not reinvent these.** For each, ask whether a direct command exists, whether the Agent's identity can call it, whether a thin Binder/JNI/CLI bridge is needed, whether stable structured output/subscribable events are needed, and how background work coexists with the main display.

Reliable events after Douyin likes, desktop WeChat context, industry-specific recognition algorithms, and ticket-booking site adapters can come from installable observers, connectors, tools, scripts, or other extensions. Without a plugin, existing capabilities and generated code can still complete one-off work. Create extensions when retention, reuse, triggers, or sharing are needed. **Discuss raw APIs, Taku wrappers, and community extensions from actual entry points rather than assigning a plugin to every Case first.**

Continue: [permission layers](https://docs.taku.build/security/permissions/), [system Shell](https://docs.taku.build/tools/system-shell/), [interface contracts](https://docs.taku.build/extensions/contract/), [capability iteration through use](https://docs.taku.build/extensions/self-extension/). This raw-interface index does not change Taku's current SDK method registry or promote research capabilities to tested ones.
