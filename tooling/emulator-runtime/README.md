# Development-emulator service recovery

This Mac development helper monitors an already running `emulator-5582` / `AgentOS_ImageCheck`. After device reboot or managed-service exit, it restores the Agent runtime and app-kit business service already deployed on that device. It depends on the Mac, the current user's login, and ADB connectivity. It is not ROM autostart and never starts, wipes, or resets an emulator. Installing it does not establish third-party app compatibility.

## Files and boundaries

- `watch.py`: checks only the selected device. It verifies AVD identity, completed boot, and unlocked user 0 before requesting `adb root`, then verifies identity again. It checks SHA-256 of all managed device files before calling `health.sh` / `restore.sh`.
- `install.py`: installs, queries, or removes the current macOS user's LaunchAgent `build.taku.emulator-runtime.5582`. It uses the installer invocation's absolute Python path, independent of interactive-shell PATH.
- `device/`: device recovery scripts and installer; see that directory's documentation/command help for parameters.
- `test_watch.py`: mock-only unit tests that call neither actual ADB nor launchctl.

Recovery ensures runtime availability. The host monitor neither creates/replays Agent tasks, sends conversations, nor changes business data, accounts, or installed apps. Each recovery attempt has a 90-second timeout. State becomes `healthy` only after a separate post-recovery health check passes.

## Installation order

First use the device installer to install files at `/data/local/agentos/dev-runtime/` and save the manifest. It accepts only already deployed runtimes and neither downloads, builds, nor replaces them. Example development-host command:

```sh
python3 tooling/emulator-runtime/device/install-device.py \
  --adb "$HOME/Library/Android/sdk/platform-tools/adb" \
  --serial emulator-5582 --avd AgentOS_ImageCheck \
  --stage /data/local/agentos/runtime-repair-20261019 \
  --service /data/local/agentos/APP_KIT_DIR/kit/api/service.sh \
  --app-id APP_NAME_FROM_GET_APPS \
  --state-method READ_ONLY_METHOD_FROM_GET_APPS \
  --output out/emulator-runtime/device-manifest.json
```

`--app-id` and `--state-method` have no defaults. They identify an already deployed app-kit app and one read-only query method from `GET /v1/apps`; the installer never treats an example app as a universal fallback. Health checks call that method once without writing business data.

The device must be fully booted, user 0 unlocked, and `adb root` available. `--dry-run` only verifies and prints a manifest. Unknown directories or externally modified managed files stop installation. Prepare host configuration from the actual manifest rather than guessed script hashes.

Configuration format; replace placeholder hashes with actual SHA-256 values:

```json
{
  "schemaVersion": 1,
  "managedBy": "taku-emulator-runtime",
  "serial": "emulator-5582",
  "avd": "AgentOS_ImageCheck",
  "adb": "/Users/YOUR_USER/Library/Android/sdk/platform-tools/adb",
  "restoreScript": "/data/local/agentos/dev-runtime/restore.sh",
  "restoreSha256": "<actual SHA-256>",
  "healthScript": "/data/local/agentos/dev-runtime/health.sh",
  "healthSha256": "<actual SHA-256>",
  "deviceFiles": {
    "/data/local/agentos/dev-runtime/common.sh": "<actual SHA-256>",
    "/data/local/agentos/dev-runtime/health.sh": "<actual SHA-256>",
    "/data/local/agentos/dev-runtime/restore.sh": "<actual SHA-256>",
    "/data/local/agentos/dev-runtime/probe.mjs": "<actual SHA-256>",
    "/data/local/agentos/dev-runtime/runtime.conf": "<actual SHA-256>",
    "/data/local/agentos/dev-runtime/assets.sha256": "<actual SHA-256>",
    "/data/local/agentos/dev-runtime/targets.sha256": "<actual SHA-256>",
    "/data/local/agentos/dev-runtime/self.sha256": "<actual SHA-256>",
    "/data/local/agentos/dev-runtime/owner.json": "<actual SHA-256>"
  }
}
```

`deviceFiles` binds every script, configuration, and manifest the entry scripts read, preventing a verified entry point from executing substituted dependencies. Separate entry-point hashes must match this mapping. After script/configuration updates, regenerate the manifest from the installed device and update host configuration.

```sh
python3 tooling/emulator-runtime/install.py install --config /absolute/path/runtime-watch-config.json
python3 tooling/emulator-runtime/install.py status
python3 tooling/emulator-runtime/install.py uninstall
```

The installer copies only its scripts/configuration/manifests and registers the LaunchAgent. It neither calls ADB nor starts an emulator. `install.py` requires an explicit subcommand and has no default install action. Existing directories require matching ownership, targets, and file hashes. Foreign configuration, unknown same-name jobs, symlinks, and externally modified managed files are not overwritten. Uninstall stops only the verified job and removes its plist, retaining configuration, logs, state, and device data.

Managed locations:

- `~/Library/Application Support/TakuOS/emulator-runtime/5582/`
- `~/Library/LaunchAgents/build.taku.emulator-runtime.5582.plist`

## Manual checks and acceptance

Without installing a LaunchAgent, run one check. Unmet device conditions return exit code 1 without starting an emulator:

```sh
python3 tooling/emulator-runtime/watch.py \
  --config /absolute/path/runtime-watch-config.json \
  --state-dir /absolute/path/runtime-watch-state \
  --once
```

Omit `--once` for continuous operation. `flock` permits one monitor per state directory. Do not start another monitor for the same target with a different state directory. Device recovery scripts also enforce mutual exclusion.

`state/state.json` includes target, `pid`, `bootId`, `status`, `lastHealth`, `lastHealthAt`, `restoredAt`, `restoredBootId`, `restoreCount` (attempts), and `successfulRestoreCount`. `restoredAt` changes only after actual recovery and successful health verification. A healthy device in the same boot does not trigger repeated recovery. `state/watch.log` is capped at 256 KiB per file with two rotated backups. It logs neither raw device-script output nor account information.

Healthy checks occur every 5 seconds; offline, booting, or locked-user checks every 3 seconds. Failures back off by 10/20/40/60 seconds. Offline state does not repeatedly emit identical logs. Temporary disconnection or screen locking does not imply data loss.

For reboot acceptance, the operator must explicitly execute ordinary `adb reboot`, observe a new `bootId` reaching `healthy`, an increased recovery count, and working business-service readback, then compare business state files and installed apps before/after. Installation and mock tests do not replace this device test.

The former half-marathon scenario supplied `reboot_recovery.py` for automated reboot acceptance. That scenario, business implementation, API, and installer were removed on 2026-11-15, so the script no longer exists. Perform manual acceptance as above and retain before/after readbacks, `bootId`, and byte comparisons of state files.

`healthy` includes actual business API readback, component hashes, process checks, and background-display checks. Broker process existence does not establish working model replies; conversation submission needs separate real acceptance.

```sh
python3 -B -m unittest discover -s tooling/emulator-runtime -p test_watch.py -v
```

This version does not resolve host disconnection, Mac logout, unavailable ADB root, replacement of the AVD with another device, locked users, or managed-file hash mismatches. It preserves state and records waiting/failure reasons. No full ROM build is required.

## 2026-10-20 Java process identification fix

Component updates reproduced `CHECK_FAIL:broker_cmdline`. Old `class_pids` searched arbitrary argv entries for the main class and mistook concurrent `grep -Fxq -- org.agentos.lab.TaskBroker` checks for Broker, recording the wrong PID and skipping real startup. It now requires `/proc/PID/exe` to identify system app_process, the working-directory argument to be `/system/bin`, and the following argument to be the target main class. Broker discovery, background-display health, and old display-process checks share this validation, preserving UID, uniqueness, PID-file, component-hash, and mount checks.

`test_process_probe.py` supplies 11 fixture regressions; together with host-monitor tests, 25 passed. The current emulator received updated managed scripts/hash manifests. Actual recovery and repeated idempotent recovery returned `API_HEALTHY / HEALTHY / RESTORED`. Two business state files remained byte-identical, and host monitoring returned to `healthy`. Records preserve the initial failure, paused monitoring, manual Broker startup through existing commands, fix deployment, and other developer intervention. This run did not reboot the device and does not replace reboot acceptance above. Evidence is local at `out/panels/half-marathon-e2e-20261020-111026/runtime-process-probe-fix/`.
