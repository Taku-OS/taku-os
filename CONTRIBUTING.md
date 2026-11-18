# Contributing to Taku OS

This guide covers how to find useful work, set up the repository, verify a change and get it reviewed. The source repository currently requires access authorization; these contribution instructions do not change its visibility.

Taku OS is a research preview maintained by a small team. Read [What to expect from review](#what-to-expect-from-review) before you put time into a large change.

Quick links:

- [Getting started](docs/getting-started.md)
- [Your first change](docs/first-change.md)
- [Development checks](docs/development.md)
- [Project structure](docs/project-structure.md)
- [Current status](docs/current-status.md)
- [Issues](https://github.com/Taku-OS/taku-os-release/issues)
- [Discord](https://discord.gg/NYK47Ngucm)

## Table of contents

- [Ways to contribute](#ways-to-contribute)
- [Development setup](#development-setup)
- [Project layout](#project-layout)
- [Branches and commits](#branches-and-commits)
- [Verify your change](#verify-your-change)
- [Commit sign-off (DCO)](#commit-sign-off-dco)
- [Pull requests](#pull-requests)
- [AI-assisted contributions](#ai-assisted-contributions)
- [What to expect from review](#what-to-expect-from-review)
- [Agent parity](#agent-parity)
- [Third-party materials](#third-party-materials)
- [Documentation changes](#documentation-changes)
- [Security](#security)
- [Code of Conduct](#code-of-conduct)
- [License of contributions](#license-of-contributions)

## Ways to contribute

You don't need to write code to help. Each kind of contribution below says what makes it useful to a maintainer.

### Report a bug

Use the [bug report form](https://github.com/Taku-OS/taku-os-release/issues/new?template=bug-report.yml). A good report covers one problem, starts from a clean state and includes the exact steps, what you expected, what happened and the build you ran. Search existing issues first. If a report turns out to be a vulnerability, follow [SECURITY](SECURITY.md) and do not open a public issue.

### Report compatibility

Use the [compatibility report form](https://github.com/Taku-OS/taku-os-release/issues/new?template=compatibility-report.yml). A good report names one device, one app version and one scenario. Install, launch, signed-out use, account features, media and background displays are separate checks, so report only the ones you tested. Report failures as well as successes. An observed failure is not a confirmed root cause, so say which it is.

### Suggest a feature

Use the [feature request form](https://github.com/Taku-OS/taku-os-release/issues/new?template=feature-request.yml). A good request starts from the problem, says who has it and describes how you would know it is solved.

### Propose a capability or extension

Use the [capability proposal form](https://github.com/Taku-OS/taku-os-release/issues/new?template=capability-proposal.yml) when you have a concrete interface in mind. A good proposal gives one real input and the state it changes, the entry point, inputs, outputs and errors, the permissions it needs and the test status in each environment.

### Write a plugin

Start with [plugin development](docs/plugins/README.md) and the samples in [examples/godmode/](examples/godmode). A good plugin validates against the manifest schema, says which host it was tested on and states what it does not cover. Plugins run as trusted code, so be clear about what yours can reach. Follow the [published first-plugin tutorial](https://docs.taku.ai/os/extensions/first-plugin/).

### Improve the documentation

See [Documentation changes](#documentation-changes). A good documentation change comes from following the page yourself and fixing what stopped you. Say what a reader can now do.

### Fix a bug

Pick something you can verify on its own. A good fix is small, targets one problem and shows the behavior before and after. Comment on the issue before you start so that two people don't do the same work. See [candidate first tasks](docs/contributing-first-tasks.md); they are suggestions, not assigned issues.

### Help other people

Answer questions in [Issues](https://github.com/Taku-OS/taku-os-release/issues) or on [Discord](https://discord.gg/NYK47Ngucm), reproduce an open issue on your own device and name the build, or test a pull request and report what you ran.

## Development setup

### Prerequisites

Choose the row that matches what you are changing.

| Goal | You need |
| --- | --- |
| Read the SDK method list and make a first change | Git and Node.js 22+. No `npm install` |
| Run the full offline checks | Node.js 22+, Python 3.11+ (3.12 recommended) and JDK 17+ |
| Build the React Native UI | Node.js 22.18+ and npm dependencies. Android builds also need a JDK and the Android SDK |
| Build the native Android components | Bash, Python 3.11+ (3.12 recommended), JDK 17+, Android SDK 36, zip and unzip |
| Build the full Pixel 9 image | The Docker builder and pinned inputs described in [getting started](docs/getting-started.md#build-the-full-image) |

### Get the source

Fork the repository on GitHub, then clone your fork and add this repository as `upstream`.

```sh
git clone https://github.com/YOUR-USERNAME/taku-os-release.git
cd taku-os-release
git remote add upstream https://github.com/Taku-OS/taku-os-release.git
```

### Run a first check

This check needs no npm install, Android SDK, device or model account.

```sh
env -u TAKU_OS_TRANSPORT -u TAKU_OS_SERIAL node sdk/cli/taku.mjs call methods.list
```

It exits `0` and prints JSON with `protocol` set to `taku/0.1`, `ok` set to `true` and six methods under `result`. Then follow [your first change](docs/first-change.md), a short exercise that uses only Node.js.

### Run the tests

Run these from the repository root. The root needs no `npm install`.

```sh
npm test
npm run check
npm run verify
```

`npm test` and `npm run check` need only Node.js. `npm run verify` also needs Python 3.11+ (3.12 recommended) and JDK 17+. It stops at the first failure.

### Build components

```sh
export ANDROID_SDK_ROOT=/absolute/path/to/android-sdk
export JAVA_HOME=/absolute/path/to/jdk
npm run build:android
```

This entry point does not build the React Native UI, plugins or the input method. The first build needs network access to download dependencies. [Getting started](docs/getting-started.md) lists the outputs for each module and the separate steps for React Native and the full image.

For the React Native UI, work in `system/agent-app/`.

```sh
cd system/agent-app
npm ci
npm run typecheck
npm run lint
npm test
```

### Run on an emulator or a device

Runtime behavior has to be checked on an isolated emulator or a named physical device. Use a test environment, not a phone you depend on. Use the [mobile installation guide](https://docs.taku.ai/quickstart/mobile/) and the documented component setup. A distributable clean Taku emulator image remains pending. `scripts/start-emulator.py` starts an existing development AVD. The AVD setup behind the documented results is in the [emulator reference](docs/pitfalls/emulator-reference.md).

## Project layout

[Project structure](docs/project-structure.md) describes every top-level path. These are the ones most changes touch.

| Path | What lives there |
| --- | --- |
| `core/` | Protocol facade, device probes and method list |
| `sdk/` | General SDK and CLI |
| `spec/` | Canonical protocol schemas, capability list and extension declarations |
| `adapters/` | Android transport and agent runtime adapters |
| `system/assistant/` | Native Home and Assistant, task service, settings and preinstalled agent documentation |
| `system/agent-app/` | React Native session UI, with its own [AGENTS.md](system/agent-app/AGENTS.md) and [README](system/agent-app/README.md) |
| `system/memory/` | Standalone Memory app |
| `system/plugins/` | Plugin manager, hosts and management page |
| `system/inputmethod/`, `system/proactivity/`, `system/surfaces/`, `system/display/` | Input method, proactive suggestions, system surfaces and displays |
| `tooling/` | App kit and development diagnostics |
| `image/` | Pinned source locks, product configuration, patches and image checks |
| `examples/` | SDK, phone app and plugin examples |
| `tests/`, `scripts/` | Host tests, build orchestration and checks |
| `docs/` | Engineering documentation, indexed in [docs/README.md](docs/README.md) |

Make your change in the module that owns the behavior. Read [AGENTS.md](AGENTS.md) first, along with any AGENTS.md in the module you are changing. [Architecture](ARCHITECTURE.md) explains how the parts fit together.

## Branches and commits

### Branches

Branch from `main` and open your pull request against `main`. Keep one topic on each branch, and name it after the topic with a type prefix.

```sh
git fetch upstream
git switch -c fix/keyboard-draft-loss upstream/main
```

Use `feat/`, `fix/` or `docs/`, followed by a short lowercase description. Update a long-running branch by rebasing on `upstream/main`.

### Commit messages

Write the summary line as `type(scope): summary`. The scope is optional. Use the imperative mood, start the summary in lowercase and leave off the final period. Explain why in the body if the diff doesn't make it obvious.

```text
fix(inputmethod): keep the draft after a failed send

The draft was cleared before the send result came back, so a failed
send lost the text.

Signed-off-by: Your Name <you@example.com>
```

| Part | Common values in this repository |
| --- | --- |
| Type | `feat`, `fix`, `docs`, `test`, `perf`, `refactor`, `style` |
| Scope | The module or area, such as `assistant`, `agent-app`, `memory`, `image`, `inputmethod`, `surfaces`, `display`, `ai` or `devtools` |

Keep each commit focused, and don't mix formatting or unrelated cleanup into a behavior change.

## Verify your change

Run the checks for what you changed, from the repository root unless noted. [Development](docs/development.md) explains what each one covers.

| Change | Verification |
| --- | --- |
| Markdown only | `python3 scripts/check_structure.py` and `git diff --check`. Check commands, links and dates by hand, and run any new offline example |
| SDK or protocol | `npm test` and `npm run check`. Update the spec, the call examples and the preinstalled agent documentation when an interface changes |
| Build logic or anything shared | `npm run verify`, then the checks for the owning module |
| Capability inventory or native interface list | `npm run check:inventory` and `npm run check:raw-interfaces` |
| Plugins | `node --test tests/godmode-manifest.test.mjs`. With a JDK, also `bash system/plugins/test.sh` and `bash system/plugins/test-host.sh` |
| Surfaces or proactivity | `npm run check:surfaces` or `npm run check:proactivity` |
| App kit API | `npm --prefix tooling/app-kit/api ci --ignore-scripts --no-audit --no-fund`, then `npm --prefix tooling/app-kit/api test` |
| Native Android components | `npm run build:android` or the module's own `build.sh`. For the input method, run `npm run build:inputmethod`. Check runtime behavior on an isolated emulator |
| React Native UI | In `system/agent-app/`, run `npm ci`, `npm run typecheck`, `npm run lint` and `npm test` |
| Image or OTA | `npm run check:images` and `npm run check:ota`, plus the full image steps in [getting started](docs/getting-started.md#build-the-full-image) when the output changes |
| New setting or user-visible feature | The GUI and the agent share the same source of truth and validation. Add preinstalled command docs and verify reads and writes on an isolated emulator. See [Agent parity](#agent-parity) |
| UI or device behavior | Attach real screenshots or recordings. State the version, Android, API and ABI, the device, UID or permissions, and the steps. Record emulator, physical-device and full-ROM results separately |

CI runs `npm run verify`, shell syntax checks and the app kit API tests on every push and pull request. Check Android builds and device behavior yourself.

If your environment has no JDK, SDK or device, say which checks did not run and why. Documentation-only changes do not need flashing, installing or signing in.

Write verification output to the git-ignored `out/` directory. Before you share output in an issue or pull request, remove accounts, private keys, credentials, chats and raw model events.

## Commit sign-off (DCO)

The current project does not require DCO or CLA, as stated in [GOVERNANCE.md](GOVERNANCE.md). Submit only materials you have the right to contribute under the applicable license. Do not add someone else's sign-off, automatically sign historical commits or rewrite history to introduce a new policy. Any future mandatory sign-off policy will be announced separately.

## Pull requests

1. **Check that the work is wanted.** Small fixes can go straight to a pull request. For larger changes, open an issue first, so you know the direction is right before you build it.
2. **Create a topic branch** from `main`, as described in [Branches and commits](#branches-and-commits).
3. **Make one focused change.** Include the tests and documentation it needs. When an interface changes, update the spec, the call examples and the preinstalled agent documentation.
4. **Verify it.** Run the checks in [Verify your change](#verify-your-change) and keep the results.
5. **Check contribution rights.** Follow the current [contribution policy](#commit-sign-off-dco).
6. **Open the pull request.** Fill in the [pull request template](.github/pull_request_template.md). Describe the final behavior, the problem it solves, what you verified and what is not covered. For documentation, say what a reader can now do. Link the issue with `Closes #123`. Use a draft pull request if it is not ready for review.
7. **Get CI to pass.** If a failure isn't caused by your change, say so in the pull request.
8. **Respond to review.** Reviewers come from [CODEOWNERS](.github/CODEOWNERS). Push new signed-off commits to the same branch, and keep the description up to date if the change moves. Leave **Allow edits by maintainers** on, so that a maintainer can help finish the branch.

## AI-assisted contributions

Built with Codex, Claude Code or another AI tool? You're welcome here. You don't need to disclose it or label your pull request. We ask the same of every pull request:

- You understand the change and can explain it.
- You ran it, and the PR says how you verified it.
- You are responsible for understanding the change and having the right to contribute it; do not fabricate a sign-off.

You are responsible for what you submit, whichever tool wrote it. Check that generated code, tests and documentation describe what the repository really does.

## What to expect from review

Taku OS is maintained by a small team, and we can't review every pull request. We prioritize:

- Contributors who are active in the project
- Changes linked to an issue we've agreed to take on
- Small, focused changes with clear verification


Reviewers look at whether the change belongs in this project, whether it works as described and whether the verification supports that. They may ask you to split a change, add a check or rework an approach. See [GOVERNANCE](GOVERNANCE.md) for how decisions are made.

## Agent parity

[AGENTS.md](AGENTS.md) is the engineering requirement for every new setting, state and user-visible data type. Taku OS treats agents as users of the phone.

- Provide an entry point the agent can discover through standard Android interfaces such as `settings`, `content` or `cmd`, using the same source of truth and validation as the GUI.
- Anything the GUI can do, the agent must also be able to do. Do not refuse agent access on the grounds of protecting the system from ordinary apps.
- Write the real commands, parameters, results, errors and any data that upstream truly does not provide into the preinstalled agent documentation. See [current interfaces](docs/agent-open-access.md).
- On an isolated emulator, verify the GUI state after an agent write and the agent's read after a GUI change. Record the source and component versions, target serial and restore steps.

Capabilities and their inputs and outputs follow the [protocol](docs/protocol.md). Extend the module that owns the behavior instead of adding a second source of session or task state. Do not describe a plugin manifest check as process isolation.

The requirement applies to new work. Existing gaps are listed in [Agent open access](docs/agent-open-access.md), and closing one is a welcome contribution.

## Third-party materials

This covers code, fonts, icons, images, audio, models and any other material you did not create.

- Before you add it, check that its license allows use in this project.
- Record its source, version, modifications and license.
- Keep its notices in [NOTICE](NOTICE) and in the owning module.
- Keep its original license text and copyright lines.
- Pin dependencies that scripts download, and verify them after download.

Do not commit secrets, sign-in state, personal data or build artifacts. That includes keys, tokens, APKs, images and phone data. `python3 scripts/check_structure.py` rejects common artifact types.

## Documentation changes

Engineering documentation lives in this repository. Guides for installing and using Taku OS live on [docs.taku.ai](https://docs.taku.ai), which syncs versioned documentation from here. Fix interface documentation in this repository first, and write each set of steps in one place. Link to the docs site instead of repeating them.

- **Language.** Community and developer documents are in English. The README is also available in [Simplified Chinese](README.zh-CN.md) and [Japanese](README.ja.md). If you change the meaning of the English README, update the other two or say in your pull request that they need it. Examples in those files use local scenarios, so adapt them instead of translating word for word.
- **Facts.** Write only what the repository supports. Run every command you document, and keep commands and output exact.
- **Status.** Keep these states separate: implemented in source, offline checks, emulator, physical device, full ROM and released. See [current status](docs/current-status.md).
- **Naming.** Write "Taku OS" in prose. Paths, identifiers, package names and commands stay as they are.
- **Style.** Use plain, direct English, and state a real limit once.
- **API reference.** Android API detail is published at [docs.taku.ai/os/reference](https://docs.taku.ai/os/reference/). Link there instead of copying it.

Before you open the pull request, run `python3 scripts/check_structure.py` and `git diff --check`.

## Security

Do not report vulnerabilities in public issues, pull requests, discussions or Discord. Follow [SECURITY](SECURITY.md).

## Code of Conduct

This project follows the [Code of Conduct](CODE_OF_CONDUCT.md). Taking part means you agree to it. See also [GOVERNANCE](GOVERNANCE.md) and [SUPPORT](SUPPORT.md).

## License of contributions

Taku OS is licensed under [Apache-2.0](LICENSE). Under section 5 of that license, anything you intentionally submit for inclusion in the project is contributed under Apache-2.0, with no additional terms, unless you explicitly state otherwise. Third-party material keeps its own license, as described in [Third-party materials](#third-party-materials).
