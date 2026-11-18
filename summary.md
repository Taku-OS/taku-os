# PR #12 continuation — dynamic scene routing (2026-10-30)

- Removed the System AI text/image slots from the page, Settings API and Broker policy. On first Broker configuration read, legacy keys and the old implicit defaults migrate to Magpie groups and explicit scenes; existing scene bindings win. Old generic `text`, `image`, `ime.image` and assistant image bindings are converted to explicit scenes. The old keys are then deleted. The migration marker makes retries idempotent.
- System AI resolves `memory.text`, `ime.vision`, `screen`, and registered application scenes to groups. Main Magpie connection and Jev selections are migrated into `assistant` and `jev` bindings respectively; their existing connection/enable controls remain. Per-application explicit overrides remain supported. Memory organizer's system choice requires an available group on a Magpie connection instead of silently falling back.
- The Settings page dynamically reads all groups and bindings, renders the same rounded row cards as the rest of the page, offers scene rebinding and group management, and provides an empty-state entry to Magpie.
- Disposable API 36 AVD `Taku_JevMagpie_20261021` (`emulator-5680`): legacy keys were deleted after migration while an existing `memory.text` binding survived. Synthetic requests succeeded through `memory.text` and `ime.vision`, and `screen` resolved to the same migrated image group. Old main and Jev selections migrated to `assistant` and `jev`; a synthetic Decisions request succeeded through the Jev group. While the page remained open, `settings put` added a group and scene, both appeared; tapping the scene changed its binding. Empty state was also checked. Screenshots: `settings-dynamic-migrated.png`, `settings-dynamic-added.png`, `settings-dynamic-rebind.png`, and `settings-dynamic-assistant.png` in `docs/reviews/jev-magpie-groups-2026-10-30/`.
- Assistant build, System AI host tests and Jev/Magpie host tests passed. The device upstream was synthetic; no real model, full ROM, rebuilt IME APK or physical device was tested. Settings writes and first-start migration still use non-transactional read/modify/write; concurrent external writers can race. PR remains draft because the broader original request described below remains incomplete.

---

# PR #12 continuation — Magpie Settings integration (2026-10-30)

This continuation implements the requested Magpie Settings item; PR #12 remains draft because the other previously requested items are outside this continuation. The older investigation sections below are historical and their statements that this item has no implementation are superseded.

- Broker migrates absent `taku_magpie_providers`, `taku_magpie_models`, `taku_magpie_groups`, and `taku_magpie_scene_bindings` from the existing private Magpie configuration, then sets `taku_magpie_migrated_v1=1`. The migration's get-then-put is intentionally non-atomic; concurrent external settings writes use last-writer-wins.
- Settings is authoritative. A system-UID ActivityManager-managed service hosts a ContentObserver and notifies the Broker over UID-checked local IPC (Android 36 rejects observer registration from standalone Broker `app_process`); Broker writes the Magpie cf9ea977 private projection and restores it after direct provider/group file changes. Credentials and headers stay in a root-only reference store, not Global settings. Jev legacy credential import writes the canonical provider configuration and private store.
- On disposable `Taku_JevMagpie_20261021`, shell `settings put` registered synthetic `fixture/shell-model`, created `group/shell-verified`, and bound `memory.text`; `magpie group`/`magpie models` showed them, and a shell-invoked Broker request reached a synthetic HTTP upstream via the group. The open settings page showed the same data: `docs/reviews/jev-magpie-groups-2026-10-30/magpie-cli-settings.png`. Migration with an absent models key preserved pre-existing group and scene values; direct Magpie group edits were reverted; a synthetic credential import did not expose its key in Global settings. Assistant build, System AI host tests and Jev/Magpie host tests passed.
- The upstream is synthetic; no real model, full ROM or physical device was tested. Invalid settings leave the last valid Magpie projection until corrected. Direct Magpie provider/group edits are overwritten; use Settings for changes. A new credential reference must already exist in the private credentials store; no credential-entry UI was added. Configuration shapes and test details: `docs/reviews/jev-magpie-groups-2026-10-30/magpie-settings.md`.

---

# PR #12 continuation — additional investigation (2026-10-30)

No requested milestone was completed in this continuation. The branch still requires all four items below; do not merge. Host System AI tests passed again. On the disposable `Taku_JevMagpie_20261021` API 36 AVD, inserting an existing fixture key through `content insert --uri content://settings/global` replaced its value; the fixture was deleted afterward. Therefore neither this insert nor a Broker-only lock provides put-if-absent against an independent `settings put`. A provider-side atomic operation (or another design that preserves the required canonical Settings reads and direct-write precedence) still needs implementation and device testing.

The research checkout currently has HEAD `1a3b9268860263f263a2c832b1f9b6d1b57e3fa0`, not the shipped release. Annotated tag object `e60b6724586f9ff6b5b764e27cee4b3e3cfa3d09` for `v0.1.2204` resolves to commit `cf9ea977ca77a4d21dbaef289e42cca725fd139c`; use that commit when inspecting or rebuilding the pinned runtime. The checked-in wrapper still executes the upstream binary, and the builtin skill installer currently handles only `taku-app-development`. The disposable emulator has `magpie` but no `taku` command on its shell PATH; CLI setup or packaging must be resolved before the requested CLI-to-GUI validation.

---

# PR #12 continuation — per-key System AI progress (2026-10-30)

Partial progress; PR remains draft and the full four-item request is not complete. Broker reads Settings.Global for every request and writes only patched keys: `taku_ai_enabled`, `taku_ai_group_text`, `taku_ai_group_image`, and `taku_ai_app_<consumer>`. On first Broker read, a legacy `/data/local/agentos/ai/settings.json` is migrated only for absent keys and then renamed `settings.json.migrated`. The GUI watches `taku_` settings, and Memory observes them to invalidate cached model/leases. Existing in-flight interfaces use a non-persisted snapshot fingerprint for compatibility, not a write revision; an A→B→A change between reads can escape that check. Memory's observer can invalidate leases when notified, but does not prove strict ordering.

Verified on disposable AVD `Taku_JevMagpie_20261021`: a synthetic legacy file migrated, a pre-existing text key won, and the legacy file was archived. With System AI open, `settings put global taku_ai_enabled 1` and `settings put global taku_ai_group_text cli/sync-demo` changed the switch and displayed model. Screenshot: `docs/reviews/jev-magpie-groups-2026-10-30/settings-put-gui.png`. Assistant build, System AI host tests and the isolated Memory Settings observer instrumentation passed.

Known limitations: migration's get-then-put can race an external write during first startup; multi-key patches are not transactions; notifications and request-time reads do not impose strict ordering. Other settings listed below, independent Memory decision group, Magpie backing and CLI/GUI proof, and installed skill remain unfinished. The old single-JSON key from the abandoned local experiment is not a production migration source.

---

# PR #12 continuation — Memory observer experiment (2026-10-30)

**Still incomplete and uncommitted. No milestone was pushed.** Remote draft HEAD was checked as `3e3b7d8`. The single-JSON prototype below still exists and is not suitable for release.

- `MemoryStore` now registers a process-lifetime observer for `Settings.Global` changes under `taku_` and invalidates its cached model and outstanding analysis leases when notified. It also invalidates on store startup to cover time while its process was absent. This is a partial building block, not Broker-side request fencing or per-key configuration.
- On disposable AVD `Taku_JevMagpie_20261021` (`emulator-5680`), the new isolated instrumentation observed an actual `settings put` notification, rejected a synthetic stale batch, observed changes back to the original value, and invalidated the model on store startup. `SystemSettingsObserverTests` reported all four checks passed. It does not prove strict ordering between a settings write and a concurrently completing Broker request. The fixture key was deleted afterward. The test APK was installed on that AVD only.
- `system/memory/build.sh` and `build-tests.sh` passed using the already-downloaded vector dependencies in the `taku-os-agent-open-access-20261030` worktree. The unrelated existing `SystemAiTests` permission assertion was updated for the product owner's open-configure instruction but was not run end-to-end: the AVD has no running Broker/Memory service at present.
- The AVD's Settings CLI has no put-if-absent or compare-and-set operation. An experiment confirmed that `settings put ... TAG default` **overwrites** an existing value; it is not an atomic migration primitive. A migration that tests for a missing canonical key and then writes can still overwrite an external write. One possible design to investigate is migrating legacy values into separate fallback keys while canonical keys take precedence, but it must be reconciled with the requested canonical-key behavior and tested before use.
- Remaining: complete per-key storage and migration, the live System AI page test and screenshot, Broker request policy semantics without ABA, then independent Memory decision group, real Magpie-backed CLI/configuration and GUI proof, and the installed skill. The previous screenshot is from the old single-key prototype and cannot prove the requested behavior. Do not commit or push the partial milestone as complete.

---

# PR #12 continuation — local partial Settings prototype (2026-10-30)

**Not complete, not committed or pushed.** PR #12 remains draft at `3e3b7d8`. The worktree contains an experimental first-item implementation; do not treat it as production-ready or as satisfying the four-item request.

- The prototype reads and writes `Settings.Global.taku_system_ai` as one JSON document, migrates the prior Broker JSON on first read, observes the key in `SystemAiActivity`, and allows the `configure` content call without `WRITE_SECURE_SETTINGS`. The legacy `usage.json` remains operational history. It builds locally.
- On the disposable `Taku_JevMagpie_20261021` AVD, the earlier version of the prototype migrated the synthetic legacy file and the existing synthetic Jev/Magpie test passed. With the AI page open, an external `settings put` changed `enabled` from false to true; accessibility state changed within one second. Screenshot: `docs/reviews/jev-magpie-groups-2026-10-30/settings-put-gui.png`. This screenshot is the **after** state, not evidence of Magpie CLI registration. The final subsequent archive/readback change was rebuilt but not device-retested.
- `bash system/assistant/test-system-ai-host.sh` passed (34 System AI checks, plus 72 existing IME checks), and `bash system/assistant/build.sh` passed. A root `content call` to configure succeeded. An attempt to use the `content` shell executable under an arbitrary app UID failed at Android's `getContentProviderExternal` permission, so ordinary-app access remains unverified; this does not establish whether an ordinary app's normal ContentResolver call succeeds.
- Outstanding correctness work before item 1 can be committed: Broker has no actual Settings change subscription; external writes do not proactively notify Memory to revoke leases or schedule refresh. The JSON read/modify/write can overwrite concurrent external changes; migration also has a race between checking absence and writing. The derived hash revision misses an A→B→A change between reads. The screenshot and existing tests do not prove those cases. Fix and test them, including migration failure/retry and GUI writes, before pushing.
- Items 2–4 remain undone: independent Memory Jev group and migration; Magpie Settings-backed provider/credential-reference/group/scenario configuration with CLI-to-GUI proof; installed agent-readable skill. In particular, the pinned Magpie binary still owns `providers.json`; the local prototype does not make Magpie settings a single source of truth.

---

# PR #12 continuation — 2026-10-30

Status: **incomplete, draft; do not merge**. The Settings Provider single-source migration and CLI-only Magpie configuration requested by the product owner have not been delivered. The included screenshot is the synthetic Jev hint, not the requested CLI-to-GUI settings demonstration.

## Verified this continuation

- Started the disposable `Taku_JevMagpie_20261021` AVD with `-writable-system`; `adb remount` succeeded with overlayfs. No physical device was changed.
- Updated the Jev UI fixture for the current Chinese labels, locale, image-only screen path, generated suggestion and separate background task. It installs Taku Surfaces, which the current background flow requires.
- `scripts/test-jev-magpie.py --serial emulator-5680` passed on a synthetic upstream: one image decision, one image suggestion generation and one Agent task. The task completed only after accepting the hint; the unsent draft and attachment remained, and the original screenshot reached the Agent. Evidence: `docs/reviews/jev-magpie-groups-2026-10-30/synthetic-image-hint.png`; detailed local output: `out/jev-magpie-device-qa/20261030T122603Z/result.json`. This is not a real-model test or ROM validation.
- System AI host policy tests passed (34 checks); Jev adapter tests passed (65 checks), plus the existing associated host suites.
- SystemAiProvider accepts `configure` from callers with Android `WRITE_SECURE_SETTINGS`; the permission is checked before clearing Binder identity. On the disposable AVD both root and shell successfully issued a `content call` configure. This currently goes through the legacy Broker store, **not** Android Settings Provider; ordinary-app denial was not independently instrumented.

## Settings inventory / work remaining

Existing user-facing settings remain split between:

- System AI: `/data/local/agentos/ai/settings.json` (`enabled`, `textModel`, `imageModel`, `apps`), `SystemAi` / `AiPolicy`.
- Agent: `agent-settings` shared preferences (`theme`, `responseStyle`, `includeScreenshot`, `model`, `effort`, `serviceTier`, `multiAgentMode`, `jevEnabled`, `jevModel`, per-connection model preferences and per-thread composer model preferences). Conversation drafts, delivery receipts and task IDs are operational data mixed into the same preferences and require separate handling.
- Main connection: `/data/local/agentos/magpie/connection.json` (`mode`, `model`), `ModelConnection`; running app-server lifecycle must also react to external edits.
- Background workspaces: `workspace-policy.json` (`allowBackground`, `maxActiveScreens`, `blockedPackages`), `WorkspacePolicy`.
- Memory: `memory` shared preferences (`enabled`, `analysisEnabled`, `queryEnabled`, `proactive`, `model`, `followSystem`, `sources`, `retentionDays`, `userName`, `autoOrganize`, `organizeModel`, `agentBoard`, `agentProfile`). Generations, cached system model, counters and job state are operational state, not user settings.
- IME AI replies: `taku-ai-replies` shared preferences (`enabled`, `model`, `apps`); the upstream Fcitx keyboard, input scheme, dictionary and theme preferences also need a complete inventory and migration.
- Surface position: `surface-position-v1` (`center-x`, `center-y`).
- Magpie: fixed v0.1.2204 keeps providers, credentials and groups in its own `providers.json`, including writes from its GUI. The exact upstream tag is `e60b6724586f9ff6b5b764e27cee4b3e3cfa3d09`; its source has been fetched in the local research checkout. A Settings Provider-backed implementation must cover its GUI and runtime as well as CLI, with credential references rather than exposing raw keys through globally readable settings. Merely mirroring files is not a single source of truth.

Still needed: finish the complete settings inventory; implement and migrate all settings to Android Settings Provider with live observers, validation, permission semantics and safe upgrade behavior; preserve Memory's separate Jev decision analysis and system-text organization by creating and migrating the decision group; provide the group and scenario UI; support provider registration/deletion, credential references, group edits and bindings; install complete agent-readable settings/CLI instructions; verify ordinary app versus authorized caller permissions; run CLI-only model/group/binding and GUI synchronization on the AVD and capture the requested screenshot. The live Jev fixture has been adjusted for the current background path but was not run against a real model. Full ROM and physical-device validation remain outstanding.
