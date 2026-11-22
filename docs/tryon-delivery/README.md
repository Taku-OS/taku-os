# TikTok LIVE 换装开发预览交付

本交付面向配套 Taku OS 的 Pixel 9 开发环境。用户在服装直播中确认 Try on，
系统识别当前衣服，再由原生换装应用打开前摄并显示 Lucy 返回的视频。
小窗可以移动、缩放、放大和关闭。关闭、失败或超时会进入原有资源清理流程。

## 交付内容

- 系统仓库源码、构建脚本、共享 LIVE 单次授权契约与测试。
- 独立的 LIVE 观察组件和原生换装应用；顶部展示仍由配套系统组件负责。
- 本地交付包：`verified-device/` 保存既有真机组合，`source-build/` 保存本分支新构建候选。
- `source.bundle`、`source.patch`、`manifest.json`、`SHA256SUMS`、源码版本与验证摘要。APK、运行时二进制和归档不提交 Git。
- [安装使用](INSTALL.zh-CN.md)、[构建与打包](BUILD.md)和 [PR 说明](PR.md)。

两组安装包有各自的版本、签名和验证范围，不交叉替换。记录中的 Pixel 组合更新为
Native 96 / Observer 29；本分支重新构建的候选为 Native 97 / Observer 30，配套
Proactivity 候选仍为 9。新编号不代表完成了新的真机验收。

本次补齐了五项后续修复：移除运行中及正常结束时额外的 AI try-on / Expand / Stop
通用控制卡；恢复 Preparing / Live 胶囊与原系统宿主触摸路径；预览打开时撤回并暂停
重复邀请；兼容已验证的 TikTok DetailActivity 直播容器；将 TLS / WebSocket 关闭放到
现有清理线程，并等待晚到连接释放后才确认清理。独立的失败提示保留，未新增界面。
Native 93 的右上角按钮、Native 92 的队列修复和既有单次授权交接均保留。

## 组件分工

| 组件 | 职责 |
| --- | --- |
| Taku Try-On，`org.taku.tryon` | 用户的 fal key、前摄、Lucy 会话、视频小窗和停止 |
| Live Try-On Suggestions，`org.agentos.shopping` | LIVE 观察、提示、原图识衣、选衣和单次授权交接 |
| Taku AI / Assistant | 图片识别模型配置、逐应用许可和既有系统运行时 |
| Proactivity / Surfaces | 已批准提示、用户动作与展示连接的生命周期 |
| SystemUI | 顶部灵动岛和原生助手卡片 |
| Home / Audio | 原有助手与语音入口；LIVE 手动 Try on 不要求用户重复说英语 |

## 用户自带密钥

本交付采用 BYOK：每个用户在 Taku Try-On 填自己的 **fal API key**。
不内置演示 key、不导出当前手机的私有数据，也不将用户的账户作为安装依赖。
应用通过 Android Keystore 的密钥加密保存凭据；状态只报告是否已配置。
该配置与系统 Taku AI 的图片识别模型配置分别管理。具体界面步骤见安装说明。

## 验证范围与发布条件

历史记录覆盖较早指定 Pixel 的真实 LIVE → 识衣 → Lucy 返回视频 → 关闭；原宿主
触摸与预览抑制也有独立模拟器检查。Observer 29 在真实 DetailActivity 直播中自动
弹出邀请并稳定保持 24.81 秒，本轮未点击 Try on、未开启相机或 Lucy。
Native 96 在一次初始化失败后确认本地连接和相机清理完成，未观察到新的进程崩溃。
这些是分别记录的检查，不能合并成当前完整试穿链路已经稳定通过。

本次新候选 97 / 30 的隔离模拟器检查通过：24 组布局、18 项实际窗口操作、30 项
系统宿主断言和 3 次真实点击，预览打开与关闭时 Observer 状态正确切换。测试未开启
相机或模型；模拟器克隆已删除，原 AVD 的 21 个文件哈希前后未变。该检查不代替新的
真机、云端或 content provider 在机验收，后者共享处理逻辑由 host suite 覆盖。

Lucy 仍存在未解决的 INTERNAL_ERROR：已记录的一次请求在约 9.4 秒后失败，未返回
视频帧。现有记录不足以确定云端内部根因。Native 96 的真实关闭按钮、自然 60 秒
退出及进程持续性回归仍待完成；本分支新构建的 Native 97 / Observer 30 也尚未完成
新的真机验收。逐项状态见 CHECKS.json。

SystemUI 黑底 APK 是基于配套版本的**本地兼容测试包**，正式 Kotlin 源码修复在仓库中。
它不是完整 Soong 构建产物。完整 SystemUI/ROM 构建、干净设备安装、冷启动与 OTA
尚未在本交付验证。交付清单将这些状态分别记录；该包不标记为正式公开发行版本。

当前适合提交代码评审与指定开发环境复现。面向普通用户发行前，由系统集成方从同一
源码版本构建并签名全部依赖，完成干净安装及重启验证，再随配套 ROM/OTA 交付。
当前源码、候选 APK、记录中的 Pixel APK 和验收范围分别锁定，后续更新须同步清单、
重新构建与验证，不能覆盖本包后继续沿用旧哈希或旧验收记录。
