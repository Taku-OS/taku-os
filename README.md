<h1 align="center">Taku OS</h1>

<p align="center"><img src="docs/media/taku-os-banner.gif" alt="Taku OS — Every era needs its OS, for your phone" width="100%"></p>

<p align="center"><strong>An open-source operating system for your phone, built for the AI era.</strong></p>

<p align="center">AI is part of the system. The agent can work across supported apps, create new ones, use your camera and sensors, and interact with connected devices, subject to device support and permissions.</p>

<p align="center"><a href="#get-started">Get started</a> · <a href="https://docs.taku.ai">Docs</a> · <a href="https://github.com/Taku-OS/taku-os/releases">Releases</a> · <a href="https://taku.ai">Website</a> · <a href="https://discord.gg/NYK47Ngucm">Discord</a> · <a href="LICENSE">License</a></p>

<p align="center">English | <a href="README.zh-CN.md">简体中文</a> | <a href="README.ja.md">日本語</a></p>

## Research Preview

Taku OS is currently in an early stage of development and is intended for testing and development. It is not recommended for work use.

## What is Taku OS?

Taku OS is built on Android and supports common Android apps. You can power the agent with your own API key (BYOK) or sign in with your ChatGPT subscription.

Most phone assistants live inside a single app and wait for a question. In Taku OS, the agent runs on your phone and can use system capabilities directly, allowing tasks to span supported apps, your camera, and system settings. You can explore the system code, understand how it works, and change it to suit your needs.

This repository contains the Taku OS system ROM and documentation, but not the server-side services. Taku for Mac and Marketplace are separate products and are not open source.

## Yours to shape, everywhere

Say what you need. Keep what works.

- **A home screen made for you.** Say, "Track my half-marathon training." The agent creates a home screen with your plan, input fields, and buttons. Refine it through conversation, save it, and come back to it later. (TODO: Add image)
- **Can't find the app you need? Build one.** Describe what you want. The agent writes the code, compiles it, installs it on your phone, and opens it.
- **Settings, without the search.** No more digging through menus. Ask the agent to change supported system settings for you.

## Eyes and ears

With Taku OS, the agent can use your phone's hardware to interact with the world around you.

- **Describe a look, get a camera filter.** Tell the agent the look you want, and it adjusts the camera filter while you shoot.
- **Translate a conversation live.** In Tokyo, hear the other person's Japanese translated into English through your earbuds. Reply in English, and the Japanese translation plays through your phone's speaker.
- **Access your phone's hardware.** The camera, microphone, Wi-Fi, Bluetooth, and motion sensors are accessible to the agent through supported interfaces. Availability depends on the device, drivers, and permissions.

## Intelligence everywhere

- **Tap and speak.** Tap the voice input at the bottom of the screen and say what you need.
- **Hold the side button.** Enter voice mode and keep the conversation going.
- **Go Live.** Talk with the agent in real time and let it work while you continue using your phone.
- **What would you build?** We're curious what AI can do with access to system-level capabilities. Bring your ideas, experiment with Taku OS, and show us what a phone could do.

## Get started

Prepare your device, let your coding agent guide the setup, then start exploring and building.

### Choose how to try Taku OS

**On a phone**

You'll need a computer, a phone that supports installing Taku OS, and a USB cable that supports data transfer. We recommend Pixel 9, our current test device. Other Android devices may work, but compatibility has not been verified. Check the current device status and back up your phone before proceeding.

[Download Taku OS for Pixel 9 (`tokay`)](https://github.com/Taku-OS/taku-os/releases/latest)

**In an emulator**

Don't have a compatible phone? You can try Taku OS in an emulator without changing your phone. Make sure your computer can run an Android ARM64 virtual device, then download the emulator image.

Download the emulator image (TODO: Add link)

### Let your coding agent guide the setup

Once you're ready, paste the following into Claude Code, Codex, or another coding agent. Follow its instructions and complete any steps that require your input or confirmation.

```text
Read https://github.com/Taku-OS/taku-os/blob/main/docs/agent-setup.md and set up Taku OS for me on my phone or in the emulator.
```

### Connect a model and start exploring

Once Taku OS starts, sign in and choose a model. You can use your own API key or sign in with your ChatGPT subscription.

Some apps may not work as expected on rooted or modified devices. Google apps and the Play Store are optional.

### Make it your own

After trying Taku OS, you can modify the system code directly or start by building your first plugin to add your own functionality.

## Documentation

| Topic | Link |
| --- | --- |
| Guides and API reference | [docs.taku.ai](https://docs.taku.ai) |
| Current status by device | [docs/current-status.md](docs/current-status.md) |
| Plugin development | [docs/plugins/README.md](docs/plugins/README.md) |
| Philosophy | [PHILOSOPHY.md](PHILOSOPHY.md) |

## Architecture

```text
Home / Feed, Agent app, widgets, notifications
                 ↓
Task service (one queue, one task lifecycle)
                 ↓
Agent runtime on the phone → model gateway → online model API
                 ↓
Android system tools, framework, apps, and hardware
```

All interaction surfaces share a single task service. The on-device agent runtime connects to model services through a model gateway. Model inference happens online, so the agent needs a network connection. See [ARCHITECTURE.md](ARCHITECTURE.md) for details.

## Development

To build the system or its components, see [docs/getting-started.md](docs/getting-started.md). Read [CONTRIBUTING.md](CONTRIBUTING.md) before opening a pull request.

## Community

Report bugs in [Issues](https://github.com/Taku-OS/taku-os/issues), ask questions in [Discussions](https://github.com/Taku-OS/taku-os/discussions), and join the conversation on [Discord](https://discord.gg/NYK47Ngucm).

For help, see [SUPPORT.md](SUPPORT.md). To report a security vulnerability, follow [SECURITY.md](SECURITY.md). All participants are expected to follow the [Code of Conduct](CODE_OF_CONDUCT.md).

## License

Taku OS is licensed under [Apache-2.0](LICENSE). Third-party components retain their own licenses, listed in [NOTICE](NOTICE).
