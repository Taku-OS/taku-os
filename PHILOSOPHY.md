# The philosophy of Taku OS

**Taku OS is an open, extensible, AI-native Android operating system that gives users, agents and developers access to low-level system capabilities and hardware.**

**Your phone. You decide what it can do.**

The Agent runs on the phone. It can use low-level system capabilities, execute commands and scripts, operate existing apps, and write code to generate, preview, install and run new apps and home-screen widgets. Generated apps can expose interfaces that agents and other extensions call directly. Their interfaces and user interfaces share real application state.

Extensions can supply context sources, triggers, tools, skills, memory and interfaces. Developers can connect phones, computers and external services to provide capabilities that work together.

Capabilities grow through use: state a need → use an existing capability or write code → test and preview → install and enable → collect feedback → improve. Useful scripts, skills, plugins and apps can be saved, edited, versioned, rolled back or shared. Self-improvement means improving code and working methods, not training model weights. Generating small native apps, compiling and installing them on a device, upgrading them without losing data, and synchronizing their API and UI have completed an emulator workflow. General autonomous plugin creation, loading and failure recovery, and third-party widget hosting still require implementation and acceptance.

People keep using familiar apps. The home screen presents progress, results, utilities and companion experiences. The Agent combines context with existing commands, tools and interfaces to complete the user's task. When a reusable capability is missing, it can create and verify a script, skill, plugin or app. Developers and users can modify, replace, combine and share these parts.

This describes the product and ecosystem direction, not a list of delivered capabilities. The source repository currently remains private. Original code, SDKs, examples and documentation use Apache-2.0. Licensing and public repository access are managed separately.

## Direct access, convenient interfaces and customization

The image layer integrates the system permissions, execution environment and services needed for the phone's owner, Agent and developers to access the system and hardware under their actual authorization. The intended capabilities include screen and input control, app management, notifications and files, as well as cameras, microphones, location and sensors. Availability depends on the current implementation, device support and required permissions.

- **Direct access:** retain authorized shell access and native system interfaces. Existing tools can be called directly. Add a system service or small tool where an entry point is missing; actions need not all pass through an SDK.
- **Convenient interfaces:** SDKs, CLIs and APIs describe parameters, return values, events, errors, permission requirements, and device and version support. Capability discovery should explain what a device supports, which permissions are currently available and whether a call can run. Events and general capability discovery still need implementation.
- **Customization:** extensions can define observation and trigger rules, execution flows, background tasks, camera processing, apps and home-screen components while sharing real application state with the Agent. Custom camera processing and a global cross-app image pipeline require separate verification; the latter may need system integration.

Direct access does not mean every piece of hardware can bypass system controls. Root, platform signing, system UID and SELinux are distinct execution conditions. Drivers, HALs and device implementations also constrain hardware access. Wrappers must preserve these prerequisites and actual failure results. The unified SDK's verified system-data methods currently remain `system.info`, `displays.status` and `tasks.status`. This section sets a direction; it does not introduce implemented APIs.

Most scripts, plugins and apps should load or install independently. Changes to the Framework, drivers, system permission policy or partition integration require the corresponding component or ROM build and device acceptance.

## A phone that remains extensible as you use it

The core team maintains Android integration, device support, a stable runtime and extension contracts. The community contributes capabilities and experiences that can be combined. The default experience should consist of replaceable parts using the same contracts available to third parties.

`System foundation + existing tools/capability extensions + user goal → a scenario assembled by the Agent`.

One plugin can serve many scenarios, and a scenario can use existing tools directly. There is no need to turn every everyday situation into a plugin first. The SDK is an optional stable interface; the phone's existing shell commands and tools are also execution entry points.

## Independently supplied and replaceable parts

Context sources, triggers and observers, tools and scripts, skills and behavior rules, Agent runtimes, memory and storage implementations, interfaces and widgets all fall within the extension design. A package may provide several of these entry points without splitting them into disconnected products.

For example, a desktop WeChat connector could provide message context, new-message triggers, query tools and a home-screen component. The Agent could combine those capabilities with a calendar and existing tools for a travel task. The connector's implementation and authorization determine which data it can actually read. Connecting a WeChat messaging channel must not be described as access to personal conversation history.

## Improve capabilities through a verifiable cycle

The goal is for the phone's Agent to understand a need → use existing capabilities → identify a gap → create an extension → test it → load and use it → retain the working version. Users can request corrections, disable it, undo changes or share it. Failures should preserve their cause and restore the previous working version.

Temporary task code, reusable workflows, long-running plugins and apps are different outputs. Simple work may call a command or use a temporary script. Reusable methods can become skills. New observation, connectivity or long-running capabilities may justify a plugin. Generating files, compiling successfully or registering a manifest does not by itself establish a working new capability.

Plugins cannot create missing low-level capabilities out of nothing. New system primitives, Framework changes, drivers or device integration require the appropriate system build and verification path. The complete autonomous extension cycle remains a design goal and has not passed acceptance.

## System capabilities before scenarios

The system supplies the execution environment, shared primitives, extension loading and runtime entry points. Default components, community plugins or Agent-created implementations can supply context and triggers. The product chooses scenarios based on actual capabilities, and the Agent assembles them for each task. Camera filters are one example of an extension, not a promised initial feature.

## The phone's owner controls Agent authority

The direction is to let the owner's Agent obtain the maximum privileges the system permits and use Bash on the phone. The capability SDK gives developers explicit interfaces, permission prerequisites and error behavior. It neither replaces the root execution environment nor requires repeated approval for every low-level action the owner has already authorized. Root access, system signing and ROM capabilities are distinct facts that cannot be collapsed into an “all permissions” label.

Third-party extensions have separate engineering requirements for declarations, grants, revocation and isolation. Extensions currently run as trusted local source in the CLI process; declaration checking is not a sandbox. Production permission management remains unfinished. Stating this boundary lets developers understand where their code actually runs.

## People and agents should work in parallel

While the user works on the main screen, the Agent can execute a task on a separate logical Display. Status and results return to the home screen, and the user can open its mirror. Native turn cancellation and cleanup of shell child processes owned by that turn have [emulator acceptance](docs/agent-cancellation-2026-10-15.md). Yielding app input, automatic pausing and conflict handling still need separate implementation and verification. Evidence of tapping a mirror after completion does not establish automatic takeover.

Separate Displays share Android and application data. They do not promise two independent instances of every app. Login, media, hardware and background behavior of traditional apps require verification for each device and version.

## Apps expose callable capabilities too

Generated tools can provide both a UI and a callable interface backed by the same application state. The Agent can invoke scoring, recording or querying logic in code and show the actual results in the UI. App authors can write arbitrary core business logic; available Android capabilities still depend on existing interfaces and actual permissions.

## Tasks need inspectable results

Input, execution, progress and results belong to one task lifecycle. Results must correspond to real interface output, application state or artifacts. Failures preserve commands, errors and limitations. Desktop companions, animations and status cards express actual system state rather than substitute for results. Verification always distinguishes research, emulator testing and physical-device testing.

## Runtimes and models can be replaced

The current phone Agent runtime is Codex App Server. The on-phone Magpie model gateway is integrated, and online DeepSeek conversations and phone tools have been verified on an emulator. Codex still manages execution and events. Extensions depend on Taku capability contracts, without tying application logic to a particular model's internal prompts or account. Changing a model provider and replacing an Agent runtime require separate acceptance. Other runtimes still need actual adaptation and regression testing; see the [Magpie record](docs/reviews/magpie-runtime-2026-10-19/README.md).

## Contributions should be small and reproducible

A capability, a device adaptation, an app compatibility report or a callable application can each be a useful contribution. Explain permissions, device/API scope, inputs, outputs and failure boundaries, and supply an example that others can run. AI-assisted code must meet the same standard; contributors are responsible for understanding and verifying it.

## Deliver on promises of openness

The original core uses this repository's LICENSE. AOSP, Linux, upstream components and device materials retain their respective licenses. Public releases should include reproducible build entry points, versions, checksums, a support matrix and recovery instructions. Source code, extensions, component APKs and flashable ROMs are distinct deliverables.

As of 2026-10-19, emulator verification covered the native Agent, on-phone app development, skills and the latest conversation/model connections. An older ROM had been flashed to a Pixel 9; later components had records of real model use, some tools and service recovery after cold boot. The latest components had not yet formed a complete ROM that passed acceptance. Production release and third-party extension isolation remained unfinished. See [current engineering status](docs/current-status.md) for version-specific facts. The [October 10 foundation acceptance](docs/acceptance.md), [scenario records](docs/scenario-acceptance.md) and [capability inventory](docs/android-api-guide.md) retain their respective historical scopes; no single batch of records describes the entire project's progress.

For implementation direction, see [ecosystem strategy](docs/ecosystem-strategy.md), [extensible architecture](docs/extensibility.md) and the [developer loop](docs/developer-loop.md).
