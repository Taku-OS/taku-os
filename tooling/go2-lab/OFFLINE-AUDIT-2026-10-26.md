# Go2 原生避障接入：六项离线验证

日期：2026-10-26（Asia/Shanghai）。对象：现有 Taku Phone Go2 开发接入。
基线：`e143ec6`，工作分支 `codex/go2-offline-audit`。

本轮是实现现状审计与可复现离线验证，没有连接机器人，没有启动真实
WebRTC 驱动，没有调用运行中的 Mac 桥接，也没有发送运动或模式切换指令。
新增文件是测试、审计工具和报告；没有把原生避障功能加入产品。

## 总体结果

**已有普通微动的软件基础通过离线检查；原生避障接入尚未完成。**
不能把下面的测试数量解释成“机器狗避障已通过”。

| 项目 | 实际验证 | 结论 | 仍缺的工作 |
| --- | --- | --- | --- |
| 1. 原生避障协议 | 对照官方 API；从固定社区版本提取查询/设置函数，用假 RPC 执行 6 个检查 | 请求/响应处理通过；本项目尚未接入 | 新增受限接口、超时、严格类型校验、设置后回读、控制权与停止策略；实机确认固件兼容 |
| 2. 位姿解析 | 给现有解析器输入 position、IMU quaternion/rpy 和障碍距离 | **缺失**：输出只有高度、模式、速度、角速度；位姿未保留 | 解析、坐标变换、时间对齐与异常处理；实机确认坐标约定 |
| 3. 模拟机器人服务 | 现有 FakeBridge HTTP 测试；新增真实 Bridge 方法配假 pub_sub；社区假 RPC | 可以隔离验证通信分支；**不是完整模拟机器人服务** | 可启动并供 Taku Phone 使用的避障场景服务、读回不一致/失联场景；不伪造真实物理效果 |
| 4. 控制状态机 | 10 项现有微动测试，另检查真实停止处理、查询超时和状态失效 | 普通微动保护通过；**不是原生避障状态机** | 原生避障状态门禁、执行期间监控、控制入口互斥；不得自动退回普通 Move |
| 5. Taku Phone 界面 | 实际 motion.js 在隔离 DOM/假传输下运行 8 项交互测试 | 微动交互通过；**没有原生避障界面** | 开启/关闭/未知/故障显示，开启后确认；完整浏览器和 Android 联调 |
| 6. 记录与回放 | 检查现有输出文件、采样保存方式；运行合成点云编码测试 | 有历史诊断证据；**没有同步录制/回放工具和数据集** | 保存原始点云、位姿、接收时间、源时间、指令/响应与模式；实现离线回放 |

## 验证方法和结果

### 已有测试重新执行

- `npm run check:go2`：只读/状态 5 项，HTTP 7 项，微动 10 项，均通过；4 个前端 JS 文件语法检查通过。
- Go2 虚拟环境执行 `tests/go2-lidar.test.py`：5 项通过。
- Python 合计 **27 项通过**。这是现有功能的回归检查。
- 新增界面测试纳入仓库 `npm test` 后，完整 Node 测试 **72 项通过**（其中包含下述 8 项，不重复计数）。

### 新增界面逻辑检查

`node --test tests/go2-motion-ui.test.mjs`：**8 项通过**。

1. 默认锁定，单独勾选就绪不会发出动作。
2. 请求处理中拒绝重复按钮点击，返回冷却状态后继续禁用方向。
3. 状态超过 1.8 秒未更新，即使继续渲染也禁用方向。
4. 停止失败警示不会被新状态自动清除；允许再次请求停止。
5. 停止之后到达的旧运动响应不会重新解锁。
6. 较早的轮询响应不会覆盖较新的锁定结果。
7. **已经启用**的页面切后台会请求停止并清除就绪勾选。
8. 显式离线事件禁用运动，保留停止按钮。

这运行的是产品 `motion.js`，DOM、时间和传输均为内存替身。
不是本轮 Android WebView 布局、WebGL、生命周期完整覆盖或真实网络测试。
测试夹具初次运行有一处未等待异步点击处理完成，修正夹具等待后 8 项通过；未修改产品逻辑。

### 社区协议检查

固定社区源码版本：`e0abc5780761539eff89a382a79319e5cf6ad1f4`。
文件 SHA-256：`7035ea5dc37c438c67fa05d6c0646d109c08db287b3987dde6184f5496b6220b`。
工具只提取并运行 `switch_get` / `switch_set`，不导入驱动、不运行 main 或 drive_loop。

**6 项通过**：查询开启、查询关闭、非零错误码、空数据、损坏 JSON、开启请求参数。
设置函数返回码 0 **没有自动回读**，集成时必须增加这一步，不能直接宣布已开启。

| 通道 | 动作 | 契约区别 |
| --- | --- | --- |
| `rt/api/obstacles_avoid/request` | 1001 设置、1002 查询、1003 避障移动、1004 API 命令来源 | 官方避障 Move 参数为 x/y/yaw/mode |
| `rt/wirelesscontroller` | 社区示例模拟遥控输入 | lx/ly/rx/ry/keys；摇杆幅度不是米/秒；本轮没有运行移动函数 |
| `rt/api/sport/request` | 当前 1008 普通 Move、1003 StopMove | 当前使用 x/y/z；同一数字在不同服务中意义不同 |

当前发送白名单仍拒绝原生避障请求、模拟遥控输入和 robot_pose 订阅。
**已知协议不等于已经接入，也不等于固件实际支持。**

参考：
- [官方避障 API](https://github.com/unitreerobotics/unitree_sdk2_python/blob/master/unitree_sdk2py/go2/obstacles_avoid/obstacles_avoid_api.py)
- [官方避障客户端](https://github.com/unitreerobotics/unitree_sdk2_python/blob/master/unitree_sdk2py/go2/obstacles_avoid/obstacles_avoid_client.py)
- [固定社区示例](https://github.com/legion1581/go2_webrtc_connect/blob/e0abc5780761539eff89a382a79319e5cf6ad1f4/examples/go2/data_channel/obstacles_avoid/obstacles_avoid.py)

### 额外探针：8 项通过，3 个缺口复现

`offline_audit.py` 用真实 Bridge/Motion 类配内存传输，禁止 DNS、socket.connect 和 sendto。
结果明确使用 `pass` / `gap`，不会把已知缺口算成通过。

通过项：网络隔离保护、停止成功、停止拒绝、停止响应缺失状态码、停止超时、
模式查询超时清除旧值、未接入通道继续被阻止、完整合成点云编码。
停止请求超时约 1002 ms，结果为 STOP_UNCONFIRMED 且撤销启用。

复现的三个缺口：

1. **位姿未被保留**：测试输入包含 position 和 IMU，解析结果没有这些字段。
2. **无雷达仍可就绪**：假连接和新鲜机身状态下，lidarEnabled=false、hasCloud=false，但 motionReady=true。
   这是现有普通微动设计，不能复用成依赖点云的保护条件。机载避障是否需要 Mac 点云则应单独设计。
3. **运动期间不持续检查就绪条件**：发出 Move 后让 ready 立即变 false，最终 Stop 仍约在 351 ms 才调用，
   即等到固定脉冲结束。此场景没有断开底层连接，不代表测出了真实断线停车行为。
   0.20 秒只是本探针用来区分“提前中断”和“等待 0.35 秒结束”的观察界限，不是安全规范。

这些结果说明当前实现适合原先有人现场观察的短动作测试，不是持续运动的避障控制器。

### 性能与记录审计

合成 64,000 个点，执行 35 次过滤、边界计算、Float32 转换与二进制编码：
本次中位 **2.590 ms**，最大 **6.140 ms**。完整点载荷 768,000 字节。
不包含压缩解码、机器人数据采集、WebRTC、位姿转换、碰撞计算、页面显示或停车。
这不是端到端延迟，也不能推出安全速度。

检查 `out/go2-lab/` 顶层现有 JSON 和常见点云/记录扩展名，未找到可同步回放的原始
点云和位姿序列。`lidar-ui-live.json` 中名为 points 的字段实际为字符串 `64,239`，
是界面点数文本，并非 64,239 个 XYZ 坐标。当前 bridge 仅保存最新一帧。
历史动作记录有状态采样，可核对发送与响应；不能重建当时障碍物的位置。

## 复现

从 taku-os 仓库根目录执行。下列命令不连接机器人：

```sh
npm run check:go2
"$HOME/Library/Caches/taku-go2/venv/bin/python" tests/go2-lidar.test.py
node --test tests/go2-motion-ui.test.mjs
"$HOME/Library/Caches/taku-go2/venv/bin/python" tooling/go2-lab/offline_audit.py
python3 tooling/go2-lab/audit_community.py /path/to/reviewed/obstacles_avoid.py
```

最后一个命令要求上述固定哈希的社区文件，版本变化会拒绝执行。
原始本轮输出在 ignored `out/go2-lab/offline-audit-2026-10-26/` 下：
`baseline.txt`、`ui.txt`、`node-suite.txt`、`probes.json`、`community.json`。
LiDAR 回归输出为 `out/go2-lab/lidar-audit-2026-10-26.txt`。

## 实机验收边界

仍需实机：位姿和点云坐标/时间一致性；PRO 固件接口与运动模式兼容；输入确实经过机载避障；
停止与控制权释放；前后及转向覆盖；真实时延、抖动、停止距离、失联行为。
机器人不在场时，本报告不会把其中任何一项标为通过。
