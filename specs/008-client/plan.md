# 008-client Technical Plan

> Requirement: 008-client ｜ 对应 [spec.md](./spec.md) ｜ [design.md](./design.md)
> 分支：不改动（master，沿用现有提交规范）

## 1. Technical Context
### 1.1 Runtime Environment
- 主进程（App / `ManagedServer`）：提供页面 + 新端点；同进程 `TaskRuntime` 跟踪执行状态。
- `:node` 进程：自动化任务执行（不变）；停止仍走 `mfa-stop/<id>.flag` 中断信号。
- shell 助手进程：虚拟屏/注入（不变）。

### 1.2 Dependencies（无新增）
全部复用现有：Ktor（ContentNegotiation/WebSockets）、PackageManager（图标）、
`WebRtcPush/WebRtcWire`（007，页面复用其信令与 DataChannel 协议）、`TaskScheduler/ScriptService`（调度）。

## 2. Constitution Compliance
- C-1 单入口：页面操作 → ScreenController（WebRTC 经 WebRtcWire、REST 经 controller）。✅
- C-3 依赖单向：`server` 不反向依赖 app；TaskRuntime 放 app 层，server 经 `statusProvider`+`TaskApi` 读。✅
- C-7 优雅降级：无会话→「暂无会话」；WebRTC 失败→MJPEG；无 appContext→图标端点 503。✅
- C-10 提交前缀：Conventional Commits。✅
- C-12 安全：页面无硬编码真实地址/凭据；文档占位符。✅

## 3. Data Model
- 新增响应字段：
  - `/health` → `{ ok, service, authEnabled }`。
  - `/status` → 追加 `runningTaskId: String?`（经 statusProvider 注入）。
  - `/apps/{pkg}/icon` → PNG bytes（`Content-Type: image/png`）或 404。
- `TaskRuntime`（app.script 单例）：
  - `@Volatile runningTaskId: String?`
  - `begin(id): Boolean`——已有一个运行时返回 false（互斥）。
  - `end(id)`——清空。
  - 内置 watchdog coroutine（进程内懒启动 1s 轮询）：当 runningTaskId 非空时
    `ScriptService.takeRunOutcome(id)` 命中 → `TaskStore.upsert(lastStatus=ok|error:..)` 并 `end(id)`。

## 4. Interface Contracts
### 4.1 ManagedServer（core:server）
```kotlin
get("/client")                         // ClientPage.HTML（?token=）
get("/apps/{pkg}/icon")                // PNG｜404；Dispatchers.IO + 内存缓存
post("/action/key") { {"code":Int} }   // controller.pressKey(code)
post("/tasks/{id}/stop")               // taskApi?.stop(id)
get("/health")                         // + authEnabled
get("/status")                         // + runningTaskId (statusProvider)
```
- `/health` 的 authEnabled 直接读 `authToken.isNotEmpty()`。
- `/status` 的 runningTaskId 经 `statusProvider`（app 注入 `TaskRuntime.runningTaskId`）。

### 4.2 TaskApi（core:server 接口）+ TaskApiImpl（app）
```kotlin
interface TaskApi { ...; fun stop(id: String): Boolean }
// TaskApiImpl:
//   run(id): if (!TaskRuntime.begin(id)) return false; scheduler.runNow(id); true
//   stop(id): TaskRuntime.end(id); scheduler.stop(id)
```
- 互斥语义：run 返回 `ok:false` 表示当前已有其它任务在执行（"同时仅一个"）。
- 结果回写：watchdog 完成后 `lastStatus` 写入 store（原来只有 app UI 轮询才写；现在服务端也写）。

### 4.3 页面（ClientPage）
单文档 HTML+JS（核心自包含，无 CDN）。协议复用：
- 信令：WS `{type:offer|answer|ice|state}`（App offerer，浏览器 answerer），同 007。
- DataChannel 输入：`{"t":"tap","nx":..,"ny":..}`｜`{"t":"swipe",...}`｜`{"t":"key","code":N}`。
- MJPEG：`<img src="/stream?token=">`；REST 操作：`POST /action/tap|swipe|key`。
- 状态轮询：`/status`(2s) + `/tasks`(2s，自动化抽屉开时) + `/logs`(3s，日志抽屉开时)。

## 5. Implementation Strategy
### 5.1 Architecture Pattern
服务端桥先行（C1），页面后接（C2），联调（C3）。

### 5.2 Key Algorithms
- 图标缓存：`object AppIconCache { LruCache<String, ByteArray> }`，命中即返；未命中 IO 取 Bitmap→PNG→缓存。
- 坐标映射：复用 007 控制台 norm()（letterbox 归一化 0..1）；WebRTC 模式 dc.send，MJPEG 模式换算后 POST。
- 降级判定：信令建连后 6s 内 `ontrack` 未触发 / `connectionState=failed` / WS 关闭 → 切 MJPEG `<img>`。

### 5.3 Error Handling
- 引导失败（网络/非 200）→ 全屏错误浮窗 + 刷新按钮。
- `/frame` 404 → 「暂无会话」；点击重试可再探（刷新按钮）。
- 应用启动失败 → 抽屉内提示 `result.message`。
- 自动化 run 被拒（ok:false）→ 提示"已有自动化在执行"。
- 无 appContext（测试环境）→ 图标端点 503、`/client` 可返回页面但绘图外调用受限（页面已有 401/暂无会话 处理）。

### 5.4 Performance
- 图标缓存避免重复 PNG 编码；
- 页面只对打开的抽屉做对应轮询，减少请求量；
- MJPEG 降级复用现有 `/stream`（8 客户端上限）。

## 6. Testing Considerations
- 真机 + 桌面 Playwright（adb forward localhost:8099）验证页面逻辑/降级/抽屉；WebRTC 真机浏览器人工验收。
- REST 新端点 curl 回归；MCP /webrtc /stream /frame 回归（保证不改既有行为）。
- 详见 [test-cases.md](./test-cases.md)。

## 7. Risks & Mitigations
| 风险 | 缓解 |
|---|---|
| 浏览器 HTTP 环境 WebRTC 受限 | localhost/自签 TLS；自动 MJPEG 降级 |
| 自动化执行快，runningTaskId 生命周期 | 服务端 outcome watchdog（1s）+ lastStatus 回写 |
| 图标全量编码开销 | LruCache 按 pkg 缓存 |
| 互斥状态在 app UI 与服务端两套 | 服务端 TaskRuntime 为权威；app UI 的 PresentationHub 保持不变（各自互斥） |
| 页面 JS 出错白屏 | 顶部 try/catch + 全局 onerror 显示错误条 |

## 8. File Inventory（新增/改动）
| File | Purpose |
|---|---|
| `core/server/.../ClientPage.kt`（新增） | `/client` 单文档页面 |
| `core/server/.../AppIconCache.kt`（新增） | 应用图标内存缓存 |
| `core/server/.../ManagedServer.kt`（改） | /client、/apps/{pkg}/icon、/action/key、/tasks/{id}/stop、/health.authEnabled |
| `core/server/.../TaskApi.kt`（改） | +stop(id) |
| `app/script/TaskRuntime.kt`（新增） | 执行中状态 + 完成回写 |
| `app/script/TaskApiImpl.kt`（改） | stop 实现；run 互斥 begin |
| `app/ManagedRuntime.kt`（改） | statusProvider + runningTaskId |
| `docs/Client.md`（新增）、`docs/API.md`（改） | 接入文档 + 端点表补充 |
| `specs/008-client/*`、`logs/20261108-3/*` | 规范与阶段产物 |
