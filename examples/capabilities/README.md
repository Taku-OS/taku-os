# Capability call examples

Run from the repository root. This directory supplies reproduction entry points, not a second OS protocol or task queue.

Probe without side effects: `python3 examples/capabilities/probe_readonly.py`. A person/Agent selects scenarios for other examples; avoid indiscriminate batches of taps, notifications or installs.

|Capability|Execution environment|Example type|
|---|---|---|
|[System version and execution identity](system.info.sh)|Host terminal, explicitly targeting emulator-5582|host_shell|
|[Memory and disk overview](system.resources.sh)|Root shell on the phone|device_shell|
|[Package name, version and installation location](packages.metadata.sh)|Root shell on the phone|device_shell|
|[Post an app's own system notifications](notifications.post.sh)|Root shell on the phone|device_shell|
|[Observe notification changes](notifications.listen.kt)|Kotlin method snippet in an ordinary Android project; complete Manifest, permissions and lifecycle as documented; not a directly executable CLI|android_api_snippet|
|[Quick replies through existing notifications](notifications.reply.kt)|Kotlin method snippet in an ordinary Android project; complete Manifest, permissions and lifecycle as documented; not a directly executable CLI|android_api_snippet|
|[ADB UI-tree and control testing](ui.tree.shell.sh)|Root shell on the phone|device_shell|
|[Read the accessible UI tree from an ordinary app](ui.tree.accessibility.kt)|Kotlin method snippet in an ordinary Android project; complete Manifest, permissions and lifecycle as documented; not a directly executable CLI|android_api_snippet|
|[Targeted touch, swipe and key input](input.shell.sh)|Root shell on the phone|device_shell|
|[Inject semantic actions and gestures without flashing](input.accessibility.kt)|Kotlin method snippet in an ordinary Android project; complete Manifest, permissions and lifecycle as documented; not a directly executable CLI|android_api_snippet|
|[Text input and input-method bridge](input.text.kt)|Kotlin method snippet in an ordinary Android project; complete Manifest, permissions and lifecycle as documented; not a directly executable CLI|android_api_snippet|
|[ADB / root screenshots](screen.capture.shell.sh)|Root shell on the phone|device_shell|
|[Screen capture without flashing](screen.capture.public.kt)|Kotlin method snippet in an ordinary Android project; complete Manifest, permissions and lifecycle as documented; not a directly executable CLI|android_api_snippet|
|[Usage history and foreground-app clues](context.foreground.kt)|Kotlin method snippet in an ordinary Android project; complete Manifest, permissions and lifecycle as documented; not a directly executable CLI|android_api_snippet|
|[Foreground clipboard / share entry point](context.clipboard.kt)|Kotlin method snippet in an ordinary Android project; complete Manifest, permissions and lifecycle as documented; not a directly executable CLI|android_api_snippet|
|[Media-session state and playback control](context.media.kt)|Kotlin method snippet in an ordinary Android project; complete Manifest, permissions and lifecycle as documented; not a directly executable CLI|android_api_snippet|
|[System SMS and call logs](data.sms.calllog.kt)|Kotlin method snippet in an ordinary Android project; complete Manifest, permissions and lifecycle as documented; not a directly executable CLI|android_api_snippet|
|[Read and write contacts](data.contacts.kt)|Kotlin method snippet in an ordinary Android project; complete Manifest, permissions and lifecycle as documented; not a directly executable CLI|android_api_snippet|
|[Query and create system calendar events](data.calendar.kt)|Kotlin method snippet in an ordinary Android project; complete Manifest, permissions and lifecycle as documented; not a directly executable CLI|android_api_snippet|
|[Photo library and file location](data.photos.kt)|Kotlin method snippet in an ordinary Android project; complete Manifest, permissions and lifecycle as documented; not a directly executable CLI|android_api_snippet|
|[Select, search and organize files](data.files.kt)|Kotlin method snippet in an ordinary Android project; complete Manifest, permissions and lifecycle as documented; not a directly executable CLI|android_api_snippet|
|[Research: export other apps' private data](data.private-files.sh)|Read-only source-location command in an AOSP checkout; not evidence of implemented functionality|source_inspection|
|[Research: backup, restore and migration](data.backup.kt)|Kotlin method snippet in an ordinary Android project; complete Manifest, permissions and lifecycle as documented; not a directly executable CLI|android_api_snippet|
|[Launch existing apps or Intent actions](apps.launch.sh)|Root shell on the phone|device_shell|
|[Install, update and uninstall standalone APKs](apps.install.sh)|Root shell on the phone|device_shell|
|[Configure runtime permissions, roles and AppOps](permissions.manage.sh)|Root shell on the phone|device_shell|
|[System settings, brightness and Do Not Disturb](settings.control.sh)|Root shell on the phone|device_shell|
|[Custom home screen, task cards and traditional app entry points](home.launcher.sh)|Root shell on the phone|device_shell|
|[Global assistive layer / overlay](home.overlay.kt)|Kotlin method snippet in an ordinary Android project; complete Manifest, permissions and lifecycle as documented; not a directly executable CLI|android_api_snippet|
|[Default digital assistant and global invocation](assistant.entry.sh)|Root shell on the phone|device_shell|
|[A truly independent background display](display.independent.sh)|Root shell on the phone|device_shell|
|[View and manually control the background display from the main screen](display.preview.sh)|Root shell on the phone|device_shell|
|[Task and multi-window policy changes](display.window-policy.sh)|Read-only source-location command in an AOSP checkout; not evidence of implemented functionality|source_inspection|
|[Microphone and speech capture](audio.microphone.kt)|Kotlin method snippet in an ordinary Android project; complete Manifest, permissions and lifecycle as documented; not a directly executable CLI|android_api_snippet|
|[Capture another app's playback with public APIs](audio.playback.kt)|Kotlin method snippet in an ordinary Android project; complete Manifest, permissions and lifecycle as documented; not a directly executable CLI|android_api_snippet|
|[Research: both sides of cellular / VoIP calls](audio.calls.sh)|Read-only source-location command in an AOSP checkout; not evidence of implemented functionality|source_inspection|
|[Own-track effects / research into system input effects](audio.effects.sh)|Read-only source-location command in an AOSP checkout; not evidence of implemented functionality|source_inspection|
|[Ordinary camera preview / real-time understanding](camera.read.kt)|Kotlin method snippet in an ordinary Android project; complete Manifest, permissions and lifecycle as documented; not a directly executable CLI|android_api_snippet|
|[Research: cross-app camera enhancement / replacement image pipeline](camera.global-filter.sh)|Read-only source-location command in an AOSP checkout; not evidence of implemented functionality|source_inspection|
|[Research: low-power voice wake-up](voice.hotword.sh)|Read-only source-location command in an AOSP checkout; not evidence of implemented functionality|source_inspection|
|[Location and location context](environment.location.kt)|Kotlin method snippet in an ordinary Android project; complete Manifest, permissions and lifecycle as documented; not a directly executable CLI|android_api_snippet|
|[Sensors and activity recognition](environment.sensors.kt)|Kotlin method snippet in an ordinary Android project; complete Manifest, permissions and lifecycle as documented; not a directly executable CLI|android_api_snippet|
|[Bluetooth / local-network device discovery](environment.nearby.kt)|Kotlin method snippet in an ordinary Android project; complete Manifest, permissions and lifecycle as documented; not a directly executable CLI|android_api_snippet|
|[Research: USB peripherals and NFC / HCE](environment.usb-nfc.kt)|Kotlin method snippet in an ordinary Android project; complete Manifest, permissions and lifecycle as documented; not a directly executable CLI|android_api_snippet|
|[On-device task execution and state queue](runtime.broker.sh)|Host terminal, explicitly targeting emulator-5582|host_shell|
|[Ordinary background tasks, boot notifications and recovery](runtime.background-public.kt)|Kotlin method snippet in an ordinary Android project; complete Manifest, permissions and lifecycle as documented; not a directly executable CLI|android_api_snippet|
|[Power, WakeLocks and task scheduling](runtime.power.kt)|Kotlin method snippet in an ordinary Android project; complete Manifest, permissions and lifecycle as documented; not a directly executable CLI|android_api_snippet|
|[Research: CPU/GPU/thermal management](runtime.performance.sh)|Read-only source-location command in an AOSP checkout; not evidence of implemented functionality|source_inspection|
|[Research: network state, VPN and per-app networking](network.vpn.kt)|Kotlin method snippet in an ordinary Android project; complete Manifest, permissions and lifecycle as documented; not a directly executable CLI|android_api_snippet|
|[Research: cross-device Agent collaboration](runtime.cross-device.kt)|Kotlin method snippet in an ordinary Android project; complete Manifest, permissions and lifecycle as documented; not a directly executable CLI|android_api_snippet|
|[System diagnostics and performance tracing](diagnostics.system.sh)|Root shell on the phone|device_shell|
|[Research: ROM builds, OTA and recovery](release.ota.sh)|Read-only source-location command in an AOSP checkout; not evidence of implemented functionality|source_inspection|
|[Generate, preview and export standalone APKs on the phone](apps.generate.sh)|Root shell on the phone|device_shell|
|[Expose generated-app business functions / external calls](apps.business-api.sh)|Root shell on the phone|device_shell|
|[On-phone Codex CLI and low-level shell](agent.cli.sh)|Root shell on the phone|device_shell|
|[Query main/background displays through the SDK](displays.status.sh)|Host terminal, explicitly targeting emulator-5582|host_shell|
|[Query existing task state through the SDK](tasks.status.sh)|Host terminal, explicitly targeting emulator-5582|host_shell|

Kotlin examples are method-level development snippets, not complete runnable APKs. Source-location commands are read-only research. See android-api-guide for authorization, limitations and verification scope; do not relabel research examples as tested.

Phone-app business API source is in tooling/app-kit, and its device runtime must be deployed separately. A clean source checkout does not install that runtime automatically. Examples labeled as existing experimental entry points require an explicit development-device deployment prerequisite; a script directory name is not evidence of installation.
