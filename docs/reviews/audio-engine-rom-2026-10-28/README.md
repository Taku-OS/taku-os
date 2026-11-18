# Audio Engine ROM integration candidate (historical record)

2026-11-06: the user approved the five audio permissions. This old patch became obsolete as source evolved and is no longer applied; see [ROM.md](../../../system/audio-engine/ROM.md) for the current implementation, resource sealing, and verification steps. The candidate state below is historical, not the current authorization state.

This was a build-integration patch awaiting authorization: **not applied to source, no ROM built or flashed, and no new device permissions granted**.

The [candidate patch](candidate.patch) contains full-context diffs for five files: a separately platform-signed Audio Engine APK, a privapp permission list scoped only to org.agentos.audio, the development product-package list, frozen component hashes/source inputs, and explicit sealing of this APK in the image snapshot. The compiled APK is under ignored out; APKs, model weights, accounts, and keys are not committed to source.

Persistent permissions are CAPTURE_AUDIO_OUTPUT, MODIFY_AUDIO_ROUTING, CALL_AUDIO_INTERCEPTION, BYPASS_CONCURRENT_RECORD_AUDIO_RESTRICTION, and START_FOREGROUND_SERVICES_FROM_BACKGROUND. These support selected-app media capture and bidirectional call routing; runtime access remains subject to engine identity, consumer authorization, and the system voice switch. RECORD_AUDIO and POST_NOTIFICATIONS remain user-managed or under existing grants, without new default-grant rules.

Pinned component information is in the [candidate record](candidate.json). git apply --check verified applicability to the then-current tree; applying in an isolated temporary directory verified Python/XML syntax and frozen input hashes. This is not evidence of successful Soong compilation or permission compatibility. Component changes require rebuilding and refreshing source inputs and APK hashes in the patch; this frozen record cannot be reused. Ordinary checks and SDK queries do not establish ROM reboot acceptance.

The historical plan kept the patch unapplied before authorization. After authorization, integrate and verify image-build inputs, then deliver model resources separately and complete normal build/reboot acceptance. This candidate alone neither completes S5 nor authorizes long builds, flashing, account changes, or real calls.
