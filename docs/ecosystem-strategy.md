# Taku OS open-source ecosystem strategy

Updated 2026-10-19. Positioning and organization plan; validated SDK, Skills, application-development, and physical-device component scope is separated from future goals. See [project status](current-status.md) for the latest status by layer.

## Positioning

**Taku OS is an open, extensible, AI-native Android operating system that opens low-level system capabilities and hardware access to users, Agents, and developers.**

**Your phone. You define what it can do.**

An Agent runs on the phone. It can call low-level system capabilities, execute commands and scripts, operate existing apps, and write code to generate, preview, install, and run new apps and desktop widgets to meet your needs. Generated apps can expose interfaces for Agents or other extensions to call directly; their interfaces and UI share real business state.

Context sources, triggers, tools, Skills, memory, and interfaces can all connect through extensions. Developers can connect phones, computers, and external services to provide composable capabilities.

Capabilities grow through use: request → use existing capabilities or write code → test and preview → install and enable → use and provide feedback → improve. Useful scripts, skills, plugins, and apps can be saved, modified, versioned, rolled back, or shared. Self-iteration means improving code and working methods, not training model weights. The complete cycle and third-party widget host still require implementation and acceptance testing.

We maintain a base image suitable for daily use and contracts for replaceable components. Users keep familiar apps; Agents use existing commands and tools, combining context, triggers, scripts, and interfaces as needed. When reusable capabilities are missing, the Agent can create and validate extensions and retain working versions. The complete cycle remains a design goal.

`System base + existing tools / capability extensions + user goal → a Case organized by the Agent at execution time`. One plugin can serve many tasks; a task need not start by creating a plugin. Use SDK, Shell, and existing interfaces within their actual available scope. SDK wrappers must not be prerequisites for all execution.

## Low-level access, wrapped interfaces, and customization

The image layer integrates the necessary system privileges, execution environments, and services so the phone's owner, Agents, and developers can access the system and hardware under actual authorization. Capability areas include screen and input, application management, notifications and files, cameras, microphones, location, and sensors. Availability depends on implementation, device support, and permission prerequisites.

- **Direct access**: retain authorized Shell and native system interfaces. Existing tools can call them directly; add a system service or small tool where an entry point is missing, without requiring every action to pass through the SDK.
- **Usable wrappers**: SDK, CLI, and interfaces document parameters, return values, events, errors, permission requirements, and device/version support to help extensions call system capabilities. Discovery should explain what a device supports, which permissions are currently available, and whether a call can execute. Events and general capability discovery still require implementation.
- **Customization**: extensions can define observation and trigger rules, execution flows, background tasks, camera processing, apps, and desktop components, sharing real business state with the Agent. Validate custom camera processing separately from a global cross-app image pipeline; the latter may need system integration.

Direct access does not mean all hardware can bypass system controls. Root, platform signatures, system UID, and SELinux are different execution conditions; hardware also depends on drivers, HALs, and device implementation. Wrappers must preserve these real prerequisites and failure results. The currently validated system-data methods in the unified SDK remain `system.info`, `displays.status`, and `tasks.status`. This section defines a direction, not additional implemented interfaces.

Most scripts, plugins, and apps should load or install independently. New Framework features, drivers, system permission policies, or partition integrations enter the relevant component or ROM build and device-acceptance cycle.

## Replaceable components are the ecosystem's units

Context sources, triggers and observers, tools and scripts, skills and behavioral rules, runtimes, memory and storage, interfaces, and widgets can all be extensions. A package may provide multiple entry points, and the same business state can serve people, Agents, and other extensions. Default components should use the same contracts available to third parties.

The community can maintain app-specific observers or more general observation capabilities. These provide verifiable events and context to Agents, while Agents continue to organize specific tasks. Desktop WeChat, Feishu, or map connectors provide reusable capabilities; they do not hard-code each final Case into the core.

## Participate in creation through use

A request may produce a result, a script, a skill, a capability plugin, an app, or a widget. Human needs drive generation, real tests determine what is retained, and later use drives corrections. Users can modify, disable, restore, combine, and share these artifacts. Shared implementations are managed separately from personal accounts and business data.

Plugin authors can maintain and publish implementations in their own repositories, with the goal of loading, running, and sharing them without merging into the core. Current trusted SDK extensions are still registered in a source registry. Public GitHub Skills already support installation at a pinned commit, native recognition, enable/disable, and session adoption; see [Harness and Skills](agent-harness.md). Automatic installation, hot loading, upgrades, rollback, uninstallation, and an autonomous extension cycle covering all entry points for general plugins have not shipped. See [extensible system](extensibility.md) for architecture and acceptance.

## Responsibilities of the core and community

Developers need concrete reasons to join: build tools using existing system capabilities that Agents can call directly; run tasks on background displays while users continue using the phone; contribute a device or app adaptation other authors can reuse. The core team maintains Android integration, stable protocols, task runtimes, compatibility tests, and releases over time. Product and community work jointly expand use cases.

Onboarding should offer two levels: SDK/examples for understanding and combining existing capabilities, and development images for validating system privileges, background displays, and ROM integration. Capabilities possible on ordinary Android can have a path without flashing; Root/system-signature/ROM capabilities must explicitly require the corresponding environment. The designated emulator has actual SDK, Skills, and native-app development results. Older Pixel 9 image installation and v44 components also have limited physical-device evidence; see [physical-device client acceptance](native-codex-implementation-2026-10-15.md). This does not establish support for stock devices, arbitrary models, or extension paths without flashing.

If contributing a workflow requires a complete ROM build, only ROM specialists can stay. If the project only supplies prompt files, Android's low-level value cannot be reused. Separate the daily cycles for extension development, system development, and device porting, then bring them together through versions and test evidence.

## What to borrow from OpenClaw

The following official materials were reviewed on 2026-10-10. Conclusions concern published designs only; they cannot establish why OpenClaw became popular.

| Primary evidence | Mechanism worth borrowing | Taku engineering choice |
| --- | --- | --- |
| [Gateway architecture](https://docs.openclaw.ai/concepts/architecture) connects clients and nodes through a persistent Gateway, requests/responses, and events | Callers collaborate around one runtime | Continue the existing TaskBroker's single task lifecycle; public interfaces and events originate there, without another queue |
| [Skills](https://docs.openclaw.ai/tools/skills) packages tool usage in distributable Markdown directories | Low barrier to contributing knowledge | Workflows may include usage docs, but system capabilities still require real code, permissions, and invocation examples; prompt declarations do not count as system implementations |
| [Plugins](https://docs.openclaw.ai/tools/plugin) extends tools, providers, channels, and more, requiring triggered capabilities to be validated in execution | Code extensions have explicit entry points and runtime feedback | SDK, CLI, registry, executable examples, and real device results form a complete validation cycle; a manifest or successful build does not prove behavior |
| [CONTRIBUTING](https://github.com/openclaw/openclaw/blob/main/CONTRIBUTING.md) routes many new features to third-party plugins and requires focused problems and validation evidence | Core boundaries give the community room to contribute | Core accepts shared primitives and interface fixes; specific business combinations belong in extensions first; AI-assisted contributions are welcome under the same acceptance criteria |

Official [OpenClaw Android](https://docs.openclaw.ai/platforms/android) is a companion node requiring a Gateway. [Kimi Claw Android](https://www.kimi.com/en/help/kimi-claw/android-user-guide) is another public example of running OpenClaw on a phone and operating apps. Its FAQ warns that operation affects normal use and recommends an idle phone. The material provides no validation evidence for an independent background Display; that does not establish that it lacks support. Taku does not position itself as the first Agent running on a phone. System integration, parallel execution, and composable interfaces are values we must prove with actual results.

## Three kinds of authors, three minimum contributions

| Author | Achievable first contribution | Required environment | Current entry point |
| --- | --- | --- | --- |
| Agent tool / MCP author | A real device report or business combination using published methods | Node 20+; authorized Android transport | [device-report](../examples/extensions/device-report/README.md), [protocol](protocol.md), [developer loop](developer-loop.md); MCP adapter not yet implemented |
| App / workflow author | Business core + UI + callable interface, or a workflow and reproduction record using existing apps | Experimental emulator with preinstalled toolchain; phone runtime | [Early C2 / C3](scenario-acceptance.md), [native Java app creation and upgrade](agent-system-app-acceptance-2026-10-15.md), [app-kit source](../tooling/app-kit/README.md); a single application cycle or experimental HTTP is not the complete OS API |
| Android / ROM / device author | A system primitive, device adaptation, or app compatibility fix | Android SDK/JDK; device porting also needs AOSP/device trees, etc. | [Source migration](source-migration.md), system/, [capability guide](android-api-guide.md); currently public methods are separate from the research catalog |

Contributors receive reusable implementations, attribution, and feedback from other authors. Initially invite people willing to make these three kinds of contributions, improving the first-use path through shared debugging. Record where authors come from, onboarding success, and reasons for staying; validate those before expanding recruitment.

## A daily loop developers can sustain

1. Check the capability catalog to distinguish what ordinary apps, authorized shell, Root, system signatures, and ROM modifications can do. Research entries are not callable methods.
2. Run `methods.list`, connect to a specified device, and obtain a real success or explicit failure. Errors should identify a missing transport, permission, version, or implementation.
3. Modify your own core code and run the extension independently. System-component changes enter the corresponding build/sign/install cycle; framework/kernel/ROM changes enter a complete system build cycle.
4. Provide an input and success condition others can reproduce, recording actual output and failure boundaries. For UI, background displays, and generated apps, include original screenshots/recordings and package metadata.
5. Submit a focused contribution and receive actionable maintainer feedback. After merging, keep compatibility scope and reproduction steps in the same source so the next author can build on it.

“A first real capability call in 5–10 minutes with the environment configured” is an onboarding goal, not a measurement from unfamiliar developers and clean computers. C2's current 770 ms measures only packaging/alignment/signing with a preinstalled phone toolchain, excluding model generation, installation, and first frame; see the scenario record. Do not use it as a substitute for newcomer onboarding cost.

## Public contracts for interfaces and contributions

- `spec/` owns the protocol and machine-readable registry; `core/methods.json` and implementations determine the actual callable scope. Capability research inventories do not replace method discovery.
- Methods need inputs/outputs, errors, permission sources, API/device scope, limitations, and reproducible examples. New authorization mechanisms must specify grant, revocation, upgrade changes, and UID/SELinux constraints.
- New state-changing operations must define idempotency, state after failure, and cancellation. Events must define ordering, duplicates, cursors, and recovery. The current event schema is reserved; subscriptions are not implemented.
- Current extensions execute as trusted source. Registry declarations restrict SDK method calls, not code execution. Third-party isolation and production authorization are further engineering work; a security badge cannot replace them.
- Maximum permissions for the Owner Agent remain the system direction. Open interfaces make extension behavior more stable, without per-action approval for authorized tasks. An extension author does not automatically receive Root through a manifest.
- Runtime replacements must pass the same task-lifecycle and result regressions. Codex has device evidence; see [project status](current-status.md) for other runtime experiments in the current workspace. Uncommitted experiments must not be described as released support. OpenClaw/MCP adaptations require separate acceptance for specific connections and tasks.

## Maintenance responsibilities

See [governance](../GOVERNANCE.md) for current internal reviewers. The following responsibilities need further allocation for future releases and ecosystem collaboration. They neither negate existing maintainers nor constitute service commitments.

| Role | Responsible outcomes | Merge / release judgment |
| --- | --- | --- |
| Runtime / protocol maintainer | TaskBroker, contracts, errors, events, upgrade compatibility | Migration and regression evidence for public-interface changes |
| Android / display maintainer | UID, signatures, SELinux, background displays, system integration | Component builds, actual installation, display and input boundaries |
| Device / compatibility maintainer | Specified devices, app versions, media, recovery | Physical-device reproduction, known failures, recovery and flashing records |
| Extension maintainer / author | Core business implementation, dependencies, permissions, usage, versions | Runtime behavior matches declarations; author owns subsequent fixes |
| Release maintainer | Source licensing, builds, checksums, image/SDK releases | Clean environment, exported-image cold boot, physical devices, support matrix |
| Developer experience maintainer | Quickstart, examples, contribution feedback, index | A real newcomer can complete the path; issues reach an owner |

Core tests first check protocols, parameters, and errors; device tests check Android behavior; release validation checks final artifacts. Maintainers may accept clearly bounded experiments, but experiments do not automatically become release support.

Contribution templates are in `.github/`. Fields separate research, emulator, and physical-device evidence and identify app login state and known failures. PRs choose validation appropriate to actual changes. Text-only changes do not require a full ROM build, and changes to system behavior cannot be validated with Node tests alone.

## Start the index as a Git directory

`spec/extensions.json` is currently the only extension registry, pointing to trusted modules within the repository. It owns extension titles, versions, declarations, and executable entry points. Extension READMEs describe usage, authors, and tested scope. Initially, source PRs add extensions and must include reviewable implementations and reproduction records.

External authors can first maintain implementations in their own repositories, citing an explicit commit, license, reproduction documentation, and version scope. Pinned-commit installation of public community Skills has been tested, but general external plugin package installation, a remote plugin registry, package signing, ratings, revenue sharing, and an app store have not shipped. Plugin/marketplace protocol routes in the Codex client also do not establish production acceptance for these OS ecosystem capabilities. First validate that other people can create, run, and share capabilities.

## Attract authors with valuable results

Suggested public demonstrations include three verifiable components: a low-level interface and permission failure; a phone-generated installable app whose UI reflects external API changes; and an Agent operating background apps while the main display remains usable. Ricky/product selects the formal Cases and success conditions; existing C1/C2/C3 are engineering candidates.

Each demonstration includes code, commands, device/version/permissions, results, and failures. Main-screen animations, desktop pets, and polished home pages can help explain the product, but recruitment must lead to “you can add a capability too.” Demonstration authors can submit small contributions and receive attribution in new-version examples. Unmeasured Star counts are not an ecosystem promise.

Measurable goals include first-real-call completion rate and time, first-extension execution rate, waiting time for first contributions, authors returning after a release, reuse of an extension by another author, and compatibility regression rate per version. Publish counting methods and samples, including failures. These currently have no baseline and cannot be presented as achieved growth.

## Release order and acceptance outcomes

| Milestone | Permissible public commitment | Completion conditions | Current |
| --- | --- | --- | --- |
| Developer source preview | Methods/source and experimental boundaries are inspectable; reproducible on specified devices | Clean-clone dependency/build/example success, clear licensing origins, reproduction by a real new author | Local code and evidence exist; complete clean first-use acceptance remains pending; not publicly released |
| Emulator development environment | External developers can rebuild and run the same system capabilities | Latest exported image cold-boots; reproducible preinstalled runtime/toolchain; no personal login state; conventional-app regression | Experimental emulator has run; latest ROM has not been re-exported |
| First physical-device development release | Specified device/version can be flashed and recovered, with known incompatibilities listed | Bootloader/flashing/recovery, real conventional apps and selected Cases, permissions, power/background behavior, media validation | Older Pixel 9 candidate installation/boot and v44 component model/recovery validated; latest complete ROM, hardware/media matrix, and release gates remain pending |
| External extension execution | Extension capabilities can be declared, granted, revoked, and isolated | Real service authorization/UID/signature/isolation, error/event specifications, end-to-end extension regression | Trusted local extensions have run; production mechanisms unvalidated |
| Stable community collaboration | Maintainers own supported versions and contribution paths | Maintenance roles, version compatibility and regression, traceable feedback, reproducible releases | Internal reviewers exist; long-term release/ecosystem responsibilities and service commitments remain to be defined |

Douyin video anomalies remain under compatibility investigation. Current WeChat evidence is before login, and rednote evidence is a guest image feed. This cannot be reduced to “all mainstream apps are compatible.” Historical per-capability inventories follow the capability guide and machine specification. Current progress follows [project status](current-status.md) and version-specific evidence; this strategy does not maintain a second count or use an outdated catalog to dismiss later physical-device results.
