# Taku system entry policy

Taku images set `ro.taku.home.package=org.agentos.agent`. The immutable product
policy uses Android's HOME role and preferred-activity records; it does not maintain
a second default-launcher database. The property has an explicit SELinux type and
is readable by Agents as well as system components.

* PermissionController assigns Taku on first role initialization and uses it as the
  fallback when HOME is removed/cleared. On package/OTA reconciliation, other HOME
  holders no longer qualify while Taku's exported HomeActivity is available to that
  user. Settings receives the same single qualifying home. Managed profiles retain
  Android's existing role rules.
* The ASSISTANT role follows the same policy, but only when Taku's real, exported
  VoiceInteractionService and valid voice metadata are present. VIMS repairs both
  `assistant` and `voice_interaction_service` at user startup and on setting changes.
  Role reconciliation handles app upgrades; app boot/unlock/MY_PACKAGE_REPLACED repair
  is additional protection, with `goAsync()` covering completion and a bounded timeout.
* PackageManager resolves an implicit MAIN/HOME directly to Taku before consulting
  a stale preference or producing a resolver. This closes the asynchronous role
  repair window, even when the app process is not running. It respects the candidate
  list already filtered for user, Direct Boot, enabled state and caller visibility.
  Explicit/package-scoped Quickstep requests and secondary-display HOME are unchanged.
  A higher-priority setup wizard still runs. Before unlock, Settings FallbackHome can
  wait for credential-encrypted storage; the launcher is not falsely made Direct Boot aware.
* The runtime retries HOME and ASSISTANT bindings on every start, with no old-package
  fallback or `ui-initialized` marker. Voice entry is
  `org.agentos.agent/.home.assistant.SystemAssistantService`. Failure to assign the
  roles logs an error and does not stop the task broker.
* PhoneWindowManager directs hardware voice/search keys to Taku's explicit voice
  entry instead of Android's legacy web-search resolver. Locked entry goes through
  keyguard dismissal. This covers the separate KEYCODE_VOICE_ASSIST path as well as
  configured long-press voice actions; it does not remap unrelated hardware actions.

If Taku is absent or disabled, Android's original recovery behavior applies. This
is an installation failure, not a successful policy state. Neither old launcher is
uninstalled or disabled: Quickstep still supplies overview/gestures and the legacy
assistant still supplies native services. The backend no longer advertises HOME or
ASSIST Activities. Its old explicit HomeActivity forwards ordinary entry to the new
desktop; explicit generated-panel actions retain their native panel host for now.

The agreed scope retains SystemUI's actual Quickstep navigation binding. The launcher
patch fixes its explicit primary HOME target during role reconciliation and selects
the new assistant for handle gestures. It does not replace navigation Binder interfaces
with a fake service or redirect recents to an ordinary Activity. Secondary-display
launcher behavior is unchanged in this step.

Hardware assistant entry is hosted by Taku Agent's `SystemAssistantSession`: normal
assist opens Home's AI input; voice-command entry opens continuous voice; handle text
and hold gestures preserve the existing overlay/lease handshake. Native controls use
the same extracted `NativeAssistantSurface` in an explicit Activity, independent of
the selected VoiceInteractionService. Explicit Jev/screen capture is handed from the
new system session to the same backend using a bounded, one-use pending token and a
read-only temporary-file descriptor; screenshots are not put in launch Intents.

`apply-home-policy.py` checks the exact framework and Permission module revisions
against `image/manifests/platform.xml`, before/after hashes, and patch coverage.
All pending patches are checked before either checkout changes. Repeated application
is safe; unknown edits and partial file sets are rejected. Resume/verify builds must
already have the matching patches. A framework + PermissionController ROM build is
required; installing TakuAgent.apk alone cannot deliver this policy.
The new frontend must also be released and re-pinned with `image/agent_ui.py` before
freezing a ROM input snapshot. The existing APK lock binds exact source bytes and
will reject this changed source tree until a matching committed-source release is
pinned. Local emulator APKs are verification artifacts, not replacements for that lock.

## Verification

Host checks: `python3 tests/home-policy.test.py`. These compile and exercise the
actual resolver and HOME/ASSISTANT role policies with small Android data-object substitutes, and verify patch
application, idempotency and rejection of unknown sources. They do not prove that
Android's framework/PermissionController build or device lifecycle passes.
`python3 tests/launcher-entry.test.py` applies the actual Quickstep patch and verifies
upgrades from both audited predecessor versions without overwriting unknown edits.

The application-side checks on 2026-11-15 used the isolated, hardware-GPU
`Taku_SystemEntry_Verify` AVD (stock Android 17, not the patched ROM). Both native APKs
compiled. Package replacement and reboot restored HOME/ASSISTANT and both secure
bindings; HOME, ASSIST, power long-press, Agent `system/open`, the old explicit HOME
trampoline and native controls reached their intended components without crashes.
The assistant UI and `assistant/get` agreed on text-entry state. Explicit screenshot
handoff honored the disabled capture setting, and reached image analysis when enabled.
The stock image has no Audio Engine, so voice entry reported `ENGINE_UNAVAILABLE`;
voice transcription/model results were not accepted as verified. Stock KEYCODE_VOICE_ASSIST
uses web search; its new framework route still needs the ROM verification below.

On an **isolated emulator running the resulting ROM**, use
`python3 image/home/verify-device.py --serial SERIAL`. It changes HOME defaults only
on that emulator, confirms role/PM agreement, removes and clears the role to exercise
recovery, attempts a legacy holder, launches HOME, and checks the resumed activity.
It also clears the assistant role and each secure voice binding independently,
then checks recovery without launching the application to trigger it.
It restores Taku on exit. Run before/after a reboot and after an in-place TakuAgent
update. For clean-start and OTA acceptance, also run on a wiped image and an upgraded
image whose previous HOME was Quickstep/legacy assistant. Check setup wizard and
locked-user boot, secondary user, explicit Quickstep overview, and gestures separately.

The generated integration report deliberately records `romBuiltVerified: false`
and `deviceVerified: false`; source checks are not device acceptance.
