# REA Android tool-provider

Community-style reverse-engineering provider for the Taku God Mode plugin bus. The
plugin manifest loads the upstream `rea-agents` MCP server as a local Android
process; it does not use a VPS or host fallback.

## Pinned runtime

- `rea-agents` 6.8.0, MIT
- Node.js 24.22.2, Termux Android ARM64 build
- OpenJDK 21.0.13, Termux Android ARM64 build
- `jadx-headless-mcp` 0.7.1, pinned release JAR
- Frida 17.25.2 server/inject, required for Android 17 process discovery
- Frida 17.23.2 Node binding, built against Android NDK r29
- `frida-java-bridge` 7.0.13, compiled into a Java-enabled script runner
- Rizin 0.9.1, Apktool 3.0.3 and AAPT2 2.20-android-16.0.0_r4

To reproduce the REA-side bridge defaults, run
`python3 tools/patch-rea-frida.py /path/to/rea-pack/rea` after installing the
upstream `rea-agents` package. The committed patch only adds a loopback Frida
default and a Java-bridge execution wrapper; it does not change REA analysis
providers.

The runtime is installed at `/data/local/agentos/rea-pack`. It is a large,
separately installed pack because it contains the Android Node, JDK, JADX bridge,
REA dependencies, reverse-engineering tools and skills. See
[Frida Node binding build notes](docs/frida-node-android.md) for the Android ARM64
N-API build.

## Android adapter changes

The upstream analysis implementation is unchanged. The provider applies three
narrow host adaptations:

1. Accept `process.platform === "android"` in the JADX host guard.
2. Disable POSIX process-ownership sweeps on Android; direct PID and process-group
   supervision remain active.
3. Supply `bin/ps` and `bin/taku-adb` shims so REA's process inspection and
   Android-tool paths understand the local device.

`bin/taku-adb` maps `devices`, `shell`, `exec-out`, `pull`, `push`, `install`,
`uninstall`, logcat and common device commands onto the local Android system.

## Agent attachment

`/system_ext/bin/taku-plugin godmode mcp` exposes every enabled God Mode
provider through one stdio MCP server. Tool names are namespaced as
`PLUGIN_ID__TOOL_NAME`; the bridge reads the live daemon registry, so disabling
a provider removes its tools without restarting the Agent runtime. The same
command is also the host-side test entry when a compile host supplies
`TAKU_GODMODE_HOST_JAR` and `TAKU_GODMODE_SOCKET`.

## Frida bridge

`bin/rea-mcp` starts the loopback-only Frida server before REA. The REA runtime
uses `REA_FRIDA_REMOTE=127.0.0.1:27042` for Frida discovery and sessions, and
`REA_FRIDA_JAVA_BRIDGE` loads a compiled `frida-java-bridge` runner before each
user script. This avoids the Android 17 local-backend `libart` loading path while
preserving `Java.perform`, class enumeration and arbitrary Frida JavaScript.

## Verified path

On a Pixel 9 running TakuOS 1.3.1:

- MCP self-check: 229 tools, 6 prompts, catalog digest matches REA 6.8.0.
- `search_android_classes` found `org.taku.plugins.ui.PluginActivity` in a real
  installed APK through the on-device JDK and JADX bridge.
- Disable removed all REA tools; enable restored all 229 without restarting the
  plugin host.
- REA 1.5.0 on Android 17 / API 37 attached to `com.android.systemui`, loaded the
  Java bridge, and enumerated 38,315 loaded classes through the remote Frida
  server; Evidence ID: `ev_8cad9a7feeac92a5740abfd7fddb66115f947de548ef7bc27f23a758e1783a53`.
