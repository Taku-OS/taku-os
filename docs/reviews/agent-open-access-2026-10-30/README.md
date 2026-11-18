# Agent open-access component probe, 2026-10-30

Base: `9dc837fc41b3884ebcaa420860c87780a186a4e2`. Independent Android 16 ARM64
AVD `Taku_AgentOpenAccess_20261030`, serial `emulator-5672`, host GPU. The AVD
was copied from a stopped development image; copied assistant, memory and Codex
user data were cleared before the probe. The assistant and Memory component APKs
were built from this branch. The broker was started from this branch's JAR and
its pinned Codex binary mounted temporarily on this AVD; this is component
acceptance, not a ROM build or physical-device test.

Device-local `content call` read the actual plugin catalog, installed plugin and
MCP status, Skills with provenance, Memory library, graph, board, profile, agenda,
settings and Memory token counters. The fresh library and plugin catalog were
empty. The packaged `taku-open-access` skill was discovered with `builtin-1`
provenance. Disabling it and restoring it to enabled both succeeded and were
read back. Memory `queryEnabled` changed false → true → false and each value was
read from the store; the original value was restored. A separate ordinary UID
application (`10171`) called both providers without their former signature
permissions after declaring Android package visibility; both returned `ok:true`.
A temporary test instrumentation then used the real Memory store to ingest one
clearly labeled synthetic record. CLI `get`, revision-checked `update`, `get`
with history, and rejection of a stale update all passed; this is a fixture,
not an accessibility capture or a GUI editing test. The unauthenticated App Server returned explicit authentication-required errors
for account usage and rate limits. No model call or live account was used.

The screenshots show **actual CLI results**, rendered on the emulator in a
small temporary evidence viewer; they are not screenshots of a terminal app.
Long Skill results are reduced to selected fields and formatted for readability.
The viewer does not generate the underlying results. The screenshot text reports
verified mutation round trips; it is not a live recording of those commands.

- [Plugins and MCP](01-plugins.png)
- [Skills and recorded versions](02-skills.png)
- [Memory library and graph](03-memory.png)
- [Memory board, profile and agenda](04-memory-derived.png)
- [Usage and explicit upstream errors](05-usage.png)
- [Synthetic Memory read and edit](06-memory-edit.png)

The synthetic fixture does not establish graph pagination under load, real plugin installation, account usage, cost reporting,
GUI visual synchronization, or Case access. Main has no canonical Case registry
or currency ledger; no fabricated values are reported. The plugin call shapes
and MCP reload match PR #13's implementation, but the two branches have not
been integrated or jointly tested. PR #12 settings integration is also pending.
