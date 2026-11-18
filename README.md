# Taku OS

<p align="center"><img src="docs/media/taku-os-banner.gif" alt="Taku OS — Every era needs its OS, for your phone" width="100%"></p>

**An Android system built for agents.**

[English](README.md) · [简体中文](README.zh-CN.md) · [日本語](README.ja.md)

Taku OS puts an Agent runtime, system tools, a home interface and background workspaces on the phone. People and agents use the same underlying settings and application state. Model inference uses your configured online service; running the Agent on the phone does not make its models offline.

This is a **development preview**. Original Taku code and documentation are licensed under Apache-2.0; third-party materials keep their own terms.

## Try it

| Path | What is available |
| --- | --- |
| Pixel 9 (`tokay`) | TakuOS 1.3.1 / Android 17 / build `20261111080323`. Full OTA, matching recovery package, SHA-256 and installation instructions are in the [mobile guide](https://docs.taku.ai/quickstart/mobile/) |
| Emulator | Component development and acceptance are documented. A clean, downloadable Taku OS emulator image has not been published; see [development setup](docs/getting-started.md) |
| First plugin | The public [Hello World example](https://github.com/Taku-OS/taku-os-plugins/tree/main/examples/taku-hello-world) includes complete source and a ZIP. Follow the [plugin tutorial](https://docs.taku.ai/os/extensions/first-plugin/) |
| SDK, without a phone | An authorized checkout can run the local method inventory below without a model account or npm install |

The released Pixel 9 build has system-update and reboot evidence. Installation from stock, other devices and complete app compatibility are separate, unfinished checks. Other LineageOS-supported devices are not automatically supported by Taku OS. See the [release manifest](docs/releases/tokay-20261111080323.json) and [current status](docs/current-status.md).

```sh
# From an authorized Git checkout, with Node.js 22+:
env -u TAKU_OS_TRANSPORT -u TAKU_OS_SERIAL node sdk/cli/taku.mjs call methods.list
```

For an AI-assisted setup, ask your coding agent to read [the setup guide](docs/agent-setup.md), identify the exact device/build and follow the linked installation procedure. Unlocking or erasing a phone needs explicit approval; a full OTA ZIP is not a fastboot image bundle.

## Build something useful

- **Extend the Agent:** package a skill or MCP tool, declare its capabilities, install it, invoke it and verify removal.
- **Work across apps:** use supported Android interfaces and background workspaces. Availability depends on the installed build, app and permissions.
- **Make a small app or interface:** start with a reproducible example and keep the GUI and Agent API on the same source of truth.
- **Use device capabilities:** camera, audio, display and sensor interfaces have separate implementation and acceptance scopes. Refer to their module documentation before promising a complete scenario.

Plugins run with their host's privileges. Capability declarations describe access; they are not a security sandbox. See [security](SECURITY.md) and [plugin development](docs/plugins/README.md).

## Develop

[Getting started](docs/getting-started.md) explains SDK, native component, React Native and full-ROM paths. [Your first change](docs/first-change.md) gives a small exercise. Run the checks relevant to the change and report code, build, emulator and device results separately.

The repository includes original on-device services, SDKs, tools, ROM integration and documentation. Hosted backends, account credentials and full Android/vendor source trees are not bundled. Some current ROM inputs still need private access; [open-source readiness](docs/open-source-readiness.md) tracks that gap.

## Documentation and contributions

- [Developer documentation](https://docs.taku.ai/) · [Architecture](ARCHITECTURE.md) · [Project structure](docs/project-structure.md)
- [Contributing](CONTRIBUTING.md) · [Support](SUPPORT.md) · [Code of Conduct](CODE_OF_CONDUCT.md)
- [Third-party materials](docs/third-party-materials.md) · [Publication scope](docs/open-source-scope.md) · [English snapshot review](docs/reviews/source-publication-2026-11-18.md)
- Report vulnerabilities through the **private reporting route in [SECURITY.md](SECURITY.md)**, not a public issue.

## License

Original Taku materials: [Apache-2.0](LICENSE). Retain the [NOTICE](NOTICE), third-party licenses and historical grants. Source licensing, repository visibility and permission to redistribute a complete ROM are separate matters.
