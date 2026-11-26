# Go2 Robot Panel — Pixel 界面适配

2026-11-09，版本 `0.7.0-robot-panel` / versionCode `15`。

设计依据：[Taku Mobile OS / Robot panel，338:4118](https://www.figma.com/design/MdImIDNqePpNZ62C0GLArW/Taku-Mobile-OS?node-id=338-4118)。只读取设计稿；主视觉图片和背景来自该设计稿。实现保留现有 Go2 App、手机本机通信服务和功能接口，不替换底层通信协议。

## 使用入口

打开手机上的 **Go2**，进入 **My Device**。

- **Skills**：集中打开各功能；首页也有六张功能卡。
- **Settings**：连接方式、狗的 IP、本机服务启停；设备 Key 收在 **Advanced** 中。
- **中间电源图标**：连接或断开会话，不代表给机器狗开机或关机。断开需要再次确认。
- **状态胶囊**：实际连接状态、电量、App 行进控制状态。电量缺失或超过时效显示 `—`，不会用设计稿的样例电量。
- **底部 Taku AI**：输入任务；左侧手掌/星光切换人工/AI，右侧箭头发送；右上角省略号打开系统 AI 设置。结果摘要可点击查看完整内容。
- **页面左上角**返回首页，右上角省略号打开全部功能。系统返回键优先关闭弹窗，再返回首页。
- 首页右上角切换中英文。原有场景脚本和底部 AI 保持用户要求的英文呈现。

## 功能对应

| 新入口 | 对应现有能力 | 本轮处理 |
| --- | --- | --- |
| Live Camera | 摄像头、人物框、距离变化、AI 建议、原有场景流程 | 统一视觉，减少重复标题，保留图像/建议布局；打开页面不自动启动跟随 |
| Voice & Sounds | 27 条预设、0–100% 音量、播放、停止 | 新增搜索、手机试听、导入入口；保留 21 条英语、3 条中文、3 条音效及来源标注 |
| Movement | 前后/转向、受限速度、启用/停止 | 调整操作顺序，速度设置折叠；准确显示当前控制策略，禁用不允许的操作 |
| Surroundings | 雷达、点云、环境观察 | 统一页面、按钮、卡片和状态视觉 |
| Navigation | 定位状态、路线准备、导航控制 | 保留定位质量和导航限制，不把未验证能力显示为已完成 |
| Obstacle Assist | 避障状态、净空与相关操作 | 保留现有检查和权限，统一视觉 |
| 全部功能菜单 | 原始摄像头、本地导航、高德路线、设备详情、音频导入 | 保留独立入口，替代原有拥挤的顶栏 |

真实设备继续使用既有 `external_motion_control` 策略，伴随遥控器负责行进，App 可显示摄像头、建议及播放语音。这次界面更新没有开放新的电机操作权限。自主导航的原有验收限制继续适用。

## 技术决定

- `panel.html/js/css` 与两张主视觉 PNG 随 APK 打包，采用明确资源白名单。连接服务停止后，首页和 Settings 仍可打开。
- 其他页面继续由手机 Termux 服务提供；`design-system.css` 为共享视觉层。更新时须同时更新 APK 和手机运行目录，不能只安装 APK。
- JavaScript 原生页面入口使用固定页面名白名单；沿用 loopback、Host/Origin 检查及现有 WebView 外链限制。
- 首页轮询有并发保护和超时；后台停止主动轮询，电量另按收到时间计算过期。
- 手机试听只读取已有预设文件，选择音频、切换页面、开始狗端播放或停止时会结束试听。选择列表项仍然不会自动让狗播放。
- 系统 AI 已从旧 `read.textModel` 读取方式迁至 `resolve("text")`，由系统验证 App 的模型路由。此次 Pixel 缺少 Go2 文字场景绑定，将它绑定到手机现有主助手的文字路由组，没有替换模型或复制凭据。

## 2026-11-09 验证

真实 Pixel 9：

- APK 构建、签名、覆盖安装通过。
- 20 项手机 WebView 检查通过，包括所有主要页面、无横向溢出、Skills 弹窗、双语切换、音频筛选/搜索/导入入口；无 JavaScript 异常。
- 本机服务完全停止后，APK 自带首页仍正常显示，Settings 可用；之后恢复真实连接模式的本机服务。
- 手机试听实际进入播放并正常结束，测试后恢复原有 Dog bark 选择；没有在狗上播放。
- Pixel 连上热点后，Taku AI 收到“查看电量，不要连接或移动”的请求，输出 `show_state` 并打开设备详情，明确提示没有连接时不能读电量。任务完成，无连接或运动操作。
- 本轮机器狗关机。实时画面、雷达、狗端音频和运动均未进行新的实机验收。

自动检查：

- Python：HTTP 13、assistant 17、follow HTTP 2、motion 16、audio 10、audio library 7、companion 6 项通过。
- Node：motion UI 16、follow tracker 4、sound controls 4、encounter 12 项通过。
- 浏览器声音页：27 预设、筛选、台词、选择不自动播放、音量松手提交、播放/停止、来源与窄屏布局通过。
- 浏览器首页：唯一元素 ID、页面入口、翻译、连接忙碌态、实时/过期电量、断开二次确认、服务离线恢复通过；测试 HTTP 全部拦截，不连接狗。
- `git diff --check`、JavaScript 语法、Python 编译通过。

浏览器测试需要 Playwright，可用 `PLAYWRIGHT_EXECUTABLE_PATH` 指定已有 Chromium：

```sh
node tests/go2-panel.test.cjs
node tests/go2-sound-page.test.cjs
node tests/go2-motion-ui.test.mjs
```

本机证据：`out/go2-ui-acceptance/`；升级前 APK 和手机源代码备份：`out/figma-ui-backup/`。这些均不进入 Git。AI 配置备份含私人配置，只保存在受限权限的本机文件中。

## 下一次开狗验收

1. Pixel 和 Go2 加入同一个热点；若 IP 改变，在 Settings 更新地址，并检查已有 VPN 的狗 IP 直连规则。
2. 连接后确认电量变为实时读数，再查看 Live Camera 与 Surroundings。
3. Voice & Sounds 先手机试听，再用低音量验证狗端播放和停止。
4. 检查原有 Stay / Circle / Come back / Bark 场景与 AI 建议。保持既有外部行进控制策略。
5. 独立安排真实导航与自动跟随的场地验收，不能以本轮 UI 验证代替。
