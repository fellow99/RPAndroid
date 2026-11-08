# REST API 接入文档

> 面向第三方：ManagedFloatingApp 的 HTTP REST 接口。
> 相关文档：[MCP 接入](./MCP.md) ｜ [MJPEG 视频流](./MJPEG.md)

- 默认端口：`8099`
- 无公网需求：仅局域网 Wi-Fi 或 USB `adb forward`，不提供跨网/公网服务。
- 术语：产品称「自动化服务」，对应代码中的 `Script*`；REST 路径等外部契约保持不变。

## 1. 网络接入

### 局域网（Wi-Fi）
手机与调用方在同一网段，直接访问 `http://<手机IP>:8099`。
服务器绑定 `0.0.0.0`，可在手机设置或 `ip addr` 查看 IP。

### USB（推荐开发用）
服务运行在**手机**上，使用 `adb forward`：

```bash
adb forward tcp:8099 tcp:8099
# 之后访问 http://localhost:8099
```

> 不要使用 `adb reverse`：它会让 adbd 占用手机侧端口，导致 App 绑定失败（BindException）。

## 2. 鉴权

- 未设置 token 时所有业务端点开放（仅限可信局域网）。
- 设置 token 后，除 `/health` 与 `/debug/*` 外都需要鉴权，三种方式任选：
  - 请求头：`X-Auth-Token: <token>`
  - 标准 Bearer：`Authorization: Bearer <token>`
  - 查询参数兜底：`?token=<token>`（适合浏览器/简单客户端）
- 设置方式：App 界面「保存 token」（重启生效），或 `POST /debug/set-token`。
- 鉴权失败返回 `401`，并带 `WWW-Authenticate: Bearer realm=ManagedFloatingApp`。
  本服务是静态 Bearer 令牌，**不是 OAuth**；若客户端尝试 OAuth 动态注册并报错，请改用手动配置 Bearer token。

## 3. 前置：会话就绪

屏幕类接口（`/session/launch`、`/frame`、`/action/*`、`/ui`）需要先建立托管会话（连接 adbd + 拉起 shell 助手 + 创建虚拟屏）：

```bash
curl -X POST http://localhost:8099/debug/session/start \
  -H 'Content-Type: application/json' \
  -d '{"connectPort":<adbd端口>}'
```

- 首次使用需在设备「无线调试 → 使用配对码配对设备」完成一次配对。
- App 重启后会用缓存的 connectPort 自动恢复会话。
- 可用 `GET /status` 检查 `sessionActive` 是否为 `true`。
- 错误恢复：`POST /debug/session/stop` 清理失效会话/助手后再重新 start。

## 4. 端点总览

Base URL：`http://<手机IP>:8099`（局域网）或 `http://localhost:8099`（USB）。

### 4.1 系统与诊断
| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/health` | 健康检查（无需鉴权），返回 `ok/service/port/authEnabled` |
| GET | `/status` | 运行状态：会话/鉴权/SDK/设备/无障碍/缓存端口/流客户端数/RTC peers/`runningTaskId` |
| GET | `/logs` | 最近 500 条统一日志，可选 `?level=E/W/I/D` |
| POST | `/logs/clear` | 清空日志缓冲 |
| GET | `/client` | 播控客户端页面（无需鉴权，见 [Client.md](./Client.md)） |
| POST | `/debug/session/start` | 建立托管会话（调试端点，无需 token） |
| POST | `/debug/session/stop` | 清理失效会话/助手（错误恢复） |
| POST | `/debug/set-token` | 设置 token（重启生效） |

### 4.2 应用与屏幕
| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/apps` | 已安装可启动 App 列表 |
| GET | `/apps/{pkg}/icon` | 应用图标 PNG（进程内缓存）；未知包 404 |
| POST | `/session/launch` | 承载 App 到虚拟屏，body：`{"pkg":"...","component":"..."}` |
| GET | `/frame` | 当前虚拟屏帧（PNG）；无会话返回 404 |
| POST | `/action/tap` | 点击，body：`{"x":360,"y":500}` |
| POST | `/action/swipe` | 滑动，body：`{"x1":..,"y1":..,"x2":..,"y2":..,"durMs":300}` |
| POST | `/action/key` | 按键注入，body：`{"code":4}`（MJPEG 降级模式按键） |
| POST | `/action/text` | 文本输入，body：`{"text":"北京"}`（ASCII 走系统 input text；非 ASCII 走剪贴板+粘贴） |
| GET | `/ui` | 界面元素树（JSON，需启用无障碍服务；不可用时 503） |

### 4.3 自动化服务任务
| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/tasks` | 自动化服务任务列表 |
| POST | `/tasks` | 创建/更新任务（JSON） |
| DELETE | `/tasks/{id}` | 删除任务 |
| POST | `/tasks/{id}/run` | 立即执行任务（互斥：已在执行其它任务时返回 `ok:false`） |
| POST | `/tasks/{id}/stop` | 停止任务的执行/调度 |

任务对象字段：`id,name,packageName,script,enabled,kind,intervalMs,cron`（另含运行统计 `lastRunAt,nextRunAt,runCount,lastStatus`）；
`kind` 取值 `IMMEDIATE` / `INTERVAL` / `CRON`。执行状态见 `GET /status` 的 `runningTaskId`。

- `cron` 支持 5 字段（`分 时 日 月 周`）与 6 字段（`秒 分 时 日 月 周`）；“每分钟第 00 秒”用 `0 * * * * *`。
- 定时触发会占用全局唯一运行态（与手动 run 互斥）并更新 `runCount/lastRunAt`；完成后 watchdog 回写 `lastStatus`。
- 定时任务依赖精确闹钟（`USE_EXACT_ALARM`）；若 ROM 回收该权限，息屏/待机会被延迟。
- `POST /tasks/{id}/stop` 立即释放运行态并写 `lastStatus=cancelled`（脚本中止为尽力而为）。

> MCP 侧另提供 `task_modify`（按 id 部分修改、保留运行统计）、`task_get`、`task_stop`、`task_run(waitMs)`、`logs` 与 `task_docs`（JS host 接口文档），详见 [MCP.md](./MCP.md)。

## 5. 调用示例

```bash
# 状态
curl -H "Authorization: Bearer <token>" http://localhost:8099/status

# 承载 App
curl -X POST http://localhost:8099/session/launch \
  -H "Authorization: Bearer <token>" -H 'Content-Type: application/json' \
  -d '{"pkg":"com.android.settings"}'

# 截图保存为 PNG
curl -H "Authorization: Bearer <token>" http://localhost:8099/frame -o screen.png

# 点击
curl -X POST http://localhost:8099/action/tap \
  -H "Authorization: Bearer <token>" -H 'Content-Type: application/json' \
  -d '{"x":360,"y":500}'
```

## 6. 界面读取（GET /ui）

- 需在系统「设置 → 无障碍」启用 **ManagedFloatingApp** 服务。
- 输出元素字段：`text/className/packageName/clickable/editable/scrollable/displayId/bounds`。
- 已知限制：部分 ROM（实测 MIUI/Android 13）的无障碍窗口列表不含自建虚拟屏，此时 `/ui` 仅能读到默认 display；
  虚拟屏内容建议使用 `/frame` 截图配合视觉模型。

## 7. 安全提示

- 生产/可信环境外务必设置 token。
- 不要把真实 token、设备 IP 提交到仓库；本文档统一使用占位符。
- 视频流接入见 [MJPEG.md](./MJPEG.md)，MCP 调用见 [MCP.md](./MCP.md)。
