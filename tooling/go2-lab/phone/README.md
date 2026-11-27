# Pixel 本机版机器狗

2026-11-09 新版首页、功能入口及验收记录见 [Go2 Robot Panel](ROBOT-PANEL.md)。

新增的中文 Taku AI → 人体跟踪、距离变化与行进建议流程，见 [视觉跟踪使用与验收](VISUAL-FOLLOW.md)。这一路径只显示建议，保持真实视频来源，服装颜色筛选不在本轮范围内。

首页「Voice & Sounds」提供 27 段离线预设：21 段英语、3 段中文和 3 段音效。先选一项，再点「在机器狗上播放」；点击另一项只切换选择，不自动播放。顶部滑条松手后写入音量，页面显示设备回传值；底部可随时停止播放。音频时长、用途、作者、来源和许可均随列表提供，完整署名见 [预设音频](../audio-presets/README.md)。此页面与视觉跟踪共用同一个真实连接，允许音频播放不解除视觉模式的运动限制。

真实预设播放使用机器狗 AudioHub 文件播放器。首次播放会显示传输进度：按内容哈希命名并分块上传，等设备音频列表确认后设置单次播放、调用播放接口。之后复用设备中已存在的同一文件；不删除或覆盖用户原有录音。停止会作废正在上传的任务，并在已经发起播放时请求设备 Pause 回执。断连时不能保证远端停止确认，但音频均为短片段、使用非循环模式。旧实时 RTP 路径在这台 Go2 上虽发送了音频包，现场未听到声音，因此预设不再使用该路径；旧的自定义导入播放仍需独立验证。

音量显示 0–100%，对应 VUI 接口的整数 0–10。Go2 的音量写入确认早于实际生效，服务会等待后续读取一致后显示确认，超时不会冒充成功。2026-10-30 实机发现 WebRTC 网关将过大的请求 ID 截成 2147483647，因此音量 RPC 已改为循环使用正 int32，并保留严格的请求/回包匹配。实际回读已验证 20%、30%、50%；不能把先前页面的 40% 附近错误当作设备最大音量。

此分支将 Go2 桥接、Python WebRTC、音视频和模拟器放到 Pixel 本机运行。Android App 通过 Termux 的受权限保护的 RUN_COMMAND 接口启动服务。运行时不需要 Mac、Docker、USB 或 ADB 端口转发。

**当前验收限制：模拟自主导航在 Pixel 的 PRoot 环境中仍会触发 POSE_STALE / TIME_SKEW / CAMERA_STALE，尚未跑通完整目标到达。** 基础状态、摄像头、点云、音频导入、受限微动和停止分别验证。不能将“安装和基础迁移完成”理解为所有功能验收通过。

这是开发版迁移方案：仍使用 Termux + PRoot 的 Linux 用户态环境，并非原生 Android WebRTC 移植。原项目的真实自主导航和真实人物跟随未完成，保持原有限制。

2026-10-29 已完成 Pixel 9 与真实 Go2 通过 OPPO 热点连接：持续电量、姿态、960×540 摄像头约 7–8 fps、约 1.6 万点/帧的实时雷达。运行未使用 ADB reverse 或 Mac 桥接，ADB root 已关闭。此次实机验收没有发送运动指令，真实电机控制、长时间稳定性和自主任务不包含在验收范围内。

现场发现 SakuraCat 会接管局域网流量：TCP 探测可被 VPN 先行接受，因此“端口开放”不能单独证明狗在线。此次在 SakuraCat「设置 → 进阶选项 → 自定义路由规则」添加了仅目标狗 IP 的 `/32` 直连规则，重连 VPN 后握手成功；其他代理规则保留。设备 IP 变化时需同时更新 App 地址和这条路由。不要把此次目标 IP 当作所有用户的固定地址。

## 手机上使用

1. 打开「Go2」，在 My Device 首页点「Settings」。
2. 没有狗时选「离线模拟」，点「启动 / 连接」。回到状态页点击「连接模拟器」。所有数值、点云和画面均明确标记模拟来源。
3. 有狗时让 Pixel 和 Go2 加入同一热点或 Wi-Fi，选择「直连真实 Go2」，填写狗的当前 IP（从热点设备列表或宇树 App 获取），点「启动 / 连接」。App 会启动手机服务、检查信令端口、建立 WebRTC 并等到新的电量和姿态数据；不会自动运动。此版本需要填写 IP，不做自动发现，也不负责给狗配网。
4. 如果提示需要设备密钥，在连接设置填入本人宇树账号所绑定设备的 32 位十六进制 Key。它是设备专属密钥，不是热点密码或 ChatGPT 凭据。旧固件可留空；留空会沿用这个 IP 已保存的 Key。热点重新分配 IP 时需更新 IP，必要时重新填写对应密钥。
5. 首次实机验收先看电量、状态、摄像头和雷达。手动微动继续要求前台确认；真实自主导航保持禁用。热点需允许客户端互通，VPN 需允许局域网通信；若提示被占用，先断开宇树 App 或其他控制端。
6. 使用完在连接设置中「停止服务」。返回后台会请求停止运动，但网络断开时不能保证远端停止确认。重启手机后重新打开 App 启动服务即可，不自动后台连接狗。

Termux 为本机服务提供前台通知与运行环境，请勿在使用中结束其任务。运行期间持有唤醒锁；停止服务后释放。安装时仅为 Termux 添加电池优化白名单，没有关闭系统整体省电功能。

## App 内的 Taku AI

页面底部提供 Taku AI 指令输入框，输入框左侧手掌图标用于人工接管；接管后图标变为星光，点击可恢复 AI 指令。首次在「AI 设置」允许“机器狗”使用 AI，选择当前可用的系统文字模型。使用手机系统已有模型服务，App 不读取账号凭据、不另外配置 API Key。系统模型目录不包含所选模型时，必须先在系统设置修正；App 不自动替换全局模型。

可输入“查电量”“打开摄像头”“查看周围环境”“后退半米”“左转45度”“先查电量，再后退50厘米”“播放刚才导入的录音”“音量调到5”。模型理解口语和单位换算，输出有限 JSON 计划，手机服务独立校验并调用既有功能，显示实际回执与模拟/实机来源。支持状态、连接、断开、摄像头、雷达、全部主要页面切换、已导入录音播放与停止、音量，以及四方向短动作和按里程计反馈的移动/转向。没有图像理解、任意文本播报、真实自主导航、真实人物跟随、跳跃或原始运动 RPC。

一组计划最多 8 步、最多 3 个运动操作，总执行时限 105 秒。前进/后退支持 0.05–1 米，转向支持 5–180 度；未指定量时使用单次 0.7 秒短动作。需在 App 中一次确认完整计划和现场路径；控制器自动处理 normal 模式、原有冷却、受限速度、逐段停止回执，并读取 sport 位姿估算进度。按距离移动用 0.12 m/s，角度转向用 0.30 rad/s，结束后恢复用户原速度。两段之间保留 2 秒冷却，因此这是较慢的分段控制，不是连续行走。位置过期/跳变、方向错误、明显侧移、无进展、断连和接管都中断任务。不能把里程计结果当成外部标定精度，也不提供后退自动避障承诺；实机距离和角度效果需要现场验收。

人工接管先撤销旧任务令牌、请求停止，再确认机器人 normal 模式，成功后打开微动页。模式切换可能调整站姿。App 显示的 AI/人工控制归属与狗固件的 ai/normal 模式独立；重新启用 AI 指令不切回狗固件 ai，也不自动执行动作。无连接或回执失败会明确显示未确认。软件按钮不能代替硬件急停。

模型推理使用独立线程，人工接管不等待模型回复。任务有一次性标识、执行代次、前台心跳和总时限；迟到模型回复、重复确认、后台或失联的任务不能继续执行。服务每次启动默认接受 AI 指令，但不自动连接或运动。直接手动微动接口要求先人工接管。

接口为 `/api/assistant/state`（GET）和 `start / heartbeat / plan / confirm / cancel / mode / stop`（POST）。请求字段严格校验；参数化操作仅有 `move_distance {direction,distance_m}`、`turn_angle {direction,angle_deg}`、`set_volume {volume}`，不接受 shell、URL、代码或原始机器人 RPC。运行时沿用 loopback 与 Host/Origin 边界；这是本地开发版，尚未提供 Android 各应用间的强身份隔离，不应作为面向不可信已安装应用的生产权限边界。

## 实现与数据

- `127.0.0.1:18845`：原有 Go2 HTTP 接口和 Web UI。
- `127.0.0.1:18846`：独立地图服务。高德查询需要互联网，不决定机器人是否可在局域网直连。
- `GET /api/phone`：实际本机运行模式、架构与进程信息。
- `GET /api/phone/network`：仅探测所配置 IP 的 9991/8081 TCP 端口，端口可达不等于设备身份或会话验证成功。
- `POST /api/phone/key`：断开状态下保存设备 Key，沿用 Host/Origin 检查；只接收严格的 `{"key":"<32 hex>"}`，不在状态接口中返回 Key。保存于 rootfs 内 `/opt/taku-go2/out/go2-lab/device-keys/<IP>.key`，目录 700、文件 600，不进 Git/APK/Intent 参数。
- `POST /api/phone/shutdown`：沿用原 Host/Origin 检查，停止控制与服务。仅接受空 JSON。
- 运行环境和日志：Termux 私有 `~/taku-go2/`。根目录 `rootfs/`，日志 `runtime.log`。
- 录音与录制：rootfs 内 `/opt/taku-go2/out/go2-lab/`。
- 私人地图 Key：rootfs 内 `/root/Library/Application Support/TakuGo2/amap-web-service.key`，权限 600。不在 APK、Git 或导出的基础运行环境里。
- Taku AI 入口：`sh /data/local/agentos/go2-lab/go2 inspect`；技能位置沿用 `/data/local/agentos/home/.codex/skills/taku-go2/SKILL.md`。必须根据 `source` 区分模拟、实机和回放。

## 重建与安装

仅开发时需要 Mac/Linux、ARM64 Docker、Android SDK 与 JDK 17+。不要使用原 `install.sh` 给 Pixel 安装：它面向 Mac 桥接，会创建 ADB reverse。

1. 从 [Termux 官方发布页](https://github.com/termux/termux-app/releases/tag/v0.118.3) 安装 `v0.118.3` 的 GitHub ARM64 开发 APK，启动一次完成 bootstrap。测试设备是 4 KB 页大小的 Pixel 9；16 KB 设备需要另行验证。
2. 在 Termux 运行 `pkg install proot`。官方 GitHub 构建支持 `run-as com.termux`，安装脚本依赖这一开发能力。
3. 在仓库根目录构建基础环境：

```sh
docker build --platform linux/arm64 -t taku-go2-pixel:local \
  -f tooling/go2-lab/phone/Dockerfile tooling/go2-lab
docker create --name go2-pixel-export taku-go2-pixel:local
mkdir -p out/go2-pixel
docker export go2-pixel-export -o out/go2-pixel/rootfs.tar
gzip out/go2-pixel/rootfs.tar
docker rm go2-pixel-export
```

4. 指定准确设备序列号安装运行环境。首次使用 `--rootfs`；以后更新代码省略它，保留录音、地图配置等数据：

```sh
python3 tooling/go2-lab/phone/install-runtime.py \
  --adb /absolute/path/to/adb --serial DEVICE_SERIAL \
  --rootfs out/go2-pixel/rootfs.tar.gz
JAVA_HOME=/absolute/path/to/jdk bash tooling/go2-lab/build.sh
adb -s DEVICE_SERIAL install --no-streaming -r out/go2-lab/TakuGo2.apk
adb -s DEVICE_SERIAL shell pm grant org.taku.go2lab com.termux.permission.RUN_COMMAND
adb -s DEVICE_SERIAL shell dumpsys deviceidle whitelist +com.termux
```

更新代码前先在 App 停止服务。私人地图配置可用 `--personal-map-key /path/to/private/key` 单独迁入；不会打印 Key。安装脚本保留其他 Termux 配置。首次安装若中断，需先检查已解包文件，不能直接覆盖正在使用的 rootfs。

5. 若需要 Taku AI 工具，在已授权的 userdebug 手机上临时开启 ADB root，再运行 `install-ai-tools.py --adb ... --serial ...`。脚本拒绝覆盖既有 Go2 工具，且要求 SELinux Enforcing。完成后关闭 Root 身份的调试，验证技能被原生列表识别。

## 验证

从 App 启动新的离线模拟服务，尚未连接时运行：

```sh
python3 tooling/go2-lab/phone/verify-phone.py \
  --adb /absolute/path/to/adb --serial DEVICE_SERIAL --basic-only
```

请求通过手机自己的 loopback 执行，脚本检查不存在 ADB reverse，并在任何动作前要求 `source=simulation`。基础检查验证连接、视频 JPEG、雷达、录音导入、动作锁、短脉冲停止、地图准备和断开；退出时请求断开模拟器。省略 `--basic-only` 会额外执行导航到达验收，目前已知失败，退出码必须保持非零。

真实模式可加 `--real-check`，只检查未连接状态、服务与自主导航禁用条件，不连接或操作真实设备。新设备和不同固件仍需现场验证；本次真实连接结果见上文，雷达坐标的物理标定和停止效果尚未验收。

### 无实机的协议验收

`tests/helpers/go2_peer_fixture.py` 是独立测试进程，必须绑定测试宿主自己的 IP，不能指定狗的地址。`--signaling v3` 提供设备密钥加密握手，`legacy` 提供旧版 8081 握手；数据通道和视频使用真实 aiortc 传输，电量、姿态和视频内容都是测试数据。其控制 API 仅监听 loopback:18847，30 分钟后自动退出。

Pixel 验证时将此测试进程单独放在手机已有 PRoot 内运行，再让 App 连接手机自己的 WLAN IP。使用 `tests/helpers/verify_go2_phone_protocol.py --adb ... --serial ... [--signaling legacy]` 检查。脚本先校验目标 IP 就是手机自身，并确认测试进程身份，才测试短动作回执。v3 首次测试要求该 IP 没有保存过 Key，测试后移除这个测试 IP 的 Key、关闭 fixture、清空 App 内测试地址。不要把它作为后台功能安装。

该测试覆盖实际客户端的信令、加密、ICE/DTLS/SCTP、状态订阅、RTP 解码、受限动作回执与断线处理；**不覆盖两台设备通过热点互通、实际固件差异、真实雷达和电机停止效果**。`source=real` 在这里仅表示选择了真实驱动路径，报告必须同时注明对端是 fixture，不能作为真狗已连接的证据。

## 回滚

停止服务后可以卸载 `org.taku.go2lab`。AI 工具和技能在上面列出的独立目录内，可备份后删除；不修改 Taku 登录态或系统镜像。Termux 是单独安装的运行依赖，若其中还有其他工作请保留，不要直接清空其数据。

接口依据：[Termux RUN_COMMAND](https://github.com/termux/termux-app/wiki/RUN_COMMAND-Intent)、[Termux PRoot](https://github.com/termux/proot)、原项目锁定的 `unitree_webrtc_connect` 提交。当前升级只发生在隔离的 Pixel 分支，原 VM/机器狗工作树未改变。

## Navigation observation build

See [NAVIGATION.md](NAVIGATION.md) for the in-app AMap MCP integration, explicit phone/robot location separation, coordinate calibration, sensor-gated proposals, verification and remaining real-navigation work. This build does not send navigation motion commands.
