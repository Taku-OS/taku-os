# Issue #55: system-handle Taku voice

The pinned Launcher3 long-press consumer previously entered
`NavHandleLongPressHandler` / `ContextualSearchInvoker`, separately from the configured
ordinary assistant. The patch now selects the Taku branch only when `AssistUtils` reports
`org.agentos.assistant`. It retains the delegate until the stationary long press wins, then
pilfers the pointer stream and cancels the delegate. It does not disable Google packages.
Other assistant selections keep the upstream branch; double tap opens the Taku text composer.

The ordered bridge sends an id, device-uptime timestamp and sequence through the exported
Home Assistant provider. Prepare does not open audio; the assistant activity must activate
that same pending id. Release/cancel before activation is terminal. Duplicate/out-of-order
input, process-restart replay and a disconnected producer cannot revive the recording.
Keep talking transfers the existing recording to explicit controls; old system-pointer events
are ignored. The provider and both UIs share `AssistantSession` and the same arc hit-testing.

Figma nodes 115:973 / 115:1254 reuse the existing bottom entry. Nodes 297:374 / 297:505 /
297:571 supply the original voice artwork, shared Cancel preview and Keep talking state.
Assets and export hashes are recorded under the module's `assets/assistant/provenance.json`.
Continuous-mode controls extend the supplied design to provide explicit Send / Cancel / Text.

## Host checks before the first implementation push

- Agent UI release build; native assistant build.
- Agent UI lint and TypeScript typecheck.
- Home module `:taku-home:testDebugUnitTest`: 84 tests passed, including 10 new gesture cases.
- Pinned upstream `818f72b74e6f914cbca2b465776d6d20f960ee87`: pristine patch application,
  idempotent application, exact v1 migration and rejection/preservation of foreign edits passed.
- Native assistant gateway/route host regression tests.

The patch applier still rejects foreign or partially modified source. Its manifest records
all four target files and exact hashes; the old two-file patch is retained solely for an
exact-byte migration. Integration reports keep `apkBuiltVerified=false` and
`physicalDeviceVerified=false` until independently verified.

## Acceptance boundary

Device acceptance follows the implementation commit/push, as requested. The assigned
hardware-rendered AVD is `Taku_Launcher_Verify` (`emulator-5586`, Android 16 / API 36).
It validates Home input, the cross-app activity and Agent protocol. Per the user’s scope
correction, real speech recognition/submission is owned by a colleague and excluded here. It does **not** contain this Android 17 Quickstep build. No full Android build tree,
build container or connected Pixel is available on this host, so an app APK install cannot
prove that the actual system handle has stopped invoking ContextualSearch. Keep #55 open
until the patched ROM is built and tested on the target device, including Home / Recents /
quick-switch, keyguard, rotation, default-assistant changes and process interruption.

Agent commands, payload fields, errors and the unavailable amplitude upstream are documented
in the preinstalled `system/assistant/assets/skills/taku-open-access/SKILL.md`.

## Post-push interaction acceptance (2026-11-13)

33 device interaction checks passed; case results and installed APK hashes are in
[interaction-results.json](interaction-results.json). Tests used the existing empty
`ExternalVoiceInput` adapter on the isolated AVD, with Audio Engine temporarily disabled.
No recognized text was fabricated, no audio was uploaded, and no conversation/task was sent.
The engine's default enabled state was restored, the frontend restarted and idle/engine
selection confirmed afterward. Real recognition remains outside this acceptance per the user.

Covered: normal/activated hint and timeout (GUI/Agent use the same state), desktop double-tap
with focused keyboard, hold, Cancel, Keep talking preserving id, explicit continuation
Cancel/Text/Send, empty release, multi-pointer and pre-threshold movement, ACTION_CANCEL,
cold terminal-before-activation, duplicate/late/out-of-order input, and lost system lease.
The real Android VoiceInteractionManager route was exercised over Settings: text and voice
opened `AssistantOverlayActivity`, continuation and Text worked, focus loss cancelled, and a
late terminal gesture did not reopen input. This tests the native assistant route; it does
not substitute for injecting a real handle hold into the patched Quickstep ROM.

Visual checks inspected normal/activated, held, armed Cancel/Keep talking, continuation,
and cross-app text/voice screenshots against the supplied Figma frames. Transcript and
reply suggestion chips remain data-driven; no example speech or fabricated replies were
inserted for a screenshot. Transcript dust with nonempty recognized speech was not exercised.
The ignored local startup APK cache was updated with checksums
and prior APK backups. The source repository contains no local emulator state or accounts.
