# Host plugins: JVM and Android development paths

The JVM and Android hosts share one manager, registry and protocol. The Android host, management page and DEX samples are available from source and component builds. Codex marketplace plugins are still managed by the original `taku-plugin` RPC. Their format and registry are separate from host plugins, so do not mix them. Commands, paths and identifiers for host plugins use the name `godmode`.

## Run on a development machine

You need JDK 17 and Python 3. In the JVM sample packages, `plugin.dex` is actually a Java jar, **not Android dex**. Use an empty temporary directory, not the data directory of a device you use every day:

```sh
export JAVA_HOME=/path/to/jdk17
export TAKU_GODMODE_HOST_JAR=/tmp/taku-godmode-build/taku-godmode-host.jar
export TAKU_GODMODE_SOCKET=/tmp/taku-godmode.sock
bash system/plugins/build-host.sh /tmp/taku-godmode-build
"$JAVA_HOME/bin/java" -cp "$TAKU_GODMODE_HOST_JAR" org.taku.plugins.host.Server /tmp/taku-godmode-state "$TAKU_GODMODE_SOCKET" &
system/plugins/taku-plugin godmode install /tmp/taku-godmode-build/examples/settings-page
system/plugins/taku-plugin godmode list
system/plugins/taku-plugin godmode show org.example.settings
system/plugins/taku-plugin godmode disable org.example.settings
system/plugins/taku-plugin godmode enable org.example.settings
system/plugins/taku-plugin godmode reload org.example.settings
system/plugins/taku-plugin godmode remove org.example.settings
system/plugins/taku-plugin godmode shutdown
```

The service holds the manager file lock in a single process, and a second instance cannot operate on the same root. `shutdown` is a command for the JVM development service. After an abnormal exit the socket path may remain. Confirm the service has exited before deleting that socket by hand, and never delete a path in use. Calls are synchronous and plugin code runs inside the service process with no sandbox. Run only trusted plugins. Any plugin can read or change data the service process can reach, and can block the whole service.

`install PATH` accepts a directory or zip containing `manifest.json` and the entry file. An extra top-level directory inside the zip is not supported. A first install is enabled by default, and versions can only go up. Installing the same version returns `ALREADY_INSTALLED`. `list` gives a manifest summary, enabled flag, actual state and errors. `show ID` gives the full manifest and the UI, settings and capabilities registered by the running instance. Setting values exist only in the running instance and do not persist across stop and start. UI registration stores only the title, and there is no renderer yet.

## Authoritative state and failures

`ROOT/registry.json` is this manager's only persistent index and enable state. `ROOT/installed/<generation>` holds immutable package generations, `staging` is for transactions and `runtime` keeps load snapshots. Install first copies or unpacks to staging, validates, publishes the new generation with an atomic rename on the same filesystem, then atomically replaces the registry. Startup cleans up unreferenced generations and leftover staging. File locks and synchronous operations prevent concurrent manager or request conflicts. The JSON registry is not a cross-filesystem transaction and does not guarantee directory entries are flushed on sudden power loss.

Enabling a plugin creates a new instance with its own loader: `URLClassLoader` on the JVM and `DexClassLoader` on Android. Raw dex is wrapped in a jar containing `classes.dex`. Reload saves the old runtime snapshot, stops and closes the old instance, then builds a new loader from the installed files and runs load and start. If the new instance fails, it tries to restore from the snapshot and saves the error in the record. If an upgrade fails to start, it tries to restore the previous generation. A plugin's external side effects cannot be rolled back, and if its stop or restore callback fails, manual inspection may be needed. Disabling clears runtime registration, and restarting the service reloads according to the persisted enable state.

The CLI outputs one line of JSON and exits 0 on success and 1 on failure. `error.code` values include `USAGE`, `SERVICE_UNAVAILABLE`, `NOT_FOUND`, `ALREADY_INSTALLED`, `VERSION_DOWNGRADE`, `HOST_VERSION`, `INVALID_PACKAGE`, `INVALID_MANIFEST`, `LOAD_FAILED`, `STOP_FAILED`, `DISABLED`, `RELOAD_FAILED`, `LOCKED`, `CORRUPT_REGISTRY` and `INTERNAL`. Startup errors such as `LOCKED` are reported on the service's stderr. remove still deletes the record if a callback or garbage cleanup fails, and returns a `warning`. The plugin's external side effects may remain. Do not blindly repeat a change after a failure. Check the state with `show` first, and keep a backup of the root directory for recovery.

## Writing and testing plugins

Manifest v1 is in `manifest-v1.schema.json`, and three examples are in `examples/godmode/`. `capabilities` is metadata for discovery and orchestration, not a permission list that refuses undeclared capabilities. `minHostVersion` is compared with `1.0.0` in this JVM manager. The entry class implements `TakuPlugin`: `onLoad` receives the context, `onStart` registers and `onStop` unregisters. The status bar example only registers a capability, and the Settings and launcher samples only register entries. None of them change real system UI.

```sh
node sdk/plugins/validate.mjs examples/godmode/status-bar/manifest.json
node --test tests/godmode-manifest.test.mjs
JAVA_HOME=/path/to/jdk17 bash system/plugins/test.sh
JAVA_HOME=/path/to/jdk17 bash system/plugins/test-host.sh
```

The JVM manager does not run Android DEX. Reload in Android `DexPluginLoader` stops the instance, closes a Closeable classloader and rebuilds it. Used on its own it has no version snapshot rollback, and the manager uses its own snapshots.

## Real limits of Android, hooks and root

`bash system/plugins/build-android.sh /tmp/taku-godmode-android-out` needs Android SDK 36 with build-tools 36 and JDK 17. It produces the host jar, a development-signed management APK and three real DEX samples. The ROM configuration installs the root `taku_godmode` init service, the SDK and the `TakuGodMode` page on debug images. The service starts after boot, is restarted by init when it exits, and holds `/data/local/agentos/godmode/registry.json` and the abstract LocalSocket `taku-godmode`. An abstract socket disappears with its process, so there is no file to delete. All page actions and `taku-plugin godmode` reach the same socket and manager. You can try opening the management page with `am start -n org.taku.plugins.ui/.PluginActivity`.

This service runs in the root/su domain on purpose, with no plugin capability allowlist and no second prompt. **Any process that can reach this socket may be able to install and run root code.** Actual reachability depends on device SELinux, UIDs and ROM configuration. The `su` domain is a development image configuration, not a production policy, and this is not an isolated production service. Use it only on an isolated emulator. On failure, back up the registry and run `stop taku_godmode` to stop a restart loop, then fix the data and run `start taku_godmode`.

Device command examples: `taku-plugin godmode list`, `show ID`, `install /data/local/tmp/plugin`, `disable ID`, `enable ID`, `reload ID`, `remove ID`. Android uses the abstract socket by default, and `TAKU_GODMODE_SOCKET` selects another abstract name. A value starting with `/` connects as a file socket. CLI install paths must be readable by the root service.

The protocol is a one-line UTF-8 JSON request `{"operation":"show","argument":"ID"}`, and the service returns one line, `{"ok":true,"result":...}` or `{"ok":false,"error":{"code":"...","message":"..."}}`. `ping` returns service status. `AndroidRootTransport` uses the same socket and adds no root state. There is no Binder interface.

`XposedHookBridge.install` reflectively calls the official Xposed `XposedBridge.hookMethod(Member, XC_MethodHook)` and returns an unhook handle. The caller must provide a real `XC_MethodHook` object and the target process's framework classloader. This bridge does not inject into processes, does not generate callback subclasses, and does not guarantee LSPosed or Zygisk is present.

## Community tool-provider

`examples/godmode/tool-provider/` uses `mcp.command/args/env`, `skills` and `healthcheck.timeoutMs`. Enable starts a stdio process and runs MCP initialize and `tools/list`. `taku-plugin godmode tools` queries current tools and skill metadata, and `taku-plugin godmode call-tool org.example.provider hello '{}'` calls one. Disable stops the process and removes the discovery results. The health check currently covers only the initialize timeout and whether the process is alive. It is not a periodic active ping. Skills are exposed as metadata only and are not installed into the Codex skill library automatically. MCP is not registered with the existing Codex app-server automatically either, and agents can query and call through the CLI.

## Evidence, tool details and checks

`tools [TOOLSET]` returns tool summaries for started providers, without `inputSchema`. `tool PLUGIN_ID TOOL_ID` returns the full normalized ToolSpec, with unknown MCP fields kept in `extensions`. The default toolset is the plugin ID, and tool IDs must be unique within one provider. Different providers can use the same tool name, and calls always use the plugin ID to disambiguate. The older `call-tool PLUGIN_ID TOOL_ID JSON` is unchanged. The manifest in `list` has only basic fields, and `show ID` is the explicit full manifest query, so large fields stay out of the default list. Call output and provider descriptions are external data, not instructions.

A manifest can use `roles` instead of the older `type`. Roles include `tool-provider`, `check-provider`, `hook-provider`, `root-service` and `ui-provider`. The last three are declarations only. They use the existing Java plugin entry and provide no new hook, root service or UI mechanism. The older type is still accepted, and if a provider role and type are both given, type must be `tool-provider`. check-provider uses the same MCP configuration and declares `id/title/description/kinds/targets` under `checks`. Each check id must match an MCP tool of the same name exposed by the provider. `checks [PLUGIN_ID]` discovers started checks. `run-check PLUGIN_ID CHECK_ID JSON` passes the JSON object directly as MCP tool arguments and accepts a direct object or MCP `structuredContent`. The content must include boolean `passed`, numeric `similarity` from 0 to 100, string `summary` and string array `unknowns`. `passed: false` is a valid check result, not a protocol error. A tool returning `isError` or a badly formatted result gives `CHECK_FAILED`. A timeout gives `CHECK_TIMEOUT` and stops the provider, which then needs a reload or re-enable. There is no consent sandbox.

install, enable, disable, reload, call-tool and run-check run by the manager, including failures, are written to `ROOT/evidence/<sha256>.json`. The default Android root is `/data/local/agentos/godmode`. `evidence` exports all records and `evidence ID` reads and verifies one. An invalid ID returns `USAGE`, a missing one `NOT_FOUND`, and changed content or a mismatched file name `CORRUPT_EVIDENCE`. A record id is the SHA-256 of its content excluding id, with keys sorted recursively and JSON encoded. Identical content is deduplicated, so this is not a time-ordered event count. The target hash is the canonical JSON hash of the file list before execution (relative paths and each file's SHA-256), null on read failure with unknown kept. It does not prove files stayed unchanged during execution, and stored hashes cannot stop someone with access from deleting records or rewriting the whole ledger. The operation and archiving are not one transaction across files. Archive failure returns `EVIDENCE_WRITE_FAILED`, and the operation may have finished, so check actual state. Records contain raw arguments and results and may include personal information or secrets. `externalContent` and `untrustedContentFields` mark external data, which consumers must not treat as instructions. Observation covers only this call and external side effects are not independently verified, so coverage is partial.

The workspace state model and JSON schema define `new → inspecting → building → checking → ready`, with moves into `needs_you` or `stopped`. Recovery and rechecking use the transitions listed in the model. Currently there is only the model and transition validation. State is not persisted, and there is no automatic build flow or ready-made GUI or agent query interface. A future marketplace or GUI should read one host-managed state record and not keep its own copy.

## Local device and REA

The main entry point is `taku-plugin godmode local JSON` on the same host. `{"target":"local","action":"apk-paths","package":"org.example"}` returns absolute paths for base and splits, which can be passed directly to REA's `inspect_android_package` with no pull. Other actions: `packages`, `read-file` (`path`, base64, 1 MiB limit), `shell` (`command`), `processes`, `logcat`, `getprop` (`key`), `settings-get` (`namespace`,`key`), `settings-put` (plus `value`), `activity-start` (`component`), `activity-stop` (`package`), `display`, `window`, `device-info`, `root`. These read files or start local Android system commands directly and do not start an adb server. Command results include `exitCode` and `output`. This root socket has no capability sandbox. Only `local` is accepted, and other targets return `REMOTE_UNAVAILABLE`. A transport for external devices is not implemented.

`taku-plugin godmode mcp` aggregates all enabled providers into one stdio MCP service, with tool names in the form `PLUGIN_ID__TOOL_NAME`. The catalog changes as soon as plugins are enabled or disabled, and the agent does not need a restart. `rollback ID` switches atomically between the current and previous version. `install-url '{"url":"HTTPS_URL","sha256":"64_HEX_CHARS","signature":"BASE64"}'` accepts only HTTPS and enforces the digest of the full package. Remote installation requires a signature over the full archive and a Base64 X.509 Ed25519 public key configured in the host service environment as `TAKU_PLUGIN_TRUSTED_PUBLISHER_KEY`. An optional request `publicKey` must match the host key exactly. The host rejects remote installation when no publisher key is configured.

The REA provider points upstream ADB tools at the local device with `REA_ADB_PATH=/data/local/agentos/rea-pack/bin/taku-adb`. `examples/godmode/rea-provider/local-device.mjs` can also call the manager's `local` action directly. `taku-adb` has no adb server. It supports common local commands and is not full adb argument compatibility.

The REA 6.8.0 fixture is in `examples/godmode/rea-provider` and declares a local ARM64 pack. The repository keeps the manifest, Skill and local shims and does not commit the hundreds of MB of Node, JDK, JADX and REA binaries. Install with `taku-rea-pack ZIP LOCK_JSON LOCK_SHA256 /data/local/agentos/rea-pack`. The lock is JSON: `platform=android-arm64`, `node=22.23.4|24.22.2`, `rea=6.8.0`, `java=17|21`, `jadx=0.7.1`, `archiveSha256`, `files` (relative file path to SHA-256), `executables` (files needing the executable bit restored) and optional `links` (relative symlink path to target). All regular files must be listed and checked one by one, and must include `bin/node`, `bin/java`, `rea/package.json`, `rea/scripts/rea.mjs` and `lib/jadx-headless-mcp-0.7.1-all.jar`. Install stages first and then renames atomically. Link targets must not escape the pack root, and an existing target is an error. Use `system/plugins/tools/build-runtime-pack.py ROOT ZIP LOCK SHA256SUMS` to generate a deterministic ZIP, lock and digest list. Checksums do not prove the Android ABI runs or that the source is trustworthy.

First install in firmware is handled by `taku-rea-provision`. `/system_ext/etc/taku/rea-pack.conf` pins the lock's SHA-256 and two immutable download URLs, and `/system_ext/etc/taku/rea-provider` carries the provider manifest. After boot, the `taku_rea_provision` service verifies the lock, downloads the pack, hands it to `taku-rea-pack` for per-file verification and atomic install, then registers and enables the provider through `taku-plugin godmode install`. If a pack already exists, the download is skipped. Retry by hand with `taku-rea-provision --force`, and view read-only status with `taku-rea-provision --status`.

### Physical device results

On Pixel 9, Node 24.22.2, OpenJDK 21.0.13, JADX bridge 0.7.1 and REA 6.8.0 ran locally on Android. MCP doctor reported 229/229 tools and 6/6 prompts, and `search_android_classes` on a real APK returned `org.taku.plugins.ui.PluginActivity`. Disabling dropped the tool count to 0, and re-enabling restored 229. The Android adaptation is two compatibility changes in the plugin pack: allowing `process.platform === 'android'`, and using `bin/ps` and `bin/taku-adb` shims for toybox and local device commands.

The REA dynamic path also ran on Pixel 9 with Android 17 / API 37. `taku-plugin godmode mcp` on the device aggregated 229 tools, `list_frida_processes` returned local processes, `start_frida_session` attached to `com.android.systemui`, and `load_frida_script` and `instrument_with_frida` both enumerated loaded classes through `Java.perform`. To avoid libart and linker problems with older Frida servers on Android 17, the runtime uses server 17.25.2, keeps the 17.23.2 Node binding REA requires, and loads the compiled `frida-java-bridge` 7.0.13.

These results come from a development install.
