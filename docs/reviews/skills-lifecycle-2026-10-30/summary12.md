# Transaction interruption retest

The transaction host suite now covers every install journal transition with `enabled=true`, plus interruption during rollback recovery itself. All 12 host tests passed. The host config implementation remains a file substitute, not the production TOML editor.

On the dedicated Android 16 ARM64 AVD `Taku_SkillsLifecycle_20261030` on the Mac mini, an isolated app-server used the experimental patched Codex 0.161.0 binary SHA-256 `5833b45db4bf3148200656f17ebed5c4b5c581adce07d861f7f4f54e61d71fc5`. The device probes use disposable homes and clean them afterward:

- An install of 1.0.0 was upgraded to 2.0.0, and the 1.0.0 backup was confirmed present. SIGKILL after the first cache file was deleted left a partial 2.0.0 cache, backup, and uninstall journal. Restart removed the cache, backup, journal, and plugin configuration.
- With a large valid TOML file to widen the timing window, SIGKILL was sent after observing the `committed_enabled` journal and before the plugin's config entry appeared. Restart restored the installed cache and enabled TOML entry while preserving an unrelated disabled entry.

These are actual process-kill tests of isolated app-server instances, not GUI or broker tests and not power-loss tests. The standard image asset lock and bootstrap still select the official unpatched archive, and the launcher reuses the existing runtime directory; therefore these results do not establish that the standard product build runs this transaction implementation. Generic configuration writes also do not share the plugin store lock, so concurrent configuration changes are not covered by the transaction guarantee. Keep the PR in draft until those integration and isolation gaps are resolved and the resulting product path is validated.
