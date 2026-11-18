# Taku OS architecture

Taku OS is an Android system with a phone-local Agent runtime, currently released as a development preview. The source repository remains private during publication review; original Taku materials are Apache-2.0. Android app compatibility depends on the exact app, device, permissions and tested scenario. Agents use system interfaces and can work across supported apps.

This page describes what each component does. For what has been verified on emulators and physical devices, see [current status](docs/current-status.md).

```text
Home / React Native Agent UI / widget / notification / mirror UI
                    ↓
task service (single queue and task lifecycle)
                    ↓
Codex App Server (thread / turn / item)
       ├── model connection → ChatGPT / local Magpie → online model API
       └── phone tools / Android shell
                    ↓
Android system tools / Binder / framework / display host
                    ↓
Android app runtime + existing apps + device hardware

CLI / SDK / trusted local extension
                    ↓
Taku capability facade (three read-only device methods)
                    ↓
shared device probe → Android shell / task service metadata
```

## Components

The system comes first, and app compatibility is the base it stands on. The root agent runs with the highest privileges the current system grants. The capability SDK gives extensions a stable way to call and reproduce behavior.

The facade currently runs inside the process that calls the SDK. ADB is the development transport from a host, and the device shell CLI is the entry point on the phone. Both use the same probe script. The SDK exposes three read-only capabilities, and the root agent can do much more through the Android shell.

The agent executor is Codex. Magpie is a model gateway and adds no sessions, task queue or tool executor of its own. The [React Native Agent UI](system/agent-app/README.md) projects the same conversation through a native gateway. [Memory](system/memory/README.md) keeps its own store and provides material and suggestions through the task service and the existing model path. Verify a model provider swap and an agent runtime swap separately. See the [model gateway notes](adapters/magpie-runtime/README.md).

Independent screens are held by the existing `VirtualDisplayHost` and ImageReader, which keep a Surface alive. They are separate logical displays in one Android system. Input selects a dynamic display ID explicitly, and the viewer on the main screen uses the UID-restricted `taku-mirror` abstract Unix socket. Shared app data, single-instance tasks, media rendering and conflicts between user and agent input all need testing case by case. An independent screen is not a separate Android VM, and not every app works on one.

## Extensions and permissions

The repository has several extension paths, each with its own contract:

- [SDK `device-report` extensions](examples/extensions/device-report/README.md) run in the same process as the CLI. Declarations limit which methods are passed into the SDK. They do not stop an extension from reading files or calling the system on its own.
- Codex marketplace plugins are managed through the App Server. On-device commands and the native UI share the same native RPC. See the [preinstalled agent documentation](system/assistant/assets/skills/taku-open-access/SKILL.md).
- [Host plugins](docs/plugins/README.md) use a separate host, registry and installed generations, with paths for JVM and Android code, MCP, install, enable, disable and rollback. Their state is separate from Codex marketplace data.

Manifests and health checks on these paths are not a third-party execution sandbox. The boundary of each path is set by its actual caller identity, process and permissions.

The task service handles privileged execution for the agent. Home, Voice, the React Native UI running under an ordinary UID, and the on-device open-access provider each have their own call path. Eligibility for an interface comes from its implementation and documentation. The SDK has no network listener and is not a source of truth for tasks. The execution boundary of the local plugin host is covered in its documentation.

Harness and Skills provide versioned context, receipts and skill management. See [Harness and Skills](docs/agent-harness.md), [plugins](docs/plugins/README.md) and [agent open access](docs/agent-open-access.md) for how each entry point works.

## Roadmap

1. Build verifiable interface boundaries from a structured capability catalog and reproducible examples.
2. Keep independent build and test entry points for system components. See [image engineering](image/README.md).
3. Keep checking device, vendor and kernel support, first install and recovery, hardware and key apps against fixed versions.
4. Move extension isolation, missing UI wiring and event coverage forward according to real product scope, with agents and the GUI sharing the same authoritative data.

The organization of gateway, adapters, skills and contributions follows OpenClaw, while the core of Taku OS is the Android system runtime and device capabilities. The official OpenClaw Android client is a companion node. Sources are in NOTICE.

## Agent open access requirement

Taku OS treats agents as users of the phone. Every setting, state and user-visible data type must have an Android standard command entry that an agent can discover. Whatever the GUI can change, an agent must be able to change too, with both sharing the same authoritative data and validation. Protecting the system from ordinary apps is not a reason to refuse an agent. Each new setting or feature ships with executable commands, parameters and error descriptions, updates the preinstalled agent documentation, and is verified on an isolated emulator by reading and writing through both CLI and GUI. Data the upstream does not provide must be reported as unavailable, never faked. See [agent open access](docs/agent-open-access.md) for existing interfaces and what is still incomplete.
