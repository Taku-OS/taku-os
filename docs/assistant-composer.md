# System Agent composer

v47 simplifies the bottom of the composer to attachments, a model ring, a reasoning-effort dial, voice, and send. Persistent background-display, lightning, and permission-shield entries are removed; task execution cards still open background displays. All icons use a 24dp canvas with a 1.8 stroke, buttons have 44dp touch targets, and the circular send background is 40dp.

When the composer contains text or attachments, it submits a message; when additional content is entered during execution, native `turn/steer` continues the same turn. An empty composer during execution shows a stop button that stops the real task through `turn/interrupt`.

`ComposerToolbar` owns icons and menus, `ComposerPreferences` saves conversation options, and `ComposerAttachmentActivity` obtains attachments through the Android system file picker and copies them into the app-private directory. New messages and steering instructions both use native `localImage` for images; other files pass the user-selected local path. Model and reasoning-effort options come from the actual runtime catalog.

Voice uses Android's local SpeechRecognizer; if the system has no recognition engine configured, it explicitly reports that voice is unavailable. The camera entry is implemented; photo capture was not accepted in this round.

## Validation record

- v46: an image was actually selected and sent on the emulator; the real model identified folder names and an empty directory in it. After send changed to stop, the actual task was canceled.
- v46: a real running turn received a steering instruction, and the model changed its final response within that same turn. An actual phone-tool request required approval; canceling in the UI caused the executor to block the operation.
- v47: component build passed; the emulator verified removal of lightning and permission-shield controls while retaining the other five, and checked the actual layout with multiline input and the keyboard open.
- `ComposerAcceptanceTest` and `ComposerInteractionTest` are real end-to-end tests requiring an already logged-in Android runtime; they do not supply simulated model responses.

Acceptance metadata is in `evidence/builds/2026-10-15-mobile-composer.json` and `evidence/builds/2026-10-15-composer-aligned.json`. Original screenshots and test logs remain local, excluding APKs, login state, and account keys.

See [assistant recovery](assistant-recovery.md) for v48's immediate draft saving and acceptance of recovery after abnormal exit/full-device restart.

## v49 sending and failure recovery

Sending snapshots the current conversation, text, attachments, and draft revision. An asynchronous callback clears only content still matching that snapshot; switching conversations or continuing to type cannot be cleared by an old callback. A send record is saved before enqueueing; being enqueued no longer means final send success.

While a conversation loads, sending, attachments, and recovery can act only on the loaded conversation. Home handoff content is first written into the draft and then its one-time handoff record is consumed, preventing duplicate sends or steering after UI refresh. A pending Home request cannot be consumed by a different manual steering instruction. If another draft already exists, its original text and attachments are stashed atomically with a “Restore stashed draft” entry. Home requests do not mix with old attachments, and nothing sends if saving fails.

When sending fails, an empty composer automatically restores the original text and attachments. If a new draft has been written, it is preserved and an entry appears to recover failed content; save or clear the current draft before recovery. Content from repeated failures is retained in order. Same-turn steering has separate acknowledgement records, so unacknowledged steering can also be recovered after app exit. Recovery does not send automatically; for requests that already started executing or cannot be confirmed, check conversation results before retrying.

Before submission, attachments are checked for private-directory location, existence, and size. Invalid attachments remain in the draft for removal or reselection. Failures after enqueueing, such as image decoding or upstream request errors, likewise retain recoverable content.

Retest entry points (dedicated, logged-in `Taku_ImageIntegration_20261010 / emulator-5634`):

```sh
bash system/assistant/test-composer.sh
bash system/assistant/test-turn-cancellation.sh
bash system/assistant/test-recovery.sh
```

Composer tests select image and text files through the system picker, actually send them to the model, and verify randomized content. They also test same-turn steering with attachments, stopping, invalid attachments, and triggering a real request failure by selecting a nonexistent model only in an isolated test conversation. An invalid image may produce a model explanation and a normally completed conversation; that reply is not treated as send failure. Logs and screenshots are in `out/composer-reliability-tests/`; test threads are archived, and the original conversation and device settings are restored.

The v49 acceptance on 2026-10-18 covered image and text-file sending, same-turn steering with attachments, actual task and turn stopping, and draft retention for failures both before and after enqueueing, all with real Android UI and upstream results. Another 95 state assertions covered callbacks across conversations, new-draft protection, repeated failures, process exit, and Home handoff boundaries. Six background Chinese-input scenarios, process-exit recovery, and full-device restart recovery were also verified. Complete metadata is in the [v49 acceptance record](../evidence/builds/2026-10-18-composer-reliability.json), with screenshots in `evidence/scenarios/composer-2026-10-18/`. These results are from the dedicated emulator, not physical-device acceptance.

## v52 hiding the keyboard after sending

For new messages, the soft keyboard hides after Codex starts accepting the turn; for same-turn steering, it hides after `turn/steer` succeeds. The composer keeps focus so the Mac input-method bridge can continue typing; tapping the composer reopens the soft keyboard. Enqueueing alone does not hide it, nor does it hide if the next draft was edited while waiting.

Validation failures before sending retain the keyboard. If a request fails asynchronously after acceptance and the current draft is still the original empty draft, the sent content is restored and the previously hidden keyboard reopens. After navigating, switching conversations, or editing a new draft, the old send no longer affects keyboard state. See the [v52 acceptance record](reviews/composer-keyboard-2026-10-19/README.md) for real-emulator validation scope and screenshots.
