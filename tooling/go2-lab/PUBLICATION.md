# Pixel Go2 source handoff

This contribution imports the existing `org.taku.go2lab` app, version `0.7.0-robot-panel` (Android version code 15), onto current `main`. It leaves stock ROM packaging, the system assistant and model-account configuration unchanged. The source is a development app for review, not a claim of completed production integration.

## Provenance

On 2026-11-26, the connected Pixel 9's installed APK matched the preserved local build byte-for-byte (SHA-256 `755883235b2cc3b70ad492cf43633471586ad59c16ffba6c4abf642675d6fffe`). All 118 checked phone runtime code/media files matched the preserved source. No robot key, map key, AI provider credential, account backup or user recording was copied from the device.

The import starts from current `main`, rather than publishing the old local branch history. Go2 source, its tests, offline presets and related documentation are the scope. Historical Mac-bridge scripts are retained for reference; [the current Pixel setup](phone/README.md) is the supported starting point for this app.

Publication adjustments are limited to an English entry point, current license/provenance notices, removing host-specific paths from historical notes, updating the optional development-kit source list/version, and making the browser fixtures self-contained. Application behavior is preserved. The phone was not reflashed, reinstalled or instructed to move a robot for this handoff.

## Verification on 2026-11-26

| Layer | Result |
| --- | --- |
| APK source build | Passed with JDK 17 and Android SDK 36, including signature verification; output stays under ignored `out/` |
| Go2 Python tests | 233 cases across 31 test files passed using the existing pinned Python 3.12 environment |
| Go2 JavaScript tests | 51 tests passed with `node --test tests/go2-*.test.mjs` |
| Browser contracts | Robot panel, sound page, follow observation/stop and reconnect recovery passed with intercepted responses; no robot network |
| Repository checks | `LANG=en_US.UTF-8 LC_ALL=en_US.UTF-8 npm run verify` passed, including all existing repository gates |
| Source/security | Structure/link checks, whitespace and syntax checks passed; Gitleaks 8.30.1 reported no secrets in the staged contribution |
| Physical Pixel | Installed APK and runtime-file comparison; opened the existing offline home and captured its real display |
| Isolated emulator | Not rerun for this source handoff; the earlier UI and device evidence is dated in the owning module |
| Robot / full ROM | Not run; no locomotion, audio playback, live camera or autonomous-route acceptance in this handoff |

Run the commands in [README.md](README.md). Browser tests require Playwright. If Chromium is already installed, point `PLAYWRIGHT_EXECUTABLE_PATH` at its executable; all four browser suites support this override. Their camera pixels and landmark data are synthetic, not a detection benchmark.

## Review map

- Android UI and system AI client: `src/org/taku/go2lab/`, `res/`, `AndroidManifest.xml`, `build.sh`.
- Phone runtime and finite-action planning: `phone/`, including `assistant.py`, `distance.py`, `follow_control.py`, `map_tools.py` and `navigation_preview.py`.
- Protocol, telemetry and control: `bridge.py`, `protocol.py`, `motion.py`, `camera.py`, `lidar.py`, `audio_hub.py` and related controllers.
- Presentation: `web/panel.*`, shared `design-system.css`, feature pages and the existing encounter sequence.
- Tests: `tests/go2-*`; test-only helper files under `tests/helpers/`.
- Third-party materials: [THIRD-PARTY.md](THIRD-PARTY.md), the audio catalog and retained license texts.

## Integration work still required

1. The phone runtime currently uses Termux + PRoot. Installation of the APK alone is insufficient. Packaging a supported runtime and integrating lifecycle/updates with Taku OS is separate work.
2. The existing UI and CLI use the same loopback service and controller validation. This service does not yet enforce Android caller identity or the current central App Permission Center on every robot operation. The native AI client uses the system AI broker, but that is not equivalent to protecting all local HTTP routes. Full GUI/agent parity and ordinary-app isolation need review and isolated-emulator acceptance before default installation.
3. `Go handle him for me`, Stay, Circle and Come back include scripted presentation stages. Displayed location/planning progress is not a new positioning sensor or verified autonomous motion. External-controller mode keeps locomotion outside the app.
4. Real map autonomy remains disabled. AMap queries and local/relative pose do not establish a reliable robot map coordinate. Camera/odometry quality, localization, stop behavior, route completion and live follow need supervised hardware acceptance.
5. Historical Mac simulation results cannot be used as new Pixel navigation acceptance. The documented PRoot timing/staleness limitations remain.
6. Public AOSP test signatures are development-only. Production signing, third-party/design-asset distribution review and longer-running device compatibility remain release work.

The draft PR preserves this complete, reviewable source snapshot while those integration boundaries remain explicit.
