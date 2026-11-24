# Tesla Control：团队开发分支

这份分支把原有手机控车归档移到当前团队仓库，保留可复用代码和已发生的实车结果。团队可以先在电脑离线构建，再在设备和车辆都可用时按本文复跑。**本轮没有访问 Pixel、连接车辆、安装组件或发车控；没有合入 main。**

表中的 P1「Tesla Control」写的是“远程控制车机”。这里已有的是手机调用官方 Tesla App 的控制入口；命令动作限定手机钥匙蓝牙，闪灯/鸣笛交给官方小组件。跨网络远程控车没有打通，不作为本分支承诺。

## 入口和文件

- [Skill](skill/tesla-model-y/SKILL.md)：手机 Agent 的使用规则。
- [动作目录](skill/tesla-model-y/capabilities.json)：八个动作、参数、版本锁、消息配方和历史证据。
- [Java 适配器](src/TeslaCommandAdapter.java)：在独立进程加载用户已安装 App 的数据类，构造后编码/解码核对，再交给 App 的内部接收器。
- [JSON 入口](skill/tesla-model-y/scripts/request.cjs)：保留自由文本，自动生成请求编号，只调用同一个执行器。
- [执行器](skill/tesla-model-y/scripts/control.sh)：统一版本/绑定检查和单次发送守卫。
- [结果读取](skill/tesla-model-y/scripts/status.cjs)：按完整 UUID 与 sourceContext 只读关联 App 结果，不把 App 成功当车辆结果。
- [构建](build.py)、[未来设备安装入口](install.sh)、[空绑定模板](vehicle-binding.example.json)、[来源与验证范围](skill/tesla-model-y/provenance.json)。

日常调用仍是手机 Agent → Skill → 本地执行器 → 官方 Tesla App。不是模拟点击，也没有向 App 进程注入 Hook。Root 让执行器到达内部 Android 组件；车辆授权仍由 App 的登录和手机钥匙提供。REA 只在原研究阶段分析过 APK，日常控制不重新反编译。

## 旧归档、团队 main 和目标系统

| 项目 | 原归档 | 本分支及目标 |
| --- | --- | --- |
| 代码 | [taku-os-legacy 固定提交](https://github.com/Taku-OS/taku-os-legacy/tree/5e86f9ec46c1daf38bcaa1de373456a5637a5acc/tooling/iot-lab/skills/tesla-model-y)，旧分支 codex/melo-iot-tesla-pixel9 | 从团队 [main 499e15e2](https://github.com/Taku-OS/taku-os/commit/499e15e2cad81b4e48a23eb113fd2181ccf82ba0) 新建 melo/tesla-control，只迁这个示例，不带旧实验平台和原分支历史 |
| 系统证据 | Pixel 9 原厂 Android 17 / CP3A.261005.005＋Magisk 30.7＋Taku v58 组件 | 目标 [TakuOS 1.4.1 / 20261123123146](https://github.com/Taku-OS/taku-os/releases/tag/v1.4.1-20261123123146)，Android 17 / API 37；未刷机或做目标系统控车验证 |
| 执行身份 | 既有 root，通常经 su 进入 | 团队 Codex launcher 由 root Broker 运行；直接要求 UID 0，不把 Magisk 或 su 当必需依赖 |
| Node | 私有 Device Lab 实验目录 | 团队共享 /data/local/agentos/rea-pack/bin/node 与 lib；先按团队 REA 运行时流程准备，本包不复制二进制或自动下载 |
| Skill 发现 | /data/local/agentos/home/.codex/skills | 当前 AgentSkills 与 Codex launcher 仍使用这个目录；本包不改公共注册/生命周期实现 |
| Tesla App | 4.61.6-4647；编码材料为固定公开 base.apk | 执行器检查已安装版本号/code、另查编码材料 SHA；不自动计算已安装 APK 的摘要，需现场另行核对两者字节。没有查共享 Pixel 现装版本，也不把商店最新版当兼容 |
| 小组件 | 曾绑定某台手机的 widget ID 和 Pixel Launcher | 从用户自己的私有绑定读取实际 ID 和 host；不带个人默认值，不切换默认桌面 |
| 构建部署 | 依赖旧 IoT Lab 的共享脚本，部署还锁原厂 build | 独立示例构建，复用团队 Android SDK 环境脚本；设备安装与主机构建完全分开 |
| 验证状态 | 下面列出的有限历史实车结果 | 八项在目标系统均为 prepared；send 拒绝执行，单独安排现场 test 后再由维护者更新状态 |

本轮核对时，团队 main 的 docs/releases/latest.json 仍指向 1.4.0，GitHub Release 已有 1.4.1。上表用的是 1.4.1 实际发布资产的元数据，sourceCommit 为 e2fcbc2c7684e367d55a7a23b17fc2ad7f403f2b。主线与该发布提交中，Codex root launcher、Skill 目录、REA provider manifest 和运行时 provision 入口未见差异。这是源码/发布元数据核对，不证明目标手机已经装包、Node 已就绪或内部广播/SELinux 已通过。

共享 Pixel 的最后已有记录为 TakuOS 1.4.0 / 20261118070714；当前由其他会话接管，本轮没有探测它。将来复跑必须重新登记实际系统、App 和运行时版本。**这是开发示例，不是可刷 ROM、自动预装或主干交付。**

## 为什么先保留这八项

选取参数含义较清楚、已有实车记录的动作，避免在首轮迁移中引入尾门切换状态、车辆坐标、座位/加热档位等额外前提。历史证据不扩展到所有参数和所有系统。

| 动作 | 历史实车记录 | 本轮及目标系统状态 |
| --- | --- | --- |
| flash_lights / honk_horn | 原环境车主确认闪灯/鸣笛；10/21 有断开 USB 使用的反馈，10/22 再报告成功。完整逐次车辆 ACK 不齐 | 小组件 ID/host 已去个人化；目标小组件、广播权限及实车效果待复跑，App 最终蓝牙/网络通道未观测 |
| set_temperature | 原环境车主报告 22℃→21℃，后续又确认空调功能；该具体温度反馈未严格关联到单次手机请求 | 同值双区、16–28℃、0.5℃步进保持；不据此宣称每个值通过，只改目标温度，不附带开启空调 |
| lock / unlock | 10/22 车主分别确认成功，没有逐次原始回执 | 明确锁/解锁，不使用 toggle；新系统待复跑 |
| next_media_track / previous_media_track | 10/22 车主确认上一首/下一首成功 | 不扩展到所有媒体源、播放/暂停或音量；新系统待复跑 |
| navigate_to_address | 10/24 两次匹配到 App BLUETOOTH SUCCESS，588ms、465ms。第一条删掉门店名后小区优先；第二条门店名＋地址，车主确认正确 | 保留原地点文本，不索完整地址、停车/P挡或重复发送确认；关键词选点、距离排序、新版普通手机对话、是否断开 USB 仍未验收 |

旧的 OWNER_API 导航两次收到 451 地区拒绝，第三次超时。本分支只允许蓝牙命令，不改域名、不拿令牌直连云 API、不自动回退网络。App SUCCESS 只证明 App 报告接受，车灯、声音、车机设定值或落点是另一层证据。

其他旧动作（备箱、车窗、加热、哨兵、充电等）没有进入本轮运行目录。旧归档中已报告和未测试的范围仍按原记录保留；这不是要求补测旧的全部能力。车辆不具备的座椅通风不能靠消息配方变成可用功能。

## 电脑离线构建

需要 Python 3.11+、JDK 17+、Android SDK platform 36 和 build-tools 36.0.0。路径由仓库现有 [Android 环境脚本](../../scripts/android-env.sh) 提供。构建只编译这个 Java 适配器并打包这一个 Skill，不运行 npm verify、不构建整包、不接触 adb/手机，也不需要 Tesla APK 或账号。

```sh
export ANDROID_SDK_ROOT=/path/to/android-sdk
export JAVA_HOME=/path/to/jdk
python3 examples/tesla-control/build.py
```

产物在 out/tesla-control/，包含 manifest.json、runtime/tesla-model-y/、install.sh、空绑定模板和本文。构建会打印 manifest 的 SHA-256。团队可检查源码和清单后复用；不包含 Node、Tesla APK、登录、钥匙或实车回执。

### 本轮离线检查结果

11 月 25 日完成的范围仅限本分支：

| 检查 | 实际结果与范围 |
| --- | --- |
| Java / DEX 打包 | JDK 21、javac --release 17、SDK/build-tools 36；适配器编译及 D8 成功，检查到 JAR 内 classes.dex 头。没有运行 Android 类加载或连接 App |
| 协议复用 | 八项参数、前缀、消息容器/配方、App 锁及 codec 与固定旧提交一致；目标状态全部 prepared。request/status 逻辑逐字节复用，仅 CommonJS 扩展名改为 cjs |
| 小组件去个人化 | 读归档 Android 小组件文本，在主机内替换身份；真实结构下的新 ID/host 匹配及错误 ID、host、provider、空绑定共五项检查通过。没有查询当前设备 |
| 安装器主机验证 | Node VM 用临时目录映射和真实包文件验证首次私有绑定、七文件发布顺序、重装保留绑定/旧 marker/其他 Skill、不同 VIN 与坏清单/篡改/链接/额外文件/pending 拒绝。只验证文件与事务逻辑，未运行 Android 安装；API 下限由外层安装脚本检查 |
| 文本/元数据 | Shell、CommonJS 语法，JSON、YAML、相对链接及脱敏检查覆盖本例；主机 quick_validate 缺 PyYAML，未冒称该工具通过，Skill frontmatter 改用 Ruby YAML 实际解析 |
| 未做的部分 | 没有安装到 Pixel、没有启动模拟器、没有 App 登录/配钥匙或发送车控；没有整包构建、全量回归、当前系统实车结论或录屏 |

构建产物由 manifest 绑定每个包文件的摘要和字节数。摘要随这份说明和源码变更更新，以本次构建输出为准。上述离线结果不会把目标系统从 prepared 改成已实车验证。

## 用户自己完成的前置

以下是车辆和设备窗口可用后的操作，不是本轮已执行事项。系统升级、App 安装和钥匙配对分别需要用户安排，不通过本包暗中完成。

1. 使用可用的目标 TakuOS 构建，确认手机 Agent 命令工具实际为 UID 0。先验证共享 REA Node pack 的 Node/lib 可运行，来源与版本按 [团队插件说明](../../docs/plugins/README.md#local-device-and-rea) 核对；不要直接复制旧 Device Lab 目录。仅源码相同或 SHA 正确不等于 Android ABI 已运行。
2. 用户在官方 Tesla App 自己登录、选择车辆并配好本机手机钥匙，先用手动操作确认可用。**新手机/清数据后不能靠复制配置恢复钥匙。** 本包不读取、迁移或分发账号令牌及配对材料。
3. 核对 App 必须为 com.teslamotors.tesla / 4.61.6-4647 / code4647。更新版本的混淆类和消息结构可能变化；不匹配就停止，另做适配。不要为了复跑卸载/降级一个已经能用的 App。
4. 从自己合法取得的同版本已安装公开 base.apk 准备只读材料：/data/local/agentos/materials/tesla/4.61.6-4647/base.apk。编码材料和已安装 base.apk 的 SHA-256 都应为 63adb6bb0556a44ea83996c719e2dfdcdbda4b33b8250bf46f6e5ee853c3d083。当前执行器自动检查的是材料摘要；已安装 APK 仅自动检查版本号/code，操作者需用 cmd package path --user 0 找到实际 base.apk 后另外核对它的摘要。split/base 不同、字节不符都应停止。APK 不在分支或产物中，不复制 App 私有数据来代替。
5. 在能放置 Android 小组件的桌面上，用户添加官方 Tesla VehicleStatusWidget 并选择自己的车。若 Taku 桌面不能添加，先由用户安排兼容的桌面入口；本包不改默认 Home。只复跑非小组件动作时可先留空小组件字段，闪灯/鸣笛会拒绝执行。
6. 把空绑定模板复制到源码/构建包以外的私有文件，仅由用户核对并填写 VIN。小组件 ID 和 host 用届时的 dumpsys appwidget 读回，不能沿用别人数字；字段只供本机运行，不进 Git 或团队聊天。文件权限 0600，目录 0700。模板空 VIN 不能执行。

```json
{"schema":"tesla-case-vehicle-binding/1","vin":"","appWidgetId":null,"widgetHostPackage":""}
```

### 未来安装（本轮未执行）

安装器必须来自已审阅的源码或构建包；期望摘要不能让一份未知安装脚本变得可信。先把已审阅的 out/tesla-control 包放到**届时获准使用的手机**，在本机 root shell 中执行。禁止将以下步骤交给当前被占用的 Pixel：

```sh
sh /private/package/tesla-control/install.sh \
  --expected-manifest-sha256 REVIEWED_MANIFEST_SHA256 \
  --binding /private/config/tesla-vehicle-binding.json
```

首次安装需要包外私有绑定；更新时省略 binding 保留原件，提供不同车辆或小组件身份会被拒绝。安装器只更新本 Skill 的清单文件，保留 binding 和 state，不改其他 Skill、会话、系统设置或账号。若留下 .pending，先停止重试并检查这次安装停在哪一步；不要删除 state 或旧请求 marker 来绕过检查。旧绑定缺少 widgetHostPackage 时会原样保留，用户需单独核对后完成小组件绑定，再复跑闪灯/鸣笛。没有后台常驻控车服务，也没有自动安装、自动测试或重启。目标系统安装和权限仍待现场确认。

## 后续实车复跑

车辆可用后先协调 Pixel 的独占窗口，记录系统 build、Tesla App 版本、Node 来源及登录/手机钥匙前置，不并发安装其他 Case。先用只读 list 和单项 check 检查本机环境，再由车主安排一次明确的真实动作；不要批量跑八项。

1. 设备 check：例如对 set_temperature 的参数 21 执行 check，预期 roundtripPassed=true、broadcastSubmitted=false，state 无新记录。这仍不是蓝牙连接或车辆接受证明。
2. 约定一个动作、目标值和是否恢复。门锁操作时用户应掌握合法进入车辆的方法；媒体先播放音乐；设温记录车机原目标值。闪灯/鸣笛选能观察到结果的位置。导航仅给一个要去的地点，无需停车或完整街道地址确认，也不替用户启动驾驶。
3. 在手机普通 Taku 对话中明确说“在目标系统实测一次，把车内目标温度设为21℃”。Agent 读取本 Skill，使用 test，只发送一次并保留真实 requestId。其他已选动作也是按次明确发起；没有授权的动作不执行。
4. 命令入口通过同 ID 的 control.sh status 查询 App 结果，记录 succeeded/failed/unknown。闪灯/鸣笛的小组件入口没有可关联本包 UUID 的 App 终态，只能记录广播投递与车主观察，不能拿另一个 App trace 充数。
5. 车主核对实际车灯/声音、车机目标温度、门锁状态、曲目或导航落点。分开记录本机检查、Android投递、App结果、实车结果和是否断 USB。失败/未知保留原 ID，不自动重发；不同参数、媒体源和任意模糊地点不由这一次通过覆盖。
6. 维护者按这个目标系统和具体参数范围更新目录状态，之后日常明确请求才用 send。纯关键词和完整新对话体验单独标注，录屏、刷机验证、PR/合入 main 另排。

可直接使用本机 JSON 入口，引用 heredoc，所有自由文本仅进入 JSON parameters 数组：

```sh
env LD_LIBRARY_PATH=/data/local/agentos/rea-pack/lib \
  /data/local/agentos/rea-pack/bin/node \
  /data/local/agentos/home/.codex/skills/tesla-model-y/scripts/request.cjs <<'TESLA_REQUEST'
{"mode":"check","action":"set_temperature","parameters":["21"]}
TESLA_REQUEST
```

只在已明确安排的那一次现场动作中将 mode 换成 test。正常导航取用户原地点词，不做网络补地址；这个开发阶段的 test/验收状态不应变成日常导航的“完整地址/停车确认”门槛。

## 隐私、停用和后续交付

仓库与构建包只放代码、空模板和整理后的历史范围。不放 VIN、车辆昵称、设备序列号、蓝牙地址、实际导航目的地、定位、凭据、钥匙、APK、原始 App/Agent 日志或请求 state。未来生成的绑定和回执保存在设备私有目录，默认不共享；示例目录忽略这些文件也不能代替逐次审查。

不用时在手机技能页停用 tesla-model-y；保留绑定和一次请求记录，不删 state 来解除重发限制。已发给 App 的命令不能保证撤回。升级执行器前应保持 Agent 空闲，避免同一请求跨版本执行。

本轮只交 melo/tesla-control 开发分支。实车补验、录屏、固件预装、PR 和合入 main 等车辆和目标系统可用后另排，不把本地通过或历史成功写成新系统已完成。
