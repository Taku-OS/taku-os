# feat(tryon): add TikTok LIVE outfit and character previews

## What changed

Two explicit TikTok entries hand one short-lived admission to the same native
front-camera preview. Each keeps the actual returned-frame reveal, movable small
window, separate expand/close targets and existing camera/RTC cleanup.

| Case | Entry and reference | Native profile |
| --- | --- | --- |
| Clothing LIVE | Accept Try on in a supported clothing LIVE; the Observer prepares the actual outfit reference | Lucy VTON |
| On-screen character | Hold the system navigation handle and make a complete character/outfit request, or double-tap and submit text; confirm Try on using that invocation's TikTok screenshot | Lucy 2.6 |

The character prompt requests clothing and wearable accessories while preserving
the user's face and hair; it is not an identity-fidelity guarantee. Character
does not require enabling the LIVE Observer or configuring its outfit-recognition
model. Voice requests still need the existing Home/Audio setup. Each user sets
their own fal API key for the selected mode in Taku Try-On. No key is bundled.

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

Add a dedicated [character setup and acceptance guide](https://github.com/Taku-OS/taku-os/blob/codex/live-tryon-delivery-local/docs/tryon-delivery/CHARACTER.zh-CN.md)
and separate the two cases' verification scope. The already backed-up November 23
ZIP remains fixed to `e059d1846ed5a458782e4f21ecaa3668b26a83b5` and SHA-256
`7a7462e236deca557ff3f04fd8f04b0a14c319151b9d16789b10249ea2b74d0f`.
Content inspection confirms its Native 96/97 character entry/profile and the
recorded Home 12 / Assistant 119 support. This supplement changes documentation
and the future assembly's document inventory only: no application code or APK
is changed or rebuilt. The new guide supplements the frozen archive; it is not
retroactively present in that ZIP or proof of new physical-device acceptance.

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

Character-specific historical evidence includes real voice-to-video output
(155 returned frames) and later typed-entry/camera results. Those recordings use
their stated earlier component versions; they are not acceptance for Native
96/97. The documentation supplement runs the existing host-only character policy
and actual-owner suites and the packaging regression; results are in CHECKS.json.
No Pixel, microphone, camera, ASR or model session is started by these checks.

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
- Follow-up independent LIVE VTON initialization captured upstream Decart
  `Concurrent session limit reached` behind `INTERNAL_ERROR`, before media.
  Session ownership and recovery remain unresolved; management requests need
  ADMIN credentials. These local diagnostics do not modify the frozen artifact.
  This supplement does not invoke character Lucy 2.6, so the VTON rejection is
  not a new success or failure test of that profile. Natural 60-second timeout,
  real close-button process persistence, and new candidate acceptance remain pending.
- The restored input path needs the existing platform-signed Assistant legacy
  view-host service and HOST_ISLAND_VIEW grant. Ordinary overlay fallback cannot
  receive input above the system status bar; no privileged service is bundled.
- The component bundle has no Quickstep/Launcher APK and is not a full ROM.
  Character needs the matching gesture bridge, default system assistant,
  enabled screen-context settings and existing Home/Audio configuration.
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

Prepared with AI assistance. This Draft PR publishes source and documentation
for review. Binary test artifacts are shared separately; this does not publish
a ROM, deploy to a device, or certify either case for production.
