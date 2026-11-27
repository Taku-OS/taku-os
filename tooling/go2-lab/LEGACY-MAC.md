# Taku Phone 机器狗：状态、点云与微动

同事首次接入优先看 [完整开发包快速开始](distribution/QUICKSTART.md) 和 [开发集成说明](distribution/INTEGRATION.md)。发行包包含 APK、Mac 服务、源码、AI CLI／技能和测试，不含个人配置；在干净提交上运行 `python3 tooling/go2-lab/package-devkit.py` 生成 ZIP、独立 APK 与校验和。

「高德路线」页支持搜索／地图点选终点、地理位置和朝向标定、模拟路线跟随，操作时显示摄像头、高德位置和滚动雷达地图。真实高德路线可验证前 10 米或全程，局部控制器在合成场景绕障、跟随并停止；不代表真实地点的地形。只有 Go2 PRO 本体时，人工参考＋里程计提供会漂移的位置估计，尚未完成实机全局定位与导航验收。具体能力、操作步骤和结果见 [地理导航验收报告](GEO-NAVIGATION-VERIFICATION.md)。

地图使用单独的 Mac 服务（18846），个人 Web 服务 Key 通过 `bash tooling/go2-lab/start-maps.sh configure` 在本机配置，然后运行 `bash tooling/go2-lab/start-maps.sh`。查询不触发运动。原地图接入与权限边界见 [高德接入报告](AMAP-VERIFICATION.md)。

自动跟随的官方 UWB 配件要求、Mac 视觉检测实测、社区代码审查和下次开机步骤，见 [自动跟随调查与实验](FOLLOW-INVESTIGATION-2026-10-27.md)。当前应用尚未实现实机视觉跟随；只读诊断新增 `--follow`，不会开启跟随或发出运动。

原生避障的官方依据、PRO 接口限制、语音开启动作和下一次实机验证顺序，见 [2026-10-27 调查与执行方案](NATIVE-AVOIDANCE-INVESTIGATION-2026-10-27.md)。不能把某个服务的 `enabled=false` 或 `3203` 推导成整台 PRO 没有原生避障。

独立只读诊断：`python3 tooling/go2-lab/diagnose-readonly.py` 默认仅打印离线计划，不联网。显式 `--connect` 才连接；不启动现有桥接、不发送移动、停止、模式切换、雷达开关或避障开关。它严格匹配 RPC 的 ID、API、topic、返回类型和状态码，测试用 `python3 tests/go2-diagnostic.test.py`。真实设备兼容性待下一次开机验证。

独立开发应用 `org.taku.go2lab`，通过 Mac 本地桥接读取真实 Unitree Go2 状态。APK 支持连接、断开、雷达环境、手动微动、语音、摄像头和模拟自主任务；AI CLI 支持状态、连接、地理标定、高德路线及模拟导航。CLI 不提供实机运动或通用 RPC；手动微动仍走独立确认和控制互锁。

链路：Taku Phone → ADB reverse → Mac `127.0.0.1:18845` → Go2 WebRTC。本轮不是 Android 独立驱动，也不修改 Unitree 云端注册状态。普通 Android 应用只申请 INTERNET。Mac 与狗必须在可互访的局域网；机器狗 IP 可能随 DHCP 改变。

## 构建与启动

需要 uv、Python 3.12、JDK 17、Android SDK 36 和既有 Taku 开发 AVD。首次运行：

```sh
bash tooling/go2-lab/setup.sh
# JAVA_HOME 应指向真实 JDK，不使用 macOS /usr/bin/javac 占位启动器。
JAVA_HOME=/path/to/jdk17 bash tooling/go2-lab/build.sh
bash tooling/go2-lab/start.sh ROBOT_LAN_IP
```

`setup.sh` 将依赖装入仓库外 `~/Library/Caches/taku-go2/venv`，可用 `TAKU_GO2_CACHE` 覆盖。社区驱动固定至 `e0abc5780761539eff89a382a79319e5cf6ad1f4`，其余运行依赖固定在 requirements.txt；只安装本功能所需依赖，省略视频示例和音频播放工具依赖。不把虚拟环境、APK 或开发签名提交 Git。

在另一个终端安装（只向明确指定的现有开发 AVD）：

```sh
TAKU_SERIAL=emulator-5554 TAKU_AVD=YOUR_AVD_NAME bash tooling/go2-lab/install.sh --with-ai-tools
```

安装保留现有应用和账号，新增机器狗 APK、状态 CLI 和 `taku-go2` 技能。安装后应用自动打开，点“连接机器狗”才建立本地会话，运动仍需单独启用。重启模拟器后需恢复 `adb -s emulator-5554 reverse tcp:18845 tcp:18845`；服务没有开机自启。测试完成可在页面点“断开连接”，再在服务终端 Ctrl-C。

## 周围环境

在机器狗应用选择「周围环境」，点「开始查看」。Mac 接收并解码 `rt/utlidar/voxel_map_compressed`，以 Float32 XYZ 二进制传输该帧全部有效点，WebGL 一次绘制提交全部点，不再抽样到 3000 点。画面提供拖动旋转、键盘方向键、3D/俯视和复位，显示本帧点数、完整显示点数、收到帧数和最近接收时间。

「完整」指这个体素点云主题解码后的全部有限坐标，不代表传感器的全部原始激光回波。无效坐标计入 invalidCount；异常尺寸或范围会报错，不静默截断。显示通道只保留最新一帧，不积压旧帧；需要保存时在「避障」页显式开启录制。GPU 深度遮挡和投影重叠会使某些点在屏幕上不可见，但没有减少提交的点数。

「画面绘制」是 requestAnimationFrame 中 WebGL 绘制提交的频率；「点云更新」是页面上传新点云缓冲的频率。旋转视角可在两次新数据之间继续流畅绘制。诊断字段 transferMs 仅测 Mac HTTP 请求到页面收到响应的耗时，不是机器人采集到屏幕的总延迟。

二进制接口 `GET /api/lidar/frame?after=FRAME_KEY`：4 字节小端无符号整数表示 JSON 头长度，头以空格补齐至 4 字节边界，之后是连续的小端 Float32 XYZ。format 为 xyz-f32-le-v1。frameKey 使用服务实例标识加接收序号，不使用较粗的固件时间戳；after 与当前帧相同时只返回新鲜度元数据，避免重复传输和上传。同一帧不重复解码为 JavaScript 对象数组。

数据保留原始 `odom` 坐标，不假设原点是狗的位置；自动居中仅用于展示。没有机器人位姿配准、障碍物语义分类、路径规划或碰撞距离保证，空白区域不代表可通行。页面显示接收时间；固件时间戳较粗，不能当作精确采集时间。五秒没有新帧即标为过期并变暗。

开始查看仅额外发送两条精确白名单消息：关闭数据节流，以及 `rt/utlidar/switch = on`。这属于传感器采集设置，不是运动控制。暂停只取消点云订阅，不关闭机器狗自身雷达。切换回状态页仍保持采集；点暂停或断开连接结束接收。

## 手动微动

选择「微动」，现场确认狗已站稳、平地四周约一米空旷、有人在旁看护，再勾选页面确认并点「启用微动 · 60 秒」。每次点一个方向；前后以狗朝向为准。按住按钮不会连发，服务拒绝并发和冷却期请求，不排队。页面离开前台会请求停止并锁定。

- 前后速度幅值可选 0.08–0.20 m/s（步进 0.01），转向角速度幅值可选 0.15–0.40 rad/s（步进 0.05）；默认最低档。方向按钮决定正负，不支持组合方向。单次 0.70 秒，动作后至少冷却两秒。理论积分约 5.6–14 cm / 6–16°；不是精确步数、距离或角度承诺。2026-10-27 按用户要求扩大普通微动时长并增加可调速度；避障实验保持独立的 0.35 秒和原最低速度。新增速度尚未做实机位移或制动测量。
- 调速流程：停止并锁定 → 调整两个滑块 → 点“应用速度” → 确认现场并重新启用微动。未应用的选择不随轮询丢失，并阻止启用；只有服务端回读确认后才成为当前速度。速度只保留在当前 Mac 服务进程中，刷新页面可恢复当前值，服务重启回到最低档。断开狗时也能修改本地配置，设置过程不连接或操作狗。
- 开始前要求普通行走模式、连接打开、最近 0.5 秒内收到机身状态以及合理机身高度。高度检查不能替代现场站稳确认。每次动作前必须先收到 StopMove 成功响应。
- 服务按所选速度发一次 Move，随后由 Mac 定时发送 StopMove；一次动作中不调整速度，中断也会尝试停止。定时不是硬实时保证；网络断开、进程崩溃或事件循环阻塞时，软件停止可能延迟或无法送达。需要现场看护与可用的实体遥控器。按钮不是硬件急停。
- 「停止移动 / 锁定」撤销启用状态并取消当前短动作；不需要先启用。停止未确认时锁定后续动作，界面保留明确提示和重试停止入口。响应成功仅代表协议确认，实际停稳仍需现场观察。
- 连接时只查询当前运动模式，不自动切换。用户本次明确授权后，已由开发端调用固定的 `POST /api/motion/normal` 切到 normal；切换可能调整站姿，至少等待五秒才允许启用微动。页面本身没有隐式模式切换。

`POST /api/motion/arm|stop|forward|backward|left|right|normal` 均只接受空 JSON，不接受速度、持续时间或任意参数。`GET /api/motion/mode` 只读查询。单独的 `POST /api/motion/speed` 仅接受 `{"linearSpeed":0.08,"angularSpeed":0.15}` 两个数值字段；服务端验证范围、步进、有限数值与严格类型。未锁定、动作/停止/模式切换进行中、停止未确认、回放或避障控制占用时拒绝设置；成功设置不发机器人命令且保持锁定。运动发送白名单独立于感知白名单：Move(1008) 只接受当前选定速度形成的四个方向向量，且只在当前动作的同步发送窗口通过；范围内的其他速度也不会随意放行。StopMove(1003) 始终可请求；模式切换(1002) 只允许 normal 且只在显式切换窗口通过。所有其他运动 API 拒绝。

已验证的当前设备使用 normal 模式的 request 格式。MCF / AI 模式的运动没有开放。SDK 对 [Move 和 StopMove 的定义](https://github.com/unitreerobotics/unitree_sdk2_python/blob/master/unitree_sdk2py/go2/sport/sport_client.py) 与固定版本社区 normal 模式示例作为协议依据；不据此承诺所有固件兼容。

0.70 秒是 Taku 自己的单次按钮参数，不是 Unitree Go App 的官方默认值。官方 [PRO 参数表](https://www.unitree.com/cn/go2/)标注型号运动速度为 0–3.5 m/s；[伴随遥控器手册](https://marketing.unitree.com/article/zh/Go2/Companion_Remote_Control.html)标注伴随慢/快档上限 1.5/3.0 m/s，以及 L1 朝向调整 0.1 rad（约 5.7°）。这些不等于 App 摇杆的默认速度、死区或映射曲线；本次未找到适用于当前 App/固件的完整公开映射表。

本次幅度调整通过 `bash tooling/go2-lab/check-simulation.sh`，覆盖 0.70 秒结束停止、在新增后半段中断、数据失效与停止失败锁定；避障实验维持原时长。已重启处于断开状态的 Mac 服务，并在 Taku Phone WebView 核对中文参数与禁用按钮；服务保持 `connected=false`、`armed=false`、机器人出站消息为空。页面核对结果和截图位于 ignored 的 `out/go2-lab/manual-pulse-070-ui.json` / `.png`。未进行实机运动测试。

可调速度后续验证：同一检查命令通过 76 项 Python 测试、14 项 UI 逻辑测试及 JavaScript 语法检查，新增范围/步进/类型/跨来源拒绝、未锁定拒绝、配置回读失败、草稿保留及精确运动白名单检查。Taku Phone 实际 WebView 完成最高档 0.20/0.40、中间档 0.12/0.25、恢复最低档 0.08/0.15 的三次本地配置；核对中英显示、键盘步进、320/768/1024/1440 宽度无横向溢出。证据在 ignored 的 `out/go2-lab/speed-range-ui.json`、`speed-range-maximum.png` 和 `speed-range-default.png`。检查结束机器狗未连接、未启用微动、机器人出站消息为空。

## 离线避障实验

没有机器狗时，可在 Mac 和同一台 Taku Phone 开发模拟器运行：

```sh
# 与实机 bridge.py 使用同一回环端口；先退出原桥接服务。
bash tooling/go2-lab/start-simulation.sh
# 另一个终端，已有开发 AVD 和机器狗 APK：
"$HOME/Library/Android/sdk/platform-tools/adb" -s emulator-5554 reverse tcp:18845 tcp:18845
"$HOME/Library/Android/sdk/platform-tools/adb" -s emulator-5554 shell am start -n org.taku.go2lab/.Go2Activity
```

Mac 浏览器打开 `http://127.0.0.1:18845/`；手机点连接后进入「避障」。顶部必须显示「离线模拟 · 未连接真实机器狗」。选择场景，点「应用场景」→「开启并验证」→勾选确认→「启用本次微动」→选择方向。每次动作结束重新锁定。模拟入口不接受机器人地址、不加载真实驱动，并拒绝出站连接；服务启动失败时不会退回实机。

提供 18 个场景，包括四方向障碍、运动中出现障碍、模拟机载拦截、点云/位姿过期、空点云、坐标系不一致、开启失败、回读不一致、查询超时、停止未确认、模式改变和断连。停止未确认时需要先移除模拟故障，再显式重试停止，不能通过换场景自动解锁。

平地模型将世界点云转换到机器人局部坐标，检查四方向短动作的机身扫过范围；全点参与计算，俯视图最多抽样 1200 个点作展示。「周围环境」的 WebGL 全点显示保持不变。模拟设置：点云 10 Hz、点云有效期 0.4 秒、位姿有效期 0.3 秒、接收时间差不超过 0.2 秒、执行中约每 25 ms 重查、操作心跳超时 0.8 秒。这些是软件实验参数，不能当作实机数据频率或安全距离。0.35 秒是动作脉冲长度，不是采样周期。

当前判断只表示模型范围中是否有观测点；没有观测点不证明真实空间安全。没有实现玻璃、台阶/悬崖、全局路径规划、真实步态或制动模型。模拟机载拦截是可控测试替身，不是宇树官方避障算法。

原生适配器独立使用 `rt/api/obstacles_avoid/request`：查询 1002、设置 1001、Move 1003、API 命令来源 1004。设置后严格回读；原生通道失败不会回退普通 Move。普通微动与避障控制互斥，切后台、心跳失效、数据失效会撤销控制；软件停止不等同硬件急停。

**实机原生避障写入当前没有开放开关。** 实机连接可查询；位姿解析保留 position、四元数、yaw 和可用源时间，但默认坐标未配准，不能直接把 sport 位置当成 LiDAR odom。需要机器狗回来后验证固件支持、模式、坐标约定和停止行为，再决定是否开放。已有普通手动微动仍按原范围工作，不因此获得避障承诺。

「录制与回放」保存完整解码 Float32 点云、位姿、状态、相对接收时间、可用源时间和动作/RPC 证据。每次录制上限 64 MiB，写入拥塞/异常显式报错。文件在仓库 ignored 的 `out/go2-lab/recordings/`；本版界面回放当前服务进程最近完成的一次录制，重启后文件保留但不自动恢复选择。回放只读取传感器事件，断开会话并禁止所有运动入口；不会执行日志中的动作。

复现测试：

```sh
bash tooling/go2-lab/check-simulation.sh
# 运行模拟服务并打开指定 AVD 的应用后：
adb -s emulator-5554 shell pidof org.taku.go2lab
adb -s emulator-5554 forward tcp:19245 localabstract:webview_devtools_remote_<上一步PID>
node tooling/go2-lab/test-phone-simulation.mjs
```

手机测试绑定 `emulator-5554`，可以用 `ADB` 环境变量指定 adb 路径；启动前拒绝非模拟服务。结果及截图写入 `out/go2-lab/phone-simulation/`。本轮完成范围与实机待验收项见 [SIMULATION-VERIFICATION.md](SIMULATION-VERIFICATION.md)。

实际触控、来源提示、场景入口和服务重启体验的后续复验见 [EXPERIENCE-VERIFICATION.md](EXPERIENCE-VERIFICATION.md)。

## 开发模拟器的 WebGL 配置

本机 API36 AVD 的默认图形配置实测会触发 WebView 的 `WebGL1 blocklisted`。已将该 AVD 的 `hw.gpu.enabled` 改为 `yes`、`hw.gpu.mode` 改为 `host`，并用 `-gpu host` 重启既有模拟器；没有擦除用户数据。实测 WebGL renderer 为 Apple M4 Pro 的 Android Emulator OpenGL ES Translator。

仅切换 GPU 不足以解除当前 WebView 的兼容性限制。本机 userdebug 模拟器还使用 `/data/local/tmp/webview-command-line`，内容为 `_ --ignore-gpu-blocklist`。这是开发环境的图形兼容性覆盖，会影响该模拟器后续启动的 WebView 应用；没有加入 APK、默认安装脚本或生产配置。其他机器应先实测 WebGL 支持，不应默认套用。设置方法来自 [Chromium WebView 开发文档](https://chromium.googlesource.com/chromium/src/+/HEAD/android_webview/docs/commandline-flags.md)。

回退本次本地配置：删除上述命令行文件，将 AVD 两项恢复为 `hw.gpu.enabled=no` / `hw.gpu.mode=auto`，退出模拟器后以原参数重启。原 AVD 配置备份在 ignored `out/go2-lab/avd-config-before-webgl.ini`。该组合的跨应用长期兼容性尚未验证；当前点云应用已完成绘制、交互和上下文恢复实测。

## AI 读取

新对话可对 Taku 说：“使用 taku-go2 技能，查看机器狗的电量，只读状态。”

```sh
adb -s emulator-5554 shell sh /data/local/agentos/go2-lab/go2 inspect
adb -s emulator-5554 shell sh /data/local/agentos/go2-lab/go2 connect
adb -s emulator-5554 shell sh /data/local/agentos/go2-lab/go2 disconnect
```

`connect` 返回 connecting 不代表完成；必须继续 inspect 到 `connected=true`，且对应状态 `fresh=true`。超过五秒未更新的读数保留为历史值，不能声称是当前电量。连接中断不自动重连；用户明确连接再试。

## 边界

- `GET /api/state`、`GET /api/lidar`（元数据）、`GET /api/lidar/frame`（二进制）；`POST /api/connect`、`/api/disconnect`、`/api/lidar/start`、`/api/lidar/stop` 只接受空 JSON。微动接口见上节；不接受客户端指定目标 IP 或任意消息内容。
- 感知白名单允许握手、心跳、网络状态、模式查询、点云采集设置和三个数据主题订阅；新增运动白名单的范围见微动章节。限制由 Mac 服务执行，不靠 UI 隐藏按钮。
- 仅监听回环，检查 Host/Origin，无 CORS，WebView 限制到固定回环地址。不是多用户生产鉴权；同一 Mac 或开发模拟器里的本地程序可访问。禁止将服务暴露到公网。
- 本机启动参数决定目标 IP；本功能未实现机器狗密码配置、每设备密钥提取或云端绑定。若固件要求额外认证，显示实际失败类型，不能声称已连接。
- 桥接采用社区驱动，不代表官方 SDK 支持 PRO 全量开发；未来固件兼容性需重新验证。
- APK 使用公开 AOSP 开发签名，不能作为生产发行包。模型技能已安装不代表每次对话一定自动加载；可明确调用技能或上述 CLI。

## 验证

```sh
python3 tests/go2-readonly.test.py
python3 tests/go2-http.test.py
python3 tests/go2-motion.test.py
node --check tooling/go2-lab/web/app.js
node --check tooling/go2-lab/web/lidar.js
node --check tooling/go2-lab/web/point-renderer.js
node --check tooling/go2-lab/web/motion.js
# setup.sh 准备的环境包含点云处理依赖 numpy
"$HOME/Library/Caches/taku-go2/venv/bin/python" tests/go2-lidar.test.py
JAVA_HOME=/path/to/jdk17 bash tooling/go2-lab/build.sh
```

离线测试覆盖运动消息拒绝、HTTP 非白名单路由/请求拒绝、跨来源请求拒绝、状态过期和无效电量。连接和状态回读须在真实狗与指定 AVD 上另外验证。详细本轮结果见 [VERIFICATION.md](VERIFICATION.md)。

上游：[unitree_webrtc_connect](https://github.com/legion1581/unitree_webrtc_connect)。

## 语音：导入录音、在狗上播放、调节音量

“语音”页可导入 WAV、MP3、M4A、FLAC、Ogg 音频，每次最多 10 MB / 60 秒。
当前保存一段录音到本机 ignored `out/go2-lab/audio/`；新录音替换旧录音，不会上传到云端或永久写进狗的存储。
Taku Phone 使用 Android 系统文件选择器，不请求麦克风或全盘存储权限。也可以在 Mac 浏览器打开 `http://127.0.0.1:18845/` 导入本机文件，Phone 会显示同一段录音。

操作：
1. 打开“语音”，点击“选择录音文件”；断开狗时也可先导入。
2. 在“状态”连接狗，再返回“语音”，点击“读取狗的音量”。
3. 需要调音量时，拖动 0–10 档滑块，再点“应用音量”。必须读回成功才显示已确认值，拖动本身不下发。
4. 点击“在狗上播放”，通过当前 WebRTC 连接发送音频。点击“停止播放”结束发送；断线也清理播放器。狗端可能还有短暂缓冲。

使用现有发送音频 transceiver，复用时间戳连续的包装 track，避免文件 EOF 令 aiortc 的 RTP sender 永久退出；无需打开或监听狗的麦克风。
参考社区源代码：[MP3 播放示例](https://github.com/legion1581/unitree_webrtc_connect/blob/master/examples/go2/audio/mp3_player/play_mp3.py)、[VUI 音量读写示例](https://github.com/legion1581/unitree_webrtc_connect/blob/master/examples/go2/data_channel/vui/vui.py)。
VUI API 1004 读取、1003 写入；精确匹配请求 ID、API、回复主题、返回码，并在写入后读回。音频接口没有扩大运动或只读诊断的白名单。

验证：`bash tooling/go2-lab/check-simulation.sh` 包含音频格式/时长限制、错误输入、音量回读和 RPC 关联、断线清理、两次本地 WebRTC 播放。
APK 已构建并安装，实际 Android 文件选择器导入测试通过。模拟服务下运行 `node tooling/go2-lab/test-phone-audio.mjs` 验证 8 个 UI 流程（先通过文件选择器导入名为 `taku-audio-test.wav` 的 3 秒测试文件，配置与其他 Phone 测试相同的 CDP 19245 端口）。
本机测试结果和截图在 `out/go2-lab/audio-verification/`。

**仍待实机确认**：这只 PRO 固件是否接受 VUI 音量接口、实际扬声器出声、停止延迟、环境中合适的音量。
模拟播放不会出声，本地 WebRTC 测试只在两个本机 peer 间验证非静音音频帧；两者都不证明真实狗已播放成功。

## 摄像头：独立页面与操作时固定画面

在“状态”连接后，打开“摄像头”页点击“开启画面”。切换到“微动”或“避障”时，画面缩小并固定在顶部；滚动到方向按钮、修改速度或操作音频时仍可看到画面。点击“关闭画面”停止请求视频；断开连接也清空画面，不会自动重开。未连接时可以查看页面，但不能启动真实视频。

采用已建立的 Go2 WebRTC 视频接收通道，显式发送社区协议 `{"type":"vid","topic":"","data":"on"}` / `off`。协议来自当前固定版本驱动的 `WebRTCDataChannel.switchVideoChannel`；参见 [上游视频接收实现](https://github.com/legion1581/unitree_webrtc_connect/blob/e0abc5780761539eff89a382a79319e5cf6ad1f4/unitree_webrtc_connect/webrtc_video.py)。仅在摄像头操作的同步发送窗口放行一个完全匹配的消息，不改变运动、音频或只读诊断白名单。

Mac 持续排空接收帧队列，最多每秒编码 12 帧 JPEG，最长边界 960×720、保持比例。只保留最新一张，后台编码忙时直接跳过中间帧，不积压视频，也不在运动事件循环中编码。Phone 按需取最新帧并解码到 Canvas；同一帧不重复传输。视频不落盘、不进入点云录制、不上传云端，也不开启麦克风。

界面显示分辨率、更新率与从 Mac 收帧开始估算的帧龄。后端/前端都以 1 秒判定过期，过期或失联时隐藏旧画面。帧龄不包含机器狗采集、编码和到 Mac 的传输耗时，因此**不能作为真实端到端延迟**；摄像头也不能替代现场观察或避障验证。摄像头异常不会自动启用运动或放宽原有动作限制。

离线验证：
- `bash tooling/go2-lab/check-simulation.sh` 包含摄像头 JPEG、限流、只保留最新帧、开关白名单、断线/过期清除、关闭过程中编码竞态和 HTTP 边界测试。
- 启动 `simulator.py`，按前述方式转发 WebView CDP 到 19245，运行 `node tooling/go2-lab/test-phone-camera.mjs`。脚本在每次操作前拒绝真实来源，使用真实 WebView 点击模拟微动四个方向，同时验证画面刷新、固定布局、请求卡住后的过期处理、关闭/重启/断线，以及双语和 320/768/1024/1440 宽度。
- 本机实测模拟画面约 9.5 fps，11 个 Phone 流程通过；证据位于 ignored `out/go2-lab/camera-verification/`，其中画面标记为“模拟画面 · 非真实摄像头”。

待实机确认：这只 PRO 的视频开关响应、画面方向/视野、实际帧率、热点带宽下与点云及控制同时工作的端到端延迟。开发验证期间未连接真实机器狗，也未发送真实运动命令。

## 自主任务：命名地点与视觉目标（模拟原型）

在隔离的 `simulator.py` 服务下进入「自主任务 → 准备模拟房间」。点击地图的浅绿色区域，填写名称后保存地点；“前往这个地点”会规划路线、以最高 0.12 m/s / 0.3 rad/s 更新模拟运动，绕过纸箱，到达半径 0.14 m 后停止。地图、路线、位置箭头和固定摄像头同页显示。位置未更新、路被封闭、数据过期或用户停止时终止任务。地点与本次地图绑定，切换场景会清除地点。

安装更新后的 APK、CLI 和 `taku-go2` 技能后，Taku 的新任务可说：

- “让模拟狗去接待点。”——先在应用地图保存“接待点”，AI 根据当前 `mapId` 和地点名提交任务。
- “实际看狗的摄像头，去门口附近并停下。”——AI 使用 `nav-observe` 导出的图片，通过 `view_image` 确认目标的地面接触点，向同一个控制器提交视觉目标；执行中继续观察图像和任务状态。

图片可交给当前配置的模型服务分析；普通摄像头预览仍只在本地显示。短期观察图在 Mac 内存保留至多4张、有效30秒，Android 仅覆盖保存一张 `observation.jpg`。服务不自动连接实机，也不替换用户选择的模型。

规划采用已观测平地的 0.1 m 栅格、机身空间膨胀、A* 和短距离路径跟随。模型负责目标语义与监督，控制器负责约20Hz的状态检查/运动更新以及约2.5Hz重规划；实际频率受执行耗时影响。这些是控制循环目标，不是实机端到端延迟保证。它不是 SLAM、ROS Nav2 或宇树固件算法的实现。

**实机自主导航保持关闭**。真实服务的目标、转向扫描、模拟初始化请求均返回 `REAL_NAVIGATION_DISABLED`。现有手动微动、摄像头和音频入口保留。不能把 `simulated` 标记或消息白名单改成可写就宣称接入成功；待完成的定位、标定与实机验收见 [NAVIGATION-VERIFICATION.md](NAVIGATION-VERIFICATION.md)。

验证：`bash tooling/go2-lab/check-simulation.sh`；实际 Phone WebView 使用前文 CDP 转发后运行 `node tooling/go2-lab/test-phone-navigation.mjs`（拒绝真实来源）。后者验证地图点击保存、命名目标绕障到达、摄像头持续更新、用户停止、关闭摄像头停止、双语/布局、HTTP 请求边界。证据保存在 ignored `out/go2-lab/navigation-verification/`。
