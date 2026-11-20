# Codex device runtime adapter

Status updated: **2026-10-19**. Codex 0.161.0 runs on the Android 16 ARM64 development emulator and physical Pixel 9 v44, with scoped acceptance completed. Evidence for the latest assistant v54 and v55/v56 remains limited to their respective emulator versions; the complete latest ROM has not yet been rebuilt successfully. See [current status](../../docs/current-status.md), [native Pixel 9 client](../../docs/native-codex-implementation-2026-10-15.md), and [Magpie acceptance](../../docs/reviews/magpie-runtime-2026-10-19/README.md).

This directory contains bootstrap and installation scripts and the Android launcher, with no embedded accounts, authentication caches, or program binaries. Codex is the current Agent runtime, managing native conversations, tools, approvals, and stopping. Integrated [Magpie](../magpie-runtime/README.md) is its model gateway, not another Agent runtime. Future runtime replacements must follow the [runtime contract](../runtime-contract.md) without creating another TaskBroker queue.

```sh
# Download and verify the pinned ARM64 runtime defined in image/runtime-assets.lock.json
bash adapters/codex-runtime/bootstrap.sh
# Experimental development AVD installer; explicitly select a device you are authorized to use
TAKU_SERIAL=emulator-5582 bash adapters/codex-runtime/install.sh
```

See [build scripts and limitations](source-build-experiment.md) for source builds and dependency pinning.

The default version is based on upstream rust-v0.161.0, includes Taku plugin transactions and the shared configuration lock patch, and applies `retire-panel-tools.patch` to remove legacy native Panel tools. Archive bytes, executable SHA-256, source inputs, and release download URL are pinned in `image/runtime-assets.lock.json`; credentials are never written into the image. The launcher hashes both archives and uses a content cache under `/data/local/agentos/runtime-v2/`, with `/system_ext/bin/codex` remaining the entry point. Upstream: https://github.com/openai/codex/releases/tag/rust-v0.161.0 . Upstream Codex retains its Apache-2.0 license.

The early lab verified musl CLI startup, model requests, and native shell commands on an Android 16 ARM64 userdebug emulator with SELinux Enforcing. The source-migration round imported only public scripts and did not run the installer or make model requests. Subsequent emulator testing on October 14 and Pixel 9 testing on October 15 provided real App Server model, tool, and conversation acceptance; the runtime as a whole can no longer be described as never run. See [Agent module](../../docs/agent-module.md) and [device acceptance](../../docs/native-codex-implementation-2026-10-15.md) for scope. This execution evidence does not imply acceptance of all CLI features, all hardware, the standalone bootstrap path, or a formal release.

The experimental installer is only for AgentOS_ImageCheck: pinned archives and CA certificates go in the system partition, while first extraction and login use the device data partition. DNS 10.0.2.3 is AVD-specific and must not be copied to physical phones. Users authenticate themselves; the project does not import or copy authentication state. Userdata or snapshots containing user authentication must not be distributed.

### Known limitation: live plugin paths

Plugin installation, removal, and configuration writes use the same interprocess file lock; this lock does not provide read snapshots. After a plugin loads, skills, MCP, or hooks may still read its cache path later, while concurrent installation or removal may replace or delete that path. The lock therefore protects controlled writes and interruption recovery, but does not guarantee that live consumers see the same file version. Immutable snapshots and interprocess lifecycle management are deferred to a separate PR. Third-party MCP still executes with the current runtime's privileges and is not an isolation boundary.

The shared write lock is at `CODEX_HOME/plugins/.lock` for this runtime, and returns an error after waiting more than 10 seconds; all participating writers must use the same `CODEX_HOME`. The lock does not cover separate homes symlinked to the same configuration file or external programs modifying configuration directly while bypassing the runtime. Interruption recovery covers process exit; complete durability after sudden power loss has not been demonstrated.
