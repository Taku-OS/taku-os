# TikTok character preview source integration

Base: upstream main `65917a4a658d06a39ac9e76b46a84e3d548f8aee`.
This topic contains the native Try-On component and the existing Kotlin Home
Assistant's short-video character route. It does not import the LIVE observer,
replace Home/Onboarding, change the task executor or bundle an APK, account or key.

## Final behavior

An eligible system navigation-handle long press retains the existing screenshot
gateway handoff and Audio Engine recording. A complete supported character request
uses that invocation's bounded frame. Other utterances retain the normal send path.
Home shows a compact English confirmation at the top, with Not now and Try on.
Only the current confirmation can prepare a one-use native handoff ticket.

The native entry has an independent task affinity and a transparent one-pixel
window until the camera-window acknowledgement. It cannot bring an old settings
task forward as its backdrop. Preparation has no white page; failure alone shows
an actionable English alert. Cancellation and expiry wipe pending frame copies,
and late callbacks cannot act on a new request. The result window retains its
existing minimize, expand, resize, compare and close controls.

The native GUI and Agent controls use the same controller, stores and permission
checks. Voice language belongs to Audio Engine `assistant-entry`; there is no
second language preference. Ordinary callers follow the App Permission Center.
Native and Home builds use the same pinned public development signer by default;
ROM builds use the platform signer. Explicit legacy development signing remains
available without accepting arbitrary target signatures.

## Verification as recorded locally

Environment: macOS arm64, Node 22.21.1, Python 3.14.3, JDK 23 with Java 17 target,
Kotlin 2.3.20 and Android build-tools 36. Runtime checks use an API 36 ARM64 AVD;
Home retains main's compile SDK 37 and target SDK 36. Both release APKs use the
normal source build scripts.

| Layer | Result and limit |
| --- | --- |
| Root checks | `npm run verify` and whitespace checks passed. |
| Agent UI checks | Typecheck, ESLint and all 453 Node tests passed after installation from main's lockfile. |
| Native core | Host policy, wire, reference, presentation, credential-pipe and lifecycle regressions passed. RTC cleanup fixture passed without JNI/network/model execution. |
| Try-On APK | Normal source build and signing verification passed, including the independent character task affinity. SHA-256: `03b756535f474b1fd2fad434e850af01e48cb65eb9a9c1c8c2453d38910a6eef`. |
| Home policy/owner | Seven JUnit policy cases and nine asynchronous owner scenarios (47 assertions) passed. The owner fixture compiles actual production sources with explicit Android/provider doubles. |
| Existing runtime components | Audio Engine, its client and the native Assistant/backend compiled from this main snapshot. No account or model session was used. |
| Home Gradle | Real release Kotlin compilation and all 184 debug JVM tests passed, including the seven new character policy cases. |
| Home release APK | The complete normal source build, alignment and signing verification passed. Package `org.agentos.agent`, version `12 / 0.5.1`. SHA-256: `05e113da0d26934096cce54837d759c40c4bdfadbf336e9cbec5ab50678dc632`. |
| Core GUI/Agent on an isolated emulator | Before the final import-binding change, four checks passed on the whole Core APK: settings round trips in both directions, rejection of the removed language preference with the Audio Engine route, and an English missing-ticket failure that closes its independent task. No model session was started. The final Core build is covered by the Home IPC checks below and the import-binding regressions. |
| Transparent entry on an isolated emulator | Five Android lifecycle cases passed using the final production Direct entry source with explicit controller/import doubles: transparent preparation and simulated acknowledgement, closeable English failure, and pause/late-callback cleanup. This does not establish a real camera or model acknowledgement. |
| Home release on an isolated emulator | After installing the matching source-built backend and Audio Engine, actual Home visibility, public Assistant projection and system-assistant declaration checks passed (3/3). The first launch failed because the bare AOSP AVD lacked the backend; that setup attempt is retained separately. No private injection seam was used for the release smoke. |
| Home interaction on an isolated emulator | The normal source debug APK passed 3/3 checks: compact confirmation with GUI cancellation and sanitized Agent projection, cancellation through the Agent API, and Try on reaching the final Core's real missing-credentials rejection with a closeable failure card. The real Home owner/UI and Core IPC ran; the transcript and frame were synthetic private test inputs. Debug APK SHA-256: `dee8a2d6946afe4743502edfa9646dac741f743bc0110c3acb49120f9ee8a71f`. |
| Physical gesture, real ASR, camera/model, ROM | The later physical entry check failed before reaching Home; see the follow-up below. Real ASR, camera/model and ROM acceptance remain unverified. Older prototype Pixel results do not establish these results for the new Home source. |

The existing dictation alpha mask now uses the theme's text colour instead of a
literal RGB value, as required by the existing design-system test. Its `DstIn`
blend and alpha curve are unchanged. This allows the full native JVM test suite
to run in CI without weakening that test.

Detailed logs, source hashes and generated packages belong in ignored
`out/character-main/`, `out/tiktok-character-core/` and the module's output folders.
They are not source contributions. An initial Android build was paused at the host
filesystem's free-space reserve and resumed after space became available. No
unrelated project or user data was removed to work around that condition.

## Remaining acceptance

A stock SDK AVD lacks the Taku Launcher handle patch. A physical bottom-handle
gesture and actual authorized system screenshot must be verified on a matching
Taku image separately. Real ASR, positive ticket handoff, camera/model
acknowledgement and provider/account/region behavior remain unverified by this
local source port. No model key was written or cloud model called during these
isolated checks. The later Pixel attempt is recorded below.

Development ROM preinstallation is opt-in with `TAKU_PREINSTALL_TRYON=true` after
checksum-verified Soong dependency staging. The saved Agent runtime asset is not
replaced by this local-only work; a future image must bind its component artifact
to the merged source through the existing build/release process.

## Physical Pixel follow-up

The source-built Home, backend, Audio Engine and Try-On APKs were installed over
the connected Pixel 9 using their existing signers and version codes. Native build
metadata overrides (`TAKU_ASSISTANT_VERSION_CODE=117` and
`TAKU_TRYON_VERSION_CODE=83`) used normal source packaging, with no APK/Dex patching
or application-data clearing. All four package UIDs were retained; Try-On still
reported the existing model key, permissions and enabled preview as ready.

The complete physical workflow **did not pass**. A real navigation-handle hold in
TikTok opened Google's screen instead of preparing the Home gesture. The installed
Quickstep v40 uses the older `taku.voice.gesture.v1` protocol and fixed legacy
Assistant target; it lacks main's Home `gesture/event` route. Home's real public
projection remained idle, with no character frame or ASR session. The installed
root voice configuration runtime also returned `UNSUPPORTED_OPERATION` to the
new Audio settings interface. This is a mismatch between the phone's earlier
system components and the current source integration, before character consent
or model startup.

No matching source-built Quickstep APK was available locally. The historical
Linux builder could not be reached using its existing SSH configuration. No cloud
machine was started, Launcher modified, root broker restarted or ROM flashed.
Testing needs the matching main Launcher and voice runtime first; replacing the
four app APKs alone is insufficient. Actual ASR, positive handoff and camera/model
startup remain unverified on this phone, and no Try-On model session was started.

All four original APKs and assistant/recognition selections were restored after
the failed entry check, with exact installed APK hash and UID verification. The
existing Try-On configuration remained ready. Detailed preflight, screenshots,
install and restoration receipts are in ignored `out/character-pixel-20261121/`.
