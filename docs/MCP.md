# MCP 接入文档

> 面向第三方：通过 Model Context Protocol 调用 ManagedFloatingApp。
> 相关文档：[REST API](./API.md) ｜ [MJPEG 视频流](./MJPEG.md)

- 端点：`POST /mcp`（Streamable HTTP）与 `GET /mcp/sse`（SSE）
- 默认端口 `8099`；JSON-RPC 2.0；协议版本 `2024-11-05`。

## 1. 网络与鉴权

网络接入（局域网 / `adb forward`）同 [API.md §1](./API.md#1-网络接入)。

鉴权三选一：
- `Authorization: Bearer <token>`（MCP 客户端推荐）
- `X-Auth-Token: <token>`
- URL 查询参数 `?token=<token>`

失败返回 HTTP `401` 与 `WWW-Authenticate: Bearer realm=ManagedFloatingApp`。
本服务**不实现 OAuth**；若客户端报 `OAuth dynamic client registration failed`，请在客户端中手动选择 API Key / Bearer Token 并填入 token，关闭 OAuth 选项。

客户端配置示例：

```json
{
  "mcpServers": {
    "ManagedFloatingApp": {
      "url": "http://192.168.x.x:8099/mcp",
      "headers": { "Authorization": "Bearer <token>" }
    }
  }
}
```

## 2. 两种传输方式

### 2.1 Streamable HTTP（请求/响应，最简单）
直接向 `POST /mcp` 发送 JSON-RPC：

```bash
curl -X POST http://localhost:8099/mcp \
  -H 'Content-Type: application/json' \
  -H 'Authorization: Bearer <token>' \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{}}}'
```

### 2.2 SSE 长连接（服务端可回推消息）
1. 建立通道：`GET /mcp/sse`（带鉴权头）。
2. 服务端先推送：
   ```
   event: endpoint
   data: /mcp?sessionId=<uuid>
   ```
3. 客户端向 `POST /mcp?sessionId=<uuid>` 发送 JSON-RPC，HTTP 返回 `202 Accepted`。
4. 工具结果通过同一条 SSE 连接以 `event: message` 推回。

## 3. 协议方法

| 方法 | 说明 |
|------|------|
| `initialize` | 握手，返回 protocolVersion / capabilities / serverInfo |
| `notifications/initialized` | 通知（无 id），服务端返回 202，无 JSON 结果 |
| `ping` | 心跳，返回空 result |
| `tools/list` | 列出全部工具及 inputSchema |
| `tools/call` | 调用工具，参数在 `params.arguments` |

标准错误码：JSON 解析失败 `-32700`；未知方法 `-32601`；HTTP 鉴权失败 `401`。

## 4. 工具清单（19 个）

以 `tools/list` 实时返回为准。屏幕类工具需要先建立托管会话（见 §5）。

### 屏幕与交互
| 工具 | 说明 | 参数（`*` 为必填） |
|------|------|--------------------|
| `screen_capture` | 截当前虚拟屏，返回 base64 PNG；静态画面无新帧时返回最后一帧 | 无 |
| `ui_read` | 读界面元素树（JSON 数组）；需启用无障碍，未启用时返回降级提示 | 无 |
| `screen_tap` | 虚拟屏坐标点击 | `x*`, `y*` |
| `screen_swipe` | 虚拟屏滑动 | `x1*`,`y1*`,`x2*`,`y2*`，可选 `durMs`（默认 300） |
| `screen_key` | 注入按键 | `code?`（KeyEvent keycode，优先）或 `name?`（`back/home/enter/del/paste/tab/esc/space/up/down/left/right/volume_up/volume_down/power/menu/search`） |
| `screen_text` | 向焦点输入框写入文本（支持中文） | `text*` |
| `status` | 会话/承载/虚拟屏分辨率/运行中任务/无障碍等状态 | 无 |

> 坐标口径：以 `status`（或脚本 `screen.info`）返回的 `vScreenWidth/vScreenHeight` 为准，随设备方向变化。
> `screen_text`：ASCII 走系统 `input text`；非 ASCII 走“剪贴板 + 粘贴键”，调用前需先让输入框获得焦点。

### 应用管理
| 工具 | 说明 | 参数（`*` 为必填） |
|------|------|--------------------|
| `app_list` | 列出所有可启动应用（packageName + label） | 无 |
| `app_launch` | 把 App 承载到虚拟屏 | `packageName*`，可选 `component` |
| `app_close` | 关闭当前承载 App（保留会话，回到应用列表） | 无 |

### 自动化服务任务
| 工具 | 说明 | 参数（`*` 为必填） |
|------|------|--------------------|
| `task_list` | 列出自动化服务任务 | 无 |
| `task_get` | 单任务详情（含 `lastRunAt/nextRunAt/runCount/lastStatus`） | `id*` |
| `task_create` | 创建/更新任务（全量 upsert，id 可省略自动生成） | `task*`：`id,name,script,enabled,kind,intervalMs,cron` |
| `task_modify` | 按 id **部分修改**已存在任务，保留运行统计；不存在返回错误 | `id*`，`patch*`（任意可编辑字段：`name,script,enabled,kind,intervalMs,cron,packageName`） |
| `task_delete` | 按 id 删除任务 | `id*` |
| `task_run` | 立即执行任务；`waitMs>0` 时等待结束并返回任务状态 | `id*`，可选 `waitMs` |
| `task_stop` | 停止执行 + 取消已排闹钟（尽力而为） | `id*` |
| `task_docs` | 返回 JS 脚本文档：全部 `host.invoke` 接口签名、坐标口径与示例 | 无 |

### 观察与诊断
| 工具 | 说明 | 参数（`*` 为必填） |
|------|------|--------------------|
| `logs` | 读/清统一日志（主进程，含 `tag=Script` 脚本日志） | 可选 `level`、`limit`（默认 200，上限 500）、`clear` |

## 5. 调用约定

- 工具结果统一在 `result.content[0].text`（文本或 JSON 字符串；截图为 base64）。
- 失败时 `result.isError=true`，文本以 `error:` 或 `ok=false` 开头；缺少必填参数会返回明确错误。
- 屏幕类工具（capture/tap/swipe/app_launch）需要先建立托管会话，见 [API.md §3](./API.md#3-前置会话就绪)；可通过 `GET /status` 确认 `sessionActive=true`。
- `ui_read` 需额外启用无障碍服务，ROM 限制见 [API.md §6](./API.md#6-界面读取get-ui)。

## 6. 示例

```bash
# 握手
curl -X POST http://localhost:8099/mcp \
  -H 'Content-Type: application/json' -H 'Authorization: Bearer <token>' \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{}}}'

# 点击（参数 x/y）
curl -X POST http://localhost:8099/mcp \
  -H 'Content-Type: application/json' -H 'Authorization: Bearer <token>' \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"screen_tap","arguments":{"x":360,"y":500}}}'

# 承载 App（注意参数名是 packageName，不是 pkg）
curl -X POST http://localhost:8099/mcp \
  -H 'Content-Type: application/json' -H 'Authorization: Bearer <token>' \
  -d '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"app_launch","arguments":{"packageName":"com.android.settings"}}}'

# 截图
curl -X POST http://localhost:8099/mcp \
  -H 'Content-Type: application/json' -H 'Authorization: Bearer <token>' \
  -d '{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"screen_capture","arguments":{}}}'
```

## 7. 自动化任务脚本与 task_docs

任务的 `script` 字段是运行在 Node 引擎中的 async JS，通过 `await host.invoke('<api>', {...})` 调用应用层接口。
可用应用层接口（共 9 个）：

| host API | 参数 | 返回 | 作用 |
|----------|------|------|------|
| `screen.tap` | `{x,y}` | `{ok}` | 虚拟屏点击 |
| `screen.swipe` | `{x1,y1,x2,y2,durMs?}` | `{ok}` | 滑动（durMs 默认 300） |
| `screen.launch` | `{pkg, component?}` | `{ok}` | 承载 App（字段名是 pkg） |
| `screen.frame` | `{}` | `{ok, base64}` | 截屏（PNG base64） |
| `screen.key` | `{code}` 或 `{name}` | `{ok, code}` | 按键注入（back/home/enter/del/paste…） |
| `screen.text` | `{text}` | `{ok}` | 文本输入（支持中文） |
| `screen.info` | `{}` | `{ok,w,h,dpi,hosted}` | 当前虚拟屏分辨率/会话信息 |
| `apps.list` | `{}` | `{ok, apps[]}` | 列可启动应用 |
| `log` | 任意对象 | `{ok}` | 写日志（REST `/logs`、MCP `logs` 可查，tag=Script） |

cron 支持 5 字段（`分 时 日 月 周`）和 6 字段（`秒 分 时 日 月 周`）；
要求“每分钟第 00 秒”用 `0 * * * * *`。

> 定时任务依赖精确闹钟：Android 13+ 需要 `USE_EXACT_ALARM`（本 App 已在清单声明）。
> 若系统/ROM 回收该权限，cron 在息屏/待机时会被延迟（`dumpsys alarm` 可见 `exact_alarm_permission_revoked`）。

大模型生成脚本前建议先调用 `task_docs` 获取权威签名与完整示例：

```bash
curl -X POST http://localhost:8099/mcp \
  -H 'Content-Type: application/json' -H 'Authorization: Bearer <token>' \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"task_docs","arguments":{}}}'
```

脚本示例：
```js
await host.invoke('screen.launch', { pkg: 'com.android.settings' });
await new Promise(r => setTimeout(r, 2000));
const info = await host.invoke('screen.info', {});
await host.invoke('screen.swipe', { x1: info.w/2, y1: info.h*0.75, x2: info.w/2, y2: info.h*0.25, durMs: 500 });
const f = await host.invoke('screen.frame', {});
await host.invoke('log', { msg: 'captured ok=' + f.ok });
```

## 8. 测试用例（真机回归）

| 用例 | 关键工具 | 预期 |
|---|---|---|
| 计算器 `1+2*3-4/55` | `app_launch` + `screen_tap`/`screen_text` | `6.9272727…` |
| 天气搜索「北京」 | `screen_tap` + `screen_text`（中文） | 进入北京市天气详情 |
| 日历倒数日（2027-01-01，提前 3 天 20:00，闹钟开） | `screen_tap` + `screen_text` + `screen_key` | 倒数日创建成功 |
| 自动化任务（cron 每分钟 00 秒，连点 10 次） | `task_create`/`task_run`/`task_get`/`task_stop`/`logs` | `runCount` 增长、`1111111111`、可停止 |
