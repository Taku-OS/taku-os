# Create and share a plugin

1. Gather the current case's reusable files into a new directory. Include instructions in `skills/<name>/SKILL.md` and any scripts or MCP configuration they actually need. Do not include credentials, private conversation data, local build caches, or unrelated files. Name the user as author when known; do not invent their identity.
2. Write `.codex-plugin/plugin.json` using the native plugin manifest. Include `name`, `version`, `description`, `skills` and/or `mcpServers`, and an `interface` if useful. Add `taku` with `capabilities` and `permissions` arrays describing what the plugin actually does and needs; use empty arrays if none. These are declarations, not enforced isolation.
3. `taku-plugin validate DIR`, then `taku-plugin pack DIR OUTPUT_DIR`. The output contains a plugin copy and a local marketplace manifest. To make it visible in the GUI, place OUTPUT_DIR inside `/data/local/agentos/work`, run `taku-plugin catalog-add OUTPUT_DIR`, and install using its returned marketplace path and plugin name; inspect `taku-plugin list` and `status`, run the actual behavior, then uninstall the test plugin. Check exit codes: 2 means the mutation succeeded but MCP reload failed.
4. `taku-plugin publish DIR OUTPUT_DIR [OWNER/REPO]` generates a package and community index entry; the default is `Taku-OS/taku-os-plugins`. If `gh` and `git` are available with authentication, it submits a fork branch and PR. On Android they are usually unavailable: retain the output and run `python3 system/assistant/publish-plugin-host.py --serial SERIAL --packed OUTPUT_DIR` on the connected host with its existing `gh` login. Alternatively, start on the host with `python3 system/assistant/publish-plugin-host.py --serial SERIAL --source DIR --output OUTPUT_DIR` to run the device publish and submit in one command. This explicitly transfers the package, not credentials; it never copies host credentials to the device. If the host or network is unavailable, report local-only artifacts and the reason. Do not claim success until the PR URL is returned. Never create the upstream repository automatically.

Plugin state is shared with the native plugin screen. MCP processes currently execute with the runtime's privileges; capabilities are descriptive and are not an authorization boundary.

# Plugin market integration status

The Expo UI route `taku-agent://plugin-market` is an integration shell, not a
new plugin manager or agent API. Until its native manager bridge is connected,
its reads and writes return `UNAVAILABLE`; no command in this change installs,
enables, disables, reloads, removes, rolls back, or lists marketplace sources.
Use the existing `taku-plugin` commands documented here for their actual
supported operations. Source-management commands and the shared manager's
read/write verification are not yet available and must be documented during
native integration; do not assume a GitHub repository URL is an index URL.
