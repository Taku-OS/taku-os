# REA local Android provider

Use `taku-plugin godmode tools`, `taku-plugin godmode mcp`, or
`taku-plugin godmode call-tool org.taku.rea TOOL '{}'` to discover and invoke the
on-device REA MCP server. REA 6.8.0 exposes 229 tools; inspect the selected tool
schema before supplying arguments.

The Android ARM64 runtime pack at `/data/local/agentos/rea-pack` contains Node
24, OpenJDK 21, JADX, the Frida 17.23.2 Node binding with a 17.25.2 server, Rizin 0.9.1,
Apktool 3.0.3, AAPT2 and the local ADB shim. No VPS or host bridge is used. Prefer `taku-plugin godmode local`
to obtain APK paths and local device state, then pass those paths directly to
REA. Frida device operations target the loopback server started by `rea-mcp`
and are exposed through `list_frida_devices`, `list_frida_processes`,
`start_frida_session`, `load_frida_script`, and `close_frida_session`. Pass
`device_id: "local"`; REA translates it to the local remote endpoint. Scripts
run with the bundled `frida-java-bridge`, so `Java.perform`,
`Java.enumerateLoadedClassesSync` and native `Interceptor` are available. Rizin
handles ELF analysis; Apktool and AAPT2 handle resource and manifest decoding.

On a provider failure, keep the returned Evidence and error code. A missing
runtime pack, unavailable executable, unsupported architecture or failed Frida
session is a capability failure, not evidence that the requested artifact was
clean.
