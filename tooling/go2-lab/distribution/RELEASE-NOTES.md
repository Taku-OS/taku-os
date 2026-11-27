完整 Go2 App 开发组件首次内部预览：状态与点云、受限手动微动、音频播放／音量、操作时摄像头、避障模拟、命名地点／视觉导航模拟、高德路线和地理跟随模拟。

推荐下载 `taku-go2-devkit-0.1.0-preview.1.zip`，解压阅读 README。它包含预编译 APK／AI CLI、Mac 服务、完整源代码、技能、测试、安装与构建脚本。个人配置及 Key 不随包分发。运行需要 Mac 桥接；这不是独立 APK 或完整 Taku ROM。

已验收环境：Mac Apple Silicon、Android API 36 的 Taku userdebug 开发模拟器。AI 工具安装需要可 adb root 的开发模拟器；仅安装 App 不要求 root。开发包使用公开 AOSP 测试签名，不用于生产。

高德需要各自的 Web 服务 Key；依赖首次联网安装。真实自动导航和未验收的 PRO 原生避障控制保持禁用，不能把模拟成功当作实机能力。详细已验证和待验证项见包内文档。
