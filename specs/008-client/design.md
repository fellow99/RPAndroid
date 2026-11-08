# 008-client 设计决策记录（brainstorm 落盘）

> Status: **Approved**
> 需求编号：008-client ｜ 需求名称：本应用的播控客户端 ｜ 分支：不改动（master）
> 关联：`docs/API.md`、`docs/MJPEG.md`、`docs/WebRTC.md`、[spec.md](./spec.md)

## 1. 背景

007-webrtc 提供 `/webrtc` 单面控制台（观看+单指操作）。用户需要一个**一站式播控客户端**页面
`/client`：状态栏 + 视频回显（WebRTC 优先/MJPEG 降级）+ 应用切换 + 自动化调度（执行/停止/编辑）+ 日志，
真机浏览器可达、可操作。

## 2. 勘察结论

- 现有端点已覆盖：`/health`、`/status`、`/apps`、`/session/launch`、`/frame`、`/action/tap`、`/action/swipe`、
  `/stream`(MJPEG)、`/webrtc`+`/webrtc/signaling`(WS+DataChannel)、`/tasks` CRUD+run、`/logs`。
- **缺口**：① 图标不经接口传输（`/apps` 仅 pkg+label，浏览器无法取 PackageManager）；② REST 无
  `/tasks/{id}/stop`（仅 app 内 `TaskScheduler.stop`）；③ REST 无 `/action/key`（WebRTC 时才可按键）；
  ④ 无服务端可见的"当前执行中任务 id"（app UI 用进程内 `PresentationHub.runningTaskId`，REST 侧读不到）；
  ⑤ `/health` 不携带 `authEnabled`，页面无法在进入时决定是否强制输 token。
- 参考工程 Mika 无同类型浏览器播控台（007 已确认其无网络视频推流），此处按本工程既有 WebRtcConsole 扩写。

## 3. 头脑风暴决策（用户确认）

| 决策点 | 结论 |
|---|---|
| Q1 页面形态 | 单文件自包含 HTML（`core:server` 内 `ClientPage.kt`，同 WebRtcConsole 模式），无外部 CDN |
| Q2 token 引导 | `/health` 增加 `authEnabled`；页面据此 + 尝试 `/status`(401) 决定是否强制 token 浮窗 |
| Q3 视频链路 | 先 `/frame` 定"有无会话"；有则 WebRTC；握手失败/超时 → MJPEG 降级；模式徽标显示当前链路 |
| Q4 操作同步 | WebRTC → DataChannel（复用 007）；MJPEG → REST（tap/swipe + **新增** key） |
| Q5 图标传输 | **能**：新增 `GET /apps/{pkg}/icon` 返回 PNG + 进程内缓存；应用/自动化抽屉 `<img>` 懒加载 |
| Q6 停止自动化 | 新增 `POST /tasks/{id}/stop` → `TaskApi.stop` → `TaskScheduler.stop`（取消闹钟+`mfa-stop` 信号） |
| Q7 只一个执行中 | 新增 `app.script.TaskRuntime`：全局唯一 runningTaskId（互斥 begin/end），完成回写 lastStatus；`/status.runningTaskId` 暴露 |
| Q8 密钥/敏感信息 | 页面只用 `?token=` 查询参数字段名，不在 HTML 里写死了任何真实 IP/token/账号/密码；文档示例一律占位符 |

## 4. 架构

```
浏览器（/client 页面）
  ├─ /health(authEnabled) ─┤ token 浮窗 / 错误浮窗（遮挡全屏）
  ├─ /status ──────────────┤ 标题栏状态摘要 + runningTaskId 轮询
  ├─ /frame → /webrtc/signaling(WS) ──▶ WebRTC <video> (DataChannel 输入)   [主]
  │     └─ 失败/超时 ──▶ /stream(MJPEG) <img> (REST 输入)                    [降级]
  ├─ /apps + /apps/{pkg}/icon ──▶ 应用抽屉 → /session/launch
  ├─ /tasks + /tasks/{id}/run|stop ──▶ 自动化抽屉（互斥 + 编辑表单 POST /tasks）
  └─ /logs ──▶ 日志抽屉
服务端（core:server + app.script）
  ManagedServer: /client、/apps/{pkg}/icon、/tasks/{id}/stop、/action/key、/health.authEnabled、/status.runningTaskId
  TaskApi(interface): +stop(id)
  TaskApiImpl: stop→TaskScheduler.stop；run→TaskRuntime.begin(互斥) 后调度
  TaskRuntime(app.script): runningTaskId + outcome 轮询回写 lastStatus
```

- 依赖单向遵守 C-3：`core:server → core:controller`；新增图标读取用 server 注入的 `appContext.packageManager`；
  TaskRuntime 在 app 层（app:script），不向下游模块引入（`server` 只读 `statusProvider` 的键 + TaskApi 接口）。
- C-1 单入口：页面所有注入/操作仍收敛到 ScreenController（DataChannel 经 WebRtcWire、REST 经 controller 直调）。
- C-10 提交前缀：Conventional Commits（feat/fix/docs/chore/build）。
- C-12 安全：页面无任何硬编码真实地址/凭据；`?token=` 仅展示占位符；`.pi/`、日志里真实 IP/token 不入库。

## 5. 关键取舍

| 点 | 选择 | 备选 | 理由 |
|---|---|---|---|
| 图标 | `/apps/{pkg}/icon`（独立端点+缓存） | `/apps` 内嵌大量 base64 | 响应小、`<img>` 原生懒加载；base64 会把列表响应撑爆 |
| 执行中状态 | 服务端 TaskRuntime，/status 权威 + /tasks lastStatus 回写 | 纯客户端本地记 | 跨来源一致（app UI / REST / 客户端），"同时仅一个"服务端也强制 |
| 停止 | REST stop 端点 | 仅客户端模拟 | 需真正取消闹钟+中断 `:node`，必须走自动化服务调度 |
| MJPEG 按键 | 新增 `/action/key` | 不做 | 降级模式 Backspace/Enter 需要，改动一行 |
| 日志 | 复用 `/logs` 快照（500 条+级别过滤） | 新 SS 流 | 满足需求、改动最小，页面轮询即可 |

## 6. 里程碑切分
| 里程碑 | 内容 |
|---|---|
| C1 服务端桥 | /health.authEnabled、/apps/{pkg}/icon(+缓存)、/action/key、/tasks/{id}/stop、TaskApi.stop、TaskRuntime(互斥+回写)、/status.runningTaskId |
| C2 页面 | ClientPage 单文档：引导(token/错误浮窗) → 状态栏 → 视频回显(RTC/MJPEG 降级) → 四大菜单抽屉 |
| C3 真机联调 | 引导分支、视频/降级、应用切换、自动化互斥/停止/编辑、日志；REST/MJPEG/MCP 回归 |
| C4 文档与评审 | docs/Client.md（+API.md 端点表补充）、code review、回归测试 |

## 7. 风险簿
| 风险 | 缓解 |
|---|---|
| 浏览器 HTTP 非安全上下文 WebRTC 受限 | localhost/adb forward/自签 TLS 走 secure context；否则自动 MJPEG 降级 |
| 自动化执行快 → runningTaskId 很快清空 | 服务端 outcome 轮询（1s）回写 lastStatus；客户端兼容乐观显示 |
| /apps 全量图标并发编码开销 | 图标缓存（WeakHashMap<LruCache>），按 pkg key |
| token 输入浮窗刷新后丢失 | 保存到 localStorage 并在地址栏同步；每次启动仍以地址栏/存储为准 |
| 真机浏览器无法自动装 App | 谓用 Playwright(桌面 Chromium + adb forward localhost) 验证页面逻辑与降级；真机手工验收 |
