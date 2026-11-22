# feat(tryon): deliver TikTok LIVE outfit preview with user-owned Lucy credentials

## What changed

An explicit Try on in a supported TikTok clothing LIVE prepares the actual outfit
reference and hands one short-lived admission to the native front-camera preview.
The result stays in a movable small window with separate expand/close targets.
The existing Home character path, actual returned-frame reveal and camera/RTC
cleanup remain authoritative. Lucy credentials are configured by each user in
Taku Try-On and are never included in the source or delivery.

Integrate the LIVE observer with the native source branch, share its handoff
contract, retain the startup acknowledgement and informational RTC queue fix,
and provide current-platform and explicitly separate recorded-Pixel adapter
builds. Include opt-in image targets, automated checks, artifact assembly,
matching component declarations and installation/setup documentation.

## Type of change

- [x] New feature or capability
- [x] Bug fix
- [x] Documentation
- [x] Tests or tooling
- [x] Build, image or OTA (opt-in product integration only; no OTA release)

## Area

`system/tryon`, `system/shopping-observer`, `system/proactivity`, existing
`system/agent-app` / Assistant character integration, SystemUI owned source and
patch, opt-in image configuration, CI and delivery documentation.

## Verification

The local handoff includes machine-readable build and check results. Results
must remain attached to the exact commit and package hashes.

| Layer | Scope |
| --- | --- |
| Source/build/offline | Root verify; native/observer/proactivity host suites; actual RTC cleanup, pressure/FIFO and 40-message burst tests; Native 94, Observer 28 and Pixel adapter builds; packaging integrity tests |
| Emulator | Existing Native 93 UI fixture: 24 layout/control combinations, no camera/model; recorded LIVE Activity/ACK deterministic checks are host checks |
| Physical device | Existing designated Pixel 9 / Android 17 LIVE/Lucy records and user acceptance; Native 93 installed/hash checked; fresh 94/28 candidates are not newly installed by this delivery task |
| Full ROM | Not run; normal SystemUI Soong build, clean-image install, reboot and OTA acceptance remain pending |

## Agent parity

Native UI and `content://org.taku.tryon.api` share the same controller and state.
Observer UI and `content://org.agentos.shopping.api` share preferences and guarded
runtime handlers. Credential writes use the existing write-only content pipe;
reads expose configured metadata only. No parallel task service, queue or model
credential store is added. Existing isolated-runtime evidence and host checks are
retained; no new device/UI parity test is claimed for packaging-only commands.

## Limits and risks

- Current-platform source candidates and the recorded Pixel compatibility set
  have separate signatures, dependencies and verification scope.
- The included black SystemUI artifact is a local development compatibility
  rewrite. The source fix is present, but full source/ROM validation is pending.
- A separate in-progress Pixel touch-host restoration is outside the frozen
  Native 93 baseline. Its final source/package must be incorporated and retested
  before this preview becomes a public user release.
- This is a development-preview handoff, not an arbitrary-Android installer or
  a publicly released ROM. TikTok UI changes and provider session limits remain
  real failure conditions. Each retry remains explicit and bounded.
- Automatic classification and system image recognition need their own user
  consent and Taku AI configuration; a fal key alone does not configure them.

## Checklist

- [x] Documentation, shared contract and preinstalled native interface docs included
- [x] No model key, authentication state, device app data, private signing material or APK in Git
- [x] Third-party native dependencies retain their existing lock and license notices
- [ ] Integrator completes normal SystemUI/ROM source build and cold-boot acceptance before public release

Prepared locally with AI assistance. This handoff does not create a remote PR,
push a branch, publish a release or certify a new full ROM.
