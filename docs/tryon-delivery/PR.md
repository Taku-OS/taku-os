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
matching component declarations and installation/setup documentation. Retain the installed Assistant permission-scope fix so `taku.control` setup is not incorrectly rejected as a workspace scope.

Remove the extra running/normal-stop AI try-on text control card, restore the
visible Preparing/Live capsule and existing system-owned video host input path,
withdraw automatic suggestions while the real preview is open, and recognize the
verified TikTok DetailActivity LIVE container without relaxing its native room
anchor checks. Keep the approved corner controls and terminal failure card.
Abort the raw signaling connection immediately on stop, then close TLS/WebSocket
on the existing cleanup actor and wait for late socket creation before confirming
local cleanup. No automatic paid retry or new UI is introduced.

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
| Source/build/offline | Root verify; native/observer/proactivity host suites; actual RTC cleanup, pressure/FIFO, informational burst and signaling-close race tests; Native 97, Observer 30 and Pixel adapter builds; packaging integrity tests |
| Emulator | Fresh Native 97 / Observer 30 Pixel adapter: 24 layouts, 18 actual Window operations, 30 system-host assertions and 3 real touch events; preview guard enters/exits. No camera/model or new content-provider device parity claim; recorded LIVE Activity/ACK deterministic checks are host checks |
| Physical device | Recorded Native 96 local cleanup after failed initialization, with no new crash observed; Observer 29 automatic invitation stable for 24.81 seconds without starting try-on. Fresh 97/30 candidates are not newly installed by this delivery task |
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
- The five follow-up repairs are included in source and rebuilt candidates. The
  recorded Pixel set is updated to Native 96 / Observer 29; historical success on
  older packages is not acceptance for this new source build.
- Lucy still returned platform INTERNAL_ERROR during initialization in a recorded
  attempt at about 9.4 seconds, with zero generated frames. The exact upstream
  cause remains unknown. Natural 60-second timeout and real close-button process
  persistence checks for Native 96, and fresh 97/30 device acceptance, are pending.
- The restored input path needs the existing platform-signed Assistant legacy
  view-host service and HOST_ISLAND_VIEW grant. Ordinary overlay fallback cannot
  receive input above the system status bar; no privileged service is bundled.
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
