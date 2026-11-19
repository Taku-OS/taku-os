<h1 align="center">Taku OS</h1>

<p align="center"><img src="docs/media/taku-os-banner.gif" alt="Taku OS — 每个时代都需要自己的操作系统" width="100%"></p>

<p align="center"><strong>面向 AI 时代的开源手机操作系统。</strong></p>

<p align="center">让 AI 成为手机系统的一部分。Agent 可以跨支持的应用执行任务、创建新应用、调用相机和传感器，并与连接的设备交互。具体能力取决于设备支持情况和权限。</p>

<p align="center"><a href="#开始使用">开始使用</a> · <a href="https://docs.taku.ai">文档</a> · <a href="https://github.com/Taku-OS/taku-os/releases">发布版本</a> · <a href="https://taku.ai">官网</a> · <a href="https://discord.gg/NYK47Ngucm">Discord</a> · <a href="LICENSE">许可证</a> · <a href="https://github.com/Taku-OS/taku-os/stargazers">Stars</a></p>

<p align="center"><a href="README.md">English</a> | 简体中文 | <a href="README.ja.md">日本語</a></p>

## Research Preview

Taku OS 当前处于早期开发阶段，供测试与开发使用。预览镜像为面向解锁 Bootloader 的 `userdebug` 构建，使用公开 AOSP 开发密钥（`test-keys`）签名——**刷入后切勿回锁 Bootloader（`fastboot flashing lock`）**，请勿侧载来源不明的 APK，也不要在主力机、工作机或网银设备上使用。

## Taku OS 是什么？

Taku OS 基于 Android 构建，支持常见的 Android 应用。你可以使用自己的 API Key（BYOK），也可以通过 ChatGPT 订阅账号登录，为 Agent 提供模型能力。

大多数手机助手停留在单个应用中，等待你提问。而在 Taku OS 中，Agent 运行在手机上，可以直接使用系统能力，让一项任务跨越支持的应用、相机和系统设置。你可以查看系统代码，了解它的工作方式，并根据自己的需求进行修改。

本仓库包含 Taku OS 系统 ROM 和文档，不包含服务端服务。Taku for Mac 和 Marketplace 是独立产品，并未开源。

## 由你塑造，随处可用

说出你的需求，留下好用的功能。

- **为你打造的主页。** 说一句“帮我跟踪半马训练”，Agent 就会创建一个包含训练计划、输入框和按钮的主页。你可以通过对话继续调整、保存，并在之后随时使用。
- **找不到想要的应用？自己创建一个。** 描述你的需求，Agent 会编写代码、编译、安装到手机上并打开。
- **调整设置，无需翻找菜单。** 不必在菜单中逐项查找，直接让 Agent 帮你调整当前支持的系统设置。

## 看见，也听见

通过 Taku OS，Agent 可以调用手机硬件，与周围的世界互动。

- **描述一种风格，生成相机滤镜。** 告诉 Agent 你想要的画面效果，它会在拍摄时调整相机滤镜。
- **实时翻译对话。** 例如在东京，你可以通过耳机听到对方说的日语被翻译成英语。用英语回答后，手机扬声器会播放对应的日语译文。
- **调用手机硬件。** 相机、麦克风、Wi-Fi、蓝牙和运动传感器可以通过受支持的接口供 Agent 使用。具体可用范围取决于设备、驱动和权限。

## 智能，随时在场

- **点一下，直接说。** 点击屏幕底部的语音输入，说出你的需求。
- **长按侧键。** 进入语音模式，继续与 Agent 对话。
- **开启 Live。** 与 Agent 实时交流，让它执行任务，同时继续使用手机。
- **你会创造什么？** 我们很好奇，当 AI 能够调用系统级能力时，还能实现什么。带上你的想法，在 Taku OS 上动手探索，看看手机还有哪些可能。

## 开始使用

准备好设备，让 Coding Agent 引导你完成安装，然后开始探索和开发。

### 选择体验方式

**在手机上体验**

你需要一台电脑、一台支持安装 Taku OS 的手机，以及一根支持数据传输的 USB 线。我们推荐使用 Pixel 9，这是目前的测试设备。其他 Android 设备可能也能运行，但尚未完成兼容性验证。开始前请查看设备当前状态，并备份手机数据。

[下载 Pixel 9（`tokay`）版 Taku OS](https://github.com/Taku-OS/taku-os/releases/latest) · [Recovery 引导包](https://github.com/Taku-OS/taku-os/releases/latest/download/TakuOS-tokay-recovery.zip) · [最新元数据 JSON](https://github.com/Taku-OS/taku-os/releases/latest/download/latest.json)

**在模拟器中体验**

没有合适的手机？也可以通过模拟器体验 Taku OS，无需改动现有手机。请确保电脑能够运行 Android ARM64 虚拟设备，然后下载模拟器镜像。

[下载 ARM64 模拟器镜像](https://github.com/Taku-OS/taku-os/releases/latest/download/TakuOS-emulator-arm64-images.tar.gz)

### 让 Coding Agent 引导安装

准备完成后，将以下内容粘贴给 Claude Code、Codex 或其他 Coding Agent。按照指引操作，需要你亲自执行或确认的步骤，请按提示完成。

```text
阅读 https://github.com/Taku-OS/taku-os/blob/main/docs/agent-setup.md，帮我在手机或模拟器上安装并配置 Taku OS。
```

### 连接模型，开始体验

Taku OS 启动后，登录并选择模型。你可以使用自己的 API Key，也可以通过 ChatGPT 订阅账号登录。

部分应用在已 Root 或修改过系统的设备上可能无法正常运行。Google 应用和 Play 商店为可选项。

### 动手改造

体验 Taku OS 后，你可以直接修改系统代码，也可以从开发第一个插件开始，为系统添加自己的功能。

## 文档

| 主题 | 链接 |
| --- | --- |
| 使用指南和 API 参考 | [docs.taku.ai](https://docs.taku.ai) |
| 各设备当前状态 | [docs/current-status.md](docs/current-status.md) |
| 插件开发 | [docs/plugins/README.md](docs/plugins/README.md) |
| 设计理念 | [PHILOSOPHY.md](PHILOSOPHY.md) |

## 架构

```text
Home / Feed、Agent 应用、小组件、通知
                 ↓
任务服务（一个队列，一套任务生命周期）
                 ↓
手机上的 Agent 运行时 → 模型网关 → 在线模型 API
                 ↓
Android 系统工具、框架、应用和硬件
```

所有交互入口共用一个任务服务。设备端 Agent 运行时通过模型网关连接模型服务。模型推理在线上完成，因此 Agent 需要联网。详见 [ARCHITECTURE.md](ARCHITECTURE.md)。

## 开发

系统及其组件的构建说明见 [docs/getting-started.md](docs/getting-started.md)。提交 Pull Request 前，请先阅读 [CONTRIBUTING.md](CONTRIBUTING.md)。

## 社区

在 [Issues](https://github.com/Taku-OS/taku-os/issues) 中报告问题，在 [Discussions](https://github.com/Taku-OS/taku-os/discussions) 中提问，也欢迎加入 [Discord](https://discord.gg/NYK47Ngucm) 交流。

如需帮助，请查看 [SUPPORT.md](SUPPORT.md)。如需报告安全漏洞，请按照 [SECURITY.md](SECURITY.md) 中的指引操作。所有参与者均应遵守 [社区行为准则](CODE_OF_CONDUCT.md)。

## 许可证

Taku OS 采用 [Apache-2.0](LICENSE) 许可证。第三方组件保留各自的许可证，详见 [NOTICE](NOTICE)。
