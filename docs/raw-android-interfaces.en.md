# Raw Android interface inventory

**Exploring what a phone can do or imagining cases? Start with the [one-page capability map](https://docs.taku.ai/os/capabilities/overview/).** This page is the detailed declaration, permission and source lookup.

This page answers a specific question: **what interfaces already exist before Taku OS adds agents, wrappers and extensions?** Use the inventory to explore product possibilities before deciding what belongs in the platform or an extension.

The [API reference](https://docs.taku.ai/os/reference/) describes tasks and verification status. This page describes **raw declarations, permissions and source provenance**. The searchable declaration data is the detailed index; the domain table is a navigation aid, not a replacement for all Android APIs.

## Pinned baseline and completeness

The baseline is **Android 16 / API 36**, from the LineageOS 23.2 development source used for the Pixel 9 candidate. Upstream declarations are read from each repository's pinned `HEAD` blobs, excluding uncommitted Taku changes. This is not Google's stock Pixel OS or a universal inventory for every Android vendor.

Two evidence classes remain separate:

- **Upstream source declarations:** public, system, module, system-server and test signatures; permission XML; Binder AIDL; HAL AIDL/HIDL; native exports and headers; Bionic syscall mappings; UAPI headers; system properties; command help source.
- **Candidate build outputs:** non-SDK descriptors from `hiddenapi-flags.csv` and binary names in partition directories. These are generated artifacts from an integrated candidate, not proof of a pristine image or runtime behavior.

A separately labelled **Android SDK 36 revision 2 annotation supplement** retains `RequiresPermission`, `RequiresFeature`, `RequiresApi` and exact argument annotations from the installed SDK's `annotations.zip`. It is not the same revision as the baseline source and does not replace service implementation checks.

**Declaration existence, caller qualification, hardware implementation and successful execution are four different claims.** Bulk extraction establishes declaration or artifact presence only. It does not increase emulator or physical-device verification counts.

Completeness has an auditable scope: every matched source file has a path, revision and SHA256; every managed signature declaration is retained, including unclassified lines. Pagination, privilege level or deprecation do not discard records. Counts include types, constructors, methods, fields, enum constants, whole source files and versioned duplicates. **They are not counts of unique callable APIs.**

Excluded or not established: proprietary vendor ABI and HAL implementations, runtime Binder registrations and command help, vendor-specific ioctls outside Bionic UAPI, Google/GMS/Play, AndroidX and third-party application APIs, and private Java declarations outside the candidate hiddenapi artifact. `removed.txt` is historical and is not included as a currently available interface.

## Search all declarations

**Public is a source export surface, not a universal promise of app-SDK availability.** The inventory aggregates platform and module `current.txt` files, including types exported during module builds. Not every owner is in an ordinary APK's `android.jar`. Check the exact source file, dependencies/build entry, feature flags and caller identity. System, Module-lib and Test names alone do not establish qualification either.

Choose a surface and search for a class, method, permission or source path: `WallpaperManager`, `setBitmap`, `CameraManager`, `INJECT_EVENTS` or `IInputManager`. Expand an owner to read every declaration and its pinned source. File-level records retain complete text, including parameters, callbacks, constants and data types. Android reference links help explain classes but may describe a newer revision; **the pinned source is the version authority**.

<!-- RAW_ANDROID_EXPLORER -->

## Caller identities and qualifications

| Caller / interface | Already provided by Android | Qualification | Not automatically granted |
| --- | --- | --- | --- |
| Ordinary APK / public SDK | UI, intents, networking, own data and authorized camera, microphone, location and sensors | APK installation, Manifest, runtime grants and lifecycle compliance | Other apps' private data, arbitrary input injection or consent-free capture |
| Special access / roles | Accessibility, notification listening, usage access, default IME, assistant or SMS role | Correct service and capability declarations; settings or role selection | A single `pm grant` does not enable all special access; callbacks are not full historical databases |
| ADB / shell UID 2000 | `am`, `pm`, `cmd`, `dumpsys`, `input`, `screencap` | Authorized debug connection or an already qualified on-device shell service | Shell is not Root; service, AppOps and SELinux checks still apply |
| Platform signature / privileged APK | Protected APIs and integrations allowed by permission declarations | Appropriate signing, partition installation and privileged allowlists | Signature, privileged placement and system UID are different qualifications |
| System Server / native daemon | Internal services, display/input/power scheduling and service capabilities | Framework or native integration, startup order, Binder policy and SELinux domain | Ordinary JS cannot become any system process without a bridge |
| Root / UID 0 | Linux-level file, process, mount and debug access permitted by policy | Development root on a suitable build or an explicitly configured path | No generic decryption of app ciphertext/hardware keys; no missing HAL implementation |
| HAL / driver / firmware | Device hardware protocols | Driver, vendor HAL, VINTF and device-specific qualification | Absent hardware or unsupported features do not appear merely by changing the ROM |

Sources: [ADB](https://developer.android.com/tools/adb), [ADB root build identities](https://android.googlesource.com/platform/packages/modules/adb/+/refs/heads/main/docs/dev/root.md), [privileged allowlists](https://source.android.com/docs/core/permissions/perms-allowlist), [non-SDK restrictions](https://developer.android.com/guide/app-compatibility/restrictions-non-sdk-interfaces), [SELinux](https://source.android.com/docs/security/features/selinux). These explain mechanisms; pinned declarations determine this inventory's exact version.

## Domain map

Each row points to raw classes or services. Inspect exact overloads, fields and callbacks in the search above. Permissions depend on the method, target SDK, device, role and foreground/background state; one row is not a permission contract for a whole class.

| Domain / purpose | Raw interfaces | Common qualification / entry | Boundary |
| --- | --- | --- | --- |
| Version, ABI, build and memory | `Build`, `ActivityManager.MemoryInfo`, `Process`, `SystemClock` | Public SDK; `getprop`, `id` | Build properties do not prove hardware support |
| Packages, components, signatures | `PackageManager`, `PackageInstaller`, `PermissionManager` | Package visibility; installer/system qualification; `pm` | Enumeration, installation and grants have separate gates |
| Launch existing apps | `Intent`, `Context.startActivity`, `PendingIntent`, `ActivityOptions` | Exported component/public intent; `am start` | Background launch, lockscreen and display selection have extra rules |
| Tasks and windows | `ActivityManager`, `ActivityTaskManager`, `WindowManager`, `IActivityTaskManager` | Own tasks publicly; cross-app control usually system/shell | Not a default cross-app window automation API |
| UI tree and semantic clicks | `AccessibilityService`, `AccessibilityNodeInfo` | Enabled service and window retrieval capabilities | Canvas/video/secure windows may lack nodes |
| Touch, keys and gestures | `GestureDescription`, `InputManager`, `IInputManager` | Accessibility gesture or protected `INJECT_EVENTS`; `input` | Confirm display, focus and actual business outcome |
| Text and IME | `InputMethodService`, `InputConnection`, `InputMethodManager` | User-selected IME and proper service binding | Shell text input is not universal Unicode entry |
| Screenshot and recording | `MediaProjectionManager`, `MediaProjection`, `ImageReader` | Capture consent, FGS, qualified shell capture | Session rules and secure content remain |
| Virtual / external displays | `DisplayManager`, `VirtualDisplay`, `SurfaceControl`, `IDisplayManager` | Private display APIs; trusted/mirror/capture privileges separately | A virtual display does not automatically run any app |
| Read notifications / reply | `NotificationListenerService`, `Notification`, `RemoteInput` | Notification access; exposed notification actions | Not full app history or absent message content |
| Publish task status | `NotificationManager`, `NotificationChannel` | Ordinary apps need `POST_NOTIFICATIONS` on Android 13+ | DND and user settings still affect delivery |
| Clipboard / sharing | `ClipboardManager`, `ClipData`, `ACTION_SEND`, `DragEvent` | Focus/IME qualification; URI grants | No unlimited ordinary background clipboard access |
| Usage / foreground signals | `UsageStatsManager`, `UsageEvents`, `AppOpsManager` | Usage access or protected qualification | Not a complete real-time window event stream |
| Camera frames and controls | `CameraManager`, `CameraDevice`, `CaptureRequest`, `ImageReader` | `CAMERA`, lifecycle, HAL | Inspect capabilities; RAW/concurrency/high FPS are hardware-specific |
| Image / GPU processing | `Bitmap`, `Canvas`, `HardwareBuffer`, OpenGL ES, Vulkan | Own buffers; buffer sharing needs qualification | Captured pixels are not an app's original file |
| Microphone / encoding | `AudioRecord`, `MediaRecorder`, `MediaCodec` | `RECORD_AUDIO`, foreground/background rules | Calls, playback capture and microphone recording differ |
| Playback / sessions | `AudioTrack`, `MediaPlayer`, `MediaSession`, `MediaController` | Own playback; external control via qualified session access | Only operations exposed by the target session |
| Audio routing / volume | `AudioManager`, `AudioDeviceInfo`, `BluetoothProfile` | Public volume paths; global policy needs privilege | Displays can still share audio devices |
| Speech / TTS / assistant | `SpeechRecognizer`, `TextToSpeech`, `VoiceInteractionService` | Microphone, role and installed provider | Interface existence does not imply an offline model |
| Location / GNSS | `LocationManager`, `GnssStatus`, `Geocoder` | Approximate, precise and background grants separately | Hardware, provider and network determine results |
| Motion / light / pressure | `SensorManager`, `Sensor`, `SensorEventListener` | Many basic sensors public; high-rate/health types differ | Query actual sensors; emulator behavior is not physical proof |
| Steps / health | `Sensor.TYPE_STEP_COUNTER`, `HealthConnectManager` | Activity recognition / health grants | Sensor events, stored health data and external clouds differ |
| Haptics / flashlight | `Vibrator`, `VibratorManager`, `VibrationEffect`, `CameraManager.setTorchMode` | `VIBRATE`; camera-service qualification | Effects and intensity depend on device and occupancy |
| Wi-Fi / hotspot / networking | `WifiManager`, `ConnectivityManager`, `NetworkCapabilities`, `TetheringManager` | Nearby/location qualifications; privileged tethering separately | Ordinary apps cannot reconfigure every network |
| Bluetooth / BLE | `BluetoothAdapter`, `BluetoothLeScanner`, `BluetoothGatt` | `BLUETOOTH_SCAN/CONNECT/ADVERTISE`; pairing | Peripheral protocols and authentication remain |
| NFC / HCE / UWB / IR | `NfcAdapter`, `HostApduService`, `UwbManager`, `ConsumerIrManager` | Hardware and `NFC`/UWB/`TRANSMIT_IR` qualifications | Missing silicon or vendor support means no capability |
| USB / serial / peripherals | `UsbManager`, `UsbDeviceConnection`, `UsbAccessory`, USB/tty native paths | USB device grant; node DAC/SELinux | Host, accessory, networking and debug modes differ |
| Cellular / calls / SMS / eSIM | `TelephonyManager`, `TelecomManager`, `SubscriptionManager`, `SmsManager`, `EuiccManager` | Roles, restricted permissions, carrier privilege | Modem, carrier and hardware-key rules still apply |
| Contacts / calendars / SMS | `ContactsContract`, `CalendarContract`, `Telephony`, `ContentResolver` | Provider and read/write/URI qualifications | Not WeChat friends or external cloud calendars |
| Photos / documents / EXIF | `MediaStore`, Photo Picker, `DocumentsContract`, SAF | Media grants or selected URI permission | All-files access is not other apps' private directories |
| Storage / encryption / backup | `StorageManager`, `StorageStatsManager`, `BackupManager`, `IVold` | Own files; protected stats/backup/mount paths | Unlock state, encryption and consistency differ |
| Wallpaper / themes | `WallpaperManager`, `WallpaperService`, `OverlayManager` | `SET_WALLPAPER`; live service selection; overlay policy | Static wallpaper is public; video wallpaper needs a player service |
| Launcher / widgets / tiles | `LauncherApps`, `AppWidgetManager`, `AppWidgetHost`, `ShortcutManager`, `TileService` | Host roles, binding/placement, tile selection | Android host protocols do not include Taku's generation pipeline |
| Lockscreen / biometric / keys | `KeyguardManager`, `BiometricPrompt`, Keystore, `IKeystoreService` | Authentication, protected roles and key policies | Root is not a universal biometric/key export API |
| Background / alarms / boot | `JobScheduler`, `AlarmManager`, `Service`, `BroadcastReceiver` | FGS types, exact alarm access, scheduler rules | An ordinary service is not permanently alive |
| Battery / thermal / power | `BatteryManager`, `PowerManager`, `ThermalService`, Health/Power HAL | Public status; protected global control | Control requires actual hardware support |
| Users / management / roles | `UserManager`, `DevicePolicyManager`, `RoleManager` | Device/profile owner or system role | Device Owner, Root and signing are not interchangeable |
| Permissions / accounts | `PermissionManager`, `AppOpsManager`, `AccountManager` | Self, shell and system paths differ per method | Account providers control token access |
| Update / OTA / recovery | `PackageInstaller`, `RecoverySystem`, `UpdateEngine`, BootControl HAL | Installer/signing/AVB/rollback/vendor compatibility | APK updates and whole-device OTA are different |
| Performance / tracing | `Debug`, `Trace`, `Process`, Perfetto, `logcat`, `dumpsys` | Own process; global access qualified | Debug-only behavior is not production support |
| Native execution / IPC | Sockets, `Binder`, `IBinder`, JNI, Bionic, epoll, Unix sockets | `INTERNET`, file/process and SELinux qualification | Android sh/toybox does not imply GNU Bash, Node or compilers |
| App UI and storage | Activity, Service, View, Resources, WebView, SQLite, Java library | Public SDK and app sandbox | APK installation is not generation/build/sign/preview automation |

## Direct shell commands

Run these in an **authorized development device shell**, not blindly against whichever phone is attached. First identify the target with `adb devices -l`, then enter `adb -s <target-serial> shell`. This page does not execute examples.

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

The three service/command listings differ. A source declaration does not prove runtime registration. Runtime discovery is not verified by this static collection.

| Purpose | Command | Result / qualification / failure boundary |
| --- | --- | --- |
| Packages | `pm list packages`; `cmd package help` | Names do not grant private-data access |
| Components / permissions | `dumpsys package <test-package>` | Inspect a test app; output can contain device information |
| Launch | `am start -n <package>/<Activity>` | Component must exist and be launchable; verify task outcome |
| Install own APK | `pm install -r /data/local/tmp/test.apk` | Writes state; signature/SDK/ABI/storage must qualify |
| Uninstall test APK | `pm uninstall <test-package>` | Deletes app and data; not a read-only probe |
| Displays / windows | `dumpsys window`; `dumpsys display` | Status output is not captured pixels |
| Input | `input --help`; `input -d <logicalDisplayId> tap 100 200` | Verify syntax, target and actual outcome |
| Screenshot | `screencap -p /data/local/tmp/test-screen.png` | Writes a file; protected content may be absent |
| Recording | `screenrecord --help`; `screenrecord --time-limit 5 /data/local/tmp/test-screen.mp4` | Codec/size/display rules vary; audio is not guaranteed |
| UI tree | `uiautomator dump /data/local/tmp/test-ui.xml` | Not all windows have accessible nodes |
| Instrumentation | `am instrument -w <test-package>/<Runner>` | Requires installed test runner; not unlimited privileges |
| Notifications | `cmd notification help`; `dumpsys notification` | Help and data-read qualification differ; not message history |
| Grants / AppOps | `pm grant <test-package> <grantable-permission>`; `cmd appops help` | Mutates state; roles/allowlists/protection can prevent grants |
| Settings | `settings get system screen_brightness`; `settings help` | A setting is not hardware feedback; writes need qualification |
| Scheduling | `cmd jobscheduler help`; `dumpsys deviceidle` | Scheduling and global idle control are different |
| Connectivity | `cmd wifi help`; `cmd bluetooth_manager help`; `dumpsys connectivity` | Discover service existence and version-specific command support |
| Hardware service status | `dumpsys media.camera`; `dumpsys audio`; `dumpsys sensorservice` | Status dumps are not frame/audio/sensor subscription APIs |
| HAL discovery | `lshal --help`; VINTF manifests | HIDL discovery does not enumerate every AIDL HAL |
| Native processes / network | `ip help`; `ps -A`; `ls /system/bin` | File presence does not establish execution/control success |
| Tracing | `perfetto --help`; `cmd activity help` | Configuration, data source qualification and outputs remain separate |

The search surfaces include system help source, Toybox config help/option grammar and candidate binary names. Source help can describe commands absent from the configured product. Runtime help must still be captured on the exact device.

## Minimal public SDK calls

These Kotlin fragments belong in an appropriately qualified Activity/Service. Add imports, grants, callbacks, lifecycle and error handling. They are **not shell commands or tested examples from this extraction**.

### Camera and sensors

```kotlin
val camera = getSystemService(android.hardware.camera2.CameraManager::class.java)
val ids = camera.cameraIdList
val info = camera.getCameraCharacteristics(ids.first())
val flash = info.get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE)
// Hardware-changing call, only with proper qualification and actual flash support:
// camera.setTorchMode(ids.first(), true)

val manager = getSystemService(android.hardware.SensorManager::class.java)
val sensors = manager.getSensorList(android.hardware.Sensor.TYPE_ALL)
val accelerometer = manager.getDefaultSensor(android.hardware.Sensor.TYPE_ACCELEROMETER)
```

Camera capture requires `CAMERA`, legal lifecycle, `openCamera`, capture session, request and a Surface/ImageReader. Camera occupancy and device characteristics can prevent a call. Sensors can be missing; listener registration must be paired with unregistration. Raw events do not automatically carry semantic meaning such as running or driving.

### Wallpaper

```kotlin
// Manifest requires android.permission.SET_WALLPAPER.
android.app.WallpaperManager.getInstance(this).setBitmap(bitmap)
```

The image must be supplied by the caller. Setting static wallpaper is already a public API; a thin qualified command or bridge is sufficient. Detecting a specific Douyin like is a separate context/observer problem. Video wallpaper requires a `WallpaperService`, playback implementation and selection flow.

### Notifications, screen and UI tree

```kotlin
// In an enabled NotificationListenerService:
override fun onNotificationPosted(sbn: android.service.notification.StatusBarNotification) {
    val text = sbn.notification.extras
        .getCharSequence(android.app.Notification.EXTRA_TEXT)
    // text can be absent; this is not complete app history.
}

val capture = getSystemService(android.media.projection.MediaProjectionManager::class.java)
val consent = capture.createScreenCaptureIntent()
// Obtain consent and establish the appropriate FGS/projection/surface lifecycle.

// Inside an enabled AccessibilityService with window retrieval capability:
val root = rootInActiveWindow
val searchNodes = root?.findAccessibilityNodeInfosByText("Search")
```

Notification access, screen capture, accessibility, input injection and trusted background display are distinct mechanisms. Android 14+ projection token/session/FGS rules depend on target SDK. Secure content and missing nodes remain valid failure boundaries. The OS does not turn notification callbacks into WeChat history, desktop messages or Douyin like events.

### Files, calendar and APK installation

```kotlin
val pick = android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT)
    .setType("image/*")
    .addCategory(android.content.Intent.CATEGORY_OPENABLE)
// Receive selection and explicitly persist URI access where appropriate.

val event = android.content.Intent(android.content.Intent.ACTION_INSERT,
    android.provider.CalendarContract.Events.CONTENT_URI)
    .putExtra(android.provider.CalendarContract.Events.TITLE, "Test trip")
// Opening the form is not proof of a saved event.

val installer = packageManager.packageInstaller
// Create session, write APK, commit and read the IntentSender completion result.
```

URI access, calendar protocols and installation already exist. Generating arbitrary app code, previewing it, compiling/signing and exposing its business API are an additional pipeline. User-confirmed installation and qualified silent installation are separate paths.

## Permission inventory

The permissions surface preserves every `<permission>` attribute from the pinned Framework Manifest: name, protection level, group, flags, label, description, known certificates and other present metadata. Duplicate conditional/versioned declarations are retained, not miscounted as unique permissions.

| Protection / qualification | How granted | Common mistake |
| --- | --- | --- |
| `normal` | Correct Manifest declaration and installation | Other method-level checks may still apply |
| `dangerous` | Runtime grant plus role/installer conditions where relevant | Not a replacement for every special-access setting |
| `signature` | Signing qualification | Copying an APK to system does not make its signature match |
| `signature\|privileged` | Signing or qualified privileged installation/allowlist path | Priv-app does not receive every protected permission |
| `internal`, `role`, `knownSigner` | Corresponding internal, role or certificate mechanism | Not an ordinary runtime dialog |
| AppOps / special access | Operation mode or settings enablement | Permission grant and allowed AppOp differ |
| Service `BIND_*` | Protects system binding to a correctly declared service | Not a universal API grant to the declaring app |
| Linux / SELinux | UID/GID, modes, domains, Binder/ioctl/seccomp rules | UID 0, signing and system UID are different |

Record caller package/UID/domain, build type, target SDK, grants, AppOps/roles, unlock state, service/hardware presence, input, output and exception. Where method-level permissions were not extracted, inspect implementation. **Unknown permission requirements never mean permission-free.**

## Binder, HAL, native and kernel interpretation

### Binder / AIDL

Search interface names such as `IActivityManager`, `IInputManager` or `ICameraService`. AIDL files include parcelables, enums, callbacks and method parameters. Source existence does not establish service access. Prefer public Managers, generated AIDL clients or a verified system command, with service-side checks and SELinux qualification.

`service call <name> <number>` is not a stable contract: transaction numbering, parcel layout, AIDL version and caller/user identity vary.

### HAL / AIDL / HIDL

Camera, audio, sensors, power, vibrator, USB, radio, Bluetooth, Wi-Fi, NFC, biometric, graphics, health, thermal, GNSS and UWB declarations include frozen versions and data types. Use VINTF and actual registration to establish the implemented instance/version. Declarations do not prove physical Pixel hardware behavior.

### Native / Bionic

Symbol maps preserve exported names or patterns, not inferred function signatures. Source headers under `include/android`, `ndk/include` and Bionic libc/libm/libdl retain prototypes, macros, structures and version annotations. `SYSCALLS.TXT` lists Bionic mappings and architectures, not every Linux syscall or every syscall allowed in an Android app.

Stable paths include native camera, AAudio/OpenSL ES, AMediaCodec, AImageReader, AHardwareBuffer/ANativeWindow, ASensorManager, JNI, Binder NDK, OpenGL ES/EGL, Vulkan and libc/libm/libdl. See [Native APIs](https://developer.android.com/ndk/guides/stable_apis) and the [NDK reference](https://developer.android.com/ndk/reference). Read exact headers, ABI, linkage and qualification rather than guessing from symbol names.

### Kernel UAPI and system properties

Bionic's Linux UAPI headers retain ioctl constants, structures, flags and ABI declarations. They are not every vendor kernel header. Nodes, drivers, architecture/layout, DAC, SELinux and seccomp still determine calls. System-property declarations retain access modes and names; their presence does not grant global read/write access.

## Reproducible extraction and coverage

The canonical extractor belongs to the system repository: `scripts/collect_raw_android_interfaces.py`. Use a pinned read-only source tree:

```sh
python3 scripts/collect_raw_android_interfaces.py \
  --source /path/to/pinned/android/source \
  --sdk /path/to/sdk/platforms/android-36 \
  --output spec/raw-android-interfaces.json.gz
```

Projects come from `.repo/project.list` within `frameworks/`, `packages/modules/`, `system/`, `hardware/`, `libcore`, `art`, `bionic`, `external/icu` and `external/toybox`. Reads use committed blobs. No device user data or proprietary binary content is collected. Signature discovery includes `/api/*current.txt`, excluding removed and lint files. AIDL/HIDL frozen versions are included; test, sample and benchmark directories are explicitly excluded from product interfaces.

The projection checks source indices and surface totals. Full data is not truncated by ten-owner pages or fifty-declaration expansion batches:

```python
import gzip, json
data = json.load(gzip.open("raw-android-interfaces.json.gz", "rt"))
for record in data["records"]:
    if "setBitmap" in record["declaration"]:
        print(record["kind"], record["owner"], record["declaration"])
```

Check the archive against `snapshotSha256` in the coverage manifest. Collection time is not device execution time. Upstream licenses remain applicable to extracted declarations and source headers; pinned public source links preserve provenance.

## What Taku OS adds above these interfaces

Android already sets static wallpaper, opens apps, captures authorized media, vibrates, publishes notifications and installs APKs. Taku OS should establish whether a usable command already exists, what caller identity an agent needs, whether a thin Binder/JNI/CLI bridge is needed, and how structured results, events and background work coexist with the person's main screen.

Reliable like events from a video app, desktop messaging context, industry models and booking-site adapters can come from observers, connectors, tools, scripts and other extensions. A one-off task can use existing capabilities or temporary generated code without a preinstalled case plugin. Persistence, reuse, triggers and sharing are reasons to form an extension.

See [permission layers](https://docs.taku.ai/os/security/permissions/), [system shell](https://docs.taku.ai/os/tools/system-shell/), [extension contract](https://docs.taku.ai/os/extensions/contract/) and [evolution during use](https://docs.taku.ai/os/extensions/self-extension/). This index does not expand the implemented Taku SDK registry or turn research into tested features.
