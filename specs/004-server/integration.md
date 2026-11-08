# 004-server 接入指南（M4 as-built）

> 面向下游：REST / MCP / 视频流。默认端口 `8099`；token 见「鉴权」。
> 无公网需求：局域网直连或 USB。

> **术语对应（自动化服务）**：本工程产品术语统一为「**自动化服务**」。
> 历史代码符号仍保留 `Script*` 命名（`ScriptService`/`ScriptTask`、模块 `core/script`、进程 `:node`），
> REST 路径 `/tasks`、JS 字段 `script`、`host.invoke('script.next'|'screen.*')`、文件 `tasks.json` 等**对外契约保持不变**。
> 即：文档中的「自动化服务 / 自动化服务任务」= 代码中的 Script / ScriptTask。

## 1. 网络接入

### 局域网（Wi-Fi）
手机与电脑/agent 同一网段，直接访问 `http://<手机IP>:8099`。
（服务器绑定 `0.0.0.0`，可用手机 `ip addr`/设置查看 IP。）

### USB（推荐开发用，不需同网段）
`adb` 端口转发二选一：

```bash
# A. forward：电脑 localhost:8099 → 手机 8099（App 在手机上监听）
adb forward tcp:8099 tcp:8099

# B. reverse：手机 localhost:8099 → 电脑 8099（仅当服务跑在电脑上时需要）
adb reverse tcp:8099 tcp:8099
```

> ⚠️ 本工程服务器运行在**手机**上，用 **forward**。
> `reverse` 会让 adbd 占用手机端口，导致 App 绑定失败（BindException）。

之后统一访问 `http://localhost:8099`。

## 2. 鉴权

- 未设置 token 时所有端点开放（仅建议局域网可信环境）。
- 设置 token 后，业务端点要求请求头：`X-Auth-Token: <token>`；`/health` 与 `/debug/*` 不校验。
- 设置方式：App 界面「保存 token」（重启生效），或 `POST /debug/set-token {"token":"..."}`。
- 也支持标准 **`Authorization: Bearer <token>`** 头（MCP 客户端默认方式）。
- 也支持查询参数兜底：`?token=<token>`（便于浏览器/简单客户端）。
- 未通过鉴权返回 `401` + `WWW-Authenticate: Bearer realm=ManagedFloatingApp`，
  明确声明为 Bearer 方案（**非 OAuth**），避免 MCP 客户端退化为 OAuth 动态客户端注册(DCR)。
  若客户端仍报 "OAuth dynamic client registration failed"，请在客户端里手动填 Bearer token，
  关闭/忽略 OAuth 选项。

## 3. REST 端点

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/health` | 健康检查（开放） |
| GET | `/status` | 运行状态：会话/鉴权/SDK/设备/无障碍/连接端口 |
| GET | `/logs` | 最近 500 条统一日志（可选 `?level=E/W/I/D`） |
| POST | `/logs/clear` | 清空日志缓冲 |
| POST | `/debug/session/stop` | 错误恢复：清理失效会话/助手，随后可重新 start |
| GET | `/apps` | 已安装可启动 App 列表 |
| POST | `/session/launch` | `{"pkg":"...","component":"..."}` 承载 App 到虚拟屏 |
| GET | `/frame` | 当前虚拟屏帧（PNG） |
| POST | `/action/tap` | `{"x":..,"y":..}` |
| POST | `/action/swipe` | `{"x1":..,"y1":..,"x2":..,"y2":..,"durMs":..}` |
| GET | `/tasks` | 自动化服务任务列表 |
| POST | `/tasks` | 创建/更新任务（JSON） |
| DELETE | `/tasks/{id}` | 删除任务 |
| POST | `/tasks/{id}/run` | 立即执行任务 |
| GET | `/ui` | 读被托管 App 界面元素（JSON；需启用无障碍服务） |
| GET | `/stream` | **MJPEG** 视频流（`multipart/x-mixed-replace`，最多 8 路并发，超出 503） |

## 4. MCP（Model Context Protocol）

- **Streamable HTTP**：`POST /mcp`，JSON-RPC 2.0。支持 `initialize`、`ping`、`tools/list`、`tools/call`。
- **SSE**：`GET /mcp/sse`，先返回 `event: endpoint` + `data: /mcp?sessionId=<id>`；
  随后 `POST /mcp?sessionId=<id>` 提交 JSON-RPC，响应经该 SSE 通道以 `event: message` 推回（POST 返回 202）。

### 工具清单（共 9 个，以 `tools/list` 实时返回为准）

#### 屏幕与交互
| 工具 | 说明 | 参数（必填标 *） |
|------|------|------------------|
| `screen_capture` | 截当前虚拟屏，返回 base64 PNG；静态画面无新帧时返回最后一帧 | 无 |
| `ui_read` | 读界面元素树（JSON 数组）；需先启用无障碍服务，未启用时优雅降级提示 | 无 |
| `screen_tap` | 在虚拟屏坐标点击 | `x*`, `y*` |
| `screen_swipe` | 虚拟屏滑动 | `x1*`,`y1*`,`x2*`,`y2*`，可选 `durMs`（默认 300） |

#### 应用管理
| 工具 | 说明 | 参数（必填标 *） |
|------|------|------------------|
| `app_list` | 列出所有可启动的已安装应用（packageName + label） | 无 |
| `app_launch` | 把指定 App 承载到虚拟屏 | `packageName*`，可选 `component` |

#### 自动化服务任务
| 工具 | 说明 | 参数（必填标 *） |
|------|------|------------------|
| `task_list` | 列出自动化服务任务 | 无 |
| `task_create` | 创建/更新任务 | `task*`（对象：`id,name,script,enabled,kind,intervalMs,cron`） |
| `task_run` | 立即执行某个任务 | `id*` |

#### 调用约定
- 协议方法：`initialize`、`notifications/initialized`（通知，回 202）、`ping`、`tools/list`、`tools/call`。
- 工具返回统一为 `result.content[0].text`；失败时 `result.isError=true`，文本以 `error:` 或 `ok=false` 开头。
- 标准 JSON-RPC 错误码：解析失败 `-32700`、未知方法 `-32601`；鉴权失败 HTTP `401`（带 `WWW-Authenticate: Bearer`）。
- **前提**：屏幕类工具（capture/tap/swipe/app_launch）需先建立会话（见 §6，`/status` 的 `sessionActive=true`）；`ui_read` 需启用无障碍。

示例：
```bash
# 点击（参数是 x/y）
curl -s -X POST http://localhost:8099/mcp \
  -H 'Content-Type: application/json' -H 'Authorization: Bearer <token>' \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"screen_tap","arguments":{"x":360,"y":500}}}'

# 承载 App（注意参数名是 packageName，不是 pkg）
curl -s -X POST http://localhost:8099/mcp \
  -H 'Content-Type: application/json' -H 'Authorization: Bearer <token>' \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"app_launch","arguments":{"packageName":"com.android.settings"}}}'

# 截图（返回 base64 PNG）
curl -s -X POST http://localhost:8099/mcp \
  -H 'Content-Type: application/json' -H 'Authorization: Bearer <token>' \
  -d '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"screen_capture","arguments":{}}}'
```

## 5. 界面读取（ui_read / GET /ui）

- 需在系统「设置 → 无障碍」启用 **ManagedFloatingApp** 服务。
- 服务遍历 `AccessibilityService.windows` 输出元素（text/class/bounds/clickable/displayId）。
- **已知限制**：部分 ROM（实测 MIUI/Android 13）的无障碍窗口列表**不含自建虚拟屏**，
  此时 `/ui` 只能读到默认 display 的元素；虚拟屏内容建议用 `screen_capture`（截图）+ 视觉模型，
  或在后续里程碑改用 shell 侧定向读取。

## 6. 视频流（MJPEG）

```bash
# 浏览器直接打开（带 token 时用 ?token= 或请求头视客户端而定）
curl -H 'X-Auth-Token: <token>' http://localhost:8099/stream --output stream.mjpeg
```
每帧为 PNG（`--frame` 分隔）。最多 8 路并发（`MAX_STREAM_CLIENTS`），超出返回 503。
WebRTC 为二期（未实现）。

## 7. 前置：会话就绪

REST/MCP 的屏幕操作需要先建立会话（配对 + 连 adbd + 拉起助手）：

```bash
curl -X POST http://localhost:8099/debug/session/start \
  -H 'Content-Type: application/json' -d '{"connectPort":<adbd端口>}'
```
- 首次需在设备「无线调试 → 使用配对码配对设备」用 `/debug/pair` 配对一次（见 M1-2 记录）。
- 连接端口可从设备 `/proc/net/tcp` 里 uid=2000 的监听端口读到（App 内受 hidepid 限制，故需外部提供/缓存）。
- App 重启后会用缓存的 connectPort 自动恢复会话。

## 8. 安全提示

- 生产使用务必设置 token。
- 不要把真实 token、设备 IP 写进仓库/文档（本文档用占位符）。
