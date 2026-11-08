# 009-mcp-tools 实现任务记录（tasks.md，as-built）

> 依赖顺序执行；提交前缀遵循 C-10（Conventional Commits）。状态为回溯记录。

## T1 核心能力：按键 / 文本 / 秒级 cron

- [x] `Cron.next` 支持 5/6 字段（`秒 分 时 日 月 周`），5 字段兼容；`CronTest` 增用例。
- [x] `ScreenController.text()`；`AppScreenController`：ASCII → 助手 `input text`，非 ASCII → 剪贴板 + `KEYCODE_PASTE`。
- [x] `VdSession.text` + `VdSessionImpl`（Base64 传参）+ `VdHelper` `text` 指令（shell 转义）。
- [x] 编译通过。

## T2 脚本宿主接口与跨进程日志

- [x] `IScreenControl` 增 `key/text/info/log`；`ManagedService` 实现。
- [x] `ScriptService` 注册 `screen.key` / `screen.text` / `screen.info`；`log` 与启动/完成日志经 Binder 回传主进程。
- [x] 编译通过。

## T3 MCP 工具补全

- [x] `McpServer` 注入状态提供者；新增 `screen_key` / `screen_text` / `status` / `app_close` /
      `task_get` / `task_stop` / `logs`；`task_run` 支持 `waitMs`。
- [x] `TaskApi` 增 `getJson` / `waitRun`；`TaskApiImpl` 实现。
- [x] `ManagedServer` 增 `POST /action/text`。
- [x] 编译通过。

## T4 调度与文档正确性

- [x] `TaskScheduler`：`scheduleAt` 不覆盖统计；停用不排闹钟；`cancel` 清 `nextRunAt`。
- [x] `TaskScheduler.onAlarm`：定时触发计入统计、占用运行态、回写结果。
- [x] `ScriptService.clearStopFlag`：执行前清理停止信号。
- [x] `task_stop` 写 `lastStatus=cancelled`。
- [x] 精确闹钟权限 `USE_EXACT_ALARM` + `BootReceiver`（重启/覆盖安装重排）。
- [x] `TaskScriptDocs` 坐标口径改为 `screen.info`/`status`，补 3 个接口与 cron 秒。
- [x] `docs/MCP.md`、`docs/API.md` 同步（占位符，无敏感信息）。

## T5 真机部署与四用例

- [x] `adb install -r`（覆盖安装，**未卸载**）后自动恢复会话。
- [x] TC-009-01 计算器 `1+2*3-4/55` = `6.9272727…`。
- [x] TC-009-02 天气 `screen_text` 输入「北京」并进入详情。
- [x] TC-009-03 日历倒数日（2027-01-01 / 元旦到了 / 提前3天20:00 / 闹钟开）。
- [x] TC-009-04 cron `0 * * * * *` 在 `:00` 触发，`1111111111`，可停止。
- [x] 证据落 `logs/20261109-1/`。

## T6 代码走查与回归

- [x] requesting-code-review 走查（fallback）→ `logs/20261109-1/REVIEW_REPORT.md`。
- [x] receiving-code-review 逐条核验，修复 Critical/Important（5 Critical + 7 Important）。
- [x] 回归 TC-009-01~04 + `CronTest` + `assembleDebug` → `logs/20261109-1/TEST_REPORT.md`。

## 提交记录（对应 git）

| 提交 | 说明 |
|---|---|
| `5f05072 docs(spec): add MCP tool-gap spec, plan and tasks`（后被本模块文档取代） | 初始缺口规格 |
| `fa72049 feat(mcp): complete toolset for screen input, task control and observability` | T1–T4 实现 |
| `b1f1bbc fix(mcp): address code-review findings in key map, cron fields and alarm path` | 评审修复 |
| `90fa350 test(mcp): record real-device results …` | T5/T6 证据与报告 |

## 遗留（非本里程碑）

- 剪贴板注入会覆盖用户剪贴板；可考虑可选 IME。
- 脚本挂起无自动超时（依赖 `task_stop`）。
- 虚拟屏 UI 树不可读（ROM 限制）。
