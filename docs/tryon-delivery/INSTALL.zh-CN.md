# 安装和首次使用

## 先选择对应的包

`verified-device/` 是记录中的 Pixel 9 开发组件组合；`source-build/` 是当前交付分支
重新编译的候选。先查交付包 `manifest.json` 的系统基线、签名、组件版本与验证状态，
再用随包校验工具核对 `SHA256SUMS`。任意原厂 Android 或旧 Taku OS 仅安装两个 APK，
不能保证顶部卡片、权限和整个 LIVE 流程可用。

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

## 第一次配置：Lucy

1. 打开桌面的 **Taku Try-On**（`org.taku.tryon`）。
2. 在本次使用的模式下，将 **Model provider** 选为 **Lucy**，点 **Save model settings**。
3. 在 **Provider key** 填自己的 fal API key，点 **Save encrypted key**。
4. 检查状态显示 **Lucy key: configured**。这表示已保存，不代替真实服务调用验证。
5. 点 **Allow camera** 授权相机和通知，再点 **Allow preview over other apps** 开启浮窗权限。

不需要把 key 发给开发者、写入源码或重新编译 APK。界面的 **Remove key** 可以移除
本应用保存的对应密钥。只改模型或保存 key 不会开启相机或模型会话。
每次明确开始或重试最多打开 60 秒会话，费用由用户自己的服务商账户承担。

## 第一次配置：LIVE 观察和识衣

1. 打开 **Live Try-On Suggestions**（`org.agentos.shopping`）。
2. 点 **Enable accessibility service**，在系统无障碍设置启用 **Taku Live Try-On Observer**。
3. 开启 **Try-on reminders in TikTok LIVE**，并通过 **Allow try-on reminders** 批准提示。
4. 点 **System AI permissions and models**，在 Taku AI 中允许该应用使用模型并配置可用的图片模型。
5. 自动标题分类和无商品卡时的自动图片检查有独立开关，按需要开启；它们默认关闭。

识衣使用 Taku AI 图片模型；生成换装视频使用 Taku Try-On 中的 fal key。
两者缺一时，分别检查识别失败或 Lucy 未配置，不重复写入 key 来修复所有错误。

## 完成一次试穿

进入受支持 TikTok 的服装 LIVE，保持手机解锁。接受提示中的 **Try on**，或在观察组件
使用其明确的 Try on 入口。识别结束后前摄小窗出现，等待实际返回的换装视频。
可拖动窗口、缩放、点右上角放大，点关闭结束。仅进入直播、看到提示或打开设置不会
自动开启 Lucy。点 Retry 会开始新的明确尝试；遇到会话正在释放时，等待关闭完成再试。

支持的 TikTok 包名为 `com.zhiliaoapp.musically`、`com.ss.android.ugc.trill`。
不同机型、TikTok 界面变更、完整 ROM/OTA 和重启恢复需各自验证。
本次冻结包的真机顶部控件触摸问题仍由另一任务修复；因此该包用于评审和复现，
不是已完成全部触摸验收的普通用户安装包。

## 开发者只读检查

```sh
adb -s SERIAL shell content call --uri content://org.taku.tryon.api --method status
adb -s SERIAL shell content call --uri content://org.agentos.shopping.api --method status
```

读取状态不开始模型，不返回 key。退出后应看到 `active=false`、`cleanupPending=false`；
失败码、识别阶段和小窗状态来自实际组件。不要通过反复自动 Retry 隐藏真实失败。
