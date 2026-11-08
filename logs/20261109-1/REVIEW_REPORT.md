# REVIEW_REPORT — 20261109-1（009-mcp-tools / MCP 工具补全）

## 0. 评审方式

- 技能：`requesting-code-review` + `receiving-code-review`（均已安装确认）。
- 本执行环境未提供可派发子代理的工具，按 `specs-based-devflow` 的 **fallback 路径**执行：
  以 reviewer 模板自建评审上下文（实现摘要 + `specs/009-mcp-tools/spec.md` + git range），
  对 diff 做只读走查，输出分级结论；随后按 `receiving-code-review` 的“先核验、再修复”流程处理。
- Git range：`5f05072..fa72049`（spec → 实现），评审后修复见 `code-review-fix` 提交。
- 只读原则：评审阶段只执行 `git diff/show/log`；修复在评审结论确认后进行。

## 1. Strengths

- 缺口→工具映射清晰：13 项实测缺口对应 7 个新 MCP 工具 + 3 个脚本宿主接口，没有加“看起来专业”但无使用方的工具。
- 中文输入选择“剪贴板 + KEYCODE_PASTE”而非新增 IME，避免改动用户默认输入法（无侵入、可回退）。
- cron 6 字段扩展保持 5 字段行为不变，且有单测覆盖（`CronTest` 5 条新用例）。
- 可观测性闭环：`:node` 日志经既有 Binder 回传，未新增进程/通道；`logs` 工具与 REST `/logs` 同源。
- 修复了真机才暴露的三个链路 bug（统计被覆盖、停用仍排闹钟、停止信号污染下一次执行），均有实测证据。

## 2. Issues

### Critical（已修）

| # | 位置 | 问题 | 修复 |
|---|---|---|---|
| C1 | `app/.../TaskScheduler.kt` `scheduleAt` | 用调用方旧副本整条 `upsert`，覆盖刚写入的 `lastRunAt/runCount`；实测 `runCount` 恒为 0 | 只按当前持久化状态回写 `nextRunAt` |
| C2 | `app/.../TaskScheduler.kt` `runNow` | 停用任务手动执行后仍写 `nextRunAt` 并排闹钟，破坏“停用”语义 | `!enabled → next=0`，不排闹钟 |
| C3 | `app/.../ScriptService.kt` / `TaskScheduler` | `task_stop` 在任务未运行时也写停止信号且从未清理 → 下一次执行第一个 `host.invoke` 即被判“已取消”（实测复现） | 新增 `clearStopFlag`，每次执行前清除 |
| C4 | `app/.../AlarmReceiver.kt` | 定时触发不经 `TaskRuntime`，导致运行态不可见、`lastStatus` 不回写、与手动执行互斥失效 | 新增 `TaskScheduler.onAlarm`：记录统计 + `begin` + 执行 + 续排 |
| C5 | `app/src/main/AndroidManifest.xml` | 仅靠 `SCHEDULE_EXACT_ALARM`，MIUI 反复 `exact_alarm_permission_revoked`，息屏时 cron 不触发（实测） | 声明 `USE_EXACT_ALARM`（安装即授予）+ 保留旧版权限 |

### Important（已修）

| # | 位置 | 问题 | 修复 |
|---|---|---|---|
| I1 | `app/.../TaskScheduler.kt` `onAlarm` | 后台 `startForegroundService` 在部分 ROM 可能抛异常；`BroadcastReceiver` 中逸出会崩进程 | `runCatching` + 出错释放运行态 |
| I2 | `app/.../BootReceiver.kt`（新增） | 覆盖安装/重启后 `AlarmManager` 闹钟丢失，定时任务静默失效 | 新增 BootReceiver（BOOT_COMPLETED / MY_PACKAGE_REPLACED）重排；应用启动也重排 |
| I3 | `core/controller/KeyCodes.kt` | `select_all → 29` 实为 `KEYCODE_A`，错误映射 | 删除该映射（避免误用） |
| I4 | `core/script/Cron.kt` | 7 个以上字段被当成“带秒”静默接受 | 只接受 5/6 字段，否则 `null`；加单测 |
| I5 | `core/server/TaskScriptDocs.kt` | 文档写死“默认 720x1560”，与实际 1600×2560 不符，会误导脚本坐标 | 改为以 `screen.info`/`status` 为准，补 3 个新接口与 cron 秒示例 |
| I6 | `app/.../TaskApiImpl.kt` `stop` | 立即 `end` 后 watchdog 拿不到本次结果，`lastStatus` 仍显示上一次 `ok` | 停止时写 `lastStatus=cancelled` |
| I7 | `core/server/McpServer.kt` `statusJson` | `hostedApp` 由控制器与 statusProvider 两次写入，口径可能不一致 | 以 provider 值为准（覆盖同 key），实测一致 |

### Minor（记录/未改）

| # | 位置 | 说明 | 处理 |
|---|---|---|---|
| M1 | `AppScreenController.text`（剪贴板路径） | 非 ASCII 输入会覆盖用户剪贴板；Android 13 后台读取受限，无法可靠恢复原值 | 记录于文档；后续可改为可选 IME（需用户授权） |
| M2 | `TaskRuntime` watchdog | 脚本永久挂起时 `runningTaskId` 不会自动释放（会阻塞后续执行） | 现状有 `task_stop` 兜底；超时上限需产品确认（长脚本可能合法），暂不引入 |
| M3 | `rescheduleAll` | 每次闹钟触发会重排所有 INTERVAL 任务（间隔从当前时刻重新计时） | 与本里程碑语义一致；如需严格周期需单独设计 |
| M4 | `ui_read` | 虚拟屏仍只返回 `displayId=0`（MIUI 限制），元素级寻址不可用 | 既有已知限制；以 `status` 分辨率 + 截图视觉定位替代 |
| M5 | `ScriptService` 启动日志 | 服务未绑定完成时 `Node 启动` 日志回落本进程（`/logs` 不可见） | 仅影响首次启动可见性，不影响执行 |

## 3. 逐条核验（receiving-code-review）

- C1/C2/C4/C5：先复现（`runCount=0`、`nextRunAt` 被停用任务写入、00:36 不触发），修复后实测
  `runCount 5→6`、`nextRunAt=00:43:00`、`00:42:00.016` 精确触发。
- C3：实测“停止→下一次执行被误取消”，修复后同一任务连续两次 `ok`。
- I1：代码核验（`startForegroundService` 异常路径）后加保护；无法在真机构造禁止场景，采用防御性写法。
- I3：对照 `android.view.KeyEvent` 常量（29=KEYCODE_A）确认错误。
- I6：实测 `task_stop` 后 `lastStatus` 由 `ok` 变 `cancelled`。
- 未发现与既有架构冲突的建议；`docs/API.md`、`docs/MCP.md` 已同步（工具数 12→19）。

## 4. Assessment

**Ready to merge?** Yes（Critical/Important 全部关闭；Minor 已记录）。

**Reasoning:** 实现与 `spec.md` 一致，新增能力均有真机证据与单测；剩余风险集中在
ROM 对剪贴板/无障碍/精确闹钟的策略限制，已文档化并有降级路径（ASCII 输入、`screen_key`、停用任务）。
