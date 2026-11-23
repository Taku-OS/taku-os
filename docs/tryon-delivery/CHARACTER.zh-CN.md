# TikTok 视频角色换装：安装与验收补充

在 TikTok 普通视频中长按底部白条唤出 Taku，说出完整角色请求，确认 **Try on** 后，
以本次屏幕截图为参考，在前摄小窗中预览角色服饰和穿戴配饰。请求要求保留本人的脸、
头发和身体；这是发给模型的约束，不是换脸功能或身份一致性的保证。

本说明补充既有交付源码 `e059d1846ed5a458782e4f21ecaa3668b26a83b5` 的角色流程。
原测试 ZIP、APK 及哈希保持冻结；本文不代表原 ZIP 已含此新增说明，也不把后续文档
提交视为原包的源码版本。组件选择、安装和签名要求仍以原包 `manifest.json`、
`SHA256SUMS` 和[安装说明](INSTALL.zh-CN.md)为准。包中存在角色代码不等于完整真机验收通过。

已核对原包：Native 96 和源码候选 Native 97 均包含角色 Activity、角色交接与 Lucy 2.6
传输；记录中的 Home 12 / Assistant 119 也包含角色入口和旧系统宿主支持。
此次只补使用说明，不重编 APK。包中没有 Quickstep/Launcher APK，也不是完整 ROM。

## 测试前准备

由测试机维护者确认配套 Taku OS、Launcher/Quickstep 手势桥、Home/Taku 助手、Assistant、Audio、SystemUI 和
`org.taku.tryon` 的系统接口、签名与权限匹配。角色入口使用系统截图和 Home 的一次性
交接；任意原厂 Android 只安装 Try-On APK 不能建立这条链路。
系统助手应使用 Taku Home；维护者应保留已授权的屏幕上下文设置：
`assist_structure_enabled=1`、`assist_screenshot_enabled=1`。
语音还需 Home 的麦克风权限、可用的 Taku Audio 引擎和匹配所说语言的 ASR 配置。

在 **Taku Try-On** 中完成：

1. **Mode** 选 **Character**，**Model provider** 选 **Lucy**，点 **Save model settings**。
2. 在 **Provider key** 保存自己的 **fal API key**，确认 **Lucy key: configured**。
   当前角色模型是 fal 的 `decart/lucy-2-6/realtime`；LIVE 试穿使用
   `decart/lucy2-vton/realtime`。此版本不接受 Decart 直连 key 代替 fal key。
3. 通过 **Allow camera** 和 **Allow preview over other apps** 批准相机、通知及浮窗权限。
4. 在 **TikTok character requests** 下开启 **Enable voice character requests** 和
   **Allow camera preview after Try on**。默认入口关闭且为 voice-only；仅保存 key
   不会把这两个开关打开，也不会启动相机或云端。
5. 需要调整语音时，点 **Assistant voice language**，在实际 Taku Audio 设置中检查
   `assistant-entry` 的语音模型及语言。不要另造 Try-On 语音语言设置。

这条角色入口直接使用本次系统截图，不依赖 LIVE Observer 的直播识衣流程；仅测试
角色时，不必为此开启 LIVE 自动提醒或自动图片检查。

## 完成一次角色预览

1. 保持手机解锁，在受支持的 TikTok 中让目标角色清楚出现在普通视频画面里。
2. 长按底部白条。等到 **Taku is listening…** 后说完整请求，例如
   **Help me put on this outfit of his**；说完松手，等待实际识别结果及角色确认。
   中文语音需实际 ASR 配置支持；也可用下一段的文字入口测试中文请求。
3. 在当前角色确认中点 **Try on**。点 **Not now** 应取消，相机和模型不应启动。
4. 前摄小窗开始时可显示真实相机及 **Camera/Connecting**。只有实际解码返回帧后，
   才能把结果记作模型视频成功；不能以“相机开了”或“出现小窗”代替。
5. 角色结果保持小窗，验证拖动、放大、收起后恢复，再点关闭。每次明确开始或重试
   最多 60 秒；会话按用户自己的服务商账户计费，不自动重试。

文字入口：在同一 TikTok 画面双击底部白条，输入 **帮我换成这个角色** 或上述完整
英文请求并发送，之后沿用同一 **Try on** 确认。它不启动麦克风，但仍需有效系统截图。
从普通桌面聊天、旧附件或已过期会话输入同一句话，不能代替本次系统手势引用。
画面引用从唤起起最多有效 120 秒；取消、锁屏或重新唤起后，应从视频重新发起请求。

## 最小验收与失败定位

| 检查 | 通过条件 |
| --- | --- |
| 真实语音入口 | 记录实际 ASR 最终文本与确认结果；没有注入或改写识别结果 |
| 双击文字入口 | 使用本次 TikTok 截图显示确认，点 Not now 不启动相机/模型 |
| 明确开始及结果 | 点 Try on 后交接成功；`returnedFrames` 增长，看到实际返回视频 |
| 小窗与退出 | 拖动、放大、收起恢复、关闭生效；另测一次自然 60 秒结束及本地清理 |

以下命令只读取状态，不开启麦克风、相机或模型，也不返回 key：

```sh
adb -s SERIAL shell settings get secure assist_structure_enabled
adb -s SERIAL shell settings get secure assist_screenshot_enabled
adb -s SERIAL shell content call --uri content://org.taku.tryon.api --method cosplayStatus
adb -s SERIAL shell content call --uri content://org.taku.tryon.api --method cosplayModelStatus
adb -s SERIAL shell content call --uri content://org.agentos.agent.home-assistant --method assistant/get
adb -s SERIAL shell content call --uri content://org.taku.tryon.api --method status
adb -s SERIAL shell content call --uri content://org.taku.tryon.api --method diagnostics
```

需要主动结束当前预览时，下面的 **stop 是写操作**，与 GUI Stop 使用同一控制器：

```sh
adb -s SERIAL shell content call --uri content://org.taku.tryon.api --method stop
adb -s SERIAL shell content call --uri content://org.taku.tryon.api --method status
adb -s SERIAL shell dumpsys media.camera
```

退出后核对 `active=false`、`cleanupPending=false`、窗口关闭，并确认 Try-On 的相机
客户端已释放。本地清理确认不代表服务商配额或计费会话已经回收。
若没有确认卡，先查屏幕上下文、角色开关及实际识别文本；若只看到相机且
`returnedFrames=0`，继续按 `diagnostics` 的阶段/失败码定位，不能记录成生成成功。
失败或清理未完成时停止本轮，不用反复 Retry 掩盖错误。

## 已有证据与待验收项

[历史角色真机记录](https://github.com/Taku-OS/taku-os/blob/e059d1846ed5a458782e4f21ecaa3668b26a83b5/docs/reviews/tiktok-character-main-2026-11-21/README.md)记录过真实语音
**HELP ME PUT ON THIS OUTFIT OF HIS** 后收到 155 帧模型结果。它证明该历史组件组合
曾跑通，不代表新候选 Native 97、配套 Home/系统组合已经重新验收。
Observer 30 的 LIVE 测试也不能代替角色入口测试。

后续独立诊断在 LIVE VTON 端点复现了上游 Decart 并发拒绝，仍未恢复；不能据此声称
本轮也已对角色 Lucy 2.6 复现了同一失败。本说明补充期间未操作 Pixel、未开启相机或
请求云端；当前角色 2.6 的可用性、新候选端到端及完整系统冷启动仍待独立验收。
角色和 LIVE 应分别记录设备/组件哈希、入口、返回帧、失败码及清理结果，继续保持
开发预览状态。

接口细节见 [Try-On Agent API 与角色交接](https://github.com/Taku-OS/taku-os/blob/e059d1846ed5a458782e4f21ecaa3668b26a83b5/system/tryon/README.md)。
