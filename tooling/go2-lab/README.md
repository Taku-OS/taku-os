# Go2 Robot Panel for Taku Phone

Go2 is a standalone development app for a Unitree Go2 robot, with a native Android shell, a WebView interface, and a phone-local Python/WebRTC service. This source imports the `0.7.0-robot-panel` application already installed on a Pixel 9; it is not part of the stock ROM or a production navigation release.

## Start here

- [Pixel setup and operation](phone/README.md): build the APK and install the ARM64 runtime in Termux. The phone can then connect to a robot on the same Wi-Fi without a Mac or USB cable.
- [Robot Panel](phone/ROBOT-PANEL.md): dashboard, camera, movement, sounds, navigation, settings, English/Chinese UI, and recorded UI verification.
- [Source handoff and current verification](PUBLICATION.md): provenance, test commands, known limits, and the difference between live device functions and scenario presentation.
- [Audio catalog and attribution](audio-presets/README.md): all 27 offline presets, transcripts, sources, and licenses.
- [Agent tool documentation](skill/taku-go2/SKILL.md) and [phone tool installer](phone/install-ai-tools.py): the existing CLI uses the same service as the UI.

## Capabilities

| Area | Included behavior | Limit |
| --- | --- | --- |
| Device | Explicit local connection, state, battery and freshness | Requires a reachable robot IP; no cloud pairing or automatic Wi-Fi provisioning |
| Camera | Live video, body detection, target recovery, relative distance and guidance | Camera-based scale is not a reliable global map position |
| Sounds | Search, filtering, phone preview, robot AudioHub playback, volume and stop | Real output needs a connected robot; imported RTP audio needs separate acceptance |
| Movement | Bounded direction/distance/turn controls with confirmations and stop | External-controller mode prevents app locomotion; physical acceptance is separate |
| Taku AI | System AI route, validated finite actions, manual takeover | No app-embedded provider keys; requires the user's system AI authorization |
| Environment | LiDAR/point-cloud display and obstacle diagnostics | Empty or stale data is not proof of a clear path |
| Navigation | AMap MCP queries, reference alignment, simulated route and local planning | Real autonomous map navigation is disabled; no verified global localization |
| Encounter scenario | Go / Stay / Circle / Come back stages and saved speech | Scripted UI stages are presentation, not proof of localization or autonomous travel |

## Build the Android app

From the repository root, with Android SDK 36 and JDK 17+:

```sh
export ANDROID_SDK_ROOT=/absolute/path/to/android-sdk
export JAVA_HOME=/absolute/path/to/jdk
bash tooling/go2-lab/build.sh
```

The development APK is written to `out/go2-lab/TakuGo2.apk`. It uses the repository's public AOSP test signing material, not a production signing identity. The APK alone does not install the Python service: follow the [Pixel installation steps](phone/README.md#重建与安装). Runtime configuration, robot keys, map keys, account state and recordings are device-local and are not source assets.

## Offline verification

Install the pinned Python dependencies with `bash tooling/go2-lab/setup.sh`, then run:

```sh
PYTHON="$HOME/Library/Caches/taku-go2/venv/bin/python"
for test_file in tests/go2-*.test.py; do "$PYTHON" "$test_file" || exit; done
node --test tests/go2-*.test.mjs
python3 scripts/check_structure.py
git diff --check
```

The four `tests/go2-*.test.cjs` browser suites require Playwright and a compatible Chromium. They use synthetic responses and must not be pointed at a real robot. See [PUBLICATION.md](PUBLICATION.md) for the tested environment.

## Architecture and development status

`src/` and `res/` contain the Android app. `phone/` manages the phone-local Termux/PRoot service and the AI action controller. `web/` contains the robot panel and feature pages. `bridge.py`, `protocol.py`, and the focused Python controllers own device communication and validation. `audio-presets/` contains offline media. `tests/go2-*` covers protocol boundaries, simulation, controllers and the UI.

This is an experimental app source handoff. The loopback service is not a production security boundary against other installed Android apps. Full parity with the current Taku App Permission Center and isolated-emulator verification remain integration work. The existing native AI access still goes through the system AI broker. Do not treat the scenario UI or historical simulation results as new physical robot acceptance.

The original Mac bridge/emulator instructions and historical experiment notes are retained in [LEGACY-MAC.md](LEGACY-MAC.md). For Pixel installations, use `phone/install-runtime.py`; the older `install.sh` is for an explicitly selected development emulator and creates ADB reverse mappings.
