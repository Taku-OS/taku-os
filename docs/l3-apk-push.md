# Standalone L3 APK push and rollback

Use a configured Android SDK and JDK and install the pinned agent-app dependencies
with `npm ci --prefix system/agent-app`. The Assistant build also needs the Kotlin JVM
compiler. Supply the serial of the intended development device and a new backup directory
for each push.

```sh
python3 scripts/push-l3-apk.py assistant --serial emulator-5554 --backup-dir out/l3-backups/assistant-01 --dry-run
python3 scripts/push-l3-apk.py assistant --serial emulator-5554 --backup-dir out/l3-backups/assistant-01
python3 scripts/push-l3-apk.py assistant --serial emulator-5554 --backup-dir out/l3-backups/assistant-01 --rollback
```

Use `agent-app` for Taku Agent. Match `TAKU_AGENT_ARCHITECTURES` to the target ABI
when building for a device that needs something other than the default `arm64-v8a`.
The Assistant target builds its APK and the Audio client library it links against.
The tool expects the same package to be present as a single APK for Android user 0.
It verifies the APK package, positive version code, and signing certificate and
compares the signer and version code against the installed copy before replacing it.

A push saves the previous APK plus its serial, package, version code, signers, and
SHA-256. Rollback checks those fields before reinstalling that APK. Android must
permit a version downgrade for a rollback to a lower version code. The tool
requests `-d` only on that path and surfaces a package manager rejection. Both
paths use `adb install -r` and preserve app data. Rollback restores the code
only. The older app must be able to read any data migrated by the newer version.

The reported time starts at CLI entry and ends after the package manager reports
the package and the pulled APK matches the requested file. It includes compilation
on push and measures package availability. UI startup time requires separate
measurement.

Run `python3 -B tests/push-l3-apk.test.py -v` for host coverage.
