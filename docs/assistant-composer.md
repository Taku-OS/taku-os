# Taku Assistant composer

Expo chat opens in `org.agentos.agent/.MainActivity`. To open a specific conversation, pass its UUID as the `threadId` extra.

**Composer tools** opens the Android document picker for files and images, or the Android camera for a photo. The Kotlin assistant copies the selected content and keeps it with the draft. Images enter the native turn as `localImage` inputs. Text files are available to the model by their local paths.

**Send** starts a new request when the conversation is idle. During an active turn, **Send** steers that turn. **Stop** remains available while you write a follow-up and keeps that draft when it interrupts the turn.

## Drafts and failed requests

Expo and on-device agents share the Kotlin composer. `composer/read` on `content://org.agentos.assistant.open-access` returns the conversation's current draft revision, text, and attachment metadata. Sending records the conversation, draft revision, and attachment IDs. Acceptance of a queued request clears the submitted draft. The task can still fail later.

After a task fails, use **Restore to draft** to recover its text and attachments into an empty composer. Save a newer draft before clearing it to make room. Review the recovered request before sending. If an attachment is missing, remove it and select the file again.

Home handoffs open editable drafts. If a handoff displaces an existing draft, **Restore saved draft** recovers the displaced text and attachments. See the [installed Assistant draft commands](../system/assistant/assets/skills/taku-open-access/SKILL.md#assistant-drafts).

Android supplies the foreground screenshot when both `assist_structure_enabled` and `assist_screenshot_enabled` are enabled. The system assistant also checks Android's assist metadata before handing off the image. If Android marks the foreground window as protected, `capture/read` reports `no_screenshot`. `capture/accept` returns `CAPTURE_NOT_ACTIONABLE`, and no task starts.

## Run the live composer regression

Install the Expo Agent and Kotlin Assistant APKs on an isolated emulator with a connected test account. The runner defaults to `Taku_ImageIntegration_20261010` at `emulator-5634`. Set `TAKU_SERIAL` and `TAKU_AVD` to use another isolated emulator. Open a conversation before running the test so the script can restore the selection. The script requires root ADB.

```sh
bash system/assistant/test-composer.sh
```

Use `--compile-only` to build the suite on the host. Use `--phase` to run one scenario on the emulator.

| Phase | What it checks |
| --- | --- |
| `attachments` | Select an image and a text file through DocumentsUI. Send from Expo, verify that the real reply contains both random fixture markers, and read back the cleared draft. |
| `home-attachments` | Pick both files in Expo and open the conversation's Home composer. Check the two visible filenames against `assistant/get`, send from Home, and verify both fixture markers in the real reply. |
| `steer` | Select both attachments through DocumentsUI. Send while a real native turn is active and verify that the same turn returns both markers. |
| `stop` | Submit an actual task through Expo. Stop its running native turn while an unsent follow-up is in the composer. Check the cancelled task, interrupted turn, and preserved follow-up. |
| `failure-missing` | Select a document and remove only the fixture copy to simulate a missing attachment. Verify that the task fails before a native turn starts. Restore the text and attachment metadata through the Expo UI. |
| `failure-async` | Seed a stale model override for a fresh test conversation. Send a text attachment and require a real upstream failure or native turn-start rejection. Restore the original draft through the Expo UI. |

The runner uses fresh test conversations, archives them on exit, and restores the original conversation and device settings. Logs, screenshots, and JSON evidence are in `out/composer-reliability-tests/<run-id>/`. The [recovery suite](assistant-recovery.md) covers process death and Android reboot.

## Historical acceptance

The [v49 acceptance record](../evidence/builds/2026-10-18-composer-reliability.json) and [v52 keyboard report](reviews/composer-keyboard-2026-10-19/README.md) describe the retired Java chat. The Expo suite uses the current controls and public draft state.

## Composer model options

Expo chat can set the model, reasoning effort, service tier, multi-agent policy, permissions, and response style for the active conversation. **Use default** removes that conversation's override and shows the inherited setting. A model change selects a supported effort from the runtime catalog. The service-tier choices come from the selected model's catalog entry.
