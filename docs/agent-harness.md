# Agent Harness and Skills

Harness is the layer Taku uses to manage background context, updates and traceability. Chat, task execution and conversation history stay with the task service and the single App Server on the device. Settings has two entry points, one for Harness context and one for Skills and the community catalog.

## Context contract

New sessions receive stable background through `thread/start.developerInstructions`. Modules come from `res/raw/taku_harness.json` in the APK and include identity, execution, boundaries, development and context-policy. At startup, the observed os-environment and the native skills-catalog are added. Each module carries an ID, version, source, body, structured data and hash.

A session saves the background bytes from its first assembly, and a resumed session keeps using them. When the environment, answer style, skill catalog or explicit selection changes, a developer message carrying only the changed fields is appended through `thread/inject_items` before the next turn. Earlier background and user messages are never rewritten, and an active turn stays on its confirmed snapshot. Manual refresh only appends while idle. Older sessions not managed by Harness get appended background, and the panel shows where it landed, because content cannot be inserted ahead of existing history.

The schema pinned on the phone does not accept developerInstructions on `turn/start`, so context updates cannot rely on that field. The fixed prefix only gives a cache-friendly structure. Whether the upstream cache was hit is reported by the actual cachedInputTokens in `thread/tokenUsage/updated`.

## Layers and single source of truth

| Module | Responsibility |
| --- | --- |
| HarnessCatalog | Reads versioned policy modules from the installed APK and assembles background |
| HarnessEnvironment | Allowlisted OS, display and toolchain probes. Does not read serial numbers or credentials |
| HarnessStore | Canonical JSON, content-addressed snapshots, immutable receipts and a rebuildable state projection |
| HarnessContext | Session background, appended updates, links to native turn/item and actual usage |
| AgentSkills | Native discovery, enable and disable, session selection and built-in development skills |
| SkillCommunity | Public GitHub sources, pinned-commit download and bounded install |
| AgentHarnessView / AgentSkillsView | Display inside the native drawer |

To extend background, prefer a new versioned data module or allowlisted probe. Changing data goes in later updates and must not modify an existing session's background. Third-party skill bodies are read on demand through the native skill input and are never promoted to Taku developer policy.

## Receipts and recovery

On the device, `files/runtime/harness/objects/<sha>.json` stores immutable content, and `threads/<native-thread-id>/<sequence>.json` stores references with the previous hash. `state.json` is only a projection of the current head, snapshot and skill selection. Objects and references are written before the projection is updated, and startup can verify and roll forward a lagging journal head.

Injection has a two-stage receipt: planned, then confirmed. If no confirmation arrives after sending, the receipt is marked pending and automatic resend is blocked to avoid duplicates. That case requires checking native history. A confirmed state whose projection was not written can be recovered from receipts. While a model is running, an observation failure is marked auditIncomplete rather than leaving the running model as a task with no terminal state. Context delivery before the model starts still requires confirmation.

The panel shows Taku-managed background, module content, the current environment, the latest 100 receipts, native turn/item IDs, redacted commands and real usage. It does not show hidden runtime system prompts and does not record reasoning or raw tool output. All past receipts are kept, and the chain is verified on read.

The context page and Skills share a compact top bar. Refresh and append actions are in its menu. The overview shows background version and prefix, an OS summary and collapsible module rows. Module source, hash, body and JSON expand in place, and each receipt expands on its own. pending and auditIncomplete are shown first, and normal states take no blank rows.

## Skills community and native runtime

The default sources are the public `openai/skills` and `anthropics/skills`. You can add a public `owner/repo` and skill directory. Browsing resolves to a pinned Git commit, install uses that commit, and the repository, commit, and SHA-256 of the archive and SKILL.md are saved. Only controlled GitHub HTTPS URLs are accepted, with limits on archive size, file count and unpacked size. Path traversal, duplicate paths and credential-shaped resources are rejected. Install does not run downloaded scripts and does not overwrite existing skills.

Skill activation stages on the same filesystem as `.codex/skills` and then moves atomically. Native `skills/list` decides whether a skill is recognized, and `skills/config/write` decides whether it is enabled. You can select up to four skills for a session, passed through the skill input on native `turn/start`. Enabled skills that were not selected can still be used by the runtime when relevant to the task.

The Skills screen is a compact, searchable grouped list with an icon, name, one-line summary and actual install state. Back and the filter and source menus share the drawer top bar. Installing happens on the skill detail page, along with enabling, disabling and loading for this session. Source management is separate from browsing, and commit and SHA-256 are collapsed by default. Display names and summaries are presentation text from `SkillPresentation` and do not change native skill identifiers or descriptions, and the detail page keeps the original description. Categories are local naming rules, not community tags or rankings.

The community catalog reads SKILL.md descriptions through one size-limited pinned-commit archive and caches them in the Harness private directory. It does not unpack executable resources or run scripts. If catalog metadata is unavailable, names can still be browsed, and the detail page reads the same commit's files again through `skills/community/detail`. The install button is enabled only after metadata is actually read. Skills with the same name from different publishers' repositories are not treated as the same installed object.

Lists and details prefer the package's own icon. Installed skills use native `interface.iconSmall/iconLarge`, and community skills use the icon declared in `agents/openai.yaml`. Originals are cached in the private directory by content hash and are not tinted. PNG, JPEG and WebP are supported. Only resources declared inside the skill directory are read, with limits on file size and dimensions, and arbitrary remote icon URLs are not fetched. A missing or unsupported icon falls back to the category icon and does not affect the skill.

The built-in `taku-app-development` skill gives route guidance for light apps and native APKs, real toolchain checks, and layered checks for build, install, UI and API. Its resources ship in the assistant APK, and an existing directory with the same name is not overwritten.

A managed community skill's detail page can check its original GitHub source for a newer commit, install the checked revision, or switch back to the previously installed version. Private repository sources can be added with a repository-scoped token.

Community-installed skills can be uninstalled from the detail page. Only managed skills whose install record matches the actual path are accepted, and built-in skills cannot be uninstalled. While idle, the skill is moved out of the directory and a rescan is forced, then session selection, source records and rebuildable cache are cleared. Uninstall does not change existing conversations, and the skill snapshot in the next turn reflects the change. Intent is saved first, so an interrupted uninstall is repeated on the next start. Download staging files are removed after install. Orphaned archives from older versions are not cleaned up automatically.

Download and recognition do not mean external services, MCP, binary tools or authentication are configured. Version switching is not journaled against power loss. There is no third-party execution sandbox. Root and global SELinux Enforcing on a development emulator do not amount to agent isolation.

The official [App Server documentation](https://developers.openai.com/codex/app-server) says `skills/list` supports `forceReload` and `skills/config/write` manages enablement. Verify the schema bundled on the device by running it.

## Testing

Install the paired Taku Agent and Assistant APKs on an isolated emulator with built-in skills and an existing test conversation. The runner defaults to `Taku_ImageIntegration_20261010` at `emulator-5634`. Set `TAKU_SERIAL` and `TAKU_AVD` to select another isolated emulator.

```sh
bash system/assistant/test-harness.sh <existing-session-id>
```

The runner checks the filesystem store, opens the conversation's Harness context and verifies it against Open Access. It also searches the installed skills in Expo and checks the selected skill's runtime recognition status.

Real model background checks use `scripts/run-agent-request.py` with `examples/scenarios/harness-context-probe.txt` and need an account already connected on the device.

The uninstall test is destructive and runs only on its dedicated disposable AVD, never a physical device. The script checks the AVD name. Online tests need a device that can reach GitHub, with this build's components installed.

```sh
bash system/assistant/test-skill-uninstall.sh emulator-5668
```

Interface references: [App Server](https://learn.chatgpt.com/docs/app-server#api-overview), [Prompt caching](https://developers.openai.com/api/docs/guides/prompt-caching), [Skills](https://developers.openai.com/plugins/concepts/skills). Upgrading the runtime requires regenerating the schema and verifying the delivery and recovery contract.

## Verification scope

Historical implementation and real-model results are recorded in the [2026-10-15 build and model receipt](../evidence/builds/2026-10-15-agent-harness-skills.json) and [review](reviews/agent-harness-skills-2026-10-15.html); the current release must be verified independently. Public-source installation, native recognition and a successful historical model call do not prove private-source authentication, version switching, power-loss recovery or all external services work on the current build. The code implements these lifecycle entries, while the documented interruption and no-sandbox limits above still apply.
