# Taku Notebook

Notebook 把用户主动保存的截图、来源和笔记放在同一个收藏里。用户可以在应用里整理收藏，也可以让 Taku Agent 查找已有收藏并打开原帖。

## 使用

首次安装时，Notebook 设置中的 **Screenshot actions** 和 **到达时提醒我** 均默认关闭，可分别开启。

1. 在 Notebook 设置里启用 **Screenshot actions**、照片访问和 **Taku Notebook screenshots** 无障碍服务。
2. 在支持的社媒 App 内按电源键和音量减键。截图后，前摄附近显示 **Reading image**，随后展开 **Save**、**Send to…** 和 **Edit**。保存动作无需等待模型。
3. 点 **Save** 后，Notebook 保存原图，并提供 **Go to Notebook**、**Change List** 和 **Add note**。分类、摘要和查找原帖在后台执行。用户手动修改的归属和备注会保留。
4. 从收藏详情点 **View source** 打开已核实的原帖。X 链接优先交给 X App，手机未安装 X 或 X 无法打开该链接时，会用浏览器打开同一地址。已有收藏可以在链接未找到或查询暂不可用时点 **Try again**，重试会沿用同一条收藏。

截图入口支持 X、Instagram、YouTube、TikTok、Facebook、Discord 和小红书。自动查找原帖适用于 X、Instagram、YouTube、TikTok、Facebook 和小红书。它根据截图里的发帖者及正文等线索核对候选，也可以通过独立后台屏在原 App 里取得帖子分享链接。需要登录才能访问的帖子要求原 App 已登录。在 X 中停留时，需要原 App 的查找会等到用户离开 X 后继续。

收藏支持按时间、来源和列表筛选，多选后可移动或删除。用户可新增列表、改名、编辑收录规则，也可编辑收藏标题和用户备注。自动归类读取现有规则及最近的手动归类，无法明确归类时放入 **Temporary**。

## 构建和系统接入

- [Android.bp](Android.bp) 定义平台签名的 `system_ext` 应用 `TakuNotebook`。[image/taku.mk](../../image/taku.mk) 将基础版放入团队的 Pixel 9 userdebug 镜像，安装到 `/system_ext/app/TakuNotebook`。后续可用同包名 `org.agentos.notebook`、同平台签名、更高版本号的 APK 原地更新。
- [默认权限配置](default-permissions.xml) 向 Notebook 授予可撤回的照片和通知权限。定位权限由 TakuArrival 持有。[镜像校验](../../image/verify-artifacts.py) 要求整包包含 `/system_ext/app/TakuNotebook/TakuNotebook.apk` 和 `/system_ext/etc/default-permissions/taku-notebook.xml`。
- 目前由用户从 Notebook 设置进入 Android 无障碍设置，手动开启 **Taku Notebook screenshots**。服务监听窗口变化以识别截图来源 App，负责前摄附近的跨 App 浮窗，也能在独立后台屏读取控件、派发手势以取得原帖分享链接。首启引导或镜像预配由系统负责人确认。
- 使用 Android SDK 36、Build Tools 36.0.0 和 JDK 17+，运行 `bash system/notebook/build.sh`。默认 AOSP 测试签名仅适用于相应的开发系统。为目标 ROM 提供独立更新时，设置 `TAKU_NOTEBOOK_SIGNING_DIR` 指向受控的平台签名目录，运行 `bash system/notebook/build.sh /path/to/base-TakuNotebook.apk`。脚本在输出 `system/notebook/out/TakuNotebook.apk` 前核对包名、签名和递增的版本号。
- 原帖回链和 Agent 工具需同步更新 TakuAssistant 与 Task Broker。`bash system/assistant/build.sh` 生成 `system/assistant/out/AgentOSAssistant.apk` 和 `system/assistant/out/task-broker.jar`。系统镜像中的 Broker 安装在 `/system_ext/framework/agentos-task-broker.jar`。独立后台屏使用系统 `phone-display`。
- Taku AI 的 [`AiPolicy`](../assistant/broker/org/agentos/lab/AiPolicy.java) 默认允许 `org.agentos.notebook`。Taku AI 总开关及已保存的 Notebook 关闭设置仍然生效。原帖查找使用支持图片的 Codex 订阅模型，或成员均为 Codex 订阅模型的路由组。优先使用 `org.agentos.notebook.image`，未配置时沿用系统读屏路由 `screen`。可单独配置 `org.agentos.notebook.filing` 负责归类。摘要使用 Notebook 图片路由。

## Agent 接口

Notebook 界面和 Agent 读写同一份本机收藏。先用 `taku_phone.notebook_recall` 或 `taku_phone.notebook_query` 取得条目的 `id`、`sourceUrl` 和 `sourceResolution`。来源状态分别为 `pending`（查找中）、`linked`（已有核实链接）、`not_found`（未核实到匹配）和 `unavailable`（查询服务暂不可用）。用户要求打开时，把原条目 ID 交给 `taku_phone.notebook_open`，`destination=source` 打开原帖，`destination=detail` 打开收藏。

列表管理用 `notebook_lists`、`notebook_create_list` 和 `notebook_edit_list`。用 `notebook_edit` 修改标题、列表或追加用户备注，用 `notebook_batch` 批量移动或删除明确指定的条目。用户备注字段为 `description`，自动摘要为 `summary`。

系统集成可通过 `content://org.agentos.notebook` 调用以下接口。Agent、ADB shell 和平台签名组件可直接调用。普通 App 必须有权限中心的 `taku.control` 授权。第三方只读图片可以使用用户分享时授予的单个 URI 读取权限。调用的 JSON 对象放在字符串参数，返回结果位于 `Bundle.result`。后台屏恢复原帖的内部接口仅供 root Broker 调用。

| 用途 | 方法 |
| --- | --- |
| 读条目及列表 | `get`、`recall`、`queryEntries`、`lists` |
| 管理收藏与列表 | `editEntry`、`batchEntries`、`createList`、`updateList` |
| 查看及重试原帖 | `open`、`openSource`、`retrySource`、`copySource` |
| 使用姿势参考 | `recall` 参数 `purpose=pose_reference`，`openPose` 按 ID 打开 |
| 到达提醒 | `placeReminderStatus`、`setPlaceRemindersEnabled`、`placeReminderIntent` |

## 到达提醒和相机

在 Notebook 设置中打开 **到达时提醒我**，开启系统定位和 Notebook 通知。模型识别具体地点后，Notebook 只把地点名称和原文能支持的区域提交给 Android 地点解析。地理编码结果须唯一并符合原文支持的区域。服务只返回地址时，带区域和仅用地名的两次查询须指向同一个具体街道地址。场馆、商铺和餐馆用 200 米，景点 300 米，沙滩 450 米。宽泛的地点类别和国家级意图暂不登记。

手机离线或地点解析服务不可用时，收藏与其他后台任务照常保留。到达开启期间，地点查询会由后台任务重试。空结果、歧义、离线及服务不可用显示在详情和 `placeReminderIntent` 的 `resolution.status`。可用状态为 `analyzing`、`pending`、`reminders_off`、`no_specific_place`、`resolved`、`offline`、`geocoder_unavailable`、`not_found` 和 `ambiguous`。只有系统地理编码成功才会产生坐标。

Notebook 按地点去重，把最近的最多 16 个已解析地点交给 [TakuArrival](../environment-signals/arrival/README.md)。监测 23 小时有效，系统后台任务在临近到期时续期。TakuArrival 需要先观察到围栏外的定位，再观察到两次围栏内定位。启用、重启和应用更新后恢复登记，关闭或删除收藏会取消不再适用的登记。由 TakuArrival 采样和发到达事件，Notebook 按事件 ID 发布并确认，通知不会常驻。

到达通知用 Android 标准标题、展开正文、收藏缩略图和两个操作。系统要求隐藏私密内容时显示通用提示。认证后，**View post** 打开同一条收藏的原帖，待回链时提供 **View saved item**。末尾的 **Go there** 打开 Google Maps 步行路线。目标包包含团队锁屏时按其统一卡片呈现。

姿势参考可从符合条件的收藏打开配套相机。Notebook 向相机发送 `org.taku.camera.action.USE_NOTEBOOK_POSE`，用临时 URI 读取权限提供该条收藏原图及其 SHA-256。相机需要实现该入口。

## 最短复验

1. 打开一条带作者和正文的公开 X 帖子，系统截图后点 **Save → Go to Notebook**。
2. 打开新收藏，核对原图、标题和列表。等详情显示 **View source** 后，点开确认是同一条 X 帖子。
3. 让 Taku Agent 查找刚保存的收藏，确认返回同一条记录及原帖链接。
4. 启用到达提醒。保存一条含有明确目的地的新帖，通过 `placeReminderIntent` 确认系统已解析该地点。在独立模拟器从范围外到范围内依次注入定位，等到达通知出现，再依次检查 **View post** 和 **Go there**。开启安全锁屏时先通过设备认证。
