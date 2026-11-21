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
| Physical Pixel entry and ASR | The subsequent Pixel upgrade verified the real navigation-handle entry and microphone-based English ASR with the matching runtime. Its first natural-utterance run failed before character confirmation; see the upgrade follow-up below. Positive screenshot/character handoff, camera/model and full-ROM acceptance remain pending. |

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

A stock SDK AVD lacks the Taku Launcher handle patch. The later Pixel upgrade
verified physical bottom-handle entry and real English microphone ASR. A fresh
authorized screenshot, character confirmation, positive ticket handoff,
camera/model acknowledgement and provider/account/region behavior still require
the complete Pixel retest described below. No model key was written or cloud
model called during the isolated emulator checks. Full-ROM acceptance remains
separate from the installed component updates.

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
That attempt needed the matching Launcher and voice runtime first; replacing the
four app APKs alone was insufficient. At that point, actual ASR, positive handoff
and camera/model startup remained unverified on this phone, and no Try-On model
session was started.

All four original APKs and assistant/recognition selections were restored after
the failed entry check, with exact installed APK hash and UID verification. The
existing Try-On configuration remained ready. Detailed preflight, screenshots,
install and restoration receipts are in ignored `out/character-pixel-20261121/`.

## Pixel upgrade follow-up (2026-11-21)

A later attempt installed the matching runtime and restored the real Launcher to
Home entry. Quickstep was extracted unchanged from the official November 18 OTA's
`system_ext` partition, after verifying all 763 payload operation hashes and the
complete partition hash. Its APK SHA-256 is
`50da56c51c2e39f7b0b9dc68353d39ca71aed104bd12b8085fa9befad503be10`;
the verified testkey certificate is
`a40da80a59d170caa950cf15c18c454d47a39b26989d8b640ecd745ba71bf5dc`.
The standard root `pm install -r -d` path installed version 37 over the prototype's
version 40 while preserving its UID and data. The APK was not patched or resigned,
and no complete ROM was flashed. Actual DEX inspection confirmed the new Home
`prepare`/`gesture/event` contract. The published ROM has no recorded build source
commit, so this does not establish binary identity with the current main Launcher
or its later Recents fixes.

The current installed set is:

| Component | Identity / SHA-256 prefix |
| --- | --- |
| Quickstep | `37`, `50da56c51c2e` |
| Home | `12 / 0.5.1`, `7175b504d295`, UID `10256` |
| Backend | Normal source packaging with version override `117`, `b8319d166360` |
| Audio Engine | `d45142510fa8` |
| Try-On | Normal source packaging with version override `85`, `db160ff29a9d` |
| Task broker | `a97143c75f25` |
| Audio bridge | `ff7f07e816e4` |

The broker and audio bridge were bind-mounted at their existing system paths and
the init-managed broker was restarted. These replacements last only for this boot
session; rollback copies and the original underlying files were retained. The App
Permission Center changed from `deny` to `manual`, with only Launcher's
`taku.control` granted. Launcher has no `taku.root` or memory grant; other untrusted
callers remain denied.

The first microphone run used the preceding Home APK, `05e113da0d26`. Audio
Engine's authoritative `assistant-entry` preference resolved to Zipformer,
`en-US`, local processing and no fallback. The user's real microphone utterance
`HELP ME PUT ON THIS OUTFIT OF HIS` reached voice `Closed / Success` and delivery
`Submit / Consumed`. This verifies actual English ASR and system gesture entry,
rather than the earlier synthetic emulator inputs. It did not complete the
character case: `characterTryOn` stayed null and the ordinary submission reported
`SCREENSHOT_UNAVAILABLE`.

Two independent prerequisites explained that first run. Both standard Android
screen-context settings, `assist_structure_enabled` and
`assist_screenshot_enabled`, were `0`; the Android 17 framework requires both for
this screenshot handoff. The original exact character-command list also did not
accept the user's natural outfit wording. Both system settings have now been set
to `1` through the standard settings interface. A narrow command-policy update
accepts the requested wording. Its nine policy tests, nine asynchronous owner
scenarios with 47 assertions, lint and typecheck passed.

The normal Home release build succeeded in 5m44s and was installed in place
successfully, retaining UID `10256`. Its APK SHA-256 is
`7175b504d2957669207388e1deeb7cbfa44540714fabe021e24a2d11fd8f192f`,
with verified certificate SHA-256 prefix `c8a2e9bccf59`. All five installed APK hashes were checked
individually. Both screen-context settings still read `1`; the assistant and voice
interaction service both resolve to
`org.agentos.agent/org.agentos.agent.home.assistant.SystemAssistantService`.

The user was asked to repeat the real English request, tap Try on and face the
front camera. That checkpoint's bounded observer saw no new input; the subsequent
black-screen report and its fix are recorded below. A fresh authorized screenshot, compact character
confirmation, positive transparent ticket handoff, camera preview and returned
model frames are still pending; this record does not claim a successful
camera/model session. Full hashes and the
installation, runtime, permission, settings and actual ASR receipts are retained
in ignored `out/character-pixel-upgrade-20261121/`; OTA extraction and APK checks
are in ignored `out/launcher-from-official-rom/`.

## Preserved-data Home startup follow-up

The next user attempt exposed a Home startup crash before camera launch.
`HomeActivity` synchronously restored Feed state; two cards saved by the older
Home contained three retired `panel` actions, which main's strict parser rejected.
The SQLite database was healthy, with migration still uncommitted. This was a
preserved-data upgrade case that the earlier fresh-emulator checks had missed.
The original database and legacy JSON were backed up privately on the phone.
Quickstep temporarily served as Home while the failure was repaired.

The storage-only parser now retains those cards and represents the removed actions
as unavailable. New published/generated `panel` actions remain invalid. Original
card JSON, all pins/removals and legacy history survive migration and later
restoration. GUI and Agent read the same action-availability projection; affected
controls display an English notice and are disabled. Dispatch rejects them before
recording an accepted event or executing an integration. No removed panel bridge
or LIVE observer was restored.

Validation passed: 24 Feed JVM cases, 472 Node tests including 19 directed binding
and renderer checks, typecheck and lint. A separate API 36 AVD used synthetic legacy
JSON and an empty SQLite v1 database. Both first migration and a genuine cold AVD
restart retained the two cards, three unavailable actions, 41 removals, four pins
and 50 original history payloads, with the same database epoch after restart.
Actual GUI-origin dispatch and Agent calls each rejected all three removed
actions, started no Activity and left the event head unchanged. Real React Native
card rendering showed the notice, disabled old controls and an enabled valid
control; the native pinned play control was also disabled. The device fixture
does not claim layout/touch acceptance for every catalog control; those bindings
also have directed React SSR coverage.

The normal Home release build succeeded in 4m46s. Its APK SHA-256 is
`ac5dacdcbdf98eac4097e178d17c5daa94e8bb3cce8456a965cb8143323c05a0`,
with the same platform development certificate and package UID. The backend was
normally rebuilt with the updated preinstalled Agent documentation, SHA-256
`2fb13276196e5ec49240a031de3ab4341bac0d9c4b8d3d2e0fe87a65747e0f71`;
its DEX and all non-signing payload entries except that documentation match the
preceding backend APK.

On Pixel, the actual original JSON passed a read-only check using the production
parser before installation. After the source release was installed without data
clearing, migration and two cold Home launches passed, the original JSON hash was
unchanged and all counts above were retained. The default Taku Home role was
restored. The real Feed showed the unavailable-action notices and disabled
controls, while three public Agent action calls returned `ACTION_UNAVAILABLE`
without changing the event cursor. A real system-handle hold then reached local
Zipformer capture with `assistant-entry/en-US`; it was cancelled before finger
release, submitted no speech and returned to idle without an error.

The user has been asked to perform a new real spoken character request. Positive
reference/confirmation/camera/model acceptance is still pending at this checkpoint.
Synthetic AVD reports and reproducible drivers are retained under
`out/legacy-feed-compat-20261121/`; sanitized Pixel receipts are under
`out/character-pixel-upgrade-20261121/black-screen-1/`. Private phone data and
generated APKs are not source contributions.

## Stale working indicator follow-up (2026-11-22)

The moving colored border was Home's `working` indicator for an earlier ordinary
chat task, not microphone capture. Public state showed voice idle and Live Talk
closed. The exact task was ownership-checked through `taskFiles/list`; a public
cancel request wrote its marker but did not end the running task. The init-managed
broker was blocked in synchronous `am broadcast` before starting its execution
screen. Restart recovery marked the task `failed` with `done`; Home then cleared
working and the border disappeared. History and phone data were retained.

The restarted broker also waited for the notification receiver during recovery,
before `NativeBridge.start()`. `notifyUser` now sends the same action, component
and task ID through `cmd activity broadcast --async`, using the existing bounded
command runner. Notification failure remains best effort and does not rewrite
task outcomes. No global command execution or Receiver behavior changed.

The normal backend build and existing SessionRemovals (875 checks) and RoundRobin
(99 checks) regressions passed. A dedicated API 36 AVD receiver held `goAsync()`
for 6.003 seconds: the default command waited 6.130 seconds, while `--async`
returned in 0.010 seconds, before receiver completion. This fixture verifies
Android's wait behavior, not the exact internal cause of the production receiver
delay. The temporary test package was removed and its isolated AVD stopped.

The rebuilt broker SHA-256 is
`c6139e9e9cd833480888a7f42f123e8b3fadacbf130bf7a93d276e94d46e91e4`.
It replaced the previous broker through a reversible boot-session bind mount;
the previous bytes were retained and the app APKs were not changed. On Pixel,
the six queued management requests drained, notification output files were
cleaned, the native endpoint became ready, and the read-only runtime projection
reported no active turn, pending request, task queue or cleanup. The failed test
conversation was then reset through Home's public interface. No new speech or
camera/model success is claimed by this recovery.

Sanitized recovery/build/runtime receipts remain under
`out/character-pixel-upgrade-20261121/marquee-20261122/`; the isolated broadcast
receipt and fixture are under `out/notification-broadcast-regression-20261122/`.

## Typed character requests (2026-11-22)

The existing system navigation-handle double tap now supports typed character
requests from TikTok. It captures through the same Android assistant and gateway
authorization as hold dictation, then opens the existing text composer. GUI Send
and public `assistant/ask` share dispatch into the same compact confirmation and
Try on handoff. No microphone, separate text plugin or caller-supplied reference
is needed. Explicit `assistant/voice-to-text` also transfers the current system
hold reference without renewing its age.

A process-local text invocation lease binds capture, the text overlay and one
submission. Cancel, close, screen lock, a new entry and conversation changes
invalidate it, including pending callbacks. Overlay intents cannot mint or revive
the lease. Configuration restoration can render the same pending confirmation
without permitting ordinary intent replay. The original 120-second invocation
limit still covers capture and editing. `assistant/get.characterReference` exposes
only fresh, unused input/source/elapsed-expiry metadata; image bytes and tickets
remain private.

Typed dispatch reads the authoritative composer under its existing lock before
interception, preserving attachments and pending receipts. A captured invocation
and submission marker prevent an older asynchronous send from consuming a newer
frame. Duplicate sends during checking/confirmation/start return `BUSY`.
Unrelated messages and requests with attachments retain ordinary chat behavior;
disabled/unavailable Try-On falls back with the text surface still visible.

The text policy/lease/IME gate suite passes 20 JUnit cases. The production owner
fixture passes 16 scenarios / 82 assertions; the existing gesture policy (7) and
voice interaction (20) suites, lint and typecheck also pass. Review caught and
fixed authoritative-attachment admission, visible text fallback and confirmation
configuration restoration before device acceptance.

The first normally built text Home release was `78c9d620c8953636b0ce4986d6d5ecb867aaa06bb4fd5843455e9053f0980ad2`.
The source-built backend documentation APK is
`188c17619e7dd46ee9982f0a052abb6ac7ac519c65964e51c6098337645bbdbd`;
its DEX is unchanged, and its only non-signing payload change is the preinstalled
open-access documentation. Both retain the existing development certificate.

On a dedicated API 36 AVD, a standard framework assistant invocation captured an
explicitly synthetic TikTok fixture; it did not inject a frame, Home lease or
ticket. GUI keyboard Send and public `assistant/ask` each reached the real English
confirmation with voice idle. Duplicate ask returned `BUSY`, confirmation survived
configuration restoration, and cancel cleared its reference. Screen sleep cleared
the reference. A real 120-second wait invalidated the original frame without
changing the clock or invoking again. No cloud model or credential was used there.

That AVD also exposed one intermittent early text-window closure and a disabled
fallback whose text window disappeared. The exact close caller was not captured;
the startup IME visibility transition is a hypothesis, not a confirmed stack.
The composer now arms keyboard-dismiss only after a visible keyboard has positive
height, and closes after it is both invisible and zero-height. This uses no delay
heuristic. Four pure production-gate regressions cover failed show, animation,
reopening and dictation. The resulting `b2efdf54f80c` release passed ten consecutive
fresh entries, GUI/API confirmation and configuration restoration, but the
disabled fallback still exposed a programmatic hide/show overlap. Its fixed,
non-private IME diagnostic confirmed the close caller. The final structural fix
keeps the text composer and matching draft throughout Checking; it hides the
composer only for confirmation/start/failure and clears a matching draft only on
Confirming. An unavailable/disabled result now retains Text without a hide/show
transition. Owner regressions replay both sides of that asynchronous boundary;
final device acceptance is recorded below.

On Pixel, the normal in-place update retained Home UID `10256`. Real navigation
double tap captured the actual TikTok screen; actual keyboard typing and GUI Send
reached confirmation with the microphone idle. A GUI Try on click transferred the
reference to Core and opened camera device 1. It supplied 15 camera input frames,
then Lucy returned `lucy_session_busy` before receiving any answer, image/prompt
acknowledgement or generation-start signal. Returned frames were **0**. This is
not successful model output. The English failure sheet was visible, no automatic
retry occurred, and GUI Close removed it; camera-service readback then showed no
active clients and Core cleanup was complete. Core's terminal snapshot retains
its historical `presentation:"compact"` after that separate result sheet closes.

The camera/model checkpoint used Home `78c9d620c895`; the later IME-only fix is
validated separately and does not imply another cloud attempt. Private/sanitized
Pixel acceptance artifacts are retained under
`out/character-pixel-upgrade-20261121/text-pixel-2/` and `text-camera-1/`.
Synthetic AVD fixtures and receipts are under `out/text-character-20261122/`.

The final normal source release, including the Checking transition fix, is
`b7b56c29c4116e1058de7ed6fc3eaa0190f98a9a5983c7d5c811248a88457530`
(build succeeded in 4m23s). On the dedicated AVD, both disabled Core and a real
`pm disable-user` unavailable Core retained the actual text field, keyboard and
original draft through the subsequent genuine `RUNTIME_UNAVAILABLE` chat failure;
the AVD has no ordinary-chat runtime and no successful chat reply is claimed.
Core was re-enabled afterward.
The same final release passed five further fresh framework entries and the
authoritative attachment race: adding an attachment through the backend without
refreshing Home prevented interception and preserved that attachment. The test
attachment was removed through the public API. Final AVD receipts are under
`out/text-character-20261122/runs/b7b56c29/`.

The final APK was installed in place on Pixel with the same UID, then verified by
installed-byte hash. Actual English keyboard GUI Send and Chinese public text
submission each reached confirmation; duplicate send returned `BUSY`. GUI Not now
and public cancel both cleared the reference and confirmation. The final state
was input closed, microphone idle, no task/error and no working glow. The backend
was ready with no active turn, queued task/request or cleanup pending. This final
verification started **no** additional cloud model session. Its receipts and
screenshots are in `out/character-pixel-upgrade-20261121/text-pixel-complete/`.

## Direct first-utterance dispatch follow-up (2026-11-22)

Pixel later showed the original character request and its attached frame in full
chat, followed by clarification questions about avatars, pets and personas. That
is not character-confirmation acceptance. Source review found that Keep talking
discarded the authorized reference before routing its first completed recording
to Live Talk; an unavailable Live provider could then leave that request in the
ordinary composer. The captured screen establishes the ordinary-chat outcome,
but does not establish every preceding gesture or recovery callback.

The first Keep talking utterance now retains only the existing fresh system
TikTok reference and defers full chat/provider startup until its final ASR result
is classified. A matching request claims the native character owner and closes
the provisional Live/audio delivery without clearing that claim. Unrelated
speech follows the existing Live handoff. Gesture identity, recording operation
and the original elapsed-time expiry bound the lease; cancellation, lock, empty
or failed recognition and expiry revoke it. Configuration recreation preserves
the same claimed confirmation, while leaving its surface cancels it. Old chat
messages and arbitrary image attachments cannot recreate a reference.

Recognized character requests now show a native failure/Close card when Core is
disabled or unavailable. This supersedes the ordinary-chat fallback described in
the earlier text-entry acceptance above; those earlier receipts remain historical
evidence for that older release.

The final source snapshot passes 25 character JUnit cases, 20 voice-interaction
JUnit cases and 21 production-owner scenarios / 106 assertions, plus lint and
typecheck. The snapshot and private diagnosis are under
`out/character-direct-implementation-final.json` and
`out/character-pixel-followup-20261122/`. Final Android build and device acceptance
are pending: the build after the surface-stop fix failed because the host disk
filled. Pixel still has Home `b7b56c29c411`; neither the earlier build candidate
nor host tests establish device acceptance of this follow-up. No additional
cloud generation has been started.
