# System AI: partial AVD smoke check, 2026-10-31

This is **not** the requested full GUI↔CLI acceptance. On the Mac mini, the dedicated `Taku_SystemAiGroups_20261030` API 36 AVD ran an APK and broker built from `3fb586c`. SELinux was Enforcing; this was a writable development emulator, not a ROM or physical-device test. The broker and Magpie were started manually after a cold boot.

A synthetic `pr12-fixture-check` group was inserted into `taku_magpie_groups` via Android `settings put`; the running settings page displayed its name, group ID and synthetic member. The [screenshot](cli-group-visible.png) was captured while that entry was present. The fixture entry was removed afterward. This checks only **group CLI → GUI display**, not GUI writes, inference, provider/model management, scenes, or concurrent recovery.

| Component | SHA-256 |
| --- | --- |
| Built APK | `473f955468d65f85c2b8f93519685d4eaa981263daca6ac50b7885d855a27519` |
| Broker JAR (device matched build) | `7d88916725e9119c26d933b086ff76f3f18a7d0cb300cbdcd1bf0055c07b9d85` |
| Unmodified Magpie 0.1.2204 (device matched lock) | `784eeac4560062f38a74a9e0d644a0aecfee6103fd453f3576914fb87ff60074` |

The runtime still writes its own provider file, and the concurrent multi-key protocol is unfinished. The complete requested acceptance must be repeated on the final implementation; see the [data contract and open gaps](../../system-ai-data-contract.md).

## Audit observation, 2026-10-31

On the same AVD, with the broker stopped, three native `settings put global`
writes to a temporary synthetic key left only the final value readable. The
SettingsProvider's `dumpsys settings` history showed three `update` entries,
but did not include the overwritten values or writer identities. The temporary
key was deleted after the probe. The current file observer likewise does not
retain overwritten `providers.json` contents while the broker is offline.

Consequently, comparing the two current snapshots on broker restart can detect
and resolve a divergence, but cannot reconstruct every intervening overwrite or
its old-value summary. The requested complete audit needs durable capture at
the write boundaries (including native Settings writes and Magpie file writes),
with crash recovery and credential redaction. It must not be represented as
complete merely because the broker logs a conflict it happens to observe.
