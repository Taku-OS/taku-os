# Hide the soft keyboard after sending, v52

Status: `emulator_verified`. Only the assistant APK was updated, on local API 36 `Taku_ImageIntegration_20261010 / emulator-5634`. Physical phones were not covered.

New messages hide the keyboard after a real turn starts; same-turn additions hide it after native RPC success. Input focus remains, and tapping reopens it. Hiding happens once and is bound to the current conversation, composer, and draft revision; new drafts, page changes, and conversation switches cancel the older send-related keyboard action. If asynchronous failure restores the original draft, the previously hidden keyboard reopens.

## Verification

- `npm run build:android` and `npm run verify` passed; existing Linux FIFO platform cases were skipped on macOS.
- `bash system/assistant/test-composer.sh --phase failure-async`: text, image attachments, and keyboard recovered after a real upstream request rejection.
- `bash system/assistant/test-composer.sh --phase attachments`: selected images/documents through the system picker; keyboard hid after send; model actually read random verification codes, the final text line was visible, and tapping the composer reopened the keyboard.
- `bash system/assistant/test-composer.sh --phase steer`: added images/documents during a real turn; keyboard hid on successful append, and the model read attachments and returned correct codes in the same turn.
- Pre-queue attachment invalidation also passed, on an earlier build this round; it is not counted among the three acceptance cases for the final APK above.

Tests created independent conversations, archived them afterward, and restored the original conversation and device settings. No mock model replies were used. Tests reacquire accessibility nodes after streaming refresh/keyboard layout invalidates them while retaining original text-boundary assertions.

Summary and build checksums: [acceptance.json](acceptance.json). All screenshots come from actual isolated tests:

- [Keyboard open before send](before-send.png)
- [Keyboard hidden after send](after-send.png)
- [Draft and keyboard restored after asynchronous failure](failure.png)

Body/tool-event layout was unchanged; Codex Desktop-style continuous replies and collapsible work logs remain future UI work.
