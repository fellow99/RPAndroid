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

## 4. 工具清单（9 个）

以 `tools/list` 实时返回为准。

### 屏幕与交互
| 工具 | 说明 | 参数（`*` 为必填） |
|------|------|--------------------|
| `screen_capture` | 截当前虚拟屏，返回 base64 PNG；静态画面无新帧时返回最后一帧 | 无 |
| `ui_read` | 读界面元素树（JSON 数组）；需启用无障碍，未启用时返回降级提示 | 无 |
| `screen_tap` | 虚拟屏坐标点击 | `x*`, `y*` |
| `screen_swipe` | 虚拟屏滑动 | `x1*`,`y1*`,`x2*`,`y2*`，可选 `durMs`（默认 300） |

### 应用管理
| 工具 | 说明 | 参数（`*` 为必填） |
|------|------|--------------------|
| `app_list` | 列出所有可启动应用（packageName + label） | 无 |
| `app_launch` | 把 App 承载到虚拟屏 | `packageName*`，可选 `component` |

### 自动化服务任务
| 工具 | 说明 | 参数（`*` 为必填） |
|------|------|--------------------|
| `task_list` | 列出自动化服务任务 | 无 |
| `task_create` | 创建/更新任务 | `task*`：`id,name,script,enabled,kind,intervalMs,cron` |
| `task_run` | 立即执行任务 | `id*` |

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
