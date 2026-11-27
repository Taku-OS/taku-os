机器狗 App 此前只存在于本地实验分支，同事无法独立安装完整功能。这个 PR 将 Go2 App、Mac 桥接和离线模拟、AI CLI／技能、高德接入及全部相关测试整理到最新 main 之上，不包含其他 IoT／喂食器／Phone Link 实验分支改动。

提供 `taku-go2-devkit/v1` ZIP：预编译 APK／CLI、完整源码、安装器、固定依赖、manifest 和逐文件 SHA-256。安装器只添加机器狗 App；AI 工具显式选装，校验目标 AVD，不刷系统、不复制账号或高德 Key。GitHub Actions 可构建评审下载附件，维护者可按文档发布私有 prerelease。

现有实机手动功能保持原边界；地理／视觉自主导航仍只允许模拟，PRO 原生避障、绝对定位和雷达配准仍需现场验收。没有通过发版放开真实自动运动。

验证：干净目录安装固定运行依赖；Go2 离线回归；APK／CLI 构建；完整 ZIP 解压、校验和及模拟服务启动；实际 Taku 开发模拟器安装与页面烟雾测试。具体本次结果在 `tooling/go2-lab/distribution/VERIFICATION.md`，历史功能证据在各 Go2 验收文档。

版本为 `0.1.0-preview.1`，使用公开 AOSP 开发测试签名。交付不包含高德 Key、模型认证、虚拟机、个人录音／录像或本机日志。此次 PR 不合并或发布生产版本。
