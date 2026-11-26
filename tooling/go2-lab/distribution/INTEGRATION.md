# Taku Phone / App 同事接入说明

这是一组独立应用组件，不是新 ROM，也不是 App Kit 自由生成应用。保留 `org.taku.go2lab` 包名、端口和 API 即可与现有 Taku 开发环境共存。

## 代码分工

| 模块 | 路径（相对包根目录） | 职责 |
| --- | --- | --- |
| Android App | `tooling/go2-lab/src/org/taku/go2lab/Go2Activity.java` | WebView、音频文件选择、导航白名单、系统 inset |
| 操作 UI | `tooling/go2-lab/web/` | 状态、点云、微动、避障实验、音频、视频、自主任务、高德页；中英文案在现有 messages 字典 |
| Mac 服务 | `bridge.py`、`protocol.py`、`motion.py` | WebRTC、严格协议白名单、控制租约及停止逻辑 |
| 音视频 | `camera.py`、`audio_control.py` | 最新帧视频、有限大小录音导入、播放和音量 |
| 雷达／导航 | `lidar.py`、`perception.py`、`navigation*.py`、`geo_*.py` | 点云、位姿、栅格、局部路径、地理参考 |
| 离线模式 | `simulator.py`、`recording.py`、`offline_audit.py` | 合成数据、录制回放、禁止实机出站 |
| 高德服务 | `amap_service.py` | 独立 18846 只读地图代理；个人 Key 保留在 Mac |
| 手机内 AI | `Client.java`、`go2`、`skill/taku-go2/SKILL.md` | 同一个本地 API 的有限工具；不开放实机移动 CLI |

Python 文件均在 `tooling/go2-lab/`。不把 WebRTC 密钥、模型账号或高德 Key 放进 APK。不能删掉来源检查、白名单或实机禁用开关来宣称支持了避障／自动导航。

## 运行链路

`App / 手机内 CLI → Android 127.0.0.1 → adb reverse → Mac 127.0.0.1:18845 → WebRTC → Go2`

模拟时链路止于 SimBridge。高德查询去独立的 18846 服务，模拟器自身仍禁止外连。App 壳没有完整 HTML 资源副本，页面由 Mac 服务提供，所以交付必须包含 APK 和服务整套组件。

两个 HTTP 服务只面向可信开发机的 loopback。Host/Origin 和类型校验不是互联网身份认证；不能直接开放端口到公网，也不能当作 Taku 服务器授权边界。公司服务密钥应放在有认证授权的服务端，当前个人开发配置不能替代它。

## HTTP 接口索引

GET 返回只读状态；JSON 写入用 `Content-Type: application/json`。服务端按精确字段和来源拒绝不允许的操作，客户端禁用按钮不构成授权。

| 接口 | 用途／约束 |
| --- | --- |
| GET `/api/state` | source、连接、电量、遥测新鲜度 |
| POST `/api/connect`、`/api/disconnect` | 空对象 `{}`，不隐式启用运动 |
| GET `/api/lidar`、`/api/lidar/frame?after=KEY` | 元数据／完整解码 Float32 点云；二进制协议见 README |
| POST `/api/lidar/start`、`stop` | 开始采集／取消订阅 |
| GET `/api/motion`；POST `/api/motion/arm`、`stop`、四方向 | 独立短动作启用、冷却和停止锁；空对象 |
| POST `/api/motion/speed` | `{linearSpeed,angularSpeed}`，范围与步进严格校验，仅锁定时可改 |
| GET `/api/lab` | 避障、模拟、录制回放状态 |
| POST `/api/avoidance/query`、`enable`、`disable`、`arm`、四方向、`heartbeat`、`stop` | 不代表所有 PRO 固件都支持；实机写入边界以服务端为准 |
| GET `/api/audio`；POST `/api/audio/import?name=NAME` | 上传音频 bytes（octet-stream），最多 10 MiB／60 s，不是 JSON base64 |
| POST `/api/audio/play`、`stop`、`refresh`、`volume` | 音量用 `{volume}`；其余空对象，协议回读确认 |
| GET `/api/camera`、`/api/camera/frame?after=KEY` | 最新帧；按返回封包解码，参考 `web/camera.js` |
| POST `/api/camera/start`、`stop` | 显式视频开关，不开启麦克风 |
| GET `/api/navigation` | mapId、source、任务状态、readiness、geo |
| POST `/api/navigation/prepare`、`place`、`goal`、`observe`、`visual`、`scan`、`stop` | 模拟室内任务；参考技能和 `valid_request` |
| POST `/api/navigation/geo-calibrate` | `{mapId,longitude,latitude,heading}`，GCJ-02，北 0°／东 90°；不解锁实机 |
| POST `/api/navigation/geo-prepare` | `{points,name,scope}`，scope 为 first10m/full；仅模拟 |
| POST `/api/navigation/geo-start`、`geo-heartbeat` | `{mapId,routeId}`；心跳失效 3 s 停止 |
| GET 18846 `/status`；POST `/search`、`/route` | 搜索 `{keyword,city}`；路线 `{origin,destination}`，坐标是 `"经度,纬度"` |
| GET 18846 `/route/<geometryId>` | 读取最近四条预览之一，无上游请求；用于 AI 和 UI 同步 |

响应失败不仅看 HTTP 状态，还要检查 `error/requestError`，任务结束检查 `active=false` 和 `stopConfirmed=true`。只收到 command ack 不等于现场实际停稳。连接变化和重置会改变 mapId，不能重用旧目标或视觉观察 ID。

## AI 接入

安装器的 `--with-ai-tools` 把 jar、CLI 放入 `/data/local/agentos/go2-lab/`，技能放入 `/data/local/agentos/home/.codex/skills/taku-go2/`。它假定已有正常的 Taku／Codex 任务运行时和模型账号，不复制开发者认证文件。

在新任务中使用 `sh /data/local/agentos/go2-lab/go2 inspect`、`geo-state` 等工具。移动能力只在模拟任务控制器开放。用户一句目的地指令仍需要可靠的目标定位和监督；视频语义、实机定位和真实避障没有因安装包而自动验证。

## 构建、验收和发布

在仓库干净提交上运行：

```sh
bash tooling/go2-lab/setup.sh
bash tooling/go2-lab/check-simulation.sh
# JDK 17+ / SDK platform 36 / build-tools 36.0.0
python3 tooling/go2-lab/package-devkit.py
python3 tooling/go2-lab/package-devkit.py --verify out/go2-releases/taku-go2-devkit-0.1.0-preview.1.zip
```

打包始终重建 APK，核对 manifest 与 Android 版本一致，按 Git 跟踪白名单取源码；没有把工作目录的 out、.env、个人数据复制进去。ZIP 含逐文件哈希，额外 SHA256SUMS 覆盖 ZIP 和单独 APK。重新打包用新输出目录；发布更新提升 manifest 与 Android versionName/versionCode。

PR 的 `go2-devkit` 工作流会构建并上传 Actions artifact。评审后，由有写权限的成员按 [发布步骤](RELEASE.md) 将同一个包发布为私有 GitHub prerelease。下载需要相同私有仓库权限；没有公开发布流程。

需要实机验收的项见 `GEO-NAVIGATION-VERIFICATION.md`、`NATIVE-AVOIDANCE-INVESTIGATION-2026-10-27.md`。历史截图和个人实验记录目录不随包发布；测试脚本可以重新生成。
