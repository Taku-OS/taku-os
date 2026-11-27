# 高德路线接入与验证

2026-10-27，Taku Phone `org.taku.go2lab` 的「高德路线」页。

后续已补充地图点选、显式地理参考和模拟路线跟随，见 [地理导航验收](GEO-NAVIGATION-VERIFICATION.md)。下文的测试数量是首次只读接入的历史结果。

## 已实现

- 城市和关键词搜索，选择起点 A / 终点 B，查询步行路线。
- 在操作界面内显示高德静态地图、起终点、路线总览、完整分步指引、距离和高德行人步行时间。
- 静态图片属于总览，不是可缩放拖动的 JS 地图。总览最多选取约 81 个路径点，完整路线点保留在接口返回的每一步中；不能用总览折线做精确运动轨迹。
- “在高德打开路线”及“在高德网页搜索”交给系统浏览器；第三方页面不加载进机器人控制 WebView。
- 查询不自动获取位置，不把 Mac、模拟器或手机的位置当作狗的位置；坐标标记为 GCJ-02。坐标转换必须来自显式标定，路线执行按钮只对模拟器开放。
- 中英文界面、无结果、错误、Key 未配置、连接失效状态。图片失败仍保留路线步骤；更改起终点会清除旧路线。

## 本机部署

在仓库根目录运行；个人 Key 不放入代码、APK、聊天或 `.env`：

```sh
# 首次配置，输入时不回显；更新配置无需重启服务。
bash tooling/go2-lab/start-maps.sh configure
# 独立终端保持运行。
bash tooling/go2-lab/start-maps.sh
# 恢复模拟器到 Mac 的地图服务通道；install.sh 也会设置。
"$HOME/Library/Android/sdk/platform-tools/adb" -s emulator-5554 reverse tcp:18846 tcp:18846
```

个人 Key 保存在 `~/Library/Application Support/TakuGo2/amap-web-service.key`，权限必须为 0600。只允许用户自己的开发 Key；此本机架构不适合存放或分发 Taku 公司服务密钥，产品化时应迁移到经认证的 Taku 服务端代理。

地图服务只监听 `127.0.0.1:18846`；无通用代理或任意 URL 入口，只请求 `https://restapi.amap.com` 的三个固定接口；不跟随重定向、不输出上游异常 URL、不记录密钥、拒绝第三方 Origin 和不正确 Host。关键字/起终点查询是用户显式触发，不定时调用计费接口；地图总览是每次路线查询附带的一次静态图请求。

机器人服务仍监听 18845。模拟器的出站网络禁止钩子保持不变；联网地图服务是另一个进程，没有机器人驱动或移动接口。只读 HTTP：GET `/status`、POST `/search`（keyword/city）、POST `/route`（origin/destination），输入只接受文档规定字段。返回 `robotExecutionAllowed:false`。后续新增 GET `/route/<geometryId>` 读取内存中最近四条地图预览，供 AI CLI 与页面对齐；不新增上游查询，进程重启后清空。

## 验证结果

- 用户提供的个人 Web 服务 Key 已通过实际联网验证：POI 查询、步行路线、静态地图均成功。没有将 Key 写入本文件或测试证据。
- 113 项 Python 测试、14 项 UI 逻辑测试通过，Android APK 构建并安装成功。
- `node tooling/go2-lab/test-phone-amap.mjs` 在实际 Android WebView 通过 6 组验收：真实搜索 10 个 POI，真实步行路线和图片显示，中英切换及 320/768/1024/1440 宽度，无地图服务时错误提示及恢复，切页状态保留，地图操作不连接机器人或产生移动。
- 公开地点示例路线为 5186 米，高德估计行人用时约 70 分钟；只是接口样例，不是用户或机器狗实际位置。
- 测试证据保存在 ignored 的 `out/go2-lab/amap-verification/`：`results.json`、`search-results.png`、`walking-route.png`、`ready.png`。没有保存密钥和上游带鉴权 URL。
- 手动补充验证：“在高德打开路线”成功打开独立 Android 浏览器，页面标题为高德步行路线；返回后保留 Taku 路线预览。证据 `amap-website.png`。
- 接口用例覆盖非法/非有限坐标、额外控制字段、无 Key/文件权限错误、跨来源/伪造 Host、图片不可用、串行限流、完整路径点保留。

## 尚未完成的机器人链路

目前已有显式 GCJ-02／odom 参考、局部可通行规划与模拟逐段跟随；尚无经过实测的户外绝对定位、真实雷达／里程计配准、室内外地图切换或实机验收。高德步行路线可能包括过街、台阶等，不能直接转换成 Go2 Move。实机自主导航继续禁用。

## 官方接口依据

- [POI 搜索 2.0](https://lbs.amap.com/api/webservice/guide/api-advanced/newpoisearch)：`/v5/place/text`。
- [步行路线](https://lbs.amap.com/api/webservice/guide/api/direction)：`/v3/direction/walking`。
- [静态地图](https://lbs.amap.com/api/webservice/guide/api/staticmaps)：`/v3/staticmap`。
- [URI 搜索](https://lbs.amap.com/api/uri-api/guide/search/search)、[URI 路线](https://lbs.amap.com/api/uri-api/guide/travel/route)：浏览器入口。
