# Taku Go2 开发包 · 0.1.0-preview.1

供 Taku Phone 与 App 开发同事使用的完整机器狗应用组件包。只向有权限的私有仓库成员分发，沿用 [项目内部许可](../../../LICENSE)。这是开发预览版，保留目前的实机功能和模拟功能边界。

## 下载什么

- **推荐整个 `taku-go2-devkit-0.1.0-preview.1.zip`**：预编译 APK、Android AI CLI／技能、Mac Python 服务、完整 App/Web 源码、测试、接口与验证文档、构建／安装脚本。
- 单独 APK 适合已经配置好 Mac 服务的同事；它不是脱离 Mac 独立工作的狗控制器。
- `manifest.json` 记录版本、源提交和组件哈希；`SHA256SUMS` 可核验文件。没有高德 Key、模型账号、Wi-Fi 密码、个人录音、录像、设备日志或虚拟机镜像。
- 外部依赖通过固定版本清单首次联网安装，ZIP 不是完全离线运行时。Taku OS／Codex 运行时沿用同事自己的开发环境。

## 先体验模拟，不需要狗

已验证 Mac Apple Silicon + Android API 36 的 Taku 开发模拟器。Mac 需要 Python 3、Git、[uv](https://docs.astral.sh/uv/getting-started/installation/)、Android SDK platform-tools；运行逻辑测试还需要 Node 22+。使用预编译 APK 不需要 JDK 或 Android build-tools。其他平台尚未验收。

解压后在开发包根目录运行：

```sh
bash go2 setup
bash go2 doctor
# 一个终端保持模拟服务运行（默认不会连接真狗）：
bash go2 simulate
```

另开终端安装到你自己的开发模拟器。`adb devices` 查看序列号，`adb -s emulator-5554 emu avd name` 查看名称；替换示例中的名称，安装器会严格匹配。

```sh
bash go2 install --serial emulator-5554 --avd YOUR_AVD_NAME --with-ai-tools
```

`--with-ai-tools` 只支持可 `adb root` 的 userdebug 开发模拟器，保持 SELinux Enforcing；安装 CLI 和 `taku-go2` 技能，不替换 Taku Home、不安装模型账号、不刷系统。只看 App 可以省略该参数。普通 API 36+ Android 可只安装 APK 并通过 USB ADB reverse 访问 Mac，但物理手机安装流程尚未实测。

打开 App 后按页面提示连接模拟服务，可看状态、完整体素点云、四方向微动、语音、摄像头，以及室内／视觉模拟任务。顶部明确显示「离线模拟」。原有手动微动需要现场确认式启用，不会在安装或启动时自动行走。

## 高德地图和路线模拟

```sh
# 使用每位同事自己的高德 Web 服务 Key；输入不回显。
bash go2 maps-configure
# 另一个终端保持运行：
bash go2 maps
```

进入「高德路线」→ 搜索、选择起终点／在图上选终点 → 规划 → 载入模拟路线 → 开始模拟跟随。默认验证前 10 米；全程模拟按低速真实耗时执行。纸箱、雷达和摄像头是合成场景，不代表高德地点实际路况。

## 连接自己的 Go2

先退出模拟服务，确认 Mac 与狗在可互访的同一网络，用狗的 **IP 地址**启动（MAC 地址不行）：

```sh
bash go2 real YOUR_ROBOT_LAN_IP
```

启动服务本身不连接或移动狗；App 点连接后才建立 WebRTC 会话。AI CLI 没有实机运动入口。手动微动需页面启用；使用者在旁看护，实体遥控器随时可停止。设备固件差异需逐台验证。

| 功能 | 交付状态 |
| --- | --- |
| 状态、电量、完整体素点云 | 实机接入代码与既有 Go2 PRO 记录，模拟可用 |
| 前后左右、可调低速、停止锁定 | 实机手动入口已实现，新的速度档仍需测量实际位移 |
| 自录音频播放、音量、实时摄像头 | 接入已实现并有模拟／接口验证，逐设备核对固件行为 |
| 高德搜索、地图点选、路线预览 | 真实高德 API，需自己的 Key |
| 命名地点、视觉目标、地图路线跟随 | 模拟可执行；不宣称实机自主导航已完成 |
| PRO 原生避障、绝对定位、雷达配准 | 诊断／标定和模拟已实现，实机验收未完成，自动运动仍受禁用边界约束 |

## 开发入口

- [完整功能与操作](../README.md)
- [同事接入与 API 索引](INTEGRATION.md)
- [地理导航验收](../GEO-NAVIGATION-VERIFICATION.md)
- [第三方依赖说明](THIRD-PARTY.md)
- `tooling/go2-lab/web/`：操作界面；`src/org/taku/go2lab/`：Android 壳与 CLI；同目录 Python：Mac 服务和模拟器。

修改 Web/Python 后重启对应 Mac 服务并刷新 App；Android 壳改动才需要重新构建安装。构建需 JDK 17+、Android platform 36 和 build-tools 36.0.0：`JAVA_HOME=/path/to/jdk bash go2 build`。APK 用公开 AOSP 测试证书签名且允许 WebView 调试，只用于开发环境，不是生产签名。

运行 `bash go2 verify` 做离线回归。测试输出、录音和回放保存在解压目录的 `out/`，不要加入 Git。Key 保存在 Mac 私有目录 `~/Library/Application Support/TakuGo2/`，不要打包或提交。

## 更新、恢复和卸载

每个 ZIP 对应固定源提交；更新时使用新包的 Mac 服务和 APK／CLI 整套安装，避免版本混用。停止任务并关闭旧服务，再运行新目录服务。恢复旧版同样使用整套旧包；Android 若拒绝版本降级，不强制卸载用户数据，重新构建旧源码时提升开发 versionCode 后安装。卸载 App 用 `adb -s SERIAL uninstall org.taku.go2lab`；先保留需要的 `out/` 录音和记录。卸载不会删除 Mac 私有 Key。

常见问题：页面打不开先检查服务和 `adb reverse --list`；18845 被占用时不要再启动另一个 bridge；没有地图只影响高德功能；看到模拟标签时不能当作真狗。`doctor` 只检查本机条件，不证明狗已连接。
