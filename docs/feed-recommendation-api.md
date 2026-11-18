# 把主动推荐推送到 Taku Home Feed

[English](feed-recommendation-api.en.md)

面向主动性与推荐方向的同事：说明如何把推荐结果作为卡片放进客户端 App（`org.agentos.agent`）的 Home Feed，如何更新、撤回和读取用户反馈。接口来自 `23a0515`（Feed 卡片生成）与 `fe7ba0e`（GenUI Kotlin 运行时，提供 `generateUi` 路由）。契约真值仍是 [`CONTRACT.md`](../system/agent-app/modules/taku-home/CONTRACT.md) 与 [模块 README](../system/agent-app/modules/taku-home/README.md)；本文只做推荐场景的用法整理，二者冲突时以契约和代码为准。

## 先选接入方式

所有方式都经过同一个 `FeedRepository`，GUI 与 Agent 看到的是同一份状态。

| 方式 | 适合 | 卡片 ID | 更新 / 撤回 | 是否调用模型 |
| --- | --- | --- | --- | --- |
| **`feed/publish`**（推荐） | 推荐方自己组装完整卡片 | 调用方指定，稳定 | 同 ID 再发布即替换；`feed/retract` 撤回 | 否 |
| `feed/generate` + `card` | 一次性导入一张卡片，并在发布前做完整主体校验 | 每次新建 `agent-generated:<uuid>` | 不能按自己的 ID 替换；只能用返回的 ID 撤回 | 否 |
| `feed/generate` + `prompt` | 没有固定模板，只有事实描述，让模型排版 | 每次新建 | 同上 | 是（设备上的 Magpie `agent-ui.text` 路由） |
| 进程内 `FeedSource` | 未来正式的推荐服务 | 来源自定 | 由来源分页决定 | — |

推荐场景通常用 **`feed/publish`**：推荐有自己的稳定键（例如 `rec.commute.leave-now`），需要随数据刷新替换，过期或失效时撤回。`feed/generate` 每次都会新建卡片，不去重、不替换，适合“导入一张”或“按描述生成一张”。

进程内 `FeedSource` 目前**不可从外部使用**：正式桌面不注册设计样例来源，只显示通过发布接口收到的卡片；无推荐时显示空态。`source.id` 的 `recommendation` 值已保留但还没有实现。外部调用方通过 `feed/publish` 或 `feed/generate` 写入的卡片，`source.id` 一律被改写为 `agent`。

## 快速开始（adb）

卡片 JSON 通常含引号和中文，不要拼进 `adb shell` 的命令行。下面的写法把请求作为一行 JSON 经 stdin 传给设备上的 `content call`，与 `scripts/generate-feed-card.mjs` 的做法相同：

```sh
SERIAL=emulator-5554          # 换成实际设备
FEED='IFS= read -r req || exit 1
exec content call --uri content://org.agentos.agent.home-feed --method "$0" --arg "$req"'
feed() {  # 用法：feed <method> <一行 JSON 请求>
  printf '%s\n' "$2" | adb -s "$SERIAL" shell -T "sh -c '$FEED' $1"
}
card_request() {  # 把 card.json 包成 {"card":{...}} 的单行请求
  python3 -c 'import json,sys;print(json.dumps({"card":json.load(open(sys.argv[1],encoding="utf-8"))},ensure_ascii=False,separators=(",",":")))' "$1"
}

feed feed/publish "$(card_request rec-card.json)"
feed feed/get '{"id":"rec.commute.leave-now"}'
feed feed/list '{}'
feed feed/retract '{"id":"rec.commute.leave-now"}'
```

简单请求直接在设备 shell 里执行也可以：

```sh
adb -s "$SERIAL" shell content call --uri content://org.agentos.agent.home-feed --method feed/list --arg '{}'
```

每个方法都返回 `Result: Bundle[{result=<json>}]`。先看 `ok`：成功为 `{"ok":true,"result":{…}}`，失败为 `{"ok":false,"code":"…","message":"…"}`。`feed/publish` 成功返回 `{"id":"…","replaced":false|true}`。

## 卡片格式（`taku.feed-card/1`）

下面是一张可以直接发布的推荐卡片。其主体已用下文的主机校验命令检查，卡片外层已用 `FeedCard.parse` 的实际代码检查。

```json
{
  "schema": "taku.feed-card/1",
  "id": "rec.commute.leave-now",
  "source": {"id": "recommendation", "app": "Commute", "package": "com.google.android.apps.maps"},
  "layout": {"span": "full", "surface": "card"},
  "rank": 0.96,
  "createdAt": 1793865600000,
  "expiresAt": 1793869200000,
  "pinned": false,
  "summary": "Leave in 10 minutes to reach the 15:00 design review at Office B on time.",
  "pin": {"label": "Leave 14:20", "detail": "Design review 15:00", "icon": {"kind": "pixel", "glyph": "car"}},
  "ui": {
    "catalog": "taku-feed-1",
    "state": {},
    "spec": {
      "root": "card",
      "elements": {
        "card": {"type": "Column", "props": {"gap": 12}, "children": ["header", "reason", "buttons"]},
        "header": {"type": "AppHeader", "props": {"title": "Commute", "trailingIcon": "clock", "trailing": "in 10 min"}, "children": []},
        "reason": {"type": "Inset", "props": {"tag": {"label": "Relevant", "tone": "ai"}}, "children": ["reasonBody"]},
        "reasonBody": {"type": "Column", "props": {"gap": 4, "padding": 12}, "children": ["title", "detail", "tags"]},
        "title": {"type": "Text", "props": {"text": "Leave at 14:20 for the design review", "variant": "body-xl-medium", "lines": 2}, "children": []},
        "detail": {"type": "Text", "props": {"text": "Traffic to Office B is heavier than usual (32 min).", "tone": "tertiary"}, "children": []},
        "tags": {"type": "Tags", "props": {"items": ["Calendar", "Traffic"]}, "children": []},
        "buttons": {"type": "Row", "props": {"gap": 8}, "children": ["later", "go"]},
        "later": {"type": "Button", "props": {"label": "Not now", "variant": "secondary", "flex": 1}, "children": [],
          "on": {"press": {"action": "dispatch", "params": {"actionId": "dismiss"}}}},
        "go": {"type": "Button", "props": {"label": "Navigate", "variant": "primary", "flex": 1}, "children": [],
          "on": {"press": {"action": "dispatch", "params": {"actionId": "navigate"}}}}
      }
    }
  },
  "actions": {
    "navigate": {"label": "Navigate", "intent": {"type": "open-app", "package": "com.google.android.apps.maps", "uri": "google.navigation:q=Office+B"}},
    "dismiss": {"label": "Not now", "intent": {"type": "dismiss"}},
    "ask": {"label": "Ask Taku", "intent": {"type": "ask-taku", "prompt": "Help me plan my trip to the 15:00 design review at Office B."}}
  }
}
```

### 外层字段

由 `FeedCard.parse` 校验；违反时返回 `INVALID_CARD`，`message` 指出字段。

| 字段 | 规则 | 推荐场景建议 |
| --- | --- | --- |
| `schema` | 必须为 `taku.feed-card/1` | — |
| `id` | `^[A-Za-z0-9._:-]{1,96}$` | 用自己的命名空间和稳定键，如 `rec.<producer>.<key>`；同 ID 再发布即替换 |
| `source.id` | 发布时被改写为 `agent` | 可写 `recommendation` 表达意图，但不会保留 |
| `source.app` | 必填，非空 | 用户能看懂的来源名 |
| `source.package` | 可选 | 关联 App 的包名 |
| `source.icon` | 可选，`asset:feed/<path>` 或 `https://…` | 其他 scheme（`file://`、`content://`）被拒绝 |
| `layout.span` | `full`（默认）或 `half` | 多数推荐用 `full`；`half` 主体是正方形，两张并排 |
| `layout.surface` | `card`（默认）、`control`、`media` | 推荐用 `card` |
| `rank` | 数字，缺省 0；越大越靠前，同分保持来源顺序 | 见“排序” |
| `createdAt` | epoch 毫秒，缺省 0 | 不参与排序 |
| `expiresAt` | epoch 毫秒或 `null`；到期后不再显示 | 时效推荐务必设置；见“生命周期” |
| `pinned` | `true` 时发布即钉到最前 | 保持 `false`，钉住应由用户决定 |
| `summary` | 必填，非空 | 一句完整的话。主体无法渲染时显示它；长按“问 Taku”也以它为上下文 |
| `pin.label` | ≤ 24 字符；设置 `pin.value` 时可省略 | 用户钉住后药丸上的主文字 |
| `pin.detail` | 可选，≤ 32 字符 | 第二行 |
| `pin.value` | 可选，≤ 12 字符 | 如 `23°`、`4 min` |
| `pin.icon` | `{"kind":"pixel","glyph":…}` 或 `{"kind":"image","src":…,"shape":"circle"\|"rounded"}` | 内置像素图标：`sun moon car lock list chat cup bolt music photo pin home`；其他名称生成对称图案。`https:` 图片在药丸上会退回生成图案 |
| `pin.trailing` | 可选 `{"icon","actionId"}`，`actionId` 必须是已声明的动作 | — |
| `ui` | `catalog` 必须为 `taku-feed-1`；`spec.root` 必须指向 `spec.elements` 中的元素 | 见下节 |
| `actions` | 动作表，见“动作” | — |

单张卡片序列化后不超过 256 KiB（`CARD_TOO_LARGE`）。

### 卡片主体（`ui`）

主体是 `@json-render` 规格，由客户端的 React Native 渲染。原生外壳负责玻璃底板、内边距、排序、钉住和删除，主体背景透明。

- **组件**（`taku-feed-1`）：Column、Row、Spacer、Text、Value、Icon、AppHeader、CornerTag、Inset、MessageQuote、Button、IconButton、Tags、Segmented、ProductImage、Image、Avatar、Checklist、ProgressBar、ArcGauge、MapRoute、OptionRow、MediaStack、SlideToAct、Pager、Dots、Cover、Chip、Switch、LevelTrack、Timeline、FlightRoute、Link。属性定义和说明在 [`feedCatalog.ts`](../system/agent-app/src/feed/feedCatalog.ts)；颜色和字号只能用令牌（`tone`、`variant`、`fill`）。
- **事件**：所有事件都必须绑定为 `{"action":"dispatch","params":{"actionId":"…","select":["/ui/…"]}}`；`actionId` 必须在 `actions` 中声明，`select` 可选，把对应状态值随动作一起传出。
- **状态**：`ui.state` 是初始状态对象（如 `{"ui":{"size":"tall"}}`），供 Segmented、Checklist、Pager 等绑定。
- **尺寸**：`full` 主体宽 356dp；`half` 主体 158×158，根 Column 用 `flex: 1`。
- **图片**：`asset:feed/<path>` 是客户端 APK 内置资源，推荐方一般用不到；卡片正文远程图片仅支持 `https://`；HTTP 地址（包括局域网）会被拒绝，不自动改写地址。SVG 地址以 `.svg` 路径结尾，可附加查询参数或片段，响应须为 `image/svg+xml`，仅支持不包含外部资源、脚本或 CSS 的静态图形子集；原生置顶图标仍遵循其独立规则。不要使用未经确认的资源路径。

**主体校验不在 `feed/publish` 里做。** `feed/publish` 只检查外层和 `spec.root`；主体不符合目录时发布仍然成功，但卡片只显示 `summary`（客户端日志有 `[feed] <id> spec rejected`）。发布前请在主机上用同一个校验器检查：

```sh
cd system/agent-app   # 在仓库中执行；需要 npm install 过的 node_modules
node --input-type=module -e "
import {readFileSync} from 'node:fs';
import {validateFeedSpec} from './src/feed/feedCatalog.ts';
const card = JSON.parse(readFileSync(process.argv[1], 'utf8'));
const issues = validateFeedSpec(card.ui.spec, card.ui.state ?? {}, new Set(Object.keys(card.actions ?? {})));
console.log(issues.length ? JSON.stringify(issues, null, 2) : 'ok');
process.exit(issues.length ? 1 : 0);" /absolute/path/card.json
```

通过时输出 `ok`；失败时逐项列出元素和原因，例如 `{"element":"go","message":"on.press dispatches unknown action missing"}`。需要 Node 22.18+ 或 24（直接加载 `.ts`）。

## 动作

| `intent.type` | 字段 | 效果 | 推荐场景 |
| --- | --- | --- | --- |
| `open-app` | `package`，可选 `uri` | 启动该 App，或在其中 VIEW `uri` | 主操作：“打开”“导航”。未安装时返回 `APP_NOT_INSTALLED` |
| `ask-taku` | `prompt` | 打开 Taku 对话，把 `prompt` 作为可编辑草稿；**从不自动发送** | 需要 Agent 替用户办事时用（按钮加 `"icon":"sparkle"`）；提示词写清事实，并要求付款等操作前先确认 |
| `open-url` | `url`（必须 `https://`） | 打开网页 | 详情链接 |
| `dismiss` | — | 删除卡片，与长按 → 删除相同（带消散动画） | “不感兴趣 / 稍后” |
| `none` | — | 只记录，不执行 | 集成尚未接通时如实声明，不要假装成功 |
| `panel` | `method`（`panels/…`）、`args` | 执行面板操作 | 面板页专用，推荐卡片不要用 |

动作只在用户点击（或 Agent 调用 `feed/action`）时执行，发布和生成都不会执行动作。

## 生命周期

- **更新**：用同一个 `id` 再次 `feed/publish`，返回 `replaced:true`。整张卡片被替换，没有局部更新。已被用户钉住的卡片保持钉住。
- **撤回**：`feed/retract {"id":…}` 删除你发布的卡片，同时取消钉住。卡片不存在返回 `NOT_FOUND`；对样例卡片返回 `NOT_AGENT_CARD`。
- **过期**：到达 `expiresAt` 的卡片不再显示，但仍留在存储里，直到被撤回。推荐失效后请主动 `feed/retract`。
- **用户删除**：用户删除（或点了 `dismiss` 动作）后，ID 进入 `removed` 列表。**再次发布同一 ID 会把它从 `removed` 中移除，卡片重新出现。** 刷新前先检查 `feed/get` 的 `removed` 或 `feed/list` 的 `removed[]`，尊重用户的删除；内容确实不同的新推荐请用新 ID。
- **容量**：普通发布和生成均没有固定张数上限，不会淘汰其他卡片；仍受设备存储空间限制。请主动撤回失效推荐。
- **重置**：`feed/reset` 清空所有发布卡片、钉住和删除记录，只用于开发与恢复。

### 排序

可见卡片按 `rank` 从大到小排列。正式桌面不加载或循环设计样例，也不再预置天气、音乐、睡眠等示例 Pin。升级保留已发布卡片及其 Pin/隐藏状态，只清理已停用来源的状态；首次安装和 `feed/reset` 后均可直接发布卡片。`feed/generate` 不采用文件里的 `rank`，而是排在当前已加载的最高分之上。

## 读取用户反馈

先读取当前快照，再从返回的游标订阅持久化事件：

```sh
feed feed/list '{}'
```

`feed/list` 返回 `cards[]`、`pins[]`、`removed[]` 和 `eventCursor`，不再返回 `recentActions`。通过 `content read --uri "content://org.agentos.agent.home-feed/events?after=0&follow=0"` 补读持久化 NDJSON 事件；`follow=1` 持续监听，`after=<cursor>` 断点续读。支持 `cardId`、`cardIdPrefix`、`origin`、`op` 筛选。消费者处理完事件后保存 cursor，完整处理 checkpoint 后保存 through；持续流 EOF 要重连。`op:"action"` 带 `actionId`、`values`、`intentType` 和 `phase:"accepted"`：它表示选择已持久化，不保证随后外部操作成功。详见 [完整事件协议](../system/agent-app/modules/taku-home/CONTRACT.md#event-stream)。

`feed/get {"id":…}` 返回完整卡片 JSON 以及 `pinned`、`removed` 两个状态。

## 在 Android App 中调用

Provider 已导出且不要求权限（产品决定，见仓库 `AGENTS.md`）。目标 SDK 30 以上的调用方需要声明包可见性：

```xml
<queries>
  <provider android:authorities="org.agentos.agent.home-feed" />
</queries>
```

```java
package com.example.rec;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import org.json.JSONException;
import org.json.JSONObject;

/** Minimal client for content://org.agentos.agent.home-feed. Call every method from a worker thread. */
public final class TakuFeed {
  public static final Uri URI = Uri.parse("content://org.agentos.agent.home-feed");

  /** Provider error: {@link #code} is the stable `code` field (INVALID_CARD, NOT_FOUND, ...). */
  public static final class FeedError extends Exception {
    public final String code;
    FeedError(String code, String message) { super(code + ": " + message); this.code = code; }
  }

  private TakuFeed() {}

  /** Calls one feed method and returns its `result` object. */
  public static JSONObject call(Context context, String method, JSONObject arg) throws FeedError {
    Bundle out;
    try {
      out = context.getContentResolver().call(URI, method, arg.toString(), null);
    } catch (IllegalArgumentException e) { // provider not installed or not visible (<queries>)
      throw new FeedError("PROVIDER_UNAVAILABLE", String.valueOf(e.getMessage()));
    }
    if (out == null || out.getString("result") == null) throw new FeedError("PROVIDER_UNAVAILABLE", "no result");
    try {
      JSONObject reply = new JSONObject(out.getString("result"));
      if (!reply.optBoolean("ok")) throw new FeedError(reply.optString("code"), reply.optString("message"));
      return reply.getJSONObject("result");
    } catch (JSONException e) {
      throw new FeedError("BAD_REPLY", String.valueOf(e.getMessage()));
    }
  }

  /** Adds or replaces the card with card.id. Returns true when an earlier version was replaced. */
  public static boolean publish(Context context, JSONObject card) throws FeedError, JSONException {
    return call(context, "feed/publish", new JSONObject().put("card", card)).getBoolean("replaced");
  }

  /** Withdraws a published card. NOT_FOUND means it is already gone. */
  public static void retract(Context context, String id) throws FeedError, JSONException {
    call(context, "feed/retract", new JSONObject().put("id", id));
  }
}
```

`PROVIDER_UNAVAILABLE` 和 `BAD_REPLY` 是这段示例在客户端一侧定义的代码，不是 Provider 返回的。所有调用都是同步 Binder 调用，`feed/list` 和 `feed/get` 最多等待 5 秒让首屏加载，`feed/generate` 可能等待两分钟，请放在工作线程。

## `feed/generate`

请求只能有一个键：`{"prompt":"…"}` 或 `{"card":{…}}`。

- **`card`**：不调用模型。先检查外层字段类型，再用与上文相同的 `validateFeedSpec` 校验主体，全部通过才发布。发布时重新分配 `id`（`agent-generated:<uuid>`）、`createdAt` 和 `rank`（当前最高之上），并强制 `pinned:false`、`source.id:"agent"`。其余内容原样保留。注意：外层不合格时只返回通用的 `INVALID_CARD: The supplied card envelope is invalid`，不指出字段；排错时可先用 `feed/publish` 或主机校验拿到具体原因。
- **`prompt`**：调用设备上已配置的 `generateUi` 路由（GenUI 运行时），模型只生成 `summary`、`ui`、`actions`，外层由宿主补齐（`source.app` 为 `Taku`，`pin.label` 取 `summary` 前 24 个字符，图标为 `list`）。成功结果带实际的 `model`。提示词只写已核实的事实：系统指令要求模型不得编造事实、媒体地址或资源路径。需要 Android 上运行的 Magpie，以及绑定到 `agent-ui.text` 的可用文本模型，见 [Android Magpie 指南](../adapters/magpie-runtime/README.md)。

主机上可用现成的调用脚本：

```sh
node system/agent-app/modules/taku-home/scripts/generate-feed-card.mjs --serial "$SERIAL" --json-file /absolute/path/card.json
node system/agent-app/modules/taku-home/scripts/generate-feed-card.mjs --serial "$SERIAL" --prompt '用这些事实生成一张出行提醒卡片：14:20 出发，15:00 在 Office B 开设计评审，路况 32 分钟。'
```

限制：请求最多 96,000 UTF-8 字节，`prompt` 最多 16,000 个 UTF-16 单元，主体最多 96 个元素。同一时间只运行一次生成，并发调用返回 `BUSY`（不排队）。调用方最多等待 120 秒。超时、断连或回复不可读时结果未知（可能已经发布），请先 `feed/list` 再决定是否重试。生成不会自动重试，也不去重。

## 错误码

| 代码 | 含义与处理 |
| --- | --- |
| `INVALID_ARGUMENT` | 参数缺失或不是合法 JSON（如缺 `card`、`id` 格式不对） |
| `INVALID_CARD` | 外层字段不合格；`feed/publish` 的 `message` 指出字段 |
| `CARD_TOO_LARGE` | 卡片超过 256 KiB |
| `ID_CONFLICT` | ID 已属于样例等来源卡片；换一个 ID |
| `NOT_FOUND` | 没有这张卡片（或尚未加载） |
| `NOT_AGENT_CARD` | 试图撤回来源卡片；来源卡片只能 `feed/remove` |
| `STATE_WRITE_FAILED` | 存储写入失败，卡片未生效 |
| `UNKNOWN_METHOD` | 方法名错误 |
| `INVALID_SPEC` / `INVALID_MODEL_OUTPUT` | 仅 `feed/generate`：主体不符合目录 / 模型输出不是一个完整 JSON 对象 |
| `BUSY`、`FEED_UNAVAILABLE`、`RANK_EXHAUSTED`、`REQUEST_TOO_LARGE`、`RUNTIME_UNAVAILABLE`、`RUNTIME_TIMEOUT`、`RUNTIME_LOST`、`CANCELLED`、`GENERATION_FAILED` | 仅 `feed/generate`；均不插入占位卡片。模型网关的错误码原样透传 |

`feed/action`、`feed/pin` 等其余方法的参数和错误见[模块 README](../system/agent-app/modules/taku-home/README.md#agent-access)。

## 当前未提供

- 正式的推荐来源（`FeedSource`）：外部只能用 `feed/publish` / `feed/generate`，`source` 会显示为 `agent`。
- 远端 Webhook：未提供；使用本机 `/events` 的历史回放和持续订阅。
- 调用方隔离：Provider 不区分调用方，任何本机调用方都能替换或撤回任意发布卡片；请用自己的 ID 前缀，并只操作自己的卡片。

## 本文的验证范围

示例卡片的主体已用 `validateFeedSpec` 校验通过，外层已用 `FeedCard.parse` / `parseGenerated` 的实际代码校验通过（包括若干反例）。stdin 传参写法已用模拟的 `content` 命令确认 JSON 原样到达；Java 示例已对 `android.jar` 编译通过。编写本文时没有可用的模拟器，`adb` 命令尚未在设备上逐条实测；行为描述依据 `HomeFeedProvider.kt`、`FeedRepository.kt`、`FeedCard.kt`、`FeedGeneration.kt` 和 `feedGeneration.ts` 的代码。
