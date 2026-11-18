# Cellular network country plugin

`org.taku.environment.country` is an Android `tool-provider` for existing God Mode, version `0.1.0`. Other cases call it through the existing tool protocol; the SDK remains the only country-sampling implementation. The plugin contains no travel, translation, or reminder rules.

## Prerequisites and build

The device needs Android 36 and the existing God Mode `PluginManager`, `ToolProvider`, and `app_process`. God Mode host must be at least `1.0.0` and include this change's Android UTF-8 file read/write compatibility fix. This package is Android DEX and cannot run on a desktop JVM host. It uses the host's identity and system-interface permissions, requesting no new permissions or accounts. The host must be able to read the installation package and launch `/system/bin/sh` and `/system/bin/app_process`. The production entry point only reads the real TelephonyManager and has no country injection parameter.

`app_process` does not pass through zygote's Telephony initialization. After creating a system Context on the main thread, the provider checks the platform `TelephonyServiceManager`. It reuses an existing instance or initializes one once through framework interfaces before creating the SDK. Framework initialization failure causes provider startup failure and exit; it is not disguised as an unknown country and does not fall back to system properties.

Run from the repository root:

```sh
ANDROID_SDK_ROOT=/path/to/android-sdk JAVA_HOME=/path/to/jdk bash system/environment-signals/plugin/build.sh
```

The script reuses `scripts/android-env.sh`, builds the SDK, compiles the provider, passes both to D8, and validates only this plugin's manifest. Outputs are in ignored `system/environment-signals/out/plugin/`:

- `install/`: installable directory with root-level `manifest.json`, `start.sh`, `country-provider.jar` containing `classes.dex`, and `skills/country/SKILL.md`.
- `country-plugin-0.1.0.zip`: the same files directly at the ZIP root.
- `SHA256SUMS`: SHA-256 values for installed files and the ZIP.

## Installation, discovery, and lifecycle

Place `install/` or the ZIP somewhere the device host can read, then call the existing CLI from the device shell:

```sh
taku-plugin godmode install /data/local/tmp/country-plugin-0.1.0.zip
taku-plugin godmode show org.taku.environment.country
taku-plugin godmode tools environment.country
taku-plugin godmode tool org.taku.environment.country country_read
taku-plugin godmode call-tool org.taku.environment.country country_read '{"refresh":true}'
taku-plugin godmode disable org.taku.environment.country
taku-plugin godmode enable org.taku.environment.country
taku-plugin godmode reload org.taku.environment.country
```

Initial installation enables the plugin by default. Reinstalling the same version returns the existing manager's `ALREADY_INSTALLED`. Four tools should appear after enabling; disabling removes them from the catalog, and enabling or reloading restores them. The management page uses the same manager for enable, disable, and reload. Remove with `taku-plugin godmode remove org.taku.environment.country`.

The host's `ToolProvider` performs MCP `initialize` and `tools/list` before exposing calls. `healthcheck.timeoutMs=10000` is the existing initialization/call timeout, not a periodic ping. `start.sh` replaces its shell with `exec app_process`; sampling occurs in that process without sampling subprocesses. Disabling and reloading use the host's `Process.destroy()` to terminate the provider. On stdin EOF, the main thread closes SDK subscriptions, receiver, and timers before exiting. The Android main Looper and Binder threads end with the process. The stdin reader is a daemon and does not prevent exit.

## Four tools

All arguments must be JSON objects. Extra fields, null, and type coercion are rejected. `country_read.refresh` must be boolean; `subscriptionId` must be a string containing non-whitespace characters.

| Tool | Arguments | Returned `structuredContent` |
| --- | --- | --- |
| `country_read` | `{"refresh":false}`; refresh is optional | Current snapshot; `true` rereads immediately and generates a new sequence |
| `country_subscribe` | `{}` | `subscriptionId`, current `snapshot`, `ttlMs` |
| `country_poll` | `{"subscriptionId":"ID"}` | `subscriptionId`, `providerGeneration`, `sequence`, `events`, `dropped`, `ttlMs` |
| `country_unsubscribe` | `{"subscriptionId":"ID"}` | `subscriptionId`, `removed`; repeated removal returns false |

Every result includes MCP `content:[{"type":"text","text":"JSON"}]`, matching `structuredContent`, and explicit `isError`. For `isError=true`, content is `{"error":{"code":"...","message":"..."}}`. Error codes include `INVALID_ARGUMENTS`, `UNKNOWN_TOOL`, `SUBSCRIPTION_INVALID` (missing, expired, or from an old process), and `SUBSCRIPTION_CAPACITY`. Unknown MCP methods return JSON-RPC `-32601`; invalid requests or arguments use `-32600`/`-32602`, and JSON parse failures use `-32700`. JSON-RPC responses retain the request ID; notifications receive no response. stdout contains only JSON lines; diagnostics go to stderr.

Snapshots contain the SDK's `countryIso`, `source`, `sourceDescription`, `availability`, `reason`, `observedAtMillis`, `observedElapsedRealtimeMillis`, `continuousSinceElapsedRealtimeMillis`, and `stable`, plus a per-process UUID `providerGeneration` and a `sequence` incremented for each sample. SDK enum names are unchanged, such as `UNKNOWN` and `EMPTY_COUNTRY`. Unknown country is an empty string, never a historical value. See the [SDK README](../README.md) for source and stability conditions.

While enabled, the plugin retains one observer and one continuous subscription. The SDK samples broadcasts, scheduled checks, and explicit refreshes on the main thread. A separate stdin reader posts requests to that same main Handler, ordering events and calls. Creating a client subscription neither creates another sampler nor forces refresh. It returns the current snapshot and places its cursor at the current sequence; the first poll returns only later samples. Two clients receive distinct IDs and do not consume each other's events.

Global history retains at most 64 samples, including samples with unchanged countries. `poll` returns immediately without waiting for a country change, then updates that subscription's cursor and activity time. `dropped` counts unread samples that have already fallen outside the history window. `events=[]` and `dropped=0` is valid. Subscriptions expire after 10 minutes without a poll. At most 64 may be active; expired entries are removed before adding one, and full capacity produces an explicit error. Unsubscribing affects only that cursor; the provider continues observing. TTL uses `elapsedRealtime`, including sleep.

This is polling, not push delivery or a persistent queue. Disabling, reloading, and host/provider recreation discard history, subscriptions, and the stability window. A new process samples afresh and changes generation; polling an old ID explicitly fails, and consumers must resubscribe. The existing manager does not guarantee automatic recovery after an independent provider crash. Inspect `show` and reload as needed. Old snapshots cannot establish continued stability.

## Calls from other cases

Reuse the existing device-side `AndroidRootTransport`; no new client library is needed:

```java
AndroidRootTransport transport = new AndroidRootTransport();
String response = transport.request("call-tool", new JSONObject()
        .put("argument", "org.taku.environment.country")
        .put("name", "country_subscribe")
        .put("arguments", new JSONObject()).toString());
// Check outer ok, then result.isError, then read result.structuredContent.
```

Pass the returned subscriptionId to `country_poll`, and to `country_unsubscribe` when finished. Equivalent existing God Mode JSON-lines requests:

```json
{"operation":"call-tool","argument":"org.taku.environment.country","name":"country_read","arguments":{"refresh":true}}
{"operation":"call-tool","argument":"org.taku.environment.country","name":"country_subscribe","arguments":{}}
{"operation":"call-tool","argument":"org.taku.environment.country","name":"country_poll","arguments":{"subscriptionId":"SUBSCRIBE_RETURNED_ID"}}
{"operation":"call-tool","argument":"org.taku.environment.country","name":"country_unsubscribe","arguments":{"subscriptionId":"SUBSCRIBE_RETURNED_ID"}}
```

The host returns `{"ok":true,"result":{...MCP result...}}` or the existing outer `{"ok":false,"error":{...}}`. The default address is the Android abstract LocalSocket `taku-godmode`, and the default registry is `/data/local/agentos/godmode`. `AndroidRootTransport` fixes that default socket; the existing CLI can select another with `TAKU_GODMODE_SOCKET`. Actual socket reachability depends on the ROM's existing SELinux, UID, and host configuration; this plugin does not change access. The existing host runs as root without a plugin capability sandbox, and `capabilities` are discovery metadata only. The plugin cannot fix socket reachability for callers.

Through `taku-plugin godmode mcp` aggregation, tool names become `org_taku_environment_country__country_read`, etc., with unchanged arguments and results. The existing host exposes skill metadata; it does not install the skill into external Agents' libraries automatically.

## Minimal acceptance

After installing in an isolated Android environment, confirm discovery of four tools. Subscribe twice and obtain different IDs; neither first poll should repeat the initial snapshot. Call `country_read {"refresh":true}`, then poll both independently: each should receive the new snapshot with the same generation/sequence. After unsubscribing one, its poll should return `SUBSCRIPTION_INVALID` while the other remains readable. Disabling removes the tools. Re-enabling changes generation and invalidates old IDs. An emulator without a cellular country must return real `UNKNOWN`; do not fabricate a country to pass. Actual border crossings, long-running operation, and reboot behavior require suitable environments.

## Recorded verification scope

An isolated API 36 AVD passed eight groups of real installation, protocol, and lifecycle checks: real country snapshot, independent consumption by two subscriptions, independent unsubscribe, argument rejection, bounded history and dropped counts, release on disable, re-enable, and reload/removal. Production TelephonyManager returned that emulator's network country `US`; this is emulator network configuration, not physical border-crossing evidence. Killing the host also caused the old provider to exit on stdin closure. Restarting the host restored the plugin from the enabled registry; the new generation invalidated old subscriptions.

Scoped acceptance script, run after starting a separate AndroidServer and pushing the install directory:

```sh
python3 system/environment-signals/plugin/tests/verify-emulator.py \
  --serial emulator-5554 --expected-avd YOUR_DISPOSABLE_AVD \
  --socket country-test-your-task --plugin-dir /data/local/tmp/country-plugin \
  --out /absolute/path/new-country-plugin-result.json
```

The script requires an emulator serial, qemu property, and exact AVD name, and accepts only a test socket. It installs and removes only this plugin, leaving the default registry, APKs, network, and microphone unchanged. The host uses an independent root directory and the matching socket, for example `AndroidServer /data/local/tmp/country-test-state country-test-your-task`. Failure preserves the environment; cleanup removes only its allocated forwarding port. Ten-minute subscription expiry was confirmed through implementation and review without waiting for expiry in this run. Real border crossings, long-term background operation, and ROM boot recovery remain unverified.
