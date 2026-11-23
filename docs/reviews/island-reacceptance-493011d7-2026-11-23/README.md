# 灵动岛最新版实测复验：493011d7（2026-11-23）

**最新代码已构建并安装到当前打开的模拟器。主要显示和交互正常，原有 Taku Agent 的本轮已测流程没有发现样式破坏；旧问题仍未全部修复，不能关闭全部验收项。** 本轮没有发现新增的阻断问题，不代表所有设备和业务场景均已通过。

接续[原验收](https://github.com/Taku-OS/taku-os/issues/30#issuecomment-7096921204)与[6dc400c7 复验](https://github.com/Taku-OS/taku-os/issues/30#issuecomment-7103179456)，新增本条记录，保留旧评论。

## 实际安装与截图方式

- 分支 `vela/dynamic-island`，源码 [`493011d749a2113afaaeebc06a9d59d0537671d0`](https://github.com/Taku-OS/taku-os/commit/493011d749a2113afaaeebc06a9d59d0537671d0)，包含 `56edb708` review 修复；验收期间再次核对远端仍为此提交。
- 当前设备 `emulator-5596 / Taku_PR27_Fresh`，ARM64 Android 17。云端复用源码及缓存，以原生 x86_64 工具链、56 路并行构建 `lineage_taku_emu_arm64-cp2a-userdebug / SystemUI`，真实 Android 构建通过。
- **SystemUI、Taku Agent、Assistant 均按本次源码重编并安装**；重启后逐一比对设备 APK 与产物 SHA-256，一致。Agent Android 构建、lint、typecheck 通过；当前解析器 APK 在设备上 **53 项通过**。底层 ROM 没有整包重刷，账号和用户数据保留。
- 产物已取回；确认没有其他构建后关闭云机，状态 `TERMINATED`，源码和缓存保留。[构建与安装摘要](evidence/build-receipt.json)。
- **所有下方图片都是本次原生模拟器窗口截图，逐张检查了内容。** 包括实际摄像头遮罩；没有用 ImageGen、没有补画开孔。当前开孔直径约 46.48 dp。
- 针对之前“点进 App 后近乎空白”的截图，这次测试 App 目标页明确展示 **ISLAND TEST APP、测试订单、实际收到的 destination、发布者和返回桌面按钮**。它是一次性接入测试工具，不是 Notebook 或真实配送业务；不能据此认定 Notebook 自己的页面已验收。

## 对照上一轮问题

| 原编号 | 本轮结论 |
| --- | --- |
| 1 动画图标 | **通过**：App 自有 AnimatedVectorDrawable 加载、动画状态与不同帧可见，进度环正常。 |
| 2 进 App 收起 | **通过**：卡片、独立 card action、操作按钮、摘要直达均进入对应目标；前台时隐藏，回桌面恢复；前台 alert 不弹卡。 |
| 3 自由设计 | **部分支持**：白色背景、深色文字与控件正常；普通签名 App 的自由内容视图仍拒绝，平台签名限制未取消。 |
| 4 按钮数量 | **限制仍在**：32 个可横向滚动，33 个被拒绝；不能记作“不限数量”已完成。 |
| 5 持续性与重要性 | **部分支持**：非 ongoing 被拒绝；但 ongoing + `timeoutS=3` + “Version updated”仍能上岛。协议不会自动识别营销或无价值静态信息。 |
| 6 高频提醒 | **仍失败**：初始提醒 5 秒窗口内，收起后 `alert=false` 更新仍重新弹卡，见下面时序。 |
| 7 开孔与裁切 | **当前大开孔配置通过**：异形容器、A/B 组、左侧第二活动、长文字省略可见。本轮未重新切换无开孔/24 dp 状态栏，也未跑新增 Canvas 渲染测试，不沿用旧截图作为新证据。 |
| 8 12 小时上限 | **单次限制通过，跨重发仍有缺口**：43201 秒被拒绝；取消再发会重置 firstSeen/到期时间，不能保证同一业务累计不超过 12 小时。按时间戳验证，没有等待 12 小时。 |
| 9 Agent Building | **通过**：真实创建独立测试页面期间出现 Building 和九点图标，完成后 `working=false / buildingApp=false`。 |
| 10 缺 fal Key 引导 | **未重新验收，不能关闭**：账号现已连接，本轮没有删除 Key。相关 Live Talk / Session 处理与上一轮相比未变；已连接后的成功不能证明缺 Key 引导已修复。 |
| 11 键盘与卡片 | **通过**：Taku 输入法、工作/回复/监听卡片正常；卡片仍 `cover=true / top=21px`，键盘没有把卡片推到开孔下方。 |

这次还确认了：手动选择的活动不会因无关的最新活动结束而被替换；Live Updates 保留系统横幅；无效图片引用不能上岛但仍保留普通横幅；胶囊与展开卡片互斥；点击等待圆点、左滑切换、右滑隐藏正常；未获白名单的普通签名测试 App 不能发布。

## 仍需修复的重复弹卡

同一发布者、同一通知 ID，本次实测：

1. `alert=true`，约 **1489 ms**：展开。
2. 主动收起，约 **2010 ms**：`expanded=false`。
3. 更新为 **`alert=false`**，约 **3459 ms**：再次 `expanded=true`。

[时序证据](evidence/alert-reproduction.json)。代码仍由 [`IslandStore.java:91`](https://github.com/Taku-OS/taku-os/blob/493011d749a2113afaaeebc06a9d59d0537671d0/system/island/src/com/android/systemui/taku/island/IslandStore.java#L91)保留提醒窗口、[`IslandPresenter.kt:155`](https://github.com/Taku-OS/taku-os/blob/493011d749a2113afaaeebc06a9d59d0537671d0/system/island/src/com/android/systemui/taku/island/IslandPresenter.kt#L155)按 revision 再次触发。30 秒冷却没有覆盖“同一提醒窗口内被更新再次展示”的情况。

## Agent 与账号实际结果

真实只读任务执行 `getprop ro.build.version.release` 并回复 **Android 17**；工作、回复、聆听仍使用 `systemui-native / native-assistant`。本地语音进入 Zipformer `Capturing / ready=true`；此项只验证进入采音，没有验证完整识别准确率。fal 卡片显示 **Fal Connected**。

按已获授权短暂启动 Live Talk，约 5 秒后读回 **`provider=fal-grok / lifecycle=active / error=null`**，截图后立即停止，确认 **`closed / capturing=false`**。这次验证了实际连接，不只是保存了密钥；没有进行长对话、语音回答质量或工具调用验收。

## 边界与清理

未覆盖首次 onboarding、缺 Key 重现、真实模型确认/错误卡片、Notebook 业务、锁屏、电话/闹钟、多用户、其他屏幕尺寸或真机帧率。新加的 Canvas 渲染仪器测试本机缺对应编译依赖，本轮未运行；不把解析器测试当作其替代。

临时白名单已恢复为仅 `org.agentos.notebook`；三个测试 App 和解析器测试包均卸载；模糊设置恢复；录音和 Agent 任务均停止。保留账号、Taku 输入法和原开孔配置。本轮没有修改产品代码；构建任务仅额外创建了 `/data/local/agentos/work/island-acceptance-493011d7/index.html` 测试页面。

## 本次流程原图

点击图片可查看原图。以下有些页面本来不显示灵动岛（例如已进入发布 App、任务结束或 Live Talk 全屏页面），对应标题说明了预期；它们不是缺图。

<details>
<summary>A · 第三方通知：摘要 → 卡片 → 目标页面</summary>

| 摘要：A 图标、B 取件码，中间避让开孔 | 点击摘要展开；覆盖开孔 |
| --- | --- |
| <a href="images/01-pickup-capsule.png"><img src="images/01-pickup-capsule.png" width="280" alt="摘要：A 图标、B 取件码，中间避让开孔" /></a> | <a href="images/02-pickup-card-cover.png"><img src="images/02-pickup-card-cover.png" width="280" alt="点击摘要展开；覆盖开孔" /></a> |

| 点击卡片到 content-detail，卡片收起 | 返回桌面恢复摘要 |
| --- | --- |
| <a href="images/03-card-opens-test-detail.png"><img src="images/03-card-opens-test-detail.png" width="280" alt="点击卡片到 content-detail，卡片收起" /></a> | <a href="images/04-return-home-capsule.png"><img src="images/04-return-home-capsule.png" width="280" alt="返回桌面恢复摘要" /></a> |

| 选择 below：卡片在开孔下方 | 独立卡片动作到 card-detail |
| --- | --- |
| <a href="images/05-card-below.png"><img src="images/05-card-below.png" width="280" alt="选择 below：卡片在开孔下方" /></a> | <a href="images/06-custom-card-destination.png"><img src="images/06-custom-card-destination.png" width="280" alt="独立卡片动作到 card-detail" /></a> |

| 配置 tap=open：摘要直接进 App | 点击操作按钮到 track |
| --- | --- |
| <a href="images/07-capsule-direct-open.png"><img src="images/07-capsule-direct-open.png" width="280" alt="配置 tap=open：摘要直接进 App" /></a> | <a href="images/42-action-button-destination.png"><img src="images/42-action-button-destination.png" width="280" alt="点击操作按钮到 track" /></a> |

| 独立切到发布 App 前台，卡片收起 | 发布 App 前台时不主动弹卡 |
| --- | --- |
| <a href="images/43-app-foreground-folds.png"><img src="images/43-app-foreground-folds.png" width="280" alt="独立切到发布 App 前台，卡片收起" /></a> | <a href="images/44-foreground-alert-suppressed.png"><img src="images/44-foreground-alert-suppressed.png" width="280" alt="发布 App 前台时不主动弹卡" /></a> |

</details>

<details>
<summary>B · 样式、动画、多活动和系统横幅</summary>

| 白色卡片、深色文字及按钮 | App 自有动画图标及进度环：帧 A |
| --- | --- |
| <a href="images/08-white-card.png"><img src="images/08-white-card.png" width="280" alt="白色卡片、深色文字及按钮" /></a> | <a href="images/09-animated-icon-a.png"><img src="images/09-animated-icon-a.png" width="280" alt="App 自有动画图标及进度环：帧 A" /></a> |

| 同一动画的另一帧 | B 组也可以显示图标 |
| --- | --- |
| <a href="images/10-animated-icon-b.png"><img src="images/10-animated-icon-b.png" width="280" alt="同一动画的另一帧" /></a> | <a href="images/11-group-b-icon.png"><img src="images/11-group-b-icon.png" width="280" alt="B 组也可以显示图标" /></a> |

| 长文字以省略号截断 | 新活动优先，等待圆点在左 |
| --- | --- |
| <a href="images/12-long-label-ellipsis.png"><img src="images/12-long-label-ellipsis.png" width="280" alt="长文字以省略号截断" /></a> | <a href="images/13-two-activities.png"><img src="images/13-two-activities.png" width="280" alt="新活动优先，等待圆点在左" /></a> |

| 点左侧圆点切换活动 | 左滑切换下一活动 |
| --- | --- |
| <a href="images/14-waiting-disc-tap.png"><img src="images/14-waiting-disc-tap.png" width="280" alt="点左侧圆点切换活动" /></a> | <a href="images/15-swipe-left-next.png"><img src="images/15-swipe-left-next.png" width="280" alt="左滑切换下一活动" /></a> |

| 右滑隐藏当前活动 | 三个活动中手动选中第一个 |
| --- | --- |
| <a href="images/16-swipe-right-hide.png"><img src="images/16-swipe-right-hide.png" width="280" alt="右滑隐藏当前活动" /></a> | <a href="images/16b-manual-selection.png"><img src="images/16b-manual-selection.png" width="280" alt="三个活动中手动选中第一个" /></a> |

| 最新活动结束后仍保持手动选择 | Android Live Update 摘要 |
| --- | --- |
| <a href="images/16c-newest-ended.png"><img src="images/16c-newest-ended.png" width="280" alt="最新活动结束后仍保持手动选择" /></a> | <a href="images/17-live-update-capsule.png"><img src="images/17-live-update-capsule.png" width="280" alt="Android Live Update 摘要" /></a> |

| 点击进入 Live Update 卡片 | Live Update 重要更新保留系统横幅 |
| --- | --- |
| <a href="images/18-live-update-card.png"><img src="images/18-live-update-card.png" width="280" alt="点击进入 Live Update 卡片" /></a> | <a href="images/18b-live-update-headsup.png"><img src="images/18b-live-update-headsup.png" width="280" alt="Live Update 重要更新保留系统横幅" /></a> |

| 无效图片引用回退系统横幅 | 32 个操作按钮：滚动前 |
| --- | --- |
| <a href="images/18c-invalid-payload-headsup.png"><img src="images/18c-invalid-payload-headsup.png" width="280" alt="无效图片引用回退系统横幅" /></a> | <a href="images/19-actions-before-scroll.png"><img src="images/19-actions-before-scroll.png" width="280" alt="32 个操作按钮：滚动前" /></a> |

| 操作按钮可横向滚动 | 系统模糊开启 |
| --- | --- |
| <a href="images/20-actions-after-scroll.png"><img src="images/20-actions-after-scroll.png" width="280" alt="操作按钮可横向滚动" /></a> | <a href="images/24-blur-enabled.png"><img src="images/24-blur-enabled.png" width="280" alt="系统模糊开启" /></a> |

| 关闭模糊后的不透明回退 |
| --- |
| <a href="images/25-blur-disabled.png"><img src="images/25-blur-disabled.png" width="280" alt="关闭模糊后的不透明回退" /></a> |

</details>

<details>
<summary>C · Taku Agent 真实流程及已连接账号</summary>

| 文字输入、Taku 输入法及开孔 | 真实只读任务工作中；卡片覆盖开孔 |
| --- | --- |
| <a href="images/30-agent-text-keyboard.png"><img src="images/30-agent-text-keyboard.png" width="280" alt="文字输入、Taku 输入法及开孔" /></a> | <a href="images/31-agent-working.png"><img src="images/31-agent-working.png" width="280" alt="真实只读任务工作中；卡片覆盖开孔" /></a> |

| 实际回复 Android 17；键盘不改变卡片位置 | 进入完整会话 |
| --- | --- |
| <a href="images/32-agent-reply.png"><img src="images/32-agent-reply.png" width="280" alt="实际回复 Android 17；键盘不改变卡片位置" /></a> | <a href="images/33-agent-full-conversation.png"><img src="images/33-agent-full-conversation.png" width="280" alt="进入完整会话" /></a> |

| 完成会话后回桌面 | 已有 fal 页面显示 Fal Connected |
| --- | --- |
| <a href="images/34-agent-return-home.png"><img src="images/34-agent-return-home.png" width="280" alt="完成会话后回桌面" /></a> | <a href="images/35-fal-connection.png"><img src="images/35-fal-connection.png" width="280" alt="已有 fal 页面显示 Fal Connected" /></a> |

| 本地 Zipformer 已开始监听 | 真实页面开发任务的 Building 胶囊 |
| --- | --- |
| <a href="images/36-local-listening.png"><img src="images/36-local-listening.png" width="280" alt="本地 Zipformer 已开始监听" /></a> | <a href="images/37-real-building-capsule.png"><img src="images/37-real-building-capsule.png" width="280" alt="真实页面开发任务的 Building 胶囊" /></a> |

| 任务完成、Building 标记清除 | 实际 fal/Grok 连接 active，无错误 |
| --- | --- |
| <a href="images/38-building-finished.png"><img src="images/38-building-finished.png" width="280" alt="任务完成、Building 标记清除" /></a> | <a href="images/39-live-talk-result.png"><img src="images/39-live-talk-result.png" width="280" alt="实际 fal/Grok 连接 active，无错误" /></a> |

| 截图后停止 Live Talk，停止采音 | 桌面、时钟、开孔及底部横条 |
| --- | --- |
| <a href="images/40-live-talk-stopped.png"><img src="images/40-live-talk-stopped.png" width="280" alt="截图后停止 Live Talk，停止采音" /></a> | <a href="images/41-final-home.png"><img src="images/41-final-home.png" width="280" alt="桌面、时钟、开孔及底部横条" /></a> |

</details>

<details>
<summary>D · 仍存在的问题</summary>

| 首次 alert=true 自动弹卡 | 主动收起后成为摘要 |
| --- | --- |
| <a href="images/21-alert-initial.png"><img src="images/21-alert-initial.png" width="280" alt="首次 alert=true 自动弹卡" /></a> | <a href="images/22-alert-collapsed.png"><img src="images/22-alert-collapsed.png" width="280" alt="主动收起后成为摘要" /></a> |

| 失败：alert=false 更新再次弹卡 | 失败：3 秒静态结果仍被准入 |
| --- | --- |
| <a href="images/23-alert-false-update.png"><img src="images/23-alert-false-update.png" width="280" alt="失败：alert=false 更新再次弹卡" /></a> | <a href="images/26-short-static-admission.png"><img src="images/26-short-static-admission.png" width="280" alt="失败：3 秒静态结果仍被准入" /></a> |

</details>
