# Magpie Settings security follow-up (2026-10-31)

Provider migration was repaired on PR #12 commit `7e5c299bfcd54b840374ac17392ee4694fbd6f60`: before writing Global Settings, API keys, balance tokens, headers, proxy credentials, and URL credentials/query parameters move to private credential files and are restored when projecting into private Magpie configuration. Older data with migration markers is also cleaned; private credentials persist first, and failed Settings writes can retry. Settings is publicly readable, so callers must still not write plaintext credentials directly: an exposure window exists between external writes and Broker cleanup.

## Verification and unfinished work

- `system/assistant/test-system-ai-host.sh` passed: initial migration, existing migration markers, failed-write retry, empty credential references, authenticated proxies with/without schemes, nested credentials, URL query stripping/restoration, recheck before projection, and no reuse of old passwords after proxy-address changes.
- `system/assistant/build.sh` passed, rebuilding Assistant APK (SHA-256 `67fc3a560cfe207b9369d7132cdccb132b02670d16cd14e1fe86936184aa9eb1`) and Broker JAR (SHA-256 `d0ed0956d16d1a044b105c21de41c2235b82d5b1fc2ee857398355669d8d4506`). These development builds have not passed installation acceptance.
- Dedicated Mac mini API 36 AVD `Taku_SystemAiGroups_20261030` started; host tests for this security fix do not establish four-way interoperability on-device.
- Magpie v0.1.2204 old management UI and native CLI still write private files directly; existing Broker overwrites those changes from Settings. Runtime changes, four-way GUI↔CLI tests/screenshots, and affected image-component rebuild/acceptance remain unfinished. PR stays Draft and must not be merged based on this record.
- Follow-up integration must handle races between direct Settings and runtime writes, private credential storage, failure recovery, and updated runtime-source/artifact locks. The four Global Settings keys are not a cross-key transaction; file-watcher synchronization alone does not establish concurrency safety.
