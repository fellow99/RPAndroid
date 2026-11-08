# 101-main-ui 追加：MCP 工具集补全（mcp-tools-spec）

> 需求编号：101-main-ui ｜ 需求名称：应用构建主要UI（本次为 MCP 能力验证与补全）
> 依据：`docs/000-工程规划.md`、`docs/API.md`、`docs/MCP.md`、`docs/MJPEG.md`、`specs/101-main-ui/spec.md`
> 环境：真机 Redmi M2105K81C / Android 13（MIUI），虚拟屏 1600×2560 dpi=360。
> 约定：本文档不落盘任何真实 IP / token / 账号，统一占位符（`192.168.x.x`、`<token>`）。

## 1. 背景与目标

用 4 条贴近真实用途的测试用例驱动 MCP 能力验证，找出"外部智能体想操作手机、但现有 12 个工具做不到"的断点，
然后补齐工具与脚本宿主接口，使 MCP 成为可独立完成"看画面 → 点 → 输入 → 调度 → 观察结果"闭环的接口。

## 2. 测试用例（验收口径）

| 编号 | 场景 | 判定 |
|---|---|---|
| TC-A | 打开「计算器」，计算 `1+2*3-4/55` | 结果 = `6.9272727…`（分数显示可接受） |
| TC-B | 打开「天气」，搜索「北京」的天气 | 进入北京市天气详情（今天/多日预报可见） |
| TC-C | 打开「日历」，到 2027-01-01 添加「倒数日」：内容「元旦到了」，提醒＝提前 3 天 20:00，闹钟＝开启 | 日历中存在该倒数日，提醒/闹钟配置正确 |
| TC-D | 新建自动化任务「测试连续按键」：目标计算器、cron 每分钟 00 秒、脚本先按 C 再以 30ms 间隔点 `1` 十次 | 脚本执行成功，计算器显示 `1111111111`；任务按 cron 触发且可停止 |

## 3. 测试暴露的工具缺口（实测）

| ID | 缺口 | 实测现象 | 影响 |
|---|---|---|---|
| G1 | 无按键注入工具 | 只能点/滑；返回、回车、删除、粘贴不可用 | 无法退出对话框/提交搜索/删除输入 |
| G2 | 无文本输入工具 | `screen_tap` 只能唤起输入框；`input text` 对中文抛 NPE | 中文搜索、标题/名称填写全部不可行 |
| G3 | 无会话/屏幕信息工具 | 分辨率只能靠 REST `/status`；脚本文档写死 720×1560（实际 1600×2560） | 智能体算坐标易错 |
| G4 | 无 `task_stop` | MCP 只能 run，不能停；REST 有 `/tasks/{id}/stop` | 自动化可能停不下来 |
| G5 | `task_run` 不返回执行结果 | 立即返回 `ok=true`，只能另查 `/status`、`/logs` | 无法判定脚本成败 |
| G6 | 无单任务查询 / 日志读取工具 | 只能 `task_list` 全量；日志只能走 REST | MCP 闭环缺观察面 |
| G7 | 脚本日志不可见 | `:node` 进程 `MfaLog` 是独立内存缓冲，主进程 `/logs` 看不到 `tag=Script` | 脚本排障困难 |
| G8 | 运行统计被覆盖 | `runCount`/`lastRunAt` 恒为 0（`scheduleAt` 用旧副本回写） | 统计与 UI 互斥逻辑失真 |
| G9 | 停用任务仍被排闹钟 | 手动 run 停用任务后写入 `nextRunAt` | 停用语义被破坏 |
| G10 | cron 无秒字段 | 5 字段最小粒度 1 分钟，"每次 00 秒"无法精确表达 | TC-D 时间语义不严谨 |
| G11 | 无关闭承载应用工具 | 只能靠 App UI 的「关闭」；REST 有 `/session/close` | 会话复位需人工 |
| G12 | `task_docs` 坐标说明错误 | 文档称默认 720×1560 | 生成脚本坐标错误 |
| G13 | 虚拟屏 UI 树不可读 | `ui_read` 只返回 `displayId=0`（主屏），MIUI 限制（既有已知问题） | 元素寻址只能靠截图+视觉 |

## 4. 新增/增强能力

### 4.1 MCP 工具（12 → 19）

| 工具 | 参数 | 说明 |
|---|---|---|
| `screen_key` | `code?:int`、`name?:string` | 注入按键。`name` 支持 back/home/enter/del/paste/tab/esc/space/up/down/left/right/volume_up/volume_down/power/menu/search |
| `screen_text` | `text*` | 向焦点输入框写入文本；ASCII 走 `input text`，非 ASCII 走"剪贴板 + 粘贴键" |
| `status` | 无 | 会话/承载 App/虚拟屏分辨率/运行中任务/无障碍/鉴权 等状态 |
| `app_close` | 无 | 关闭虚拟屏上当前承载 App（保留会话，回到应用列表） |
| `task_get` | `id*` | 单任务详情（含运行统计） |
| `task_stop` | `id*` | 停止执行 + 取消已排闹钟 |
| `task_run`（增强） | `id*`、`waitMs?` | `waitMs>0` 时等待执行结束，返回 `lastStatus/runCount` |
| `logs` | `level?`、`limit?`、`clear?` | 读/清统一日志（跨进程聚合，含脚本日志） |

### 4.2 JS 脚本宿主接口（6 → 9）

| host API | 说明 |
|---|---|
| `screen.key` | `{code}` 或 `{name}` 注入按键 |
| `screen.text` | `{text}` 输入文本 |
| `screen.info` | 返回 `{ok,w,h,dpi,hosted}`，用于按当前虚拟屏分辨率算坐标 |

### 4.3 行为修复

- cron 支持可选 **6 字段**（`秒 分 时 日 月 周`），5 字段保持兼容；TC-D 使用 `0 * * * * *`。
- `runNow` 不再用旧副本覆盖 `lastRunAt/runCount`；停用任务不排闹钟并清零 `nextRunAt`。
- `:node` 进程日志经 Binder 回传主进程统一日志（`tag=Script`）。
- `task_docs` 改为"坐标以 `screen.info`/`status` 的虚拟屏分辨率为准"，并补全新接口。

## 5. 非功能约束

- 兼容：既有 12 个工具、REST 路径与返回不变；5 字段 cron 行为不变。
- 安全：不新增公网暴露；文本注入不落盘、不写日志明文（日志只记长度）。
- 健壮：无会话时所有屏幕类工具返回 `ok=false`/`error:`，不崩溃。
- 语言：中文文案；文档占位符。

## 6. 验收

- TC-A ~ TC-D 全部通过并留截图/日志证据（`logs/20261109-1/`）。
- `assembleDebug` 通过；`CronTest` 覆盖 5/6 字段。
- 新增工具在 `docs/MCP.md`、`docs/API.md` 有契约说明（无敏感信息）。
