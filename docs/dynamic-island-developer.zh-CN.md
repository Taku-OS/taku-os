# 灵动岛开发者接入指南

[English](dynamic-island-developer.md) · 简体中文

本指南介绍 Android 应用如何在 Taku OS 灵动岛上展示正在进行的活动。协议定义见
[`spec/taku-island-v2.schema.json`](../spec/taku-island-v2.schema.json)，设计与决策见
[v2 规范](dynamic-island-v2-spec.md)，源码和设备验收状态见[进度记录](dynamic-island-progress.md)。

无需接入 Taku SDK，两条路径都通过普通的 Android 通知发布：

- **路径 A：Android Live Updates（优先推荐）。** 发布一条提升展示优先级的持续通知。它适用于原生 Android 和其他厂商的系统，无需 Taku 审批。
- **路径 B：Taku 扩展 `taku.island.v2`。** 在同一条通知的扩展字段中附加 JSON，用来表达 Live Updates 不支持的能力：
  - 自定义胶囊图标、动效、数字或百分比。
  - 卡片模板、布局和强调色。
  - 点击胶囊时打开应用或展开卡片。
  - 指定卡片位置。
  - 自动展开卡片。

  同时让这条通知满足 Live Update 的条件，以便在其他系统上正常回退展示。

## 1. 何时使用灵动岛

**适合使用**：通常持续数分钟到数小时，且用户需要持续关注的活动或服务，例如取件码、航班或列车动态、配送进度、导航、正在进行的录音和计时器。

**每项活动**都要有明确的开始和结束。结束时由应用取消通知，总时长须在 **12 小时以内**。

**不适合使用**：

- 几秒钟就结束的事件。可用普通通知、顶部横幅或应用内 Toast。
- 静态结果，例如“更新完成”“已登录”“网络已连接”“已保存”或应用错误。
- 营销和促销。
- 点赞、评论等频繁打断用户的事件。
- 没有明确结束时间的服务，例如持续跟踪股价。

路径 B 的允许名单按这些规则审核，由 SystemUI 执行时长限制。

## 2. 用户看到的界面

灵动岛有两种形态，同一时间只显示一种：

- **紧凑态胶囊。** 位于前置摄像头周围的黑色胶囊。
  - **A 组**在摄像头左侧，显示一个图形，可以是图标、动态图标或环形进度。
  - **B 组**在摄像头右侧，用短语、数字、运行中的计时器、百分比或图标展示关键信息。
  - 文字只显示一行，在状态栏图标前按可用空间截断并加省略号。文字不滚动，也不缩小字号。连短文案都放不下时，只保留 A 组。
- **展开态卡片。** SystemUI 根据模板原生绘制，使用 92% 不透明度的黑色背景和 40 dp 圆角。卡片可以覆盖摄像头开孔（`cardPosition: "cover"`，默认值），也可以位于开孔下方（`"below"`）。键盘可见时强制使用 `below`。

| 位置 | 手势 | 结果 |
| --- | --- | --- |
| 胶囊 | 点击 | `tap: "expand"`（默认）展开卡片，`tap: "open"` 触发内容 Intent |
| 胶囊 | 长按 | 展开卡片，行为不受 `tap` 的设置影响 |
| 胶囊 | 左滑 | 有多个活动时切换到下一个 |
| 胶囊 | 右滑 | 隐藏该活动，直到对应通知结束 |
| 卡片 | 点击正文 | 触发 `tapAction`，未配置时触发内容 Intent |
| 卡片 | 上滑或返回 | 收回为胶囊 |

系统行为：

- **多个活动。** SystemUI 管理活动，应用只能发布自己的活动。来电、闹钟和屏幕录制优先显示，其余活动按最近一次*触发*的时间排序。首次发布，或更新时将 `alert` 从 `false` 改为 `true`，都算一次触发。常规更新不会改变排序。下一个活动会在胶囊后显示为小圆盘，使用该活动的 A 组图形。
- **应用在前台。** 当应用位于任务栈顶部时，其胶囊隐藏。用户离开应用后，如果通知提供了胶囊数据，胶囊可以显示。
- **顶部横幅（Heads-up）。** 对路径 B 的活动，当系统可以展示灵动岛时（已解锁、通知面板已关闭、竖屏），不会同时弹出 Android 原生横幅。可以用 `alert: true` 让卡片短暂展开。纯 Live Updates（路径 A）保留 Android 原有的横幅行为。
- **生命周期。** 以下条件以最先发生的为准：
  - 应用取消通知。
  - 到达应用声明的超时时间（`timeoutS` 或 `setTimeoutAfter`）。
  - 未声明超时时间时，距首次发布已满 **1 小时**。
  - 达到 12 小时上限。

  更新不会延长活动时长。灵动岛到期后，通知本身仍留在通知面板中。
- **暂不支持锁屏、息屏显示（AOD）及副屏。** 用户在锁屏和通知面板中看到的是普通通知，请提供完整的标题、正文和操作。

## 3. 路径 A：Android Live Updates

遵循 Android 的要求：

- 发布具有标题的持续通知。
- 使用标准样式、BigText、Call 或 `ProgressStyle`。
- 不使用自定义视图、着色背景或通知组摘要。
- 在 manifest 中声明 `android.permission.POST_PROMOTED_NOTIFICATIONS`。
- 请求提升通知的展示优先级。

用户可以按应用关闭这项功能。通过 `NotificationManager.canPostPromotedNotifications()` 查询当前状态。

```kotlin
val notification = Notification.Builder(context, channelId)
    .setSmallIcon(R.drawable.ic_delivery)          // group A when there is no large icon (tinted white)
    .setLargeIcon(courierIcon)                      // group A, untinted
    .setContentTitle("Delivery on the way")         // card title
    .setContentText("Courier is 12 minutes away")   // card body
    .setShortCriticalText("12 min")                 // group B
    .setStyle(Notification.ProgressStyle()
        .setProgressSegments(listOf(Notification.ProgressStyle.Segment(100))).setProgress(40))
    .setOngoing(true)
    .setRequestPromotedOngoing(true)                // API 36.1; on API 36 put extra "android.requestPromotedOngoing"
    .setTimeoutAfter(2 * 60 * 60 * 1000L)           // the island's declared end (≤ 12 h)
    .setContentIntent(openOrderPendingIntent)
    .addAction(cancelAction)                        // becomes a card chip
    .build()
notificationManager.notify(TAG, ID, notification)
```

| 通知字段 | 灵动岛展示 |
| --- | --- |
| 优先使用大图标，否则使用着色的小图标 | A 组 |
| 优先使用 `setShortCriticalText`，否则使用计时器（`setUsesChronometer`／倒计时），再回退到 `when` 时间 | B 组 |
| `ProgressStyle` 中的进度，或旧版 `setProgress` | 带进度条的 `progress` 卡片 |
| 标题和文本 | 卡片标题和正文 |
| 操作 | 按顺序显示为卡片按钮 |
| 内容 Intent | 点击卡片和胶囊中的展开图标时使用 |
| `setTimeoutAfter` | 灵动岛存续时间，默认 1 小时 |

Live Updates 固定使用 `tap: expand` 和 `cardPosition: cover`。

## 4. 路径 B：`taku.island.v2`

### 发布授权

SystemUI 只读取已获授权应用的自定义扩展字段：

- **使用平台签名的 Taku 应用**在 manifest 中声明
  `<uses-permission android:name="org.agentos.permission.POST_ISLAND"/>`。这是签名权限。
- **其他应用**需要按第 1 节的规则通过审核，之后才可加入用户可见的允许名单（设置 → 显示 → Activity island，对应 `taku_island_allowed_packages`）。请联系 Taku OS 团队申请。
- `org.agentos.notebook` 已预授权。系统会在首次启动时将它加入允许名单。若使用平台签名，也可以声明 `POST_ISLAND`。

未获授权或扩展字段无效时，SystemUI 会忽略这些字段。如果该通知符合 Live Update 条件，则使用对应的展示方式。通知本身不会被丢弃。

### 示例：语音备忘录

```kotlin
val stop = Notification.Action.Builder(null as Icon?, "Stop", stopPendingIntent).build()
val payload = JSONObject()
    .put("version", 2)
    .put("tap", "open")                 // tapping the capsule opens the recorder
    .put("timeoutS", 7200)              // declared end: at most 2 h of recording
    .put("compact", JSONObject()
        .put("a", JSONObject().put("type", "icon").put("pic", "mic").put("tint", true))
        .put("b", JSONObject().put("type", "timer").put("startEpochMs", startedAt)))
    .put("expanded", JSONObject()
        .put("template", "basic")
        .put("title", "Recording voice memo")
        .put("body", "Inbox · 00:42 saved so far")
        .put("actions", JSONArray(listOf("stop")))
        .put("primaryAction", "stop"))
val extras = Bundle().apply {
    putString("taku.island.v2", payload.toString())
    putBundle("taku.island.pics", Bundle().apply { putParcelable("mic", Icon.createWithResource(context, R.drawable.ic_mic)) })
    putBundle("taku.island.actions", Bundle().apply { putParcelable("stop", stop) })
}
val notification = Notification.Builder(context, channelId)
    .setSmallIcon(R.drawable.ic_mic).setContentTitle("Recording voice memo")
    .setOngoing(true).setUsesChronometer(true).setWhen(startedAt)
    .setContentIntent(openRecorderPendingIntent).addAction(stop).addExtras(extras).build()
notificationManager.notify("memo:" + memoId, 1, notification)
```

使用相同的 tag 和 ID 重新发布即可更新，取消通知即可结束活动。新活动使用新的 tag 或 ID。

### 数据字段参考

| 字段 | 规则 |
| --- | --- |
| `version` | `2` |
| `compact` | 可选。省略时不显示胶囊 |
| `compact.a` | `{"type":"icon","pic":KEY,"animate"?,"progress"?,"tint"?,"contentDescription"?}` 或 `{"type":"progress","value"?}`。`animate` 每次更新最多播放 60 秒的 AnimatedVectorDrawable 动画。用户关闭动画时不播放。`progress`（0–1）在图标周围绘制环形进度 |
| `compact.b` | `{"type":"text","text":≤16,"emphasis":"normal"\|"accent"\|"muted"}`、`{"type":"number","value":"1"-"999","unit"?:≤4}`、`{"type":"timer","startEpochMs"}`、`{"type":"countdown","endEpochMs"}`、`{"type":"progress","value"?}`（显示为百分比）或 `icon` |
| `animation` 槽位 | `ai-working` 和 `ai-listening` 为 Taku Agent 专用，其他发布方使用时会收到 `invalid_animation` |
| `expanded.template` | `basic`（文字和操作）、`progress`（增加进度条，须提供 `progress`）、`highlight`（展示一个大号数值，须提供 `highlight`）。`assistant` 为 Taku Agent 专用 |
| `expanded.title` / `body` | 分别不超过 80 / 2000 个字符 |
| `expanded.pic` | 标题区图标，默认使用通知的小图标 |
| `expanded.steps` | 最多 8 行，每行为 `{kind,text≤120,running?}` |
| `expanded.progress` | `{"value"?: 0–1}`。省略 `value` 表示不确定进度 |
| `expanded.highlight` | `{"text":≤12,"label"?:≤40}`，可展示取件码、登机口或排队号码 |
| `expanded.actions` | 引用 `taku.island.actions` 中的键，按显示顺序排列为一行可横向滚动的按钮。设计无数量上限，解析时最多接受 32 项 |
| `expanded.primaryAction` | `actions` 中的一个操作，使用实心按钮样式 |
| `expanded.tapAction` | 点击卡片正文时触发的操作键，无需同时显示为按钮。默认触发内容 Intent |
| `expanded.accentColor` | `#RRGGBB`，与 `#121212` 的对比度须至少为 4.5:1，否则返回 `low_contrast`。用于主操作按钮、进度条、高亮数值和 `accent` 胶囊文字 |
| `tap` | `expand`（默认）或 `open` |
| `cardPosition` | `cover`（默认）或 `below` |
| `alert` | 从 `false` 变为 `true` 时，将活动移到最前并展开卡片，最长 5 秒 |
| `timeoutS` | 1–43200 秒。自首次发布起计算，默认 3600 秒 |
| `staleAfterS` | 1–43200 秒。这段时间内未更新时，胶囊以弱化样式显示 |

通用规则：

- JSON 最大 8192 字节（UTF-8），嵌套深度不超过 8。
- 未知字段、重复键及字段类型错误都会被拒绝。
- 键名必须匹配 `[a-z][a-z0-9_]{0,31}`。
- 图片以 `Icon` 的形式放在 `taku.island.pics` 中。支持不超过 512×512 px 且不超过 1 MiB 的位图，以及应用自身的资源图标。系统不会加载 URI 图标或数据图标。
- 操作使用真实的 `Notification.Action`，其中的 PendingIntent 必须由发布应用创建。
- 不允许标记语言、脚本或 URL。

`category`、`relevance`、`bigIsland`、`smallIsland`、`statusBar` 和 `islandTimeoutS` 属于 v1 协议，在 v2 中会被拒绝。

### 设计限制

B 组尽量使用短语或数字，建议控制在约 8 个拉丁字符或 4 个中日韩字符。胶囊与时钟和状态栏图标共用空间，较长的文字会被截断。卡片内容需要适应所选模板，正文可在卡片内滚动，卡片高度最多为屏幕的三分之一。Taku 设计团队将在此补充其他视觉约束。

## 5. 检查与调试

应用可以查询自己是否有发布权限。只能查询自身包名，省略包名时也会查询自身：

```kotlin
// Add <queries><provider android:authorities="com.android.systemui.island" /></queries> to your manifest.
context.contentResolver.query(Uri.parse("content://com.android.systemui.island/canShowIsland"),
    null, null, null, null)?.use { if (it.moveToFirst()) JSONObject(it.getString(0)) }
// → {"ok":true,"enabled":true,"package":"…","customAllowed":false,"liveUpdates":true}
```

通过 ADB 检查（开发用 shell 拥有完整访问权限）：

```sh
adb shell cmd statusbar island-validate org.example.app '{"version":2,…}'   # same parser and publisher checks
adb shell cmd statusbar island state       # activities, what is on screen, diagnostics
adb shell cmd statusbar island get 'KEY'   # canonical payload, source, tier, triggeredAtElapsedMs, timeoutKind
```

`diagnostics` 会列出 `KEY:error` 条目。常见错误码：

| 错误码 | 含义 |
| --- | --- |
| `publisher_not_allowed` | 应用没有 `POST_ISLAND` 权限，也未加入允许名单 |
| `invalid_references_or_host` | Bundle 中缺少引用的 `pic` 或操作键，图标类型不受支持，或 PendingIntent 不属于发布应用 |
| `unknown_field`、`invalid_enum`、`text_length`、`number_range`、`invalid_key`、`expected_*` | 不符合 Schema 约束 |
| `missing_progress`、`missing_highlight`、`highlight_template_only` | 缺少模板所需数据，或将数据用于不匹配的模板 |
| `missing_primary_action`、`duplicate_action`、`too_many_actions` | 操作列表无效 |
| `invalid_color`、`low_contrast` | `accentColor` 无效 |
| `reserved_template`、`invalid_animation`、`reserved_content` | 该能力仅供 Taku Agent 或平台应用使用 |
| `payload_too_large`、`too_deep`、`unbalanced_json`、`invalid_payload` | JSON 无效 |

第三方应用不能读取其他应用的活动。应用读取或操作灵动岛需要应用权限中心授予 `taku.control` 权限，且始终无法写入设置。详见 [Agent 命令](../system/assistant/assets/skills/taku-island/SKILL.md)。

## 6. 从 v1 迁移

系统仍然接受 `taku.island.v1`，会先将它转换为 v2，再按 v2 的发布方规则校验：

| v1 字段 | v2 字段 |
| --- | --- |
| `bigIsland.leading` 中的图形 | `compact.a` |
| `bigIsland.trailing` | `compact.b` |
| `smallIsland` | 当 `leading` 为文字时，用它填充 `compact.a` |
| `compact: hidden` | 省略 `compact` |
| `statusBar: below` | `cardPosition: below` |
| `islandTimeoutS` | `timeoutS`，上限现为 43200 秒 |
| `text`、`steps`、`media` 模板 | `basic` |
| `category`、`relevance` | 移除，活动按触发时间排序 |

请发布 `taku.island.v2`。如果应用还需要在旧版 Taku 系统镜像上运行，请在同一条通知中同时发送 v1，直到这些镜像完成更新。当前 SystemUI 优先读取 v2。

## 7. 平台签名应用：富内容进程

`expanded.content` 可以通过 `SurfaceControlViewHost` 嵌入由应用自身进程渲染的正文。只有**使用平台签名的发布方**可以使用，否则返回 `reserved_content`。

- `service` 在发布应用的包内，使用 `package/full.ServiceClass` 格式。服务必须导出，并由签名权限 `org.agentos.permission.BIND_ISLAND_CONTENT` 保护。
- `token` 是由应用定义的不透明值，最多 256 个字符。`layout` 可设为 `body`（默认）或 `card`。`heightDp` 的取值范围是 96–340，且不得超过屏幕高度的三分之一。

协议见 [`IslandContentProtocol.java`](../system/island/src/org/agentos/island/IslandContentProtocol.java)：

- 绑定服务后返回 Messenger。
- ATTACH（`what=1`）在 `arg1` 中携带代次标识，并提供 `replyTo` Messenger。Bundle 字段包括 `version=1`、`hostToken`、`width`、`height`、`display` 和 `token`。
- 首帧渲染后回复 READY（`what=3`），在 Bundle 的 `surface` 字段中返回 SurfacePackage。失败时回复 FAILED（`what=4`）。
- RELEASE（`what=2`）释放对应代次的资源。
- 宿主超时为 2500 ms，宽高均不得超过 4096。
- 绑定过程中或绑定失败后，原生模板继续显示。同一张已打开的卡片不会自动重试。
- 内容服务终止时，不要取消正在进行的任务。

## 8. Taku Agent

Taku Agent 会发布 `taku.island.v2`，并同时发送 v1 以兼容旧版镜像。

- 卡片使用专用的 `assistant` 模板和 `expanded.assistant:{token,phase,heightDp,suggestions}`。由 `IslandAssistantCard` 绘制，`suggestions` 只能引用实际存在的操作 ID。
- 只有在构建应用时才显示胶囊，使用 `ai-working` 动效及 “Building...” 文案。
- 首次开机引导中的登录活动使用 Taku 自己的进度卡片样式。
