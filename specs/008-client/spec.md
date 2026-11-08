# 008-client Specification — 播控客户端（/client）

> Requirement: 008-client（本应用的播控客户端）
> Status: Design（已批准方案 见 [design.md](./design.md)）
> 对应源码：`core/server/`（新增页面/端点）、`app/`（TaskRuntime 运行状态）
> 关联设计：[design.md](./design.md) ｜ 上游：004-server（REST）、007-webrtc（推流/信令/DataChannel）

## 1. Module Overview
### 1.1 Purpose
在 HTTP 服务上提供一个面向**浏览器/手机浏览器**的播控客户端页面 `GET /client?token=<token>`：
把虚拟屏视频回显（WebRTC 优先、MJPEG 降级）与"应用 / 自动化 / 日志"三大操作入口收拢到一个
移动端友好页面，供用户在任意浏览器上观看并操作被托管 App、管理自动化服务任务。

### 1.2 Problems Solved
- 现有 `/webrtc` 控制台只解决"观看+单指操作"，没有应用切换、自动化调度、日志查看入口。
- 浏览器侧需要一个完整的一站式播控台：状态 + 视频回显 + 应用切换 + 自动化调度 + 日志。

### 1.3 Scope
- **做**：
  - `GET /client` 单文件自包含 HTML 页面（引导/Token 浮窗/状态栏/视频回显/四大菜单抽屉）。
  - 视频回显：先 `/frame`（无图像→「暂无会话」）；有帧 → WebRTC；失败/超时 → MJPEG(`/stream`) 降级。
  - 操作同步：WebRTC 模式走 DataChannel（`/webrtc/signaling` 复用 007）；MJPEG 模式走 REST API
    （`/action/tap`、`/action/swipe`、新增 `/action/key`）。
  - 应用抽屉：`GET /apps` + 新增 `GET /apps/{pkg}/icon`（图标经接口传输）→ 点击切换会话。
  - 自动化抽屉：`GET /tasks`、`/tasks/{id}/run`、**新增** `/tasks/{id}/stop`、编辑表单走 `/tasks` upsert。
  - 执行状态服务化：新增 `TaskRuntime`（唯一运行 id、互斥、完成回写 lastStatus），经 `/status`+`/tasks` 展示。
  - Token 引导：`/health` 增加 `authEnabled` 字段；无 token 时强制 token 输入浮窗；服务异常显示错误浮窗。
- **不做**：不回写呈现模式（浮窗/常规/headless 切换）；不做 agent/MCP 的 WebRTC 客户端；
  不改 MCP 工具集；不改 007 的 `/webrtc`、`/stream`、`/frame` 行为。

## 2. User Stories
- US-C-001：作为操作者，用手机/桌面浏览器打开 `/client?token=` 即可在一个页面看到虚拟屏画面并操作。
- US-C-002：作为操作者，未配置/填错 token 时页面强制提示输入，不暴露业务界面。
- US-C-003：作为操作者，服务未就绪/无会话时页面明确提示（错误浮窗 / 暂无会话），不白屏。
- US-C-004：作为操作者，可在应用抽屉一键切换被托管 App。
- US-C-005：作为操作者，可在自动化抽屉查看、执行、停止、编辑自动化任务，同一时刻至多一个在执行。
- US-C-006：作为操作者，可在日志抽屉查看最近服务日志排障。

## 3. Functional Requirements
### 3.1 页面引导
- FR-C-001：系统 MUST 提供 `GET /client`，返回含完整页面逻辑的单 HTML 文档（UTF-8），鉴权沿用 token 三通道（页面用 `?token=`）。
- FR-C-002：页面进入 MUST 先请求 `GET /health`：
  - 服务不可达 / 返回异常 → 全屏错误浮窗（遮挡一切），提供「刷新」按钮（`location.reload()`）。
  - `authEnabled=false`（服务未设 token）→ 无需 token，直接进入。
  - `authEnabled=true` 且未携带/带错 token → 全屏 token 输入浮窗（强制，遮挡一切）。
- FR-C-003：token 浮窗输入后 MUST 将 token 更新到地址栏 query 并重走 `/status`；
  仍 401 则浮窗保留并提示「token 无效」。
- FR-C-004：通过引导后 MUST 请求 `GET /status` 获取会话/鉴权/流/设备状态，用于标题栏右侧展示。

### 3.2 布局
- FR-C-005：标题栏：左=应用名「ManagedFloatingApp 客户端」；中=栏目菜单【播控｜应用｜自动化｜日志】；右=/status 状态摘要（会话点、鉴权、webrtc peers、stream 客户端、无障碍等）。
- FR-C-006：主区域 MUST 固定显示视频回显（WebRTC `<video>` 或降级 MJPEG `<img>`），视频区支持点击/拖动→注入。
- FR-C-007：菜单行为：「播控」=关闭其它抽屉回到主视图；「应用/自动化/日志」= 打开右侧半屏（约 50vw）抽屉。

### 3.3 视频回显与降级
- FR-C-008：页面 MUST 先调 `GET /frame`：
  - 404/无图 → 显示「暂无会话」提示，不发起视频流。
  - 有图 → 发起 WebRTC（复用 007 `/webrtc/signaling` WS 协议，App 为 offerer）。
- FR-C-009：WebRTC 握手失败 / `connectionState=failed` / 超时（如 6s）未出流 → 降级为 MJPEG：`<img src="/stream?token=">`。
- FR-C-010：MJPEG 模式下坐标/key 操作走 REST API（tap/swipe/key）；WebRTC 模式走 DataChannel（tap/swipe/key）。
- FR-C-011：页面 MUST 显示当前模式徽标（RTC / MJPEG / 暂无会话）便于排障。

### 3.4 应用抽屉
- FR-C-012：「应用」菜单打开右侧抽屉：`GET /apps` 列应用（pkg+label），图标经 `GET /apps/{pkg}/icon?token=` 加载。
- FR-C-013：点击图标 → `POST /session/launch {"pkg":...}` 切换会话承载；成功后关闭抽屉；失败显示原因。

### 3.5 自动化抽屉
- FR-C-014：「自动化」菜单打开右侧抽屉：`GET /tasks` 列任务。
- FR-C-015：每项显示：承载 App 图标+名称（经 `/apps/{pkg}/icon`+label 或 pkg）、执行时间设置（立即/间隔 X 秒/cron）、是否启用（Switch）。
- FR-C-016：启用项 right 侧显示「执行状态及按钮」（停止/播放）与「编辑」按钮：
  - 点「执行」→ `POST /tasks/{id}/run`；该 id 置为执行中，**其它启用项的按钮隐藏**。
  - 点「停止」→ `POST /tasks/{id}/stop`；清除执行中，其它项按钮恢复显示。
  - 同一时刻至多一个任务在执行：客户端互斥 + 服务端 TaskRuntime 互斥（已有一个运行时报 `ok:false`）。
- FR-C-017：执行状态来源：客户端轮询 `/status.runningTaskId`（服务端权威）+ `/tasks`（lastStatus 回写）；
  运行完成后该项恢复普通态并显示「上次：ok / error: …」。
- FR-C-018：「编辑」→ 打开表单：名称、目标 App(pkg)、脚本(script textarea)、是否启用、执行方式(kind=立即/间隔/cron)、间隔毫秒、cron；保存走 `POST /tasks` upsert。
- FR-C-019：提供「＋ 新建」入口（复用同一表单，existing=null）。

### 3.6 日志抽屉
- FR-C-020：「日志」菜单打开右侧抽屉：`GET /logs`（最近 500 条）展示（时间/级别/标签/消息）；支持级别过滤与轮询刷新、清空按钮。

### 3.7 服务端端点（为客户端新增）
- FR-C-021：`GET /apps/{pkg}/icon`：返回该应用图标 PNG（appContext.packageManager），带进程内缓存；未知包 404；鉴权同其它业务端点。
- FR-C-022：`POST /tasks/{id}/stop`：停止自动化（TaskApi.stop → TaskScheduler.stop：取消闹钟 + 写 `:node` 停止信号）。
- FR-C-023：`POST /action/key`：Body `{"code":<keycode>}` → `ScreenController.pressKey(code)`（MJPEG 降级模式按键）。
- FR-C-024：`/health` 响应增加 `authEnabled`（是否启用了 token），字段向后兼容（`ok/service` 不变）。
- FR-C-025：`/status` 响应增加 `runningTaskId`（当前执行的自动化 id，null=无）。

## 4. Success Criteria
- SC-1：`/client` 页面打开（带/不带 token）引导分支正确：无 token→token 浮窗；服务挂→错误浮窗；正常→进入主界面。
- SC-2：有会话时页面出现视频回显；WebRTC 首帧 ≤3s（局域网）；WebRTC 无法建立时自动降级 MJPEG 且可操作。
- SC-3：无会话时显示「暂无会话」，不崩溃不白屏。
- SC-4：应用抽屉图标加载、点击切换会话生效。
- SC-5：自动化：执行互斥（同时仅一个）、停止后其它项按钮恢复、编辑保存生效、lastStatus 回写。
- SC-6：日志抽屉展示日志并可过滤。
- SC-7：全部新端点真机回归通过（/frame、/stream、/apps、/apps/{pkg}/icon、/tasks CRUD/run/stop、/action/tap/swipe/key）。
- SC-8：安全：页面 401 分支、token 浮窗闭环；文档不落真实 IP/token/凭据。

## 5. Key Entities
| Entity | Description | Key Attributes |
|--------|-------------|----------------|
| ClientPage | `/client` 单页面（core:server） | token、模式(RTC/MJPEG/暂无会话)、runningTaskId、抽屉开关 |
| TaskRuntime | 服务端可见的"当前执行自动化"状态（app.script） | runningTaskId、互斥 begin/end、完成回写 lastStatus |
| 应用托盘 | /apps + /apps/{pkg}/icon | pkg、label、iconPNG（缓存） |
| 自动化任务 | REST /tasks（沿用 ScriptTask 模型） | id、packageName、enabled、kind、intervalMs、cron、lastStatus |

## 6. Acceptance Scenarios
### Scenario: token 引导
- Given 服务已设 token 且页面不带 token
- When 打开 `/client`
- Then 出现强制 token 浮窗；输入正确 token → 进入主界面显示状态/画面；输入错误 → 提示无效

### Scenario: 视频回显与降级
- Given 已建立会话
- When 打开 `/client` 且 WebRTC 可连接
- Then `<video>` 显示虚拟屏画面，点击画面注入成功
- When WebRTC 不可用（无浏览器 WebRTC / 握手失败）
- Then 降级为 MJPEG `<img>`，点击/按键经 REST 注入成功

### Scenario: 自动化互斥调度
- Given 存在多个启用任务
- When 执行任务 A
- Then A 显示停止按钮，其它启用项按钮隐藏；`/status.runningTaskId=A`
- When 执行任务 B
- Then 被拒绝（ok:false），替换提示
- When 停止 A
- Then 其它项按钮恢复显示

## 7. Non-Functional Requirements
- 局域网直连（Wi-Fi 同网段 / USB adb forward 到 localhost）；无公网、无 TURN。
- 页面为单文件、无外部 CDN 依赖（可离线/内网打开），移动端优先。
- 敏感信息（测试 URL/IP/账号/密码/凭证/密钥）不落盘文档与提交；文档与示例一律占位符。
- 服务端改动最小化：全部为新增端点/字段，不改既有行为（/webrtc、/stream、/frame、MCP 回归）。

## 8. Assumptions & Constraints
- 复用 007 的 WebRTC 信令与 DataChannel 输入协议（App offerer / 浏览器 answerer）。
- 复用现有 token 三通道鉴权（页面 `?token=`、WS 查询参数）；不引入新鉴权。
- 会话互斥只针对自动化执行；应用承载会话保持单一。
- 浏览器为现代 Chromium/WebKit 内核（RTCPeerConnection+H.264；HTTP 非安全上下文的 WebRTC 受限，此时走 MJPEG 降级）。

## 9. Dependencies
- 上游：`core:server`（REST/MJPEG/MCP/WebRTC 信令）、`core:controller`（ScreenController/frameSource）、
  `core:script`（ScriptTask/TaskStore）、`app:script`（TaskApi、TaskScheduler、ScriptService）。
- 新增依赖：无（全部复用现有 Ktor/PackageManager）。
