# 101-main-ui 追加：MCP 工具集补全（mcp-tools-tasks）

> 依赖顺序执行；每个回合计一个 Conventional Commit（前缀按 git-commit 技能）。

## T1 核心能力：按键/文本/秒级 cron
- [x] `Cron.next` 支持可选 6 字段（秒 分 时 日 月 周），5 字段兼容；`CronTest` 增用例。
- [x] `ScreenController.text(text)`；`AppScreenController`：ASCII→`VdSession.text`，非 ASCII→剪贴板+PASTE 键。
- [x] `VdSession.text` + `VdSessionImpl`（base64 传参）+ `VdHelper` `text` 指令（shell 转义）。
- [x] `./gradlew :app:assembleDebug` 通过。

## T2 脚本宿主接口与日志
- [x] `IScreenControl` 增 `key/text/info/log`；`ManagedService` 实现。
- [x] `ScriptService.registerHost` 增 `screen.key`/`screen.text`/`screen.info`；`log` 与启动/完成日志经 Binder 回传主进程。
- [x] 编译通过。

## T3 MCP 工具补全
- [x] `McpServer` 注入状态提供者；新增 `screen_key`/`screen_text`/`status`/`app_close`/`task_get`/`task_stop`/`logs`；`task_run` 支持 `waitMs`。
- [x] `TaskApi` 增 `getJson`/`waitRun`；`TaskApiImpl` 实现。
- [x] `ManagedServer` 增 `POST /action/text`（REST 与 MCP 对齐）。
- [x] 编译通过。

## T4 调度与文档正确性
- [x] `TaskScheduler`：`scheduleAt` 不覆盖 `lastRunAt/runCount`；停用任务不排闹钟；`cancel` 清 `nextRunAt`。
- [x] `TaskScriptDocs`：坐标口径改为 `screen.info`/`status`；补 `screen.key/text/info` 与 cron 秒。
- [x] `docs/MCP.md`、`docs/API.md` 更新（占位符，无敏感信息）。
- [x] 编译通过。

## T5 真机部署与四用例复测
- [x] `adb install -r`（覆盖安装，**不卸载**）后自动恢复会话。
- [x] TC-A 计算器 `1+2*3-4/55` = `6.9272727…`（用 `screen_text` 或点按）。
- [x] TC-B 天气：`screen_text` 输入「北京」并搜索，进入北京市详情。
- [x] TC-C 日历：2027-01-01 倒数日「元旦到了」，提前 3 天 20:00 提醒，闹钟开启。
- [x] TC-D 任务「测试连续按键」：cron `0 * * * * *`，先 C 再 30ms×10 点 `1`，显示 `1111111111`；`task_stop` 可停。
- [x] 证据截图/日志落到 `logs/20261109-1/`。

## T6 代码走查与回归
- [x] requesting-code-review 走查 → REVIEW_REPORT.md。
- [x] receiving-code-review 逐条核验，修 Critical/Important。
- [x] 回归 TC-A~TC-D + `CronTest`；`TEST_REPORT.md` 定稿。
