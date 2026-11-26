# Pixel 视觉跟踪

在机器狗 App 底部 Taku AI 输入“跟踪前面黑裤子黑鞋子的人”，使用系统文字模型理解意图，然后打开手机本地视觉跟踪页。当前按用户最后确认的范围只检测人体，不检查衣物颜色，不识别个人身份。单人入镜自动选择；多人需点击目标。目标消失、遮挡或换流后不会自动选择另一个人，需要重新点选。

## 页面与边界

- 原始 Go2 摄像头实时 JPEG → Pixel Web Worker 内 MediaPipe Pose Landmarker Lite → 人体框、视觉距离变化、行进建议。所有视觉资源在手机本地，无外部 CDN 请求。
- 页面没有“拍摄”“演示”“遥控器执行”等标签。它显示“行进建议”，不声称动作已执行。
- 进入视觉跟踪后，手机服务开启 observationOnly：拒绝运动、模式切换等控制写入。App 失焦、取消、停止跟踪、切换 AI/人工图标也不会发 StopMove 或 gait 切换，避免干扰外部控制。
- 该限制持续到当前手机服务退出；需要恢复此前的直接运动控制时，从连接设置重启真实服务。这一步会短暂重连。不能把观察模式中的“人工接管”理解为机器人已切换控制协议。
- 距离百分比来自相对首次选择时的腿部尺度；正数表示变远。点“距离标定”，输入已知镜头至人物距离后可显示估算米数。它不是测距传感器读数；坐下、弯腿、镜头俯仰和畸变会影响估算。目标丢失时清空数值。
- 建议是目标在画面中的左右位置、远近变化，不含障碍物判断、路径规划或自动跟随。

## 使用与现场验收

1. Pixel 和 Go2 在同一网络，App 真实连接成功。打开 Taku AI，用中文拼音输入跟踪请求并发送。
2. 人站到镜头前约 2 米，腿部完整、清晰入镜。确认显示人物框、“已锁定人物”。当前模型不适合贴近镜头、坐姿腿部遮挡或只露出局部的构图。
3. 缓慢左右移动，看建议变化；走远/靠近，看距离百分比变化。若要米数，先做已知距离标定。
4. 离开画面：框和距离消失、建议停止；重新入镜后点击人物或“重新选择”。
5. “停止跟踪”只停止视觉跟踪。页面上的建议由用户自己的设备执行。

## 复现

`python3 tooling/go2-lab/prepare-vision.py` 下载并核对 `vision-assets.lock.json` 中固定的 npm 包、模型和 SHA256。二进制资源不入 Git，`install-runtime.py` 会准备并复制到 Pixel。

MediaPipe Tasks Vision 1.2.0（Apache-2.0）：https://www.npmjs.com/package/@mediapipe/tasks-vision 。模型及 33 个关键点定义来自 Google 官方文档：https://developers.google.com/edge/mediapipe/solutions/vision/pose_landmarker 。Web Worker 用法与同步检测限制：https://developers.google.com/edge/mediapipe/solutions/vision/pose_landmarker/web_js 。

`node --test tests/go2-follow-person.test.mjs` 验证目标选择、距离标定、丢失、方向稳定性。`tests/helpers/verify-go2-vision.cjs <本地相机图> --page` 可在桌面浏览器中用指定离线素材验证真实模型与 UI；这是独立测试，不将素材发送到 Pixel 或替换真实视频。需要安装 Playwright/Chromium。

2026-10-29：已在 Pixel 安装 0.3.0-pixel.1，中文拼音上屏和系统 AI → show_follow → 实时画面已验证。手机本地模型已加载，单次推理观察约 70–101ms；当时画面为近距离坐姿局部，框不能稳定保持。完整入镜与移动的现场验收由用户稍后完成，不标为已通过。
