# Local Codex plugin lifecycle fixture

This local marketplace includes one compatibility-format Codex plugin, a skill, and a small stdio MCP server with one `hello` tool. It is an Android emulator fixture, not a third-party sandbox or a general-purpose MCP implementation. The relative MCP `cwd` is resolved by the pinned Codex runtime against the installed plugin; do not substitute `${PLUGIN_ROOT}` in the command arguments without independently verifying runtime support.

Run the guarded test only on the dedicated lifecycle emulator: `python3 system/assistant/test-plugin-lifecycle.py emulator-5668`. It installs the fixture using the app-server protocol, checks the native MCP tool list, disables the plugin, and uninstalls it. No model request is made.
