# 应用发现与后台工作空间

[English](background-workspaces.en.md)

> 本页保留 v73 的历史说明。专屏分支已撤销下文的 `executionScreen.bind` 和 `returnControl`；当前使用 `request_apps` / `release_apps`、`workspaces` 与 `returnAndResume`，接口以[专屏名单文档](dedicated-screens-ui-fields.md)为准。

Agent 做跨 App 任务前，先确认这台手机实际有哪些应用，再申请对应的后台屏。用户可以在 **设置 → Taku AI → 后台工作空间** 查看屏幕、暂停 Agent、接管操作和交还控制权；助手设置也提供同名入口。

这两部分解决的是“可以用哪个 App”和“谁在操作这块屏幕”。应用发现不会自动拆分任务，工作空间也不是独立账号或第二套 Android 系统。跨 App 父任务编排和结果汇总仍是单独的工作，参见[多屏案例与边界](multi-screen-parallel-case-2026-10-26.md)。

## 当前状态

本页描述 2026 年 10 月 27 日的 v73（`20261027-workspaces-design`），已合入 main，并安装到 Pixel 9 完成下述界面验收。v73 在 v72 的后台接口上调整了管理界面的阅读和操作顺序；完整 ROM 与文档网站尚未发布此次更新。

| 环境 | 本次功能的验收状态 |
| --- | --- |
| 模拟器 | v73 实际 APK 已完成列表、详情、实时画面、暂停、接管点按和交还验证；检查了中英文、明暗、130%/150% 字号和横屏。v72 的 23 项后台检查保留为前版基线，本轮没有重跑整套后台测试或发送模型请求 |
| Pixel 9 | 已安装 v73 APK，更新当前启动周期的开发 broker；首页、详情、只读实时画面、暂停、接管和交还检查通过。保留原有三个工作空间，未向 App 发送业务输入，未请求模型推理或重跑跨 App 业务 |
| 完整 ROM | 尚未集成并验收本次功能 |
| main 与网站 | 已于 2026-10-27 合入 main（`f947854`），远端 CI 通过；文档网站尚未发布此次更新 |

本次进度见[界面候选记录](background-workspaces-design-acceptance-2026-10-27.md)。上一版证据仍保留在 [v72 验收记录](background-workspaces-acceptance-2026-10-27.md)。Pixel 9 使用临时 bind mount 加载 broker，仅对当前启动周期有效，未刷写 ROM。运行中真实模型回合的取消与跨 App 业务仍需单独验收。

真机查看小红书（rednote）实时画面时，应用提示账号因其他设备登录达到设备数量上限而退出，当前需要重新登录。本轮没有处理账号；管理界面显示“就绪”只表示工作空间状态，不能当作 App 已登录或业务可用的证明。

## 用户怎么使用

1. 打开“后台工作空间”。顶部摘要显示当前工作空间数量和不同 `threadId` 的任务数量；下方把各 App 放在同一个紧凑列表里，展示应用名称和真实状态。没有屏幕时显示空态，系统服务不可用时显示连接错误。
2. 点击 App 行进入**工作区详情**，查看状态、任务标签、输入控制权和共享账号说明。三个独立 thread 会显示为三个任务，不会合成一个未经实现的父任务。
3. 需要看实际页面时，再选择**查看实时画面**。默认仅查看，不向 App 发送操作；查看画面不是管理工作空间的必经步骤。
4. 需要自己操作时，点击**我来接管**。系统先停止该任务的 Agent 执行，确认工具和进程已停止，再开放该屏的点按、滑动和“应用内返回”。停止未确认时不会交出输入控制权。
5. 点击**交还 Agent**后，回到原任务明确继续。交还只改变控制权，**不会自动重放被打断的操作**；需要继续的任务会保留相应状态。
6. 不再需要屏幕时，在详情页选择**关闭工作空间**。关闭不会撤销已完成的订座、删除 App 数据或取消已有订单。

详情中的“任务”目前使用屏幕绑定时保存的标签。它帮助用户辨认对应任务，**不代表 Agent 当前正在执行的业务步骤，也不证明任务已经完成**。界面不会仅凭 App 名称生成“正在查路线”等未经执行状态支持的描述。

偏好设置可限制新任务使用后台屏、将并行上限设为 1–3 个，以及选择 Agent 可使用的应用。关闭“允许后台工作”或降低上限只影响新的分配，已有屏幕保留；撤销应用访问授权会阻止后续受管操作，并尝试停止该应用现有分支。应以列表和操作回执确认停止结果。达到上限不等于已实现自动排队。

界面跟随系统中英文和明暗主题，页面可滚动。工作空间复用应用已有账号和数据；同一个 App 包不能同时分配给两条后台分支。

## Agent 先发现，再选择

Harness 在任务准备时加入 `environment.apps` 摘要。摘要最多 12 条，不能把没有出现在摘要中的应用当作未安装。Agent 应调用 `taku_phone.apps` 按名称或包名继续查询，例如：

```json
{"query":"高德","offset":0,"limit":20}
```

这是一项只读发现工具，不要求已有执行屏，也不会创建屏幕。每次查询从当前设备的 `PackageManager` 读取当前 Android 用户的目录；启动目标应用时再次检查包、可启动状态和授权，无需在每次点击前扫描全目录。

| 返回字段 | 应怎样使用 |
| --- | --- |
| `deviceScope`、`userId`、`observedAt` | 确认结果属于这台 Android 手机、当前用户和本次观察时间 |
| `catalogRevision` | 应用目录内容的指纹；策略另看 `backgroundPolicy.revision` |
| `apps`、`total`、`nextOffset` | 分页结果；`nextOffset=null` 表示结束。单页上限 50 条 |
| `packageName`、`label`、`versionName`、`versionCode` | 真实包信息，不能当作模型指令 |
| `installed`、`enabled`、`suspended`、`hasLauncher`、`launchable` | 分别表达安装、启用、暂停和启动入口状态；已安装不等于可启动 |
| `agentAllowed` | 用户策略是否允许受管工具自动操作该应用 |
| `backgroundAllowed` | 是否符合新后台分配的应用与总开关策略；不保证有剩余槽位或兼容独立屏 |
| `loginState`、`backgroundCompatibility`、`businessCapabilities` | 当前返回 `unknown`；必须通过实际页面或授权接口确认，不能从安装情况推断 |

空查询返回带启动入口的应用，包括被禁用的启动应用；精确包名查询也可以返回没有启动入口的包。目录不读取登录凭据、App 私有内容或其他用户档案。

典型执行顺序是：**发现应用 → 核对任务所需条件 → 为分支分配屏幕 → 在该分支操作 → 检查结果 → 释放屏幕**。受控宿主用现有 `executionScreen.bind` 绑定 `threadId` 和 `packageName`；Agent 的手机工具从 thread 取屏幕，不能自行选择其他分支或主屏的显示 ID。

## 工作空间接口

管理界面读取 broker 的真实屏幕租约、原生任务状态和设备策略，不另建任务记录。下列 JSON 是系统内部 `NativeBridge` 请求，**不是开放给普通第三方 App 的公共 SDK**；当前 socket 只接受系统或 root UID。

```json
{"op":"workspaces","action":"list"}
```

结果包含 `schema: "taku.workspaces/1"`、`policy`、`limits` 和 `screens`。`limits` 是 `configure` 接受的取值范围，例如 `{"maxActiveScreens":{"min":1,"max":10}}`，设置页按它列出上限选项。每个屏幕的关键字段如下：

| 字段 | 含义 |
| --- | --- |
| `threadId`、`screenKey`、`generation` | 操作必须使用的完整租约身份；不能只记住 `displayId` |
| `packageName`、`label`、`displayId` | 分支应用、任务标签及当前显示 ID |
| `controlOwner`、`controlRevision` | `agent` / `paused` / `user` 及控制权版本 |
| `admitted`、`status`、`activeTurnId` | 租约是否准入、当前管理状态和真实执行轮次 |
| `resumeRequired`、`taskResumeAutomatic` | 是否需要回原任务继续；交还控制权不会自动继续任务 |
| `lastError` | 诊断信息；界面用可读的本地化提示解释错误 |

`pause`、`takeControl`、`returnControl` 和 `release` 都需要上述三个租约身份字段。`input` 还需要当前 `controlRevision`，只支持当前已接管屏幕的点按、滑动和返回键。控制权版本变化后，旧手势和排队输入失效；屏幕代次变化或连接丢失时，禁止把旧输入送到替代屏幕。

`configure` 接受 `patch`，可设置 `allowBackground`、`maxActiveScreens` 和 `blockedPackages`。例如只调整新分配的上限：

```json
{"op":"workspaces","action":"configure","patch":{"maxActiveScreens":2}}
```

`release` 根据真实租约状态关闭可验证的同代屏幕，或清理已失效的绑定；不会停止身份不符的新 host。若无法确认执行已停止，操作应失败并保留明确状态，不能显示“已关闭”后留下不受管理的执行。

## 权限与实现边界

这套授权和交接约束覆盖 **managed phone tools（受管手机工具）** 及其工作空间准入，不是 Android 全局安全沙箱，也不声称拦截任意 root shell、其他无障碍服务或外部 ADB 输入。独立屏共享应用进程、账号和数据，不能宣传为应用数据隔离。

应用目录由 [PhoneAppCatalog](../system/assistant/broker/org/agentos/lab/PhoneAppCatalog.java) 提供，屏幕及控制权由 [PhoneExecutionScreens](../system/assistant/broker/org/agentos/lab/PhoneExecutionScreens.java) 和 [WorkspaceControl](../system/assistant/broker/org/agentos/lab/WorkspaceControl.java) 管理。管理入口在 [Expo 工作区页面](../system/agent-app/src/components/WorkspaceManagementScreen.tsx)，实时画面和触控由 [MirrorActivity](../system/assistant/src/org/agentos/assistant/MirrorActivity.kt) 承载。

后台接口的验收范围与复验方法见 [v72 记录](background-workspaces-acceptance-2026-10-27.md)；新界面的验证单独记在 [v73 界面候选记录](background-workspaces-design-acceptance-2026-10-27.md)。
