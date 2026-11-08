# 101-main-ui 追加：MCP 工具集补全（mcp-tools-plan）

## 1. 变更范围

| 模块 | 文件 | 变更 |
|---|---|---|
| core:controller | `ScreenController.kt` | 新增 `text()` |
| core:controller | `AppScreenController.kt` | 实现 `text()`（ASCII→input text；非 ASCII→剪贴板+粘贴） |
| core:controller | `VdSession`/`VdSessionImpl.kt` | 新增 `text()` → 助手 `text` 指令（base64 传参，避免 `|` 冲突） |
| core:shell | `vd/VdHelper.kt` | 新增 `text` 操作（`input -d <d> text`，shell 转义） |
| core:server | `McpServer.kt` | +`screen_key`/`screen_text`/`status`/`app_close`/`task_get`/`task_stop`/`logs`；`task_run` 支持 `waitMs` |
| core:server | `TaskApi.kt` | +`getJson(id)`、`waitRun(id,timeoutMs)` |
| core:server | `TaskScriptDocs.kt` | 文档补全（screen.key/text/info、坐标口径、cron 秒） |
| core:server | `ManagedServer.kt` | +`POST /action/text`；MCP 注入状态提供者 |
| core:script | `Cron.kt` | 可选 6 字段（秒）解析；5 字段兼容 |
| core:script | `CronTest.kt` | 新增 6 字段/兼容用例 |
| app | `TaskScheduler.kt` | 统计覆盖修复；停用不排闹钟；cancel 清 `nextRunAt` |
| app | `ScriptService.kt` | 宿主新增 `screen.key/text/info`；日志回传主进程；完成日志 |
| app | `TaskApiImpl.kt` | 实现 `getJson`/`waitRun` |
| app | `ManagedService.kt` | AIDL 新增 `key/text/info/log` |
| app | `aidl/.../IScreenControl.aidl` | 同上 |
| 文档 | `docs/MCP.md`、`docs/API.md` | 新工具/端点/限制说明（占位符） |

## 2. 关键技术决策

1. **中文输入**：不改用户默认输入法、不新增 IME 服务。优先 `input -d <display> text`（ASCII）；非 ASCII 用
   `ClipboardManager.setPrimaryClip` + `input -d <display> keyevent 279`（PASTE）。失败时返回 `ok=false`，由
   调用方用 `screen_key` 兜底。理由：虚拟屏上的 a11y `ACTION_SET_TEXT` 在 MIUI 不可用（G13）。
2. **按键名映射**：MCP 层把 `name` 映射为 `KeyEvent.KEYCODE_*`，避免调用方记不住数值；`code` 优先。
3. **`waitMs`**：`TaskApiImpl.waitRun` 轮询 `TaskRuntime.runningTaskId`（watchdog 在结果文件出现后释放），
   超时返回 false；随后 `task_get` 返回 `lastStatus`。
4. **跨进程日志**：`:node` 进程经既有 `IScreenControl` Binder 调 `log(tag,msg)` → 主进程 `MfaLog`，无需新通道。
5. **cron 秒**：`Cron.next` 按字段数选择步长（6→逐秒，5→逐分），返回下一次匹配时刻；执行仍由 AlarmManager 精确闹钟承担。
6. **统计一致性**：`scheduleAt` 只按当前持久化状态回写 `nextRunAt`，不覆盖运行统计；停用任务不排闹钟。

## 3. 实现顺序

1. core：Cron/秒 + CronTest；controller text/key；helper text。
2. app：AIDL/ManagedService/ScriptService 宿主接口 + 日志回传。
3. core/server：MCP 工具 + TaskApi 扩展 + 状态注入 + REST `/action/text`。
4. 文档与脚本文档更新。
5. 构建 → `adb install -r`（不卸载）→ 4 条 TC 真机复测。
6. 代码走查（requesting/receiving-code-review）→ 修复 → 回归。

## 4. 风险与对策

- **剪贴板写入被 ROM 限制/弹提示**：优先 ASCII 路径；中文以 TC-B/TC-C 实测验证，失败则记录并改用 `screen_key` 逐键兜底（仅 ASCII）。
- **30ms 连点**：实测 10 次全部命中（TC-D），保持；脚本仍建议 ≥30ms。
- **安装方式**：`adb install -r` 覆盖安装，**不卸载**（用户明确要求），保留数据与 token。
- **重复 cron 触发**：任务验证后置为停用，避免每分钟扰动设备；报告注明原因。
