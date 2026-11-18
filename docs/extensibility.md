# Extensibility and growing capabilities through use

This page separates what exists today, Skills, plugins and the app development loop, from the general extension architecture still to be built. See [current status](current-status.md) for progress. Host plugins are described in the [plugin guide](plugins/README.md) and are separate from the Codex plugin system.

## Three layers of responsibility

| Layer | Responsible for |
| --- | --- |
| Image and system base | Android, required system services and device support, execution environment, extension contracts, lifecycle and shared human entry points |
| Agent | Understands what a person needs, organizes tasks with current capabilities, uses commands, scripts, interfaces or generated apps, and creates and verifies extensions when needed |
| Optional extensions | Provide context, triggers, tools, workflows, memory implementations, runtimes or interfaces. Can be written by the team, the community, users or agents |

Default components should use the same extension contracts, so third parties can really replace them. The image should ship with a default set that works for daily use, and parts that plugins can update independently should not require rebuilding the whole ROM.

## Direct access, wrapped interfaces, free customization

The image integrates the system permissions, execution environment and services needed for phone owners, agents and developers to reach the system and hardware. Capability directions include screen and input, app management, notifications and files, plus camera, microphone, location and sensors. Whether each one can be called depends on the current implementation, device support and permission requirements.

- **Direct access.** Keep the shell and native system interfaces. Existing tools can be called directly, and a missing entry point gets a system service or a small tool. Not every action has to go through the SDK first.
- **Wrapped interfaces.** The SDK, CLI and interfaces state parameters, return values, events, errors, permission requirements, and device and version support. Capability discovery should answer what the device supports, what permissions exist and whether a call can run. Events and general capability discovery are still to be implemented.
- **Free customization.** Extensions can define observation and trigger rules, workflows, background work, camera processing, apps and home screen components, and share real business state with the agent.

Root, platform signing, system UID and SELinux are different execution conditions, and hardware is limited by drivers, HALs and the device. Wrappers must keep these conditions and their failures visible. The system data methods verified in the unified SDK are `system.info`, `displays.status` and `tasks.status`.

Most scripts, plugins and apps should load or install independently. New framework work, drivers, system permission policy or partition integration go through the matching component or ROM build.

## Extension entry points

| Type | What it provides | Examples |
| --- | --- | --- |
| Context source | Queries and incremental context, keeping source and freshness | Desktop messaging, calendar, photos, current screen |
| Trigger or observer | Detects events and conditions worth handling | New message, screenshot, saving an item, scheduled check |
| Tool or script | Commands, functions or remote calls that run directly | Bash, small programs, device interfaces, computer services |
| Skill or behavior rule | Teaches an agent how to combine capabilities and handle repeated tasks | Trip planning methods, reminder and communication rules |
| Runtime | Model or agent executor and its adapters | The current Codex path and future alternatives |
| Memory or storage | Saves, retrieves and migrates lasting information | Personal memory, saved-item index, project material |
| Human interface | Pages, widgets, suggestions, pet actions and result display | Trip tool, where to go today, message to-dos |

One package can provide several entry points. Plugin core logic and interfaces can be any code, not just edits to fixed template configuration. Extensions that need native components must still compile, install and verify those components.

## Observation, triggers and execution compose separately

Take "turn an image I liked into my wallpaper". Execution can use an existing command or wallpaper tool. Detecting the like in an app and getting the content is a separate capability, provided by an app adapter or a general observer. Without an observer, an ordinary like is not guaranteed to wake an agent, but when a user asks directly the agent can still use existing execution capabilities.

App adapters declare the app versions, signals, context and failure scope they actually support. General observers combine available signals such as foreground state, the view tree and the screen. Both output events that can be checked, and the agent decides what to do based on what the user needs. Trigger deduplication, recovery, frequency and notification placement are managed by shared mechanisms and user settings.

When a screenshot or share enters a travel case, the agent can organize it once without a complete travel plugin installed. A repeated method can become a skill, and an extension can be created when a stable connection or observation is missing. Generated trip interfaces and callable business interfaces share the same state.

## Shared foundations in the image

1. Plugin discovery, loading, enable and disable, upgrade, uninstall and version rollback, plus compatible APIs and ABIs and real capability discovery.
2. Callable entry points for system primitives and existing commands. SDK, CLI and system interfaces can coexist, and not every action needs another wrapper.
3. Context and event delivery: identity, source, time, object references, deduplication, replay and failure semantics.
4. Agent and background work lifecycle: reuse the task service as the source of truth for tasks, with clear handling of long tasks, cancellation and recovery.
5. Hosts for pages, widgets, suggestions and results, with defined insertion points, action callbacks and unified business state.
6. Management of data, assets and generated artifacts, with clear ownership of business data, code, versions and account material.
7. Development and verification tools: local loading, preview, logs, command and interface regression, packaging and compatibility reports.

Root, system UID, platform signing and SELinux requirements are verified by the actual implementation, and registering a capability does not grant permissions.

## The one-sentence capability loop

User request → check existing capabilities → use or combine → generate an extension if needed → test it → load and verify → keep the version that works, or fix or restore.

| Artifact | When it fits | Success means |
| --- | --- | --- |
| Temporary script | This one task, with enough existing primitives | The command actually succeeds and state reads back correctly |
| Skill with helper scripts | Repeatable methods and workflows | The next task can use it and the steps reproduce |
| Capability plugin | New context, triggers, connections or long-running services | Discovery, running, calling, disabling and recovery can all be verified |
| App or widget | People need to keep viewing or operating something | The interface and API share real state |
| System change | Existing primitives are not enough and framework or driver integration is needed | The matching build, install, image and device checks |

Loading must state when a change takes effect. Calling a new script from the shell during a task, loading a new tool schema on the next turn, and upgrading a long-running plugin are different paths. A saved file or a model's own report is not evidence that something ran and took effect.

Code, skills, installed versions, verification results and business state should be tracked separately, and earlier working versions kept. Rolling back plugin code does not undo its external side effects, and a specific operation needs its own recovery semantics.

## Where the project stands

There are three development paths at different depths:

- **SDK extensions.** The three system data methods and the trusted local `device-report` have real inputs and outputs. The CLI loads source in the same process and checks method declarations, with no third-party sandbox.
- **Skills.** Pinned-commit download from public GitHub, source and SHA tracking, native recognition, enabling, disabling, explicit loading per session, version switching and uninstall are implemented. See [Harness and Skills](agent-harness.md).
- **Apps built on the phone.** React/WebView APKs and Java native APKs are two paths. An agent can write Java and a manifest, then compile, sign and install on the device, and upgrade with the same signature while keeping data. The native toolchain is prepared separately and is not part of the general ROM install flow.

Marketplace plugins and host plugins each have their own lifecycle, covered in [current status](current-status.md). Event subscription, a third-party interface host, third-party execution isolation and an autonomous extension loop covering these entry points are not finished. Home widgets are not an extension surface yet.

See the [API reference](https://docs.taku.ai/os/reference/) and the [developer loop](developer-loop.md) for capabilities and current commands.

## References

OpenClaw plugins provide channels, tools, runtimes and other extensions. Skills store how to use tools, and Skill Workshop stores learned skills that can be undone. Taku OS borrows the composition and retention mechanisms.

- [Plugins](https://docs.openclaw.ai/tools/plugin)
- [Creating skills](https://docs.openclaw.ai/tools/creating-skills)
- [Skill Workshop](https://docs.openclaw.ai/tools/skill-workshop)
- [Self-learning](https://docs.openclaw.ai/tools/self-learning)
