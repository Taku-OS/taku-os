# Assistant draft and crash recovery

Expo saves edits through the Kotlin composer. On-device agents can read the same draft by calling `composer/read` on `content://org.agentos.assistant.open-access` with the conversation UUID.

On upgrade, Expo imports legacy drafts when you open their conversations. If a conversation already has a different draft, use **Restore saved draft** after clearing the editor. Repeating the upgrade import does not add a second copy of the same draft. After an interrupted send, Expo looks up the original receipt and preserves any newer draft.

## Run the live recovery regression

Install the Expo Agent and Kotlin Assistant APKs on an isolated emulator with a connected test account. The runner defaults to `Taku_ImageIntegration_20261010` at `emulator-5634`. Set `TAKU_SERIAL` and `TAKU_AVD` to use another isolated emulator. Open a conversation before running the test so the script can restore the selection. The script requires root ADB and uses display 0.

```sh
bash system/assistant/test-recovery.sh
```

Use `--compile-only` to build the suite on the host. A full run covers these flows.

1. Save a Chinese draft in Expo and confirm it through Open Access. Kill the `org.agentos.agent` process, reopen the same conversation in a new process, and verify the draft again.
2. Switch to Android Settings. Reopen the conversation and verify the draft in Expo and through Open Access.
3. Submit a silent task from Expo with a unique reply marker. Kill the Expo process while the real native turn is active. Reopen the conversation and check that the same task completes and its reply appears in Expo.
4. Save a second draft in that conversation after the task completes. Reboot the AVD. Verify the draft, the same completed native turn, and the visible reply after reopening Expo.

The script waits up to 180 seconds for a new Android boot ID and a working root ADB connection, then up to 120 seconds for a native read of the existing conversation. It restores touch sounds, stay-awake settings, and the original conversation. The test conversation remains available for diagnosis. Logs are in `out/agent-recovery-tests/<run-id>/`. Screenshots and `acceptance.json` are in the run's `device/` directory.

The device reboot checks durable drafts and completed replies. The separate process-death test covers a running turn with the Kotlin backend and native broker still active. The [v49 acceptance record](../evidence/builds/2026-10-18-composer-reliability.json) describes the earlier Java chat tests.
