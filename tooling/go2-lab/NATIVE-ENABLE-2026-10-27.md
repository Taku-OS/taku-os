# PRO 原生避障开关实机验证

用户明确要求开启机载原生避障。只验证开关，没有发送移动、停止、命令来源接管或模式切换指令。

- 目标：已核对 MAC 的 Go2 PRO，192.168.5.128；运动模式 ai。
- 首次开启未成功，工具当时只记录拒绝，没有输出具体返回码。
- 独立只读重连：1002 查询 code=0、enable=false。
- 第二次开启：`rt/api/obstacles_avoid/request`，1001，`{"enable":true}`；返回 code=3203。
- 随后三次 1002 查询均 code=0、enable=false。
- 恢复 Mac bridge 后，其独立轮询仍报告 enabled=false、statusFresh=true。
- 运动未启用，工具的 actuation_allowed 始终 false。已恢复雷达接收。

官方 `RPC_ERR_SERVER_API_NOT_IMPL = 3203`：
https://github.com/unitreerobotics/unitree_sdk2_python/blob/master/unitree_sdk2py/rpc/internal.py

结论：这台设备当前服务没有接受该开关 API，不能声称原生避障已开启，也不能由此断言所有固件或 App 控制路径都不可用。尚未确定固件版本、其他受支持入口及模式兼容性。未通过猜测 API 或切换模式继续试验。

## 工具

`enable-native-avoidance.py --robot-ip <已核实IP> --enable` 只为开启请求临时允许 1001/enable=true；不开放运动、关闭、API 控制来源或通用写入。已有 bridge 必须先断开，结束后重新连接。

`--query-only` 仅输出经过筛选的查询返回码及布尔开关状态。

本轮离线检查确认：默认拒绝写入；仅在 enable_once 执行期间允许精确的开启消息；拒绝关闭、数字冒充布尔值、附加字段、移动和控制来源接管。Python 编译检查通过。实机日志保留在 ignored `out/go2-lab/native-*-2026-10-27.log`。
