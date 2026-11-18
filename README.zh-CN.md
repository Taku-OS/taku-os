<h1 align="center">Taku OS</h1>

<p align="center"><img src="docs/media/taku-os-banner.gif" alt="Taku OS — 每个时代都需要自己的操作系统" width="100%"></p>

<p align="center"><strong>为 Agent 打造的 Android 系统。</strong></p>

<p align="center"><a href="README.md">English</a> · 简体中文 · <a href="README.ja.md">日本語</a></p>

Taku OS 将 Agent 运行时、系统工具、主页和后台工作屏集成在手机上。用户与 Agent 共用设置和应用状态；模型推理由用户配置的在线服务提供，Agent 在手机上运行不等于模型离线运行。

当前为**开发预览版**。Taku 自研代码和文档采用 Apache-2.0，第三方材料保留各自条款。

## 开始使用

| 体验方式 | 当前可用内容 |
| --- | --- |
| Pixel 9（`tokay`） | TakuOS 1.3.1 / Android 17 / `20261111080323`；[安装与更新教程](https://docs.taku.ai/zh/quickstart/mobile/)提供完整 OTA、同批 recovery、SHA-256 与安装步骤 |
| 模拟器 | 已有组件开发与验收路径；尚未发布可直接下载的干净 Taku OS 模拟器镜像，见[开发准备](docs/getting-started.md) |
| 第一个插件 | 公开的 [Hello World 示例](https://github.com/Taku-OS/taku-os-plugins/tree/main/examples/taku-hello-world)包含全部源码和 ZIP，按[插件教程](https://docs.taku.ai/zh/os/extensions/first-plugin/)安装与调用 |
| 不接手机的 SDK | 获授权的源码 checkout 可运行下面的接口清单，不需要模型账号或 npm install |

已发行的 Pixel 9 版本有系统更新与重启证据。原厂首装、其他机型和完整 App 兼容性仍须分别验收；LineageOS 支持的设备不自动成为 Taku OS 支持的设备。具体见[版本清单](docs/releases/tokay-20261111080323.json)和[当前状态](docs/current-status.md)。

```sh
# 在已获授权的 Git checkout 中，使用 Node.js 22+：
env -u TAKU_OS_TRANSPORT -u TAKU_OS_SERIAL node sdk/cli/taku.mjs call methods.list
```

需要 Coding Agent 帮助安装时，让它先读[安装执行指引](docs/agent-setup.md)，核对设备与版本，再遵循链接中的安装流程。解锁或清空手机需要明确授权；完整 OTA ZIP 不能当成 fastboot 镜像包。

## 动手改造

- **扩展 Agent：**制作 skill 或 MCP 工具，声明能力，安装、调用并验证卸载。
- **跨应用工作：**使用已支持的 Android 接口和后台工作屏，具体可用性取决于版本、应用与权限。
- **制作小应用和界面：**从可复现示例开始，让 GUI 与 Agent API 共用数据和校验。
- **调用硬件能力：**相机、音频、显示和传感器有各自的实现与验收范围，不能从一个接口可用推断整个场景已完成。

插件使用宿主权限；能力声明不是安全沙箱。请阅读[安全说明](SECURITY.md)和[插件开发](docs/plugins/README.md)。

## 开发与贡献

[开发准备](docs/getting-started.md)区分 SDK、原生组件、React Native 与完整 ROM。[第一次修改](docs/first-change.md)提供小练习；验证结果区分源码、构建、模拟器与真机。

本仓库包含端上服务、SDK、工具、ROM 整合和文档，不包含托管后端、账号凭据或完整 Android/vendor 源码树。当前部分 ROM 输入仍需私有访问权限，缺口见[开源就绪清单](docs/open-source-readiness.md)。

[公开文档](https://docs.taku.ai/zh/) · [架构](ARCHITECTURE.md) · [贡献指南](CONTRIBUTING.md) · [获取帮助](SUPPORT.md) · [行为准则](CODE_OF_CONDUCT.md) · [第三方材料](docs/third-party-materials.md)

漏洞请走 [SECURITY.md](SECURITY.md) 的私密报告入口，不发到公开 Issue。

## 许可证

Taku 自研内容采用 [Apache-2.0](LICENSE)，保留 [NOTICE](NOTICE)、第三方许可证和历史授权。源码许可、仓库可见性与完整 ROM 的再分发权分别管理。
