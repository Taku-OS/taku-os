# Plugin CLI

On TakuOS, an Agent running as the runtime identity can use `/system_ext/bin/taku-plugin`. It sends requests to the same broker and Codex app-server used by the plugin screen. Output is JSON; `list` returns the same workspace catalog and `localVersion`, `installed` and `enabled` fields as the screen. `status` returns MCP server and tool status.

```sh
taku-plugin list
taku-plugin status
taku-plugin install /absolute/path/.agents/plugins/marketplace.json plugin-name
taku-plugin disable plugin-name@marketplace-name
taku-plugin enable plugin-name@marketplace-name
taku-plugin uninstall plugin-name@marketplace-name
```

Mutations request MCP reload after the native operation, like the GUI. Exit 0 means both calls succeeded; exit 1 means the first call failed or input was invalid; exit 2 means the first operation succeeded but the reload failed, so inspect `list` and `status` before retrying. The workspace catalog covers sources visible to native `plugin/list` at `/data/local/agentos/work`, not necessarily orphaned installations. Upstream local version labels need not identify immutable content. Upgrade, rollback, uninstall recovery and cross-process serialization are not implemented. Third-party MCP still launches with the root runtime's privileges; use only trusted plugins until isolation is implemented.

Plugin creation commands (the creator skill provides the full workflow):

```sh
taku-plugin validate /path/to/plugin
taku-plugin pack /path/to/plugin /data/local/agentos/work/my-package
taku-plugin catalog-add /data/local/agentos/work/my-package
taku-plugin install /data/local/agentos/work/.agents/plugins/marketplace.json plugin-name
taku-plugin publish /path/to/plugin /path/to/new-output [OWNER/REPO]
```

`validate` checks the native manifest, declared `taku.capabilities` and `taku.permissions`, referenced skills/MCP JSON and rejects symlinks. These declarations are descriptive, not enforced grants or a comparison against actual behavior. `pack` writes a copied plugin, ZIP, SHA-256, a local marketplace and a community index entry; the output directory must not already exist. `catalog-add` adds the packed copy to the same workspace marketplace used by the screen; duplicate plugin names are rejected. `publish` always makes local artifacts first; if an existing repository is specified and `gh`, `git`, authentication and repository write access are available it pushes a branch and opens a PR. Read the JSON `published`, `reason`, `branch` and `pr` fields: a local-only result has exit status 0 because packaging succeeded. No repository is created. On the tested emulator `gh` and `git` are absent, so community submission remains local.

Plugin RPC and workspace catalog registration accept ordinary app callers as well as the runtime and GUI; they operate on the same plugin state. This deliberately does not create a permission boundary for plugin operations. Other broker interfaces retain their existing access checks.
