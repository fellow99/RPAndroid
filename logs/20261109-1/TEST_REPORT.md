# TEST_REPORT — 20261109-1（101-main-ui / MCP 工具补全）

> 需求：101-main-ui 追加（MCP 能力验证与补全）｜ 依据：`specs/101-main-ui/mcp-tools-spec.md`
> 结论：**TC-A ~ TC-D 全部通过**；新增工具真机可用；回归通过。
> 环境：Redmi M2105K81C / Android 13（MIUI）/ 虚拟屏 1600×2560 dpi=360 / 覆盖安装（未卸载）。
> 说明：文档与证据不含真实 IP/token/账号（已用占位符；`.pi/` 配置不入库）。

## 1. 结果总览

| 编号 | 用例 | 结果 | 关键证据 |
|---|---|---|---|
| TC-A | 计算器 `1+2*3-4/55` | **PASS** `6.9272727…` | `evidence/40-regression-calc.png`、`evidence/40-calc-expr.png` |
| TC-B | 天气搜索「北京」 | **PASS** 输入框显示「北京」→ 北京市详情 | `evidence/12-weather-typed.png`、`evidence/41-regression-weather-search.png`、`evidence/42-regression-weather-beijing.png` |
| TC-C | 日历倒数日（2027-01-01 / 元旦到了 / 提前3天20:00 / 闹钟开） | **PASS** 日程显示「元旦到了·53天后」，详情「已开启闹钟提醒」 | `evidence/15-cal-title.png`、`evidence/25-date-final.png`、`evidence/27-remind-dialog.png`、`evidence/28-h08.png`、`evidence/32-cal-countdown-detail.png` |
| TC-D | 任务「测试连续按键」（cron 每分钟 00 秒，C 后 30ms×10 点 `1`） | **PASS** `00:42:00.016` 触发，`1111111111`，`runCount 5→6`，可停止 | `evidence/34-task-cron-fired2.png`、`evidence/44-regression-task-cron.png`、`evidence/44-crop.png`、`evidence/37-crop.png`（停止在 6 个 1） |

## 2. 工具级验证（新增/增强）

| 工具 | 用例 | 结果 |
|---|---|---|
| `status` | 读会话/承载/分辨率/运行态 | PASS（与 REST `/status` 同源） |
| `screen_key` | 注入 `back`（code=4）/ `home` 等 | PASS |
| `screen_text` | 中文「北京」「元旦到了」写入输入框 | PASS（剪贴板 + PASTE 路径） |
| `app_close` | 关闭承载 App，会话保持 | PASS（`hostedApp=null`，`sessionActive=true`） |
| `task_get` | 单任务（含统计） | PASS |
| `task_run(waitMs)` | 等待完成并返回 `lastStatus/runCount` | PASS（5.1s 返回，`ok`） |
| `task_stop` | 中止运行 + 取消闹钟 | PASS（`runningTaskId=null`、`nextRunAt=0`、`lastStatus=cancelled`） |
| `logs` | 读/清，脚本日志可见 | PASS（`tag=Script`：run start/run done） |
| REST `/action/text` | ASCII 注入 | PASS `{"ok":true}` |
| 脚本 `screen.info/key/text` | 任务脚本内调用 | PASS（日志 `run start screen=1600x2560`） |

工具数量：`tools/list` = **19**（原 12 + 7 新增；`task_run` 为增强）。

## 3. TC-D 关键时间线（真机）

| 时间 | 事件 | 证据 |
|---|---|---|
| 00:42:00.016 | cron `0 * * * * *` 精确触发（`lastRunAt`） | `task_get` |
| 00:42:00.349 | 脚本 `log: run start screen=1600x2560` | `/logs` |
| 00:42:04.224 | 脚本 `run done` → `lastStatus=ok` | `/logs` |
| 00:42:09 | 计算器显示 `1,111,111,111` | `evidence/44-crop.png` |
| 00:42:09 | `runCount=6`、`nextRunAt=00:43:00` | `task_get` |
| 00:42:09 | `task_stop` + 停用 → `nextRunAt=0` | `task_get` |

停止用例：任务运行 3s 后 `task_stop`，计算器停在 `111,111`（6 次点击），
`lastStatus=cancelled`，`runningTaskId=null`（`evidence/37-crop.png`）。

## 4. 测试过程中发现并修复的缺陷

| 编号 | 现象 | 根因 | 状态 |
|---|---|---|---|
| D1 | `runCount/lastRunAt` 恒为 0 | `scheduleAt` 用旧副本覆盖统计 | 已修（`task_get` 实测增长） |
| D2 | 停用任务手动执行后 `nextRunAt` 被写入 | `runNow` 未判断 `enabled` | 已修 |
| D3 | 停止后下一次执行立即“cancelled” | 停止信号残留 | 已修（执行前清理） |
| D4 | 定时触发时 `runningTaskId/lastStatus` 不可见 | AlarmReceiver 绕过 `TaskRuntime` | 已修（`onAlarm`） |
| D5 | 息屏时 cron 不触发（00:36 未执行） | MIUI 回收 `SCHEDULE_EXACT_ALARM` | 已修（`USE_EXACT_ALARM`，00:42 精确触发） |
| D6 | 覆盖安装/重启后定时任务丢失 | 未重排闹钟 | 已修（BootReceiver + 启动重排） |
| D7 | `task_stop` 后 `lastStatus` 仍为 `ok` | 提前释放运行态，watchdog 拿不到结果 | 已修（写 `cancelled`） |
| D8 | 中文搜索/填标题不可行 | 无文本注入能力 | 已修（`screen_text`） |
| D9 | 无法返回/提交/删除 | 无按键注入能力 | 已修（`screen_key`） |
| D10 | 脚本日志不可见 | `:node` 进程独立日志缓冲 | 已修（Binder 回传） |

明细见 `REVIEW_REPORT.md`。

## 5. 回归

- `./gradlew :core:script:test` → PASS（含 5/6 字段 cron）。
- `./gradlew :app:assembleDebug` → PASS。
- 最终构建复测：TC-A（`evidence/40`）、TC-B（`evidence/41/42`）、TC-C（`evidence/43`）、TC-D（`evidence/44`）全部通过。
- 覆盖安装后 `MY_PACKAGE_REPLACED` 收到并重排：日志 `boot reschedule done`。

## 6. 已知限制 / 未覆盖

- `ui_read` 虚拟屏仍只返回 `displayId=0`（MIUI 限制）→ 元素寻址依赖截图 + 视觉模型；已由 `status` 提供分辨率。
- `screen_text` 非 ASCII 走剪贴板，会覆盖用户剪贴板；后台无法可靠恢复原值（记录在 `REVIEW_REPORT.md` M1）。
- 脚本永久挂起时运行态不会自动超时释放（需 `task_stop` 兜底，M2）。
- 断言/提醒类系统 UI 无法在本环境自动化验证（闹钟是否真正响铃），仅验证配置落库与展示「已开启闹钟提醒」。
- 测试任务 `test-seq-key` 保留在设备上但**已停用**（`nextRunAt=0`），避免每分钟扰动设备；如需复查可手动启用。

## 7. 复现步骤（可重跑）

```bash
# 0) 环境：手机同网段，token 从客户端配置读取（本仓库 .pi/mcp.json，不入库）
# 1) 基线检查
curl -s http://192.168.x.x:8099/health
curl -s -H 'Authorization: Bearer <token>' http://192.168.x.x:8099/status
# 2) 计算器（screen_tap 序列见 logs/20261109-1/tapseq.py）
# 3) 天气：app_launch → screen_tap(城市管理 +) → screen_tap(搜索框) → screen_text("北京") → screen_tap(北京市)
# 4) 日历：app_launch → + → 倒数日 → screen_text("元旦到了") → 时间 2027-01-01
#          → 提醒 提前3天下午8:00 → 闹钟开 → 保存
# 5) 自动化：task_create + task_run(waitMs) / task_get / task_stop / logs
# 脚本与任务定义见 logs/20261109-1/task_script.js、make_task.py
```
