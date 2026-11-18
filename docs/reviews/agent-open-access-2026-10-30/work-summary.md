# Agent open access — work summary

Base `origin/main` at `9dc837fc41b3884ebcaa420860c87780a186a4e2`; branch
`codex/agent-open-access-20261030` in its own worktree. Other worktrees and the
concurrently assigned emulator were not modified.

Implemented open Android `content call` endpoints that reuse the native Skills
controller, plugin/usage RPCs and Memory store. They expose Skills provenance
and controls, plugin catalog/status and controls, Memory records/graph/derived
views/settings and available usage. The providers deliberately allow other
local apps to call them: this also gives them access to personal Memory and
runtime configuration, as required by the requested open product policy.
The repository policy and architecture now make Agent/GUI parity mandatory;
the packaged `taku-open-access` skill documents the available commands.

Assistant and Memory builds passed. `npm run verify` passed with Homebrew Python
3.14 and the project JDK; the first two attempts failed due to the host default
`javac` and Python 3.9 respectively. Existing agent gateway host checks passed.
On isolated `emulator-5672`, ordinary shell calls queried empty plugin and Memory
stores, Skills and derived Memory, toggled and restored a Skill and Memory
setting, and returned genuine authentication-required errors for account usage.
An ordinary app UID independently reached both providers. A synthetic record
was ingested into the real Memory store; CLI read, edit, history, and stale
revision rejection passed. Screenshots and scope
are recorded in `docs/reviews/agent-open-access-2026-10-30/`.

**Not complete:** this base has no canonical persistent Case registry or
currency-cost ledger, and this isolated emulator has no authenticated account;
Case, actual account token usage and currency cost cannot be demonstrated. Installed plugin mutation remains unverified. The API does not yet cover every GUI setting/state, and end-to-end GUI
parity, joint integration with PRs #12 and #13, full ROM and physical-device
acceptance remain outstanding. The preinstalled skill currently covers only the
implemented endpoints and does not claim universal settings coverage. Built-in
skills already present on a device are not automatically overwritten by an APK
update, so updating that documentation on existing devices needs a migration.
