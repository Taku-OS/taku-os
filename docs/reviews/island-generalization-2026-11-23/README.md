## 灵动岛通用化验收更新

**结论：尚未全部满足。已覆盖的 Taku Agent 卡片样式未见被通用化改造替换或破坏，但不能给出“全量无回归”的结论；Building 胶囊在当前模拟器桌面存在条件性显示问题。** 下面补齐可阅读的验收说明和流程截图，JSON 仅作为原始证据。

验收版本：`vela/dynamic-island` 的 `ca2d36a9532c9491fa1f3de9e4d61c32fc254dce`。当前安装的运行代码为 `a48ca6f1`；两者差异只有开发文档。设备为 `emulator-5596`，ARM64 Android 17。日期为 2026-11-23。

### 已有 Taku Agent 是否受到影响

对比通用化前的 `65917a4a`，`IslandAssistantCard.kt` 和 `DotMatrixLoader.java` 没有变化。Agent 保留专用卡片、九点工作动画、回复图标和横向选项；发布端新增 v2 数据，同时保留 v1。

| 流程 | 本次实际结果 |
| --- | --- |
| 原版 Agent 打开文字输入 | 键盘与卡片可以同时显示，卡片避让状态栏；有原版截图。 |
| 聆听、工作中、普通回复、确认和错误 | 显示回放覆盖，仍由 SystemUI 原生 Agent 卡片绘制，没有退回 issue 原图的旧灰色矩形按钮卡片。 |
| 回复选项 | 两个动态选项出现；横向手势后截图确认内容实际位移。无选项时按钮行消失。 |
| 长回复 | 纵向滚动前后截图确认内容移动；上滑头部可关闭卡片。 |
| 关闭与再次展开 | 上滑关闭保留回合数据；新交互可重新展开；Agent 订阅者收到隐藏状态。 |
| 横屏和竖屏 | 横屏关闭不支持的展开卡片；返回竖屏后可显式重新展开。 |
| 原版确认卡片 → 完整会话 → 桌面 | 点击右上角打开会话，卡片消失；返回桌面没有遗留展开卡片。第三方卡片未收起的问题不能直接套用到 Agent。 |
| Building 胶囊 | 在 Settings 前台可以显示九点动画和 `Building...`；当前 Taku Home 前台被抑制，详见问题 9。 |

**测试边界：**聆听、回复、选项、长文本和 Building 使用仓库现有测试探针，临时投射内存状态，未伪造模型完成结果。为运行探针，使用同一份源码关闭混淆的临时 APK；测试后恢复原 APK，SHA-256 与备份一致。原版上另发起了一次只读 Android 版本查询，未得到模型回复；完整会话页面明确提示 **This device has no connected account**，状态停在 `needs_user`。因此真实模型回答、真实录音识别、建议按钮提交到模型以及实际生成 App，均不在本次通过范围内。

### 未满足和部分满足的需求

| # | 项目 | 观察结果与影响 |
| --- | --- | --- |
| 1 | 第三方图标动画 | 合法的 AnimatedVectorDrawable 在发布 App 内能加载，在 SystemUI 中报资源找不到；A/B 区退化成圆点。新安装 App 也复现。静态图标正常。 |
| 2 | 第三方展开卡片的收起 | 胶囊展开后，点卡片进入 App 再回桌面，卡片仍保持展开。普通“摘要直接进 App → 桌面”能恢复摘要。 |
| 3 | 普通第三方自由设计 | 当前只有固定摘要槽位和 basic/progress/highlight 模板。自定义背景字段被拒绝；自定义内容仅向平台签名应用开放，普通白名单 App 仍收到 `reserved_content`。 |
| 4 | 不限制操作按钮数量 | 横向滚动可用，但 32 个通过，33/40 个返回 `too_many_actions`。现有 40 按钮测试示例也因此不可用。 |
| 5 | 仅允许持续且重要的活动 | 白名单 App 的 `ongoing=false`、静态 `Saving failed` 报错可以上岛并获得默认一小时寿命；两秒活动也可以上岛。使用规范主要依靠准入审核，运行时没有持续性门槛。营销语义本身仍需要审核。 |
| 6 | 高频打扰限制 | 约十秒内四次把同一条通知的 `alert` 从 false 切到 true，四次都重新弹出。重复 true 不重弹，但没有频率冷却。 |
| 7 | 异形容器完整显示 | 居中开孔配置形状完整；当前无开孔、矮状态栏配置会裁平上下边缘。需要适配绘制尺寸与容器高度。 |
| 8 | 整个业务活动最多十二小时 | 单条通知十二小时上限有效，普通更新不续期；取消后重发会获得新寿命。没有跨通知的业务活动身份，无法强制同一服务累计不超过十二小时。 |
| 9 | 已有 Agent 的 Building 桌面展示 | 新的前台抑制规则依赖默认 HOME 解析。当前 HOME 角色为 `org.agentos.agent`，但 HOME Intent 解析到 `org.lineageos.setupwizard/.SetupWizardActivity`，Agent 在桌面被当作普通前台 App，Building 不显示。切到 Settings 后出现。属于当前配置下复现的兼容问题，不能推定所有设备必现。 |

问题 1 的[资源加载日志](evidence/animation-error.log)、问题 3/4 的[字段与数量验证](evidence/validation.json)、问题 6 的[重复弹出记录](evidence/alert-burst.json)，以及 [Agent 原始失败记录](evidence/AppBuildIslandProbe.txt)和[切换到其他 App 后的对照记录](evidence/AppBuildIslandProbe-rerun.txt)均保留。没有将首次失败覆盖成通过。前台抑制实现见 [IslandForeground.kt](https://github.com/Taku-OS/taku-os/blob/a48ca6f1edb8ac28e2ae1276c754036599d60440/system/island/src/com/android/systemui/taku/island/IslandForeground.kt#L43)，动画加载见 [IslandCapsuleView.kt](https://github.com/Taku-OS/taku-os/blob/a48ca6f1edb8ac28e2ae1276c754036599d60440/system/island/src/com/android/systemui/taku/island/IslandCapsuleView.kt#L245)，自定义内容与按钮限制见 [IslandPayload.java](https://github.com/Taku-OS/taku-os/blob/a48ca6f1edb8ac28e2ae1276c754036599d60440/system/island/src/com/android/systemui/taku/island/IslandPayload.java#L210)。

### 其他需求的验收结果

- **已验证：**两种展示状态及互斥；应用提供静态图标和短文案；摘要点击展开或直接进 App；卡片默认点击与自定义页面跳转；遮挡/避让开孔；滑动切换和隐藏多个 App；普通活动按触发时间排序；通知取消后退出；Live Updates 与 Taku JSON 两种接入。
- **时间限制：**默认一小时、单通知十二小时上限及更新不续期，经过设备状态与测试时钟核对；设备上另验证了两秒到期退出。没有实际连续运行十二小时。
- **Notebook：**默认白名单已有 `org.agentos.notebook`，但设备未安装 Notebook，真实“收藏、列表切换、备注”等流程尚未验收。测试 App 不能替代 Notebook 验收。
- **范围与未完成项：**锁屏按下一版本处理；没有做小米真机对比、帧率或卡顿专项测试，不能声称流畅度已对齐小米。完整[宿主测试记录](evidence/host-tests.log)和[Agent 显示回归记录](evidence/NativeIslandProbe-scroll-verified.txt)供复核。

### 截图说明

以下均来自模拟器实际画面。Agent 显示回放与原版真实请求分别标注；第三方测试使用普通签名 App，临时加入白名单，未使用平台签名冒充第三方。为便于看清灵动岛，部分预览只截取屏幕顶部；点击图片可看完整原图。截图证明显示结果，时限、权限和错误码以对应读回记录为准。

<details>
<summary>A 原版 Agent 的真实入口和会话流程</summary>

| 文字输入与键盘 | 真实请求进入工作中 |
| --- | --- |
| <a href="images/release-text-entry-full.png"><img src="images/release-text-entry.png" width="280" alt="release-text-entry" /></a> | <a href="images/release-working-full.png"><img src="images/release-working.png" width="280" alt="release-working" /></a> |

| 实际 needs_user 卡片 | 打开会话后提示未连接账号 | 回到桌面 卡片已消失 |
| --- | --- | --- |
| <a href="images/release-actual-confirmation-full.png"><img src="images/release-actual-confirmation.png" width="280" alt="release-actual-confirmation" /></a> | <a href="images/release-actual-open-conversation-full.png"><img src="images/release-actual-open-conversation.png" width="280" alt="release-actual-open-conversation" /></a> | <a href="images/release-actual-return-home-full.png"><img src="images/release-actual-return-home.png" width="280" alt="release-actual-return-home" /></a> |

</details>

<details>
<summary>B Agent 原有卡片样式回归 显示层回放</summary>

| 聆听 | 工作中 九点与光效 | 普通回复 |
| --- | --- | --- |
| <a href="images/agent-listening-full.png"><img src="images/agent-listening.png" width="280" alt="agent-listening" /></a> | <a href="images/agent-working-full.png"><img src="images/agent-working.png" width="280" alt="agent-working" /></a> | <a href="images/agent-reply-full.png"><img src="images/agent-reply.png" width="280" alt="agent-reply" /></a> |

| 确认 | 错误 |
| --- | --- |
| <a href="images/agent-confirmation-full.png"><img src="images/agent-confirmation.png" width="280" alt="agent-confirmation" /></a> | <a href="images/agent-error-full.png"><img src="images/agent-error.png" width="280" alt="agent-error" /></a> |

| 选项滚动前 | 选项滚动后 |
| --- | --- |
| <a href="images/agent-reply-options-full.png"><img src="images/agent-reply-options.png" width="280" alt="agent-reply-options" /></a> | <a href="images/agent-reply-options-scrolled-full.png"><img src="images/agent-reply-options-scrolled.png" width="280" alt="agent-reply-options-scrolled" /></a> |

| 长文本滚动前 | 长文本滚动后 |
| --- | --- |
| <a href="images/agent-long-reply-full.png"><img src="images/agent-long-reply.png" width="280" alt="agent-long-reply" /></a> | <a href="images/agent-long-reply-scrolled-full.png"><img src="images/agent-long-reply-scrolled.png" width="280" alt="agent-long-reply-scrolled" /></a> |

| Building 在当前 Home 不显示 | 同一状态切到 Settings 后显示 |
| --- | --- |
| <a href="images/agent-building-home-full.png"><img src="images/agent-building-home.png" width="280" alt="agent-building-home" /></a> | <a href="images/agent-building-other-full.png"><img src="images/agent-building-other.png" width="280" alt="agent-building-other" /></a> |

</details>

<details>
<summary>C 第三方 App 接入与交互</summary>

| 标准 Live Update 摘要 | 标准 Live Update 展开 |
| --- | --- |
| <a href="images/third-live-update-capsule-full.png"><img src="images/third-live-update-capsule.png" width="280" alt="third-live-update-capsule" /></a> | <a href="images/third-live-update-card-full.png"><img src="images/third-live-update-card.png" width="280" alt="third-live-update-card" /></a> |

| Taku JSON 摘要 | Taku JSON 卡片 |
| --- | --- |
| <a href="images/third-custom-capsule-full.png"><img src="images/third-custom-capsule.png" width="280" alt="third-custom-capsule" /></a> | <a href="images/third-custom-card-full.png"><img src="images/third-custom-card.png" width="280" alt="third-custom-card" /></a> |

| 摘要直接进入 App 指定页 | 回桌面恢复摘要 |
| --- | --- |
| <a href="images/third-direct-app-full.png"><img src="images/third-direct-app.png" width="280" alt="third-direct-app" /></a> | <a href="images/third-return-capsule-full.png"><img src="images/third-return-capsule.png" width="280" alt="third-return-capsule" /></a> |

| 多 App 新活动 FL123 优先 | 左滑切到 A123 | 右滑隐藏 A123 后剩 FL123 |
| --- | --- | --- |
| <a href="images/third-multi-newest-full.png"><img src="images/third-multi-newest.png" width="280" alt="third-multi-newest" /></a> | <a href="images/third-multi-swipe-next-full.png"><img src="images/third-multi-swipe-next.png" width="280" alt="third-multi-swipe-next" /></a> | <a href="images/third-multi-hide-current-full.png"><img src="images/third-multi-hide-current.png" width="280" alt="third-multi-hide-current" /></a> |

| 12 个操作滚动前 | 横向滚动后 |
| --- | --- |
| <a href="images/audit-12-actions-before-full.png"><img src="images/audit-12-actions-before.png" width="280" alt="audit-12-actions-before" /></a> | <a href="images/audit-12-actions-scrolled-full.png"><img src="images/audit-12-actions-scrolled.png" width="280" alt="audit-12-actions-scrolled" /></a> |

| 卡片遮挡开孔位置 | 卡片避让开孔位置 |
| --- | --- |
| <a href="images/audit-card-cover-full.png"><img src="images/audit-card-cover.png" width="280" alt="audit-card-cover" /></a> | <a href="images/audit-card-below-full.png"><img src="images/audit-card-below.png" width="280" alt="audit-card-below" /></a> |

</details>

<details>
<summary>D 未通过项目的画面证据</summary>

| 动画资源失败 退化成圆点 | 静态错误仍被允许上岛 |
| --- | --- |
| <a href="images/audit-animated-fresh-app-full.png"><img src="images/audit-animated-fresh-app.png" width="280" alt="audit-animated-fresh-app" /></a> | <a href="images/audit-static-error-accepted-full.png"><img src="images/audit-static-error-accepted.png" width="280" alt="audit-static-error-accepted" /></a> |

| 第三方进入 App 再回桌面 卡片仍展开 |
| --- |
| <a href="images/audit-return-home-full.png"><img src="images/audit-return-home.png" width="280" alt="audit-return-home" /></a> |

| 无开孔矮状态栏 边缘裁切 | 居中开孔配置 形状完整 |
| --- | --- |
| <a href="images/audit-custom-basic-full.png"><img src="images/audit-custom-basic.png" width="280" alt="audit-custom-basic" /></a> | <a href="images/audit-hole-capsule-full.png"><img src="images/audit-hole-capsule.png" width="280" alt="audit-hole-capsule" /></a> |

</details>

### 接入方案与类别

建议保留 **标准 Live Updates + 可选 Taku Island JSON 扩展**，通过同一条 Android Notification 承载，共享身份、生命周期和权限。标准路径降低接入成本；扩展承载 A/B 区、模板、点击方式和卡片位置。第三方不负责多岛管理。仅靠标准 Live Updates 无法满足完整自由样式需求，官方标准限制自定义通知视图：[Android Live Updates](https://developer.android.com/develop/ui/views/notifications/live-update)。

新 v2 已不要求 `category`，提供它反而会被视为未知字段；旧 v1 和内部系统来源仍有分类。`template` 是视觉选择，不应要求发布者先选业务类别才能上岛。小米文档有必填 `business`，用途是运营场景统计，并不等于通过类别选择 UI：[小米接入文档](https://dev.mi.com/xiaomihyperos/documentation/detail?pId=2131)。

本次仅验收，没有修复或修改产品代码。临时测试应用已卸载，发布白名单和开孔配置已恢复；Agent 恢复原 APK，测试会话的桌面状态已清理。真实请求留下的一条测试会话仍保留在聊天历史中，未清除原有数据。截图和说明单独存放在证据分支，不修改 main。
