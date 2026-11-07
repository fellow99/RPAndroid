# 004-server 接入指南（M4 as-built）

> 面向下游：REST / MCP / 视频流。默认端口 `8099`；token 见「鉴权」。
> 无公网需求：局域网直连或 USB。

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

## 3. REST 端点

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/health` | 健康检查 |
| GET | `/apps` | 已安装可启动 App 列表 |
| POST | `/session/launch` | `{"pkg":"...","component":"..."}` 承载 App 到虚拟屏 |
| GET | `/frame` | 当前虚拟屏帧（PNG） |
| POST | `/action/tap` | `{"x":..,"y":..}` |
| POST | `/action/swipe` | `{"x1":..,"y1":..,"x2":..,"y2":..,"durMs":..}` |
| GET | `/tasks` | 脚本任务列表 |
| POST | `/tasks` | 创建/更新任务（JSON） |
| DELETE | `/tasks/{id}` | 删除任务 |
| POST | `/tasks/{id}/run` | 立即执行任务 |
| GET | `/stream` | **MJPEG** 视频流（`multipart/x-mixed-replace`） |

## 4. MCP（Model Context Protocol）

- **Streamable HTTP**：`POST /mcp`，JSON-RPC 2.0。支持 `initialize`、`ping`、`tools/list`、`tools/call`。
- **SSE**：`GET /mcp/sse`，先返回 `event: endpoint` + `data: /mcp?sessionId=...`，随后经 `POST /mcp` 交互。

工具清单：
| 工具 | 说明 |
|------|------|
| `screen_capture` | 截当前虚拟屏（base64 PNG） |
| `screen_tap` | 点击（虚拟屏坐标） |
| `screen_swipe` | 滑动 |
| `app_list` | 列出 App |
| `app_launch` | 承载 App |
| `task_list` / `task_create` / `task_run` | 脚本任务 |

示例：
```bash
curl -s -X POST http://localhost:8099/mcp \
  -H 'Content-Type: application/json' -H 'X-Auth-Token: <token>' \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"screen_tap","arguments":{"x":360,"y":500}}}'
```

## 5. 视频流（MJPEG）

```bash
# 浏览器直接打开（带 token 时用 ?token= 或请求头视客户端而定）
curl -H 'X-Auth-Token: <token>' http://localhost:8099/stream --output stream.mjpeg
```
每帧为 PNG（`--frame` 分隔）。WebRTC 为二期（未实现）。

## 6. 前置：会话就绪

REST/MCP 的屏幕操作需要先建立会话（配对 + 连 adbd + 拉起助手）：

```bash
curl -X POST http://localhost:8099/debug/session/start \
  -H 'Content-Type: application/json' -d '{"connectPort":<adbd端口>}'
```
- 首次需在设备「无线调试 → 使用配对码配对设备」用 `/debug/pair` 配对一次（见 M1-2 记录）。
- 连接端口可从设备 `/proc/net/tcp` 里 uid=2000 的监听端口读到（App 内受 hidepid 限制，故需外部提供/缓存）。
- App 重启后会用缓存的 connectPort 自动恢复会话。

## 7. 安全提示

- 生产使用务必设置 token。
- 不要把真实 token、设备 IP 写进仓库/文档（本文档用占位符）。
