# Taku Memory daily-app acceptance — Chinese summary

This is the intentionally retained Chinese summary. The complete English report is [README.md](README.md); original evidence remains unchanged.

## 中文说明

本轮已换成真正的 Android 日常 App：短信、Fossify 日历、Organic Maps 地图，未使用网页或合成页面 App 冒充。场景内容是明确标注的演示短信、本地日程及路线规划，不是用户私人会话、真实公司日程或预订。

短信和日程完成了「自动记忆 → 手机 Magpie / 真实 Jev → 英文轻提示 → 实际点按 → 原 TaskBroker / Agent → 成功 memory_get → 英文结果」；地图真实算出步行路线，分类为 Travel，但模型返回 none、置信度 0.69，因此保持安静，没有任务。三种结果均按实际决策记录，没有为了截图强行弹建议。

中英文界面、分类显示、设置、语言选择、轻提示和新任务回复语言已适配，以英文为主要展示。实际切换往返后保留 30 条记忆，原文、配置和批计数不变，不因换语言请求模型。既有模型、凭据和旧记录保留。

143 项隔离检查和仓库验证通过。原 27 条记忆保留，新增三条有用的实际 App 记忆；仅删除本轮自动记录的一个空白相邻日期页面。一次意外退出后专用模拟器的数据与已完成任务保留，只说明本次恢复，不代表真机或整包冷启动通过。自动记忆等开关、来源与无障碍设置和模拟器暂停状态恢复，英文按本次要求保留。

本轮是**模拟器组件验收**。同版真机尚未验收，独立记忆 APK 尚未接入产品 ROM 构建，也没有完整 ROM 验收结论。完整摘要、真实工具回执、制品及截图哈希见本页上方证据链接。
