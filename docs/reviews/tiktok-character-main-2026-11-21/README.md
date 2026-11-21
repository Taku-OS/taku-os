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

Environment: macOS arm64, Node 22.21.1, Python 3.13, JDK 23 with Java 17 target,
Kotlin 2.3.20, Android SDK/build-tools 36. The source APK uses the normal build script.

| Layer | Result and limit |
| --- | --- |
| Root checks | `npm run verify` and whitespace checks passed. |
| Agent UI checks | Typecheck, ESLint and all 453 Node tests passed after installation from main's lockfile. |
| Native core | Host policy, wire, reference, presentation, credential-pipe and lifecycle regressions passed. RTC cleanup fixture passed without JNI/network/model execution. |
| Try-On APK | Normal source build and signing verification passed, including the independent character task affinity. SHA-256: `80e6f2e9be5e3fa89e43cc4d69ac3db94bf3ed42632e49c0ca508614c461877a`. |
| Home policy/owner | Seven JUnit policy cases and nine asynchronous owner scenarios (47 assertions) passed. The owner fixture compiles actual production sources with explicit Android/provider doubles. |
| Home targeted SDK/Compose compile | The actual confirmation card, current Session, owner/policy, voice helpers and Audio client (26 source files) compiled with Android 36 and the Compose compiler. Existing UI/theme ABI came from a cached module JAR. This is not whole-module or APK acceptance. |
| Existing runtime components | Audio Engine, its client and the native Assistant/backend compiled from this main snapshot. No account or model session was used. |
| Home Gradle/APK | Not completed. NDK and grammar prerequisites were resolved; the subsequent React Android AAR download failed with `No space left on device` before production Kotlin compilation. Full compilation and runtime checks remain required. |
| Isolated emulator | A new stock API 36 ARM64 AVD booted, was verified and stopped. No feature installation or GUI/Agent runtime acceptance is claimed. |
| Physical gesture, real ASR, camera/model, ROM | Not run for this source port. Older prototype Pixel results do not establish these results for the new Home source. |

Detailed logs, source hashes and generated packages belong in ignored
`out/character-main/`, `out/tiktok-character-core/` and the module's output folders.
They are not source contributions. The Android build was paused when the host
filesystem reached its free-space reserve; no unrelated project or user data was
removed to work around that condition.

## Remaining acceptance

Complete Home release compilation and JVM regressions, build the Agent APK, then
install the matching backend, Audio Engine and both feature apps on an isolated
target. Verify GUI/Agent preference round trips, compact layout, failure close and
pause/late callback behavior using those exact source packages. A stock SDK AVD
lacks the Taku Launcher handle patch: verify a physical bottom-handle gesture on a
matching Taku image separately. Real speech and provider/account/region behavior
must also be recorded independently.

Development ROM preinstallation is opt-in with `TAKU_PREINSTALL_TRYON=true` after
checksum-verified Soong dependency staging. The saved Agent runtime asset is not
replaced by this local-only work; a future image must bind its component artifact
to the merged source through the existing build/release process.
