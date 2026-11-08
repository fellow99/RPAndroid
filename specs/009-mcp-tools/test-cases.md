# 009-mcp-tools 测试用例（test-cases.md）

> 对应 [spec.md](./spec.md) 验收场景 A–E。真机验证（Redmi M2105K81C / Android 13 / MIUI，虚拟屏 1600×2560 dpi=360）。
> 记录格式：`[PASS] TC-009-xx 描述` / `[FAIL] …（现象/日志）`。
> 安全：文档与实际命令均使用占位地址与 `<token>`；真实端点来自本地未入库配置。

## 前置条件

- App 已运行且会话就绪：`status.sessionActive = true`。
- MCP 可访问：`tools/list` 返回 19 个工具。
- 虚拟屏分辨率以 `status.vScreenWidth/vScreenHeight` 为准（本次 1600×2560）。

---

## TC-009-01 计算器求值（输入 + 视觉校验）

| 项 | 内容 |
|---|---|
| 目标 | 验证点按序列与画面反馈，得到正确结果 |
| 前置 | 会话就绪；已安装「计算器」 |

**步骤**
1. `app_launch {packageName: "com.miui.calculator"}`
2. `screen_capture` 确认计算器界面（记录键盘布局坐标）
3. 依次 `screen_tap`：`C(797,1650)` → `1(797,2214)` → `+(1418,2214)` → `2(1004,2214)` → `×(1418,1838)`
   → `3(1211,2214)` → `−(1418,2026)` → `4(797,2026)` → `÷(1418,1650)` → `5(1004,2026)` → `5(1004,2026)` → `=(1418,2400)`
4. `screen_capture` 读取结果

**预期**：表达式为 `1+2×3−4÷55`，结果 `6.9272727…`。

**实测**：`[PASS]`，结果 `6.9272727272727272`。
证据：`logs/20261109-1/evidence/40-regression-calc.png`、`40-calc-expr.png`。

---

## TC-009-02 天气搜索「北京」（中文输入）

| 项 | 内容 |
|---|---|
| 目标 | 验证 `screen_text` 中文写入与搜索交互 |
| 前置 | 会话就绪；已安装「天气」 |

**步骤**
1. `app_launch {packageName: "com.miui.weather2"}`
2. `screen_tap {x:1412,y:107}` 打开「城市管理」
3. `screen_tap {x:803,y:785}` 聚焦搜索框
4. `screen_text {text:"北京"}`
5. `screen_capture` 确认输入框与候选结果
6. `screen_tap {x:397,y:902}` 打开「北京市 - 中国」
7. `screen_capture` 确认详情

**预期**：搜索框出现「北京」；候选含「北京市 - 中国」；进入后显示北京市多日预报。

**实测**：`[PASS]`，搜索框显示「北京」，进入北京市详情（今天/明天/周三…）。
证据：`logs/20261109-1/evidence/12-weather-typed.png`、`41-regression-weather-search.png`、`42-regression-weather-beijing.png`、`42-crop.png`。

---

## TC-009-03 日历倒数日（中文 + 日期 + 提醒/闹钟）

| 项 | 内容 |
|---|---|
| 目标 | 验证多步表单：中文标题、跨年日期滚轮、提醒时间、开关 |
| 前置 | 会话就绪；已安装「日历」 |

**步骤**
1. `app_launch {packageName: "com.android.calendar"}`
2. `screen_tap {x:1231,y:143}` → 新建；`screen_tap {x:1219,y:806}` 切到「倒数日」
3. `screen_tap {x:803,y:1011}` 聚焦标题 → `screen_text {text:"元旦到了"}`
4. `screen_tap {x:1100,y:1129}` 打开「选择日期」
5. 滚轮设 `2027 / 01 / 01`：年列 `(583,1405)`；月列 `(844,1405)`×2；日列上方行 `(1074,1131)`×8
   （每次操作间隔 ≥1.6s，避免滚轮动画被当成“选中并确认”）
6. `screen_tap {x:972,y:1547}` 确定
7. `screen_tap {x:1100,y:1457}` 打开「提醒时间」→ 期间选「下午」（`(635,1361)`）、小时设 `8`（`(827,1089)`×2）
   → `screen_tap {x:969,y:1512}` 确定
8. `screen_tap {x:1257,y:1550}` 打开「闹钟」
9. `screen_tap {x:1289,y:675}` 保存
10. `screen_capture` 确认日程与详情

**预期**：日程显示「元旦到了 · 53 天后 / 倒数日 | 2027年1月1日」；详情显示「已开启闹钟提醒」；
表单显示「提前3天,下午8:00」。

**实测**：`[PASS]`。
证据：`logs/20261109-1/evidence/15-cal-title.png`、`25-title.png`、`27-remind-dialog.png`、`28-h08.png`、
`32-cal-countdown-detail.png`、`43-crop.png`。

> 备注：滚轮控件在快速连续点击时会把“点相邻项”误判为“确认当前项”并关闭弹窗；用例要求每次操作后等待动画结束。

---

## TC-009-04 自动化任务「测试连续按键」（cron + 脚本 + 停止）

| 项 | 内容 |
|---|---|
| 目标 | 验证 cron 秒级语义、脚本输入能力（30ms 连点）、运行统计/运行态、停止与恢复 |
| 前置 | 会话就绪；任务存在（见下） |

**任务定义**
```json
{
  "id": "test-seq-key",
  "name": "测试连续按键",
  "packageName": "com.miui.calculator",
  "kind": "CRON",
  "cron": "0 * * * * *",
  "enabled": true,
  "script": "screen.info → log → launch 计算器 → 等待 1.5s → tap C → 循环 10 次：tap 1 + 等待 30ms → log done"
}
```
脚本原文：`logs/20261109-1/task_script.js`（坐标按 `screen.info` 分辨率等比换算）。

**步骤与预期**

| 步骤 | 操作 | 预期 |
|---|---|---|
| 4-1 | `task_create` / `task_modify` 写入上述任务并启用 | `task_get.nextRunAt` = 下一个整分 `:00` |
| 4-2 | 等待到 `:00` 后 `task_get` | `lastRunAt` = `:00.0xx`；`runCount` +1；`nextRunAt` = 下一分钟 |
| 4-3 | `status` | 执行期间 `runningTaskId = test-seq-key` |
| 4-4 | `logs`（tag=Script） | 出现 `run start screen=1600x2560` 与 `run done` |
| 4-5 | `screen_capture` | 计算器显示 `1,111,111,111`（10 个 1） |
| 4-6 | `task_run {waitMs: 30000}` | 返回 `finished=true`，`lastStatus=ok`，`runCount` +1 |
| 4-7 | 运行中 `task_stop` | `runningTaskId=null`；`lastStatus=cancelled`；`nextRunAt=0`；画面停在部分输入（6 个 1） |
| 4-8 | 停止后再次 `task_run` | 正常执行（上次停止信号不影响本次），`lastStatus=ok` |
| 4-9 | `task_modify {enabled:false}` | `nextRunAt=0`，不再触发 |
| 4-10 | 覆盖安装后查看 `logs` | `boot reschedule done action=…MY_PACKAGE_REPLACED` |

**实测**：`[PASS]`
- 00:42:00.016 精确触发；`runCount 5→6`；`nextRunAt=00:43:00`。
- 显示 `1,111,111,111`；停止后停在 `111,111` 且 `lastStatus=cancelled`。
- 覆盖安装后收到 `MY_PACKAGE_REPLACED` 并重排。

证据：`logs/20261109-1/evidence/34-task-cron-fired2.png`、`37-crop.png`、`44-regression-task-cron.png`、`44-crop.png`。

---

## TC-009-05 工具冒烟（辅助回归）

| 工具 | 操作 | 预期 | 实测 |
|---|---|---|---|
| `status` | 直接调用 | 返回 sessionActive/hosted/vScreen/runningTaskId/accessibility | `[PASS]` |
| `screen_key` | `{name:"back"}` | `ok=true code=4` | `[PASS]` |
| `app_close` | 承载应用后调用 | `ok=true`；`hostedApp=null`；会话保持 | `[PASS]` |
| `task_get` | 查询任务 | 含 `runCount/lastStatus/nextRunAt` | `[PASS]` |
| `logs` | 读/清 | 返回 JSON 数组；清空后为空 | `[PASS]` |
| REST `/action/text` | `{text:"abc"}` | `{"ok":true}` | `[PASS]` |
| 脚本 `screen.info` | 任务脚本内调用 | `ok=true` + 分辨率 | `[PASS]` |

## 回归清单

- [x] TC-009-01 ~ TC-009-04 在最终构建上复测通过。
- [x] `CronTest`（5/6 字段、跨分钟、非法字段数）通过。
- [x] `assembleDebug` 通过。
- [x] 既有工具（`screen_capture`/`ui_read`/`app_list`/`task_list` 等）行为不变。

## 未覆盖/环境限制

- 闹钟是否真正响铃属于系统行为，未做自动化验证；仅验证配置落库与界面「已开启闹钟提醒」。
- `ui_read` 在虚拟屏只返回主屏（ROM 限制）。
- 剪贴板注入会覆盖用户剪贴板（记录于评审报告）。
