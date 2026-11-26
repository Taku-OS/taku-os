# 第三方组件与分发边界

本次提交的原创源码遵循当前仓库的 Apache-2.0 许可。第三方音频、图像、驱动和依赖保留各自的来源与许可，见 `../THIRD-PARTY.md`。历史内部开发包记录不代表当前发布状态。

- Go2 WebRTC 使用 [unitree_webrtc_connect](https://github.com/legion1581/unitree_webrtc_connect)，固定提交 `e0abc5780761539eff89a382a79319e5cf6ad1f4`；其 [MIT 许可](https://github.com/legion1581/unitree_webrtc_connect/blob/e0abc5780761539eff89a382a79319e5cf6ad1f4/LICENSE)署名 Konstantin Severov。驱动由 setup 从上游安装，ZIP 不捆绑其源码／二进制；安装后的 wheel 保留原许可。
- Python 运行依赖版本列在 `requirements.txt`，包括 aiortc、PyAV、NumPy、requests、cryptography 等；分别遵循上游许可，由依赖管理器联网安装，不改变原许可。PyAV wheel 的底层媒体组件以该 wheel 的构建和许可说明为准。
- Android SDK、JDK、uv、Node 与 AVD 不包含在包内，同事使用自己的合法开发工具。Android API stub 不打入 App。
- 构建使用脚本固定并校验的公开 AOSP **开发测试签名**；APK 可被任何拥有该公开测试材料的人替换，不作生产身份保证。ZIP 不包含测试私钥或任何个人／公司签名私钥。
- 高德地图图片和路线通过每位开发者自己的 Key 实时取得，按高德服务条款使用；交付包不包含地图瓦片库、Key 或用户位置记录。
- 2026-10-27 对固定 requirements 运行 pip-audit，未报告已知漏洞。它不覆盖 Git 安装的社区驱动代码、所有原生媒体库或机器人固件，也不是零风险保证。
