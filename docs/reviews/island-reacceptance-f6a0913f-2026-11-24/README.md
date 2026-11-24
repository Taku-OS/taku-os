# 灵动岛模拟器复验 · f6a0913f · 2026-11-24

本轮基于 [vela/dynamic-island @ f6a0913f](https://github.com/Taku-OS/taku-os/commit/f6a0913f3f90f910ebc24825a159fdd0687b14cb)，接续 [上一次模拟器验收](https://github.com/Taku-OS/taku-os/issues/30#issuecomment-7105043464)。下方是本轮重新截取的模拟器画面，包含可见的摄像头开孔。

## 构建与装机

- **设备** `Taku_PR27_Fresh / emulator-5596`，Android 17，ARM64。
- **SystemUI** 复用云端 `lineage_taku_emu_arm64-cp2a-userdebug / SystemUI` 成功产物。由原生 x86_64 工具链以 56 路并行增量交叉构建。输入归档内 38 个文件逐字节匹配本次提交，云端、取回和设备上的 APK 同一 SHA-256。云机确认空闲后已关停。
- **Taku Agent / Assistant** 用本次提交生成新 APK，在原 AVD 上覆盖安装并保留数据。核对两份签名和装机 SHA-256。 [构建与装机记录](evidence/build-receipt.json)
- GitHub 的 7 项 CI、Agent 的 464 项 JS 测试、类型和 lint 均通过。原生 JVM 共 223 项，222 项通过，包括移交和卡片呈现相关的 19 项。唯一失败是 `DesignSystemTest.screensDoNotHardcodeColours` 对已有 `Composer.kt:305` 中黑色渐变的检查，该行在本次范围内没有改动。

## SystemUI 和原流程

| 检查 | 本轮结果 |
| --- | --- |
| 摘要、展开、进 App | 通过。摘要和卡片互斥。分别验证了覆盖／避让开孔、卡片默认和独立跳转、操作按钮、摘要直达、回桌面恢复摘要、发布应用在前台时的抑制。目标页显示实收 destination。 |
| A/B 和多活动 | 通过。验证左侧等待圆点、点按与滑动切换、右滑隐藏、手动选中在其他活动结束后仍保留、B 组图标和长文字省略。 |
| 样式与规则 | 白色卡片、应用自己的动效和进度环、模糊开关、32 个按钮横滑通过。33 个按钮和普通签名应用的自定义内容仍被拒绝。 |
| 系统通知 | Live Update 有摘要和展开卡片，重要更新仍显示 Android 横幅。无效图标引用回退普通横幅。 |
| 重复自动展开 | **已修复。** 同一通知首次弹出后手动收起，在初始 5 秒提醒窗口内更新为 `alert=false`，卡片保持收起。 [时序](evidence/alert-reproduction.json) |
| 准入和时长 | 仍有原限制。非持续通知及大于 12 小时的配置被拒绝。持续标记加 3 秒有效期的静态通知仍可上岛。取消后重发会重置过期时间。 |

## Taku Agent 与新增修复

| 流程 | 本轮结果 |
| --- | --- |
| 键盘、语音与回复 | 只读任务返回 Android 17。Taku 输入法打开时，工作、回复卡片仍覆盖开孔。本地 Zipformer 进入聆听。 |
| Building | 真实测试页面开发期间出现 Building 胶囊和九点动画，完成后标记清除。 |
| Live Talk | fal/Grok 实际连接进入 `active`，随后停止，读回 `closed / capturing=false`。缺 fal Key 的首次接入和音质不在本轮结论内。 |
| 卡片跳转（4140783f） | 先打开 Taku 主页浮层并收起，再点击展开卡片。实测关闭浮层并返回同一条对话，原回复仍显示。 |
| 语音长按（BUG-0028） | 长按显示聆听状态。取消后卡片隐藏，原会话仍保留，上一条回复没有重新上岛。 |
| New chat | 点击立即显示新会话的问候，原回复不再显示。 |
| 退出对话（BUG-0027） | 从完整对话发起任务后回桌面，或在任务运行时按返回、经最近任务切到 Settings，处理期间无卡片和胶囊，结束后自动展开，点卡片回原对话。桌面和 Settings 上验证了卡片持续超过 5 秒。提前返回对话，回复留在对话里。 |
| 移交接口 | `assistant/handoff` 和 `assistant/reclaim` 按状态实测通过。另一会话占用桌面时返回 `HOME_BUSY`。 |

- **BUG-0027 解锁路径未通过。** 关屏前已成功移交。屏幕熄灭时任务完成，SystemUI 报 `expanded=true`，没有进入 `revealPending`，唤醒并解锁后等待 65 秒仍没有看到该回复自动展开。这里的 `expanded` 来自设备状态读回。 [锁屏状态记录](evidence/unlock-pending-proof.json)
- **刚提交、任务仍在排队时离开对话，结果尚不稳定。** 早期尝试中有移交或结果等待超时。任务进入运行后，原始复现、返回键和切换应用都通过。提前回聊的一次测试同时出现了草稿恢复提示，回复仍正常显示，本轮未定位该提示的原因。
- 本轮没有实测等待用户输入和运行错误两种结束状态、旋转、附件选择器、仅熄屏是否触发移交，也没有测量提交到弹卡的亚秒级延迟。

设备上还跑了 **53 项 JSON 解析**和 **109 项 Canvas 渲染**检查，涵盖短状态栏和大开孔等输入。[解析器](evidence/parser-results.txt) · [渲染](evidence/capsule-results.txt)

临时测试 App 已卸载，白名单与模糊设置已恢复。原本的 AI 账号、Taku 输入法和开孔配置继续使用。测试产生的新对话和独立计数器页面保留在模拟器内。

## 流程截图

图片来自这一轮的模拟器窗口。点击可查看原图。入目标页面、手动隐藏或完成后的截图用于确认对应状态。

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
<summary>D · 重复弹卡修复与仍存在的限制</summary>

| 首次 alert=true 自动弹卡 | 主动收起后成为摘要 |
| --- | --- |
| <a href="images/21-alert-initial.png"><img src="images/21-alert-initial.png" width="280" alt="首次 alert=true 自动弹卡" /></a> | <a href="images/22-alert-collapsed.png"><img src="images/22-alert-collapsed.png" width="280" alt="主动收起后成为摘要" /></a> |

| 更新 alert=false 后仍是摘要态，卡片未重开 | 失败：3 秒静态结果仍被准入 |
| --- | --- |
| <a href="images/23-alert-false-update.png"><img src="images/23-alert-false-update.png" width="280" alt="更新 alert=false 后仍是摘要态，卡片未重开" /></a> | <a href="images/26-short-static-admission.png"><img src="images/26-short-static-admission.png" width="280" alt="失败：3 秒静态结果仍被准入" /></a> |

</details>

<details>
<summary>E · BUG-0027：退出对话后接续回复</summary>

| 完整对话已在执行测试任务 | 返回桌面后已移交，工作期间无卡片及胶囊 |
| --- | --- |
| <a href="images/71-home-running-chat.png"><img src="images/71-home-running-chat.png" width="280" alt="完整对话已在执行测试任务" /></a> | <a href="images/72-home-hidden-during-work.png"><img src="images/72-home-hidden-during-work.png" width="280" alt="返回桌面后已移交，工作期间无卡片及胶囊" /></a> |

| 回复完成后自动展开 | 点击展开卡片返回原对话 |
| --- | --- |
| <a href="images/73-home-expanded-reply.png"><img src="images/73-home-expanded-reply.png" width="280" alt="回复完成后自动展开" /></a> | <a href="images/74-home-reopened-reply.png"><img src="images/74-home-reopened-reply.png" width="280" alt="点击展开卡片返回原对话" /></a> |

| 切换前，完整对话正在执行 | 切换到设置后，工作期间不显示灵动岛 |
| --- | --- |
| <a href="images/71-recents-running-chat.png"><img src="images/71-recents-running-chat.png" width="280" alt="切换前，完整对话正在执行" /></a> | <a href="images/72-recents-hidden-during-work.png"><img src="images/72-recents-hidden-during-work.png" width="280" alt="切换到设置后，工作期间不显示灵动岛" /></a> |

| 设置前台收到已完成回复 | 点击卡片，回到对应完整对话 |
| --- | --- |
| <a href="images/73-recents-expanded-reply.png"><img src="images/73-recents-expanded-reply.png" width="280" alt="设置前台收到已完成回复" /></a> | <a href="images/74-recents-reopened-reply.png"><img src="images/74-recents-reopened-reply.png" width="280" alt="点击卡片，回到对应完整对话" /></a> |

| 按返回键前，任务正在完整对话中执行 | 完整对话按返回键，任务继续且灵动岛隐藏 |
| --- | --- |
| <a href="images/75-back-running-chat.png"><img src="images/75-back-running-chat.png" width="280" alt="按返回键前，任务正在完整对话中执行" /></a> | <a href="images/76-back-hidden.png"><img src="images/76-back-hidden.png" width="280" alt="完整对话按返回键，任务继续且灵动岛隐藏" /></a> |

| 返回路径任务完成，自动展开回复 | 返回路径点卡片打开对应对话 |
| --- | --- |
| <a href="images/77-back-reply-expanded.png"><img src="images/77-back-reply-expanded.png" width="280" alt="返回路径任务完成，自动展开回复" /></a> | <a href="images/78-back-tap-original-chat.png"><img src="images/78-back-tap-original-chat.png" width="280" alt="返回路径点卡片打开对应对话" /></a> |

| 提前回聊测试开始，任务仍在执行 | 返回桌面后已移交，准备返回聊天 |
| --- | --- |
| <a href="images/75-reclaim-running-chat.png"><img src="images/75-reclaim-running-chat.png" width="280" alt="提前回聊测试开始，任务仍在执行" /></a> | <a href="images/76-reclaim-hidden.png"><img src="images/76-reclaim-hidden.png" width="280" alt="返回桌面后已移交，准备返回聊天" /></a> |

| 提前返回后，回复留在完整对话 | 从完整对话发送本轮测试任务 |
| --- | --- |
| <a href="images/77-reclaim-completed.png"><img src="images/77-reclaim-completed.png" width="280" alt="提前返回后，回复留在完整对话" /></a> | <a href="images/79-direct-chat-submitted.png"><img src="images/79-direct-chat-submitted.png" width="280" alt="从完整对话发送本轮测试任务" /></a> |

| 离开完整对话后，工作期间不显示灵动岛 | 任务完成后显示对应的回复卡片 |
| --- | --- |
| <a href="images/80-direct-chat-handoff-hidden.png"><img src="images/80-direct-chat-handoff-hidden.png" width="280" alt="离开完整对话后，工作期间不显示灵动岛" /></a> | <a href="images/81-direct-chat-reply.png"><img src="images/81-direct-chat-reply.png" width="280" alt="任务完成后显示对应的回复卡片" /></a> |

| 点击卡片回到本次测试对话 | 锁屏前已完成移交，工作期间保持隐藏 |
| --- | --- |
| <a href="images/82-direct-chat-returned.png"><img src="images/82-direct-chat-returned.png" width="280" alt="点击卡片回到本次测试对话" /></a> | <a href="images/83-wake-handoff-hidden.png"><img src="images/83-wake-handoff-hidden.png" width="280" alt="锁屏前已完成移交，工作期间保持隐藏" /></a> |

</details>

<details>
<summary>F · Taku Agent 卡片跳转、语音、New chat</summary>

| 从 Taku 主页收起后显示原会话卡片 | 点击卡片打开同一对话，Taku 主页浮层关闭 |
| --- | --- |
| <a href="images/62-collapse-card.png"><img src="images/62-collapse-card.png" width="280" alt="从 Taku 主页收起后显示原会话卡片" /></a> | <a href="images/63-card-reopens-same-chat.png"><img src="images/63-card-reopens-same-chat.png" width="280" alt="点击卡片打开同一对话，Taku 主页浮层关闭" /></a> |

| 点击 New chat 后立即显示新对话问候 | 上一条对话完成并收起卡片 |
| --- | --- |
| <a href="images/64-new-chat-immediate.png"><img src="images/64-new-chat-immediate.png" width="280" alt="点击 New chat 后立即显示新对话问候" /></a> | <a href="images/66-voice-previous-reply-folded.png"><img src="images/66-voice-previous-reply-folded.png" width="280" alt="上一条对话完成并收起卡片" /></a> |

| 再次长按进入聆听，无上一条回复 | 取消采音后卡片关闭 |
| --- | --- |
| <a href="images/67-voice-hold-listens.png"><img src="images/67-voice-hold-listens.png" width="280" alt="再次长按进入聆听，无上一条回复" /></a> | <a href="images/68-voice-cancel-hides.png"><img src="images/68-voice-cancel-hides.png" width="280" alt="取消采音后卡片关闭" /></a> |

</details>
