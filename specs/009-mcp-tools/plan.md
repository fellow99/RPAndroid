# 009-mcp-tools Technical Plan (As-Built)

> 本文档为**回溯技术方案**：记录已实现的架构、决策与策略，不是前瞻规划。
> Module: 009-mcp-tools ｜ 对应规格：[spec.md](./spec.md)
> Last Updated: 2026-11-09

## 1. Technical Context

### 1.1 Runtime Environment

| 项 | 值 |
|---|---|
| 平台 | Android（真机 Redmi M2105K81C / Android 13 / MIUI），arm64-v8a |
| SDK | compileSdk 36 / targetSdk 36 / minSdk 24（Node 引擎要求） |
| 语言/构建 | Kotlin，AGP 9 内置 Kotlin（不单独 apply kotlin 插件） |
| HTTP/传输 | Ktor（CIO）内嵌服务，端口 8099；MCP Streamable HTTP `POST /mcp` + SSE `GET /mcp/sse` |
| 序列化 | `org.json`（MCP/任务）、Jackson（REST 请求体） |
| 脚本运行时 | `io.github.fellow99:node-bridge:0.2.0` + `runtimeOnly io.github.fellow99:node-runtime:26.13.0`，独立 `:node` 进程 |
| 进程模型 | 主进程（REST/MCP/UI/controller）+ `:node`（ScriptService + Node runner）+ shell 助手（app_process，shell UID） |

### 1.2 Dependencies

| 依赖 | 版本/位置 | 用途 |
|---|---|---|
| Ktor server CIO | 见 `gradle/libs.versions.toml` | REST + MCP + MJPEG/WebRTC 端点 |
| node-bridge / node-runtime | 0.2.0 / 26.13.0 | JS 自动化执行引擎 |
| kotlinx-coroutines | 现有版本 | TaskRuntime watchdog |
| AndroidX Compose | 现有版本 | 主界面（009 不新增 UI） |

## 2. Constitution Compliance

| 原则 | 结论 | 说明 |
|---|---|---|
| C-1 操作单一入口 | ✅ | 新增 `screen_key`/`screen_text` 仍走 `ScreenController`；脚本经 Binder 代理到同一入口 |
| C-2 模块单一职责 | ✅ | 协议与工具分发在 `core:server`；输入实现在 `core:controller`/`core:shell`；调度在 `app/script` |
| C-3 依赖单向 | ✅ | `app → controller → shell`、`server → controller`、`:node → AIDL → app`；未引入反向依赖 |
| C-4 AGP 内置 Kotlin | ✅ | 未新增 Kotlin 插件 |
| C-5 Node 独立进程 | ✅ | 脚本仍在 `:node`；新增日志经 Binder 回主进程，不把 Node 移入主进程 |
| C-6 arm64-only | ✅ | 未改 ABI |
| C-7 优雅降级 | ✅ | 无会话/无帧/未知键名/参数缺失均返回 `ok=false`/`error:`，不崩溃 |
| C-8 呈现无关控制 | ✅ | 新工具只依赖会话，不依赖嵌入/全屏/浮窗 |
| C-9 规范文档为真相 | ✅ | 反向补齐 `specs/009-mcp-tools/`、`docs/MCP.md`、`docs/API.md` |
| C-10 提交规范 | ✅ | `docs(spec)` / `feat(mcp)` / `fix(mcp)` / `test(mcp)` |
| C-11 代码评审门禁 | ✅ | requesting/receiving-code-review fallback 走查，见 `logs/20261109-1/REVIEW_REPORT.md` |
| C-12 安全底线 | ✅ | 文档/证据仅占位符；`.pi/mcp.json` gitignore；日志只记长度/状态 |

## 3. Research Findings（关键决策）

| # | 决策 | 理由 / 备选 |
|---|---|---|
| R1 | 中文输入 = 系统剪贴板 + `KEYCODE_PASTE`；ASCII = 助手 `input text` | 不新增 IME、不改用户默认输入法；无障碍 `ACTION_SET_TEXT` 在虚拟屏不可用（MIUI 窗口列表不含虚拟屏）。备选 IME 方案需 `ime set` 切换，侵入性高 |
| R2 | 键名映射集中在 `KeyCodes`，MCP 与脚本共用 | 避免调用方记数值；`code` 优先、`name` 兜底 |
| R3 | 状态工具复用 REST `/status` 的 statusProvider | 保证 MCP 与 REST 口径一致（分辨率、运行中任务、承载应用） |
| R4 | 脚本日志经既有 `IScreenControl` Binder 回传主进程 | `:node` 的 `MfaLog` 是进程内缓冲，不聚合；不新增通道 |
| R5 | cron 允许 5 或 6 字段，6 字段逐分钟匹配 + 命中分钟内选秒 | 5 字段行为不变；`0 * * * * *` 精确表达“每分钟第 00 秒” |
| R6 | 定时触发统一走 `TaskScheduler.onAlarm` | 原 `AlarmReceiver` 直接 `runTask`，绕过 `TaskRuntime` 导致统计/运行态/结果回写全部缺失 |
| R7 | `USE_EXACT_ALARM`（安装即授予）+ `SCHEDULE_EXACT_ALARM(maxSdk=32)` | 实测 MIUI 反复 `exact_alarm_permission_revoked`，仅靠 `SCHEDULE_EXACT_ALARM` 息屏不触发 |
| R8 | 停止信号文件“执行前清除” | `task_stop` 在空闲时也会写信号，未清理会让下一次执行在首个 `host.invoke` 被误判取消 |
| R9 | `task_run` 增加 `waitMs`，由调用方决定是否等待 | 保持默认异步语义，同时提供可判定结果 |

## 4. Data Model

### 4.1 ScriptTask（`core/script/.../ScriptTask.kt`）

| 字段 | 类型 | 说明 |
|---|---|---|
| id | String | 唯一标识；为空时自动生成 `t<epochMs>` |
| name | String | 任务名 |
| packageName | String? | 目标应用（可选） |
| script | String | JS 脚本体（async） |
| enabled | Boolean | 停用时撤销调度 |
| kind | IMMEDIATE / INTERVAL / CRON | 调度方式 |
| intervalMs | Long | INTERVAL 间隔（>0 有效） |
| cron | String | 5/6 字段表达式 |
| lastRunAt / nextRunAt | Long | 统计/计划（epoch ms） |
| runCount | Int | 运行次数 |
| lastStatus | String | `ok` / `error: …` / `cancelled` |

### 4.2 运行态与结果

- `TaskRuntime.runningTaskId: String?`：全局唯一执行者（`begin` 原子占用，`end` 匹配释放）。
- `RunOutcome(ok, error)`：`:node` runner 写 `files/mfa-run/<id>.json`，主进程 watchdog 消费后回写 `lastStatus` 并释放运行态。
- 停止信号：`files/mfa-stop/<id>.flag`；`clearStopFlag` 在每次执行前清理。

### 4.3 工具返回约定

- 成功：`ok=true …`；失败：`ok=false …` 或 `error: …`（MCP `isError=true`）。
- 截图：纯 base64；状态/任务/日志：JSON 字符串。

## 5. Interface Contracts

### 5.1 Provided — MCP（19 个，`core/server/.../McpServer.kt`）

| 分类 | 工具 | 入参 | 实现落点 |
|---|---|---|---|
| 屏幕 | `screen_capture` / `ui_read` | — | `ScreenController.captureFrame/readUi` |
| 屏幕 | `screen_tap` / `screen_swipe` | x/y；x1,y1,x2,y2,durMs | `tap/swipe` |
| 屏幕 | `screen_key` | `code`? / `name`? | `pressKey(KeyCodes.byName)` |
| 屏幕 | `screen_text` | `text` | `AppScreenController.text` |
| 状态 | `status` | — | `statusJson()`（controller + statusProvider） |
| 应用 | `app_list` / `app_launch` / `app_close` | pkg/component | `listApps/launch/closeHostedApp` |
| 任务 | `task_list` / `task_get` / `task_create` / `task_modify` / `task_delete` | id / task / patch | `TaskApi` |
| 任务 | `task_run` | id, waitMs? | `TaskApi.run` + `waitRun` |
| 任务 | `task_stop` | id | `TaskApi.stop` |
| 任务 | `task_docs` | — | `TaskScriptDocs.text()` |
| 诊断 | `logs` | level?/limit?/clear? | `MfaLog.snapshot/clear` |

JSON-RPC 方法：`initialize` / `notifications/initialized`（无响应）/ `ping` / `tools/list` / `tools/call`；
错误码：`-32700` parse error、`-32601` method not found；HTTP 401 为鉴权失败。

### 5.2 Provided — 脚本宿主接口（9 个，`app/.../ScriptService.kt`）

`screen.tap` / `screen.swipe` / `screen.launch` / `screen.frame` / `screen.key` / `screen.text` /
`screen.info` / `apps.list` / `log`（内部还有 `script.next` / `script.done`，不对脚本作者开放）。

### 5.3 Provided — REST（`core/server/.../ManagedServer.kt`）

- `POST /action/key`（既有）
- `POST /action/text`（新增，body `{"text": "…"}`）
- `GET /status`、`GET /logs`、`POST /logs/clear`、`POST /session/close` 等既有端点不变

### 5.4 Provided — AIDL（`:node` → 主进程）

`IScreenControl`：`tap/swipe/launch/captureFrame/listAppsJson`（既有）+ `key/text/info/log`（新增，`log` 为 oneway）。

### 5.5 Consumed

`VdSession`（launch/captureFrame/tap/swipe/pressKey/text/closeApp/frameSource）→ shell 助手文件 IPC；
`ServerConfig`（vScreenWidth/Height/Dpi）；`TaskStore`（JSON 文件）；`AlarmManager`；`ClipboardManager`；
`PackageManager`；`AccessibilityService`（既有读树）。

## 6. Implementation Strategy

### 6.1 Architecture Pattern

- **协议层薄、能力层厚**：`McpServer` 只做 JSON-RPC 与参数校验，能力全部落到 `ScreenController` / `TaskApi`。
- **命令-回执（文件 IPC）**：`VdSessionImpl.command()` 写 `cmd.txt`（`seq|op|args`）等待 `result.txt`，`seq` 单调递增防残留。
- **跨进程能力代理**：`:node` 通过 AIDL 调用主进程实现，保持 C-1 单一入口。
- **状态旁路**：定时/手动执行共用 `TaskRuntime` + `TaskStore`，watchdog 统一回写。

### 6.2 Key Algorithms

**a) 文本注入（FR-009-002/003）** — `AppScreenController.text`
```
if text 全为可打印 ASCII:  VdSession.text(b64) → 助手 `input -d <d> text '<escaped>'`
else:                      ClipboardManager.setPrimaryClip(text) → pressKey(KEYCODE_PASTE=279)
```
助手侧以 Base64 传参（避免文件 IPC `|` 与换行冲突），shell 单引号转义 `'`。

**b) 6 字段 cron（FR-009-030/031）** — `Cron.next`
- 字段数 ≠5/6 → null；6 字段时第 0 段为秒。
- 从“当前分钟”开始按分钟推进（5 字段从下一分钟），命中分钟内按秒升序找第一个 `t > from`。
- 最多回看 366 天；支持 `*`、数字、`a-b`、`a,b`、步长。

**c) 定时触发（FR-009-032/033）** — `TaskScheduler.onAlarm`
```
enabled? → TaskRuntime.begin(id)（互斥）
        → runCount+1 / lastRunAt=now
        → clearStopFlag → ScriptService.runTask（异常则释放运行态）
        → rescheduleAll()
```
`scheduleAt` 只按当前持久化状态回写 `nextRunAt`（避免旧副本覆盖统计）。

**d) 结果回写（FR-009-032）** — `TaskRuntime` watchdog 每 1s 读取 outcome 文件：
`lastStatus = ok | error:<msg>` → `end(id)`。

**e) 停止（FR-009-021）** — 写停止信号 + 取消闹钟 + 立即 `lastStatus=cancelled` + 释放运行态；
runner 每次 `host.invoke` 前检查信号并抛 `cancelled by user`。

**f) 精确闹钟与恢复（FR-009-035/036）** — 声明 `USE_EXACT_ALARM`；
`canScheduleExactAlarms()` 为真用 `setExactAndAllowWhileIdle`，否则降级 `setAndAllowWhileIdle`；
`BootReceiver`（BOOT_COMPLETED / MY_PACKAGE_REPLACED）与应用启动均 `rescheduleAll()`。

### 6.3 Error Handling

| 场景 | 行为 |
|---|---|
| 无会话 | 屏幕类工具/脚本返回 `ok=false`；`app_close` 仍成功（幂等清状态） |
| 缺少必填参数 | `error: missing required argument …` + 已知键名提示 |
| 任务不存在 | `error: task not found: <id>` |
| 忙（已有任务执行） | `ok=false run rejected (… another task is running)` |
| 后台启前台服务失败 | 捕获异常、写错误日志、释放运行态（不崩 BroadcastReceiver） |
| 脚本异常 | runner 捕获 → `script.done(ok=false)` + outcome 文件 → `lastStatus=error: …` |
| cron 非法 | 解析 null → 不排闹钟（停止路径会清 `nextRunAt`） |

### 6.4 Performance

- `screen.frame` base64 体积大，文档提示避免在循环中频繁调用。
- 日志读取默认 200 条、上限 500；清空为 O(1)。
- 状态查询只读内存/共享首选项，常量级。
- cron 解析按分钟推进（最多 527k 次比较），6 字段仅命中分钟内再检查 60 次。

## 7. Testing Considerations

- 单元测试：`core/script/src/test/.../CronTest.kt`（5/6 字段、跨分钟、严格大于、非法字段数）。
- 真机用例：见 [test-cases.md](./test-cases.md)（计算器/天气/日历/自动化）。
- 关键边界：
  - 中文输入（剪贴板路径）与 ASCII（input text 路径）。
  - 30ms 连点 10 次是否全部命中（实测命中）。
  - 息屏状态下 cron 是否按时触发（需精确闹钟权限）。
  - 停止后再执行（停止信号清理）。
  - 停用任务手动执行（不得排下一次）。
  - 覆盖安装后闹钟恢复（BootReceiver）。

## 8. File Inventory

| 文件 | 作用 | 行数 |
|---|---|---|
| `core/server/.../McpServer.kt` | JSON-RPC、19 工具定义与分发、statusJson | 322 |
| `core/server/.../ManagedServer.kt` | Ktor 路由、鉴权、`/action/text`、MCP 传输、status 汇总 | 473 |
| `core/server/.../TaskApi.kt` | 任务 API 契约（含 getJson/waitRun） | 23 |
| `core/server/.../TaskScriptDocs.kt` | 脚本接口文档（task_docs） | 136 |
| `core/script/.../Cron.kt` | 5/6 字段 cron 解析 | 87 |
| `core/script/.../ScriptTask.kt` | 任务模型与 TaskStore | 93 |
| `core/controller/.../ScreenController.kt` | 操作入口契约（+text） | 59 |
| `core/controller/.../AppScreenController.kt` | 入口实现、剪贴板文本注入、承载状态 | 184 |
| `core/controller/.../VdSessionImpl.kt` | 会话实现、文件 IPC（含 text） | 204 |
| `core/controller/.../KeyCodes.kt` | 键名 → keycode 映射 | 45 |
| `core/shell/.../vd/VdHelper.kt` | shell 助手：tap/swipe/key/text/frame/launch/closeapp | 250 |
| `app/.../script/ScriptService.kt` | `:node` 前台服务、9 个 host 接口、日志回传、runner | 335 |
| `app/.../script/TaskScheduler.kt` | 闹钟调度、onAlarm、统计与停止 | 121 |
| `app/.../script/TaskApiImpl.kt` | TaskApi 实现（run/stop/getJson/waitRun） | 95 |
| `app/.../script/TaskRuntime.kt` | 运行态互斥 + watchdog 结果回写 | 95 |
| `app/.../script/BootReceiver.kt` | 开机/覆盖安装重排 | 27 |
| `app/.../ManagedService.kt` | AIDL 实现（key/text/info/log） | 76 |
| `app/.../ManagedRuntime.kt` | 服务/会话装配、statusProvider、启动重排 | 205 |
| `app/src/main/aidl/.../IScreenControl.aidl` | `:node` ↔ 主进程契约 | 20 |
| `app/src/main/AndroidManifest.xml` | 精确闹钟/开机广播权限与接收器 | — |
| `docs/MCP.md` / `docs/API.md` | 对外契约同步 | — |
