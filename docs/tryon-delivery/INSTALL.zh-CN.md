# 安装和首次使用

本页包含 LIVE 试穿和 TikTok 视频角色换装的共用安装要求。角色功能的完整入口、
配置与独立验收步骤见[角色换装补充说明](CHARACTER.zh-CN.md)。该补充在线文档不改变
已冻结测试 ZIP 的源码版本或 APK 哈希，也不表示原 ZIP 已含此新增文件。

## 先选择对应的包

`verified-device/` 是记录中的 Pixel 9 开发组件组合；`source-build/` 是当前交付分支
重新编译的候选。先查交付包 `manifest.json` 的系统基线、签名、组件版本与验证状态，
再用随包校验工具核对 `SHA256SUMS`。任意原厂 Android 或旧 Taku OS 仅安装两个 APK，
不能保证顶部卡片、权限和整个 LIVE 流程可用。

本包记录的 Pixel 应用为 Native 96 / Observer 29；新源码候选为 Native 97 /
Observer 30（主平台与旧 Pixel 提示协议适配包分开）。不能只按版本号选择安装包。
候选 97 / 30 使用平台开发签名，与记录中 Native 96 / Observer 29 的开发签名不同，
不能在那台 Pixel 上直接用候选 APK 保数据升级。

### 系统集成方

正式源码路径：构建并签名配套 SystemUI、Assistant、Proactivity、Surfaces、Home，以及
`TakuShoppingObserver` 和 `TakuTryOn`。换装应用在 `image/taku.mk` 以
`TAKU_PREINSTALL_TRYON=true` 选择预装；本分支以同一开关加入 LIVE Observer。
签名资格与 APK 普通 UID 分开管理，不能用清除数据或卸载系统组件解决签名冲突。

`verified-device/apk/com.android.systemui.apk` 只作为该开发基线的兼容复现物保留。
不要把它按普通应用批量安装。该基线曾依赖运行时临时挂载，重启需要维护者核对后台
组件；本交付不提供触及用户数据的自动恢复脚本，也不把它当通用系统升级包。
普通使用者应由集成方提供已验证的完整系统或配套组件安装环境。

`verified-device/runtime/` 保留三个已核对哈希的运行代码文件（启动脚本、任务 broker
和音频 bridge），仅供维护者对照清单集成。JAR/脚本不是点击安装的 App；本包不会
自动替换它们、修改挂载或恢复用户数据。其目标路径、模式和已记录上下文在清单中。

### 已有匹配环境的开发测试人员

先确认设备标识、构建与已安装签名。仅更新匹配签名的应用，使用普通保数据升级：

```sh
# 将 SERIAL 和路径替换为清单中匹配的目标；以下命令会真实安装应用。
adb -s SERIAL install -r verified-device/apk/org.taku.tryon.apk
adb -s SERIAL install -r verified-device/apk/org.agentos.shopping.apk
```

其余依赖由系统维护者按清单安排。不要混用两组包；包名相同不意味着签名、协议和
系统环境相同。本交付的校验与构建命令都不会自动安装或开始模型会话。
Observer 升级后，若“主动性 → 观察者”显示“更新待确认”，打开该观察者并点
“确认更新并启用”；更新确认前不会产生新邀请。

顶部 Preparing / Live 胶囊的原触摸路径使用已有系统服务：
`org.agentos.assistant/org.agentos.surfaces.IslandViewHostService`。
服务必须启用、导出、由平台签名，应用还须获授
`org.agentos.surfaces.permission.HOST_ISLAND_VIEW`。不要绑定到 Surfaces 包内同名类。
未满足该依赖时使用普通应用浮窗；它无法在更高层的系统状态栏上接收触摸，不能据此
宣称顶部胶囊已经通过该环境的交互验收。宿主服务由配套系统提供，本应用不打包新宿主。

## 第一次配置：Lucy

1. 打开桌面的 **Taku Try-On**（`org.taku.tryon`）。
2. 在本次使用的模式下，将 **Model provider** 选为 **Lucy**，点 **Save model settings**。
3. 在 **Provider key** 填自己的 fal API key，点 **Save encrypted key**。
4. 检查状态显示 **Lucy key: configured**。这表示已保存，不代替真实服务调用验证。
5. 点 **Allow camera** 授权相机和通知，再点 **Allow preview over other apps** 开启浮窗权限。

不需要把 key 发给开发者、写入源码或重新编译 APK。界面的 **Remove key** 可以移除
本应用保存的对应密钥。只改模型或保存 key 不会开启相机或模型会话。
每次明确开始或重试最多打开 60 秒会话，费用由用户自己的服务商账户承担。

## 第一次配置与使用：TikTok 角色换装

在 **Taku Try-On** 将 **Mode** 选为 **Character**、provider 选 **Lucy** 并保存，
使用自己的 fal key。此模式使用 fal 的 Lucy 2.6，和 LIVE VTON 端点分开；
不要把 Decart 直连 key 填入当前 fal key 栏。
在 **TikTok character requests** 开启 **Enable voice character requests** 和
**Allow camera preview after Try on**；默认关闭/voice-only 不会出现相机模型预览。

打开 TikTok 普通角色视频，长按底部白条，等 **Taku is listening…** 后说完整请求，
例如 **Help me put on this outfit of his**，说完松手并等待确认，点 **Try on** 开始。
也可双击底部白条，输入 **帮我换成这个角色** 后发送，再确认 Try on。
该流程需要匹配的 Home、Assistant、Audio、SystemUI、Try-On 与系统截图授权；
文字入口不需要麦克风，角色入口不需要先开启 LIVE Observer 的自动识衣。
逐项配置、取消/退出及待验收范围见[角色换装补充说明](CHARACTER.zh-CN.md)。

## 第一次配置：LIVE 观察和识衣

1. 打开 **Live Try-On Suggestions**（`org.agentos.shopping`）。
2. 点 **Enable accessibility service**，在系统无障碍设置启用 **Taku Live Try-On Observer**。
3. 开启 **Try-on reminders in TikTok LIVE**，并通过 **Allow try-on reminders** 批准提示。
4. 点 **System AI permissions and models**，在 Taku AI 中允许该应用使用模型并配置可用的图片模型。
5. 自动标题分类和无商品卡时的自动图片检查有独立开关，按需要开启；它们默认关闭。
   启用自动标题分类时，还须在系统 AI 中配置可用的文字模型；图片模型不能代替文字模型。

识衣使用 Taku AI 图片模型；生成换装视频使用 Taku Try-On 中的 fal key。
两者缺一时，分别检查识别失败或 Lucy 未配置，不重复写入 key 来修复所有错误。

## 完成一次试穿

进入受支持 TikTok 的服装 LIVE，保持手机解锁。接受提示中的 **Try on**，或在观察组件
使用其明确的 Try on 入口。识别结束后前摄小窗出现，等待实际返回的换装视频。
可拖动窗口、缩放、点右上角放大，点关闭结束；收起后点 Preparing / Live 胶囊恢复
同一个小窗。正常会话不再额外生成 AI try-on / Expand / Stop 控制卡，失败提示保留。
仅进入直播、看到提示或打开设置不会
自动开启 Lucy。点 Retry 会开始新的明确尝试；遇到会话正在释放时，等待关闭完成再试。

支持的 TikTok 包名为 `com.zhiliaoapp.musically`、`com.ss.android.ugc.trill`。
不同机型、TikTok 界面变更、完整 ROM/OTA 和重启恢复需各自验证。
本次已合入 DetailActivity 直播入口兼容、原触摸宿主和预览暂停重复邀请的修复。
Lucy 的 INTERNAL_ERROR 仍未根治，当前链路未标为稳定通过；Native 96 的真实关闭
按钮与自然 60 秒退出仍需完成进程持续性回归，新候选 97 / 30 尚未完成新的真机验收。
该包用于评审和开发复现，不能作为已验收的普通用户发行包。

## 开发者只读检查

```sh
adb -s SERIAL shell content call --uri content://org.taku.tryon.api --method status
adb -s SERIAL shell content call --uri content://org.agentos.shopping.api --method status
# 需要核对实际宿主时，使用配套系统的服务诊断；不启动会话：
adb -s SERIAL shell dumpsys activity service org.agentos.assistant/org.agentos.surfaces.IslandViewHostService
```

读取状态不开始模型，不返回 key。退出后应看到 `active=false`、`cleanupPending=false`；
失败码、识别阶段和小窗状态来自实际组件。不要通过反复自动 Retry 隐藏真实失败。
