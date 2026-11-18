# Isolated emulator cleanup probe

Uses production `NativeBridge`, `SessionRemovals`, `DedicatedScreens`, GUI, and
`AgentOpenAccessProvider`. Codex conversation metadata and operations are fixtures;
Android dedicated screens, app tasks, persistence, sockets, and Provider use real
components. This probe is only for disposable emulators and checks
`ro.kernel.qemu`. It does not establish physical-device or real-model acceptance.

## Build

Build the assistant and display modules first, then run:

```sh
bash system/assistant/test-session-removals-device-build.sh
```

Artifacts are in `out/session-removals-device/`. The APK contains the complete
current assistant plus a test Activity, signed with the project development
platform key; the isolated image must have a matching platform signature.

## Run checks

1. Explicitly select a newly created independent emulator serial and dedicated
   ADB server port. Verify `ro.kernel.qemu=1` before root/remount. Deploy the
   current wrapper to `/system_ext/bin/phone-display`, the JAR to
   `/system_ext/framework/agentos-virtual-display.jar`, and install the fixture
   APK. Operate only this disposable emulator.
2. Create `/data/local/tmp/taku-pr62-removals/` with empty `defer-archive` and
   `defer-cancellation` files and `probe.jar`. Start
   `org.agentos.lab.SessionRemovalDeviceProbe` through root `app_process`.
   Wait for `ready` and verify both real dedicated screens and Settings/Clock
   tasks in `screens.fixture`.
3. Start `org.agentos.assistant/.SessionRemovalTestActivity` with
   `--es page archive` and tap the production GUI archive button. Query the
   `sessions/removals` content interface to check acceptance and progress.
   Submit deletion of the same conversation through content; requestId must
   remain unchanged, revision must increase, and the target must become deletion.
   The default Activity page shows production cleanup progress for comparison.
4. The child-conversation cancellation fixture temporarily fails. Expect
   targetReached=true and completed=false; its screen and app must remain.
   Another member's real task must be cleaned up and its screen reusable.
   `native-actions` must contain only one delete. No failed step may be reported
   as completion of the entire group.
5. Save query and screen evidence. Terminate only the process identified by
   `probe.pid` whose cmdline matches this probe. Remove both defer files and
   restart. Verify the same requestId resumes to completed=true without another
   delete. Confirm the saved child-screen generation exited without stopping a
   replacement generation. Clean up only probe-owned screens and destroy the
   disposable emulator afterward.

See `system/assistant/assets/skills/taku-open-access/SKILL.md` for content
parameters. The root test conversation is `00000000-0000-0000-0000-000000000001`;
the child ends in 2.

## Current validation limits

On 2026-11-17, a new local SDK Emulator 37.2.12.0 / Android 36 arm64 AVD entered
the QEMU hang detector and SIGSEGV before Android boot in all three attempts:
host graphics, software graphics, and acceleration disabled. No installation,
GUI taps, or device Provider reads/writes ran. Successful probe compilation does
not establish emulator acceptance. Physical-device and real-model tests await
other sessions releasing the device/model as requested by the user.
