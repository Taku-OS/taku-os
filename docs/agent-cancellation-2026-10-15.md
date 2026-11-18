# Agent cancellation and updated image integration · 2026-10-15

Previously, the stop button confirmed only that the native turn was `interrupted`, while actual shell/sleep processes kept running. `TurnProcesses` now supplements this with Android process cleanup: it assigns a temporary ownership marker when the runtime starts and snapshots existing child-process identities before a turn. On stop, it handles only observed child processes newly created in this turn for this runtime and conversation, checks PID and start time, sends TERM followed by KILL if needed, and rereads process state. The native processId is a session handle, not a Linux PID. Environment variables are inspected only in memory for ownership markers; environments and authentication values are not logged.

A stop request for an old turn checks the turn ID before dispatch and is not forwarded to a new turn. Unconfirmed process termination is displayed as failure; native interruption cannot be equated with stopped operations. Closing the runtime also cleans up its still-running owned child processes; other services are outside the cleanup scope.

## Real acceptance

A dedicated Android 16 emulator made real model calls and used the actual stop button to interrupt nested shells and a child process ignoring TERM: 3 processes were cleaned up in about 2.08 seconds, with 0 remaining. A session process retained from the previous turn in the same conversation and a command in another conversation remained alive. A stop request for an old turn was rejected, and a subsequent real model reply returned `TAKU_CANCEL_RECOVERED`. The whole test took 36.226 seconds. Test-created processes were cleaned up in finally.

One retest read history before the native completion event and misclassified a transient state as final interruption; the failure log is retained. Acceptance now waits for native `turn/completed` and then verifies its status against persisted history, without inferring success from model text or tool completion status.

Retest: `bash system/assistant/test-turn-cancellation.sh`. Only `Taku_ImageIntegration_20261010 / emulator-5634` is accepted; current components must already be installed and the device already logged in. It does not log in, log out, or change accounts. See the [acceptance record](../evidence/builds/2026-10-15-turn-cancellation.json).

This mechanism is not a production sandbox and does not undo system operations that already happened. Long-running services that deliberately detach from the process tree, independent plugin lifecycles, and complete physical-device acceptance still require separate validation.

## Incremental complete-image build

Subsequent status (checked 2026-10-19): the integration and fixes below do not mean the complete package succeeded. The last saved [build status](research/pixel9-image-build-state.json) is `failed-exit-1`, with no new artifacts, at 10-16 00:40 Beijing time; that status file does not record the final root cause. The older version was successfully built and flashed, while the latest complete package remains unfinished; see [current status](current-status.md).

`python3 scripts/pixel9-image.py rebuild` supports updating Taku inputs from an existing tokay source/kernel checkpoint. It first exports and verifies the previous successful artifacts and preserves containers, logs, and immutable snapshots; it verifies platform/kernel pins, existing integration files, and 340 boot/kernel-module artifacts, then replaces `vendor/taku` and reuses the compiler cache. It does not resync source, rebuild the kernel, clear out, or modify the original emulator cache volume.

The latest round encountered Soong blocking while opening a Ninja FIFO under Rosetta: logs stopped at the bootstrap-command launch, CPU usage was near zero, and no compiler/Ninja child processes existed. Opening a temporary endpoint on that FIFO allowed the previously delayed exit signal to be handled. Failure logs are saved. The build entry point now uses a temporary FIFO guard solely to unblock opening; it immediately releases the endpoint when the actual Ninja writer takes over, writes no progress data, and does not prevent real EOF. Regression testing passed in a Linux container for real FIFO opening, raw-byte transfer, and EOF.

This target package additionally checks that the APK's Harness, native protocol, and development-skill assets match immutable inputs byte for byte, and confirms that the broker includes current feature classes. Exports use separate snapshot directories to avoid overwriting earlier deliveries. These checks do not equal flashing, physical-device cold boot, or production-signing acceptance; nothing is delivered as complete before the build finishes.

See the [OpenAI App Server documentation](https://learn.chatgpt.com/docs/app-server) for the interface distinction between native turns and independent command sessions.
