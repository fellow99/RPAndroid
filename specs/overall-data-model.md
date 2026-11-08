# overall-data-model.md — 数据模型

> 状态：000-scaffold（目标数据模型，标记为设计；随实现校准）。

## 1. 核心实体

### ManagedSession（被托管会话）
| 属性 | 类型 | 说明 |
|------|------|------|
| id | String | 会话唯一标识 |
| packageName | String | 目标 App 包名 |
| displayId | Int | 关联虚拟屏 id（shell 层） |
| state | enum(SessionState) | Idle / Starting / Running / Paused / Terminated |
| presentation | enum(PresentationMode) | Floating / Regular / Headless |
| startedAt | Long | 启动时间戳 |

状态机：
```
Idle → Starting → Running ⇄ Paused → Terminated
```

### Task（自动化服务任务）
| 属性 | 类型 | 说明 |
|------|------|------|
| id | String | 任务 id |
| packageName | String | 目标 App（可为空=当前会话） |
| script | String | JS 自动化脚本内容或引用 |
| enabled | Boolean | 是否启用 |
| schedule | Schedule | 执行方式 |
| lastRunAt / nextRunAt | Long? | 执行时间 |
| runCount | Int | 已执行次数 |

### Schedule（调度）
| kind | enum(ScheduleKind) | Immediate / Interval / Cron |
| intervalMs | Long? | 间隔（Interval 用） |
| cron | String? | cron 表达式（Cron 用） |
| maxRuns | Int? | 最多次数（可选） |

### ScreenElement（界面元素，读UI结果）
| 属性 | 类型 | 说明 |
|------|------|------|
| id | String | 元素编号（无障碍） |
| bounds | Rect | 屏幕坐标 |
| text | String? | 文本 |
| className | String? | 控件类型 |
| clickable / scrollable | Boolean | 能力标记 |

### ActionResult（操作结果）
| 属性 | 类型 | 说明 |
|------|------|------|
| ok | Boolean | 是否成功 |
| code | String | 结果码（如 done / blocked / not_clickable） |
| timestamp | Long | 时间 |
| detail | String? | 说明 |

### ScriptRunRecord（自动化脚本运行记录）
| 属性 | 类型 | 说明 |
|------|------|------|
| taskId | String | 所属任务 |
| startedAt / finishedAt | Long? | 起止 |
| status | enum(RunStatus) | running / ok / failed / stopped |
| log | String | 执行日志 |
| resultJson | String? | 结果 |

## 2. 前端/配置状态形态
- Config（应用配置）：默认呈现模式、server 端口、token 开关、adb reverse 提示等。
- ServerStatus：监听地址、运行态、活动会话数、最近错误。

## 3. 校验规则（设计）
- cron 表达式须合法（采用既定方言，见模块 plan 细化）。
- 任务启用且调度有效才允许执行。
- 操作坐标须落在会话可视范围；越界返回 `out_of_bounds`。

## 4. 关系
- ManagedSession 1—N ScreenElement（某次读取快照）。
- Task 1—N ScriptRunRecord（执行历史）。
- ScreenController 串行处理对会话的所有写操作（单一入口）。
