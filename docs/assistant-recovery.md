# Assistant draft and crash recovery

In v47, the composer saved text only at lifecycle events such as closing the drawer or switching conversations. Directly killing the `org.agentos.assistant` process on the emulator lost an unsent draft; the original failure log remains locally at `out/recovery-2026-10-16/before.log`.

In v48, changes to input immediately trigger an asynchronous SharedPreferences save for the current conversation, without relying on drawer closure. Normal sending, clearing a draft, and switching conversations use the same save path. When the application process recovers, it reads the conversation's existing draft without sending compensating messages.

## Automated acceptance

Run `bash system/assistant/test-recovery.sh`. Only the account-configured `Taku_ImageIntegration_20261010 / emulator-5634` is allowed, and root ADB is required. The script preserves the existing account and history, and restores touch sounds, stay-awake-while-plugged-in settings, and the selected conversation to their pre-test values.

Acceptance includes real process and system operations:

1. Enter a Chinese draft in the native composer, kill the assistant application process, reopen it, and verify the text.
2. Switch to Android Settings, reopen the assistant, and verify the draft.
3. Submit a mute task from the composer, then kill the assistant process after the native turn starts; wait for the real model task to complete and verify the result file and visible response after recovery.
4. Write another draft in the same conversation, restart the Android emulator, and verify the draft, completed task state, and actual response history.

This test does not clear application data, modify login state, or simulate model responses. The full-device restart occurs after task completion; it does not prove that in-flight phone writes can continue across a system restart, and it does not automatically replay those operations. Voice engines, real microphones, physical power buttons, and phone network switching are outside this acceptance scope.

One restart retest read the composer before loading had completed; an independent read confirmed the draft remained on disk. Assertions now wait for recovered content. The failure log remains locally at `out/recovery-2026-10-16/reboot-first-read.txt`.

v49 repeated these flows on 2026-10-18, successfully verifying recovery of drafts, running real model tasks, and completed responses. This cold boot exceeded the original 60-second timeout, and the first UI read also preceded conversation-service readiness. An independent read confirmed the disk draft was intact; once the service was ready, the draft and history saved across that same restart were verified. The test now pins the local ADB and display 0, waits up to 180 seconds for Android boot and 120 seconds for conversation-service reads to become ready, then makes UI assertions. Records and the original failure-log index are in the [v49 acceptance record](../evidence/builds/2026-10-18-composer-reliability.json).
