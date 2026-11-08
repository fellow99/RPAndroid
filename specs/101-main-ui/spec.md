# 101-main-ui Specification — 应用构建主要UI

> Module: app（仅在 `app` 模块内实现；core 仅做最小必要支撑：虚拟屏按键注入、分辨率配置）
> Status: 设计（2026-11-08，specs-based-devflow）
> 参考：Mika 卡片/列表式 UE（只借鉴组织方式，不引入聊天/智能体）；不落盘任何真实 IP/token。

## 1. Module Overview

### 1.1 Purpose
把当前"技术预研"用的编号步骤式配置页（`MainActivity` 纯 View）重做为**可直接交付终端用户的正式主界面**：
底部三栏目导航（应用 / 自动化 / 设置），每栏目按运行时状态自适应展示；支持三种呈现模式（嵌入、全屏、浮窗）与关闭；自动化任务支持互斥的执行/停止与表单编辑；系统返回键按"呈现焦点"规则转发给被托管 App。

### 1.2 Problems Solved
- 预研 UI 是给开发者看的过程引导，不符合终端用户心智。
- 呈现/自动化/设置没有统一导航与状态自适应。
- 系统返回键当前不会透传到被托管 App，用户无法"返回"操作被托管界面。

### 1.3 Scope
- 底部三栏目导航 + 每栏目状态自适应。
- `应用`：图标网格选 App；嵌入/全屏/浮窗三呈现 + 关闭；顶栏状态。
- `自动化`：任务列表、互斥执行/停止、编辑表单（App/脚本/启用/时间）。
- `设置`：adbd 端口、安全 token、虚拟屏分辨率、无障碍开关。
- 系统返回键转发规则。
- 最小 core 支撑：虚拟屏 back 注入、分辨率配置持久化。

### 1.4 不做（明确排除）
- 聊天 UI / 智能体 / AI 主循环（参照 000-工程规划"明确不做"）。
- 呈现模式之外的二期能力（WebRTC 等）。
- 不落盘敏感信息（真实 IP / token 一律占位符）。

## 2. User Stories / 功能点

- 作为用户，我可以从底部导航切换「应用 / 自动化 / 设置」。
- 作为应用栏目，我可以按 4 态（未配端口 / 未建会话 / 未承载 / 已承载）自适应展示。
- 作为用户，我可以从图标网格选择要承载的 App，并在 嵌入/全屏/浮窗 间切换、或关闭呈现。
- 作为用户，我可以在「自动化」管理多个任务：执行（互斥）/ 停止 / 编辑/启用。
- 作为用户，我可以在「设置」配置端口、token、虚拟屏分辨率、无障碍。
- 作为用户，在应用/全屏/浮窗场景下按系统返回，操作被托管 App（返回），而不是退出本 App。

## 3. Functional Requirements

### FR-101-1 底部导航
- 三栏目，固定底部：应用、自动化、设置。
- 当前栏目高亮；切换只换内容区，不重建整体。

### FR-101-2 应用栏目状态机（顺序判定，高优先级在前）
| 状态 | 判定 | 展示 |
|---|---|---|
| S1 未配置端口 | `ServerConfig.connectPort == 0` | 提示"请先到设置配置 adbd 连接端口"，并提供「去设置」；**其它 UI 不显示** |
| S2 未建立会话 | `!controller.isSessionActive()` | 居中「连接并建立会话」按钮；**其它 UI 不显示**；点按→`ManagedRuntime.startSession(connectPort)` |
| S3 未承载应用 | 会话可用且 `hostedApp == null` | 已安装可启动 App 的**图标网格**（应用图标+名称） |
| S4 已承载应用 | `hostedApp != null` | 呈现区域（右上角：嵌入/全屏/浮窗/关闭 按钮）+ 顶栏当前 App 名 |

- 会话建立失败/进行中：顶栏或按钮反馈状态。

### FR-101-3 呈现模式与切换
- **嵌入**：在应用栏目主区域渲染虚拟屏画帧（等比、letterbox），触摸坐标经 `TouchMapper` 换算回写注入。
- **全屏**：进入 `ScreenActivity` 全屏承载同一会话画帧，触摸回写；顶部提供 嵌入/浮窗/关闭 悬浮控制。
- **浮窗**：`FloatingService`（已有）显示同一会话画帧；可拖动、点击回写、✕ 关闭。
- 三模式共用同一会话与控制器，切换不中断会话；切模式时记录 `hostedApp`，任何模式回到应用栏目都保持 S4。

### FR-101-4 关闭呈现
- S4 右上角「关闭」：停止当前呈现（全屏→finish、浮窗→停服务、嵌入→回到 S3 图标网格），置 `hostedApp=null`。
- 关闭仅是停止呈现；被托管 App 可留在虚拟屏（不强制卸载）。

### FR-101-5 自动化栏目状态机
- 复用应用栏目前两态：S1 未配置端口→提示；S2 未建会话→连接按钮；否则展示任务列表。**其它 UI 按状态隐藏。**

### FR-101-6 自动化任务列表
- 每项显示：目标 App 图标+名称、执行时间设置（立即/间隔毫秒/cron 表达式）、启用开关。
- **启用项**右侧显示：`执行状态及按钮`（播放/停止图标）+ 编辑按钮；停用项仅显示编辑按钮与启用开关。
- 互斥规则：
  - 某一项「执行」后，该项显示「停止」，**其它启用项的播放/停止按钮隐藏**。
  - 「停止」后恢复，其它项的按钮重新显示。
  - 全局同时只能有一个正在执行的自动化（由 UI 运行时运行状态 + 调度器队列保证）。
- 「执行」→ 调用自动化服务调度立即执行当前任务（`TaskScheduler.runNow`）。
- 「停止」→ 请求自动化服务中止当前执行 + 取消该任务已排闹钟，并清空运行态。

### FR-101-7 自动化编辑表单
- 点「编辑」弹出表单，字段（对应 `ScriptTask`）：
  - 名称(name)；目标 App(从已安装应用选择，packageName)；是否启用(enabled)；
  - 执行方式(kind)：立即 / 间隔 / cron；
  - 间隔毫秒(intervalMs，当 interval)；cron 表达式(cron，当 cron)；
  - **执行 JS 脚本**(script，多行编辑，node-runtime 执行)。
- 保存 → `TaskStore.upsert` + `TaskScheduler.rescheduleAll()`。
- 表单校验：非空名称；interval>0；cron 合法（`Cron.next` 可解析）。

### FR-101-8 设置栏目
- adbd 连接端口：编辑+保存，写入 `ServerConfig.connectPort`。
- 安全 token：编辑+保存，写入 `ServerConfig.token`（服务端鉴权用，重启生效语义同现状）。
- 虚拟屏分辨率（宽×高，可选 dpi）：默认与当前屏幕分辨率一致；保存写入配置，**下次建会话生效**；变更敏感→提示需重建会话。
- 启用无障碍服务：跳转系统无障碍设置（现状逻辑），显示当前是否已启用（`MfaAccessibilityService.isEnabled()`）。
- 运行时即时保存，无"保存"整页动作（除端口/token/分辩率个别字段）。

### FR-101-9 系统返回键转发
- 当前焦点为**全屏**（ScreenActivity）或**浮窗**（FloatingService 活动）：`返回`注入到被托管 App（虚拟屏 back）。
- 焦点为本 App 且栏目在**应用**栏目（S4）：`返回`注入到被托管 App。
- 其余（设置/自动化栏目，或应用栏目但非 S4）：常规处理（退出/回上一页）。
- 实现：`MainActivity` 与 `ScreenActivity` 拦截返回；`FloatingService` 提供活动标记，主界面在浮窗激活时同样转发。

### FR-101-10 状态可观察（辅助）
- 应用栏目顶栏/设置页可反映：会话是否就绪、已承载 App、自动化服务状态（Node 是否拉起）。
- 使用既有 `ManagedRuntime` / `ManagedBridge` / `TaskStore` 数据源，不新增后端接口。

## 4. Key Entities / 数据契约

- `ServerConfig.connectPort` —— 是否已配置 adbd 端口（0=未配置）→ S1 判定。
- `controller.isSessionActive()` —— → S2 判定（会话可用）。
- `hostedApp: AppInfo?` —— 本 UI 持有的"当前承载应用"运行时状态（跨 Activity 共享，`PresentationHub` 单例）。
- `ScriptTask`（既有 `core:script`）—— 自动化任务模型，复用字段与 `TaskStore`。
- 运行态 `runningTaskId: String?` —— 全局唯一正在执行的任务 id（UI 持有时钟 + 调度器队列串行）。
- `ServerConfig` 新增：`vScreenWidth / vScreenHeight / vScreenDpi`（虚拟屏分辨率）。

## 5. Acceptance Scenarios

### Scenario 1：应用栏目四态
- Given 首次进入且未配置端口
- When 打开 `应用`
- Then 显示"请配置端口 + 去设置"，无其它 UI
- When 到设置填端口并存，回 `应用`
- Then 显示「连接并建立会话」
- When 点击连接成功
- Then 显示 App 图标网格；点某 App → S4 呈现区域显示，右上角四个按钮可用，全屏/浮窗切换正常，关闭回网格

### Scenario 2：自动化互斥执行/停止
- Given 会话就绪且已有 2 个启用任务 A、B
- When 点 A 执行
- Then A 行显示停止，B 行播放/停止按钮隐藏；A 的 `runCount` 增加
- When 点 A 停止
- Then A、B 行按钮恢复；仅存在一个运行态

### Scenario 3：编辑表单
- Given 会话就绪
- When 点某任务「编辑」，改 cron 为合法表达式 + 关闭启用，保存
- Then 列表项显示新时间设置与停用态，调度器取消原闹钟；非法 cron 时提示不保存

### Scenario 4：返回转发
- Given 已承载 App，栏目在应用
- When 点系统返回
- Then 被托管 App 收到返回（画帧变化），本 App 不退出
- When 全屏呈现中点返回
- Then 同样转发给被托管 App
- When 栏目在设置，无浮窗/全屏
- Then 常规返回处理

### Scenario 5：设置栏目
- Given 打开设置
- Then 可编辑端口/token；分辨率默认等于当前屏；无障碍显示启用状态并可跳转

## 6. Non-Functional Requirements
- 构建稳定：沿用 AGP9 内置 Kotlin + Compose（spike 已验证编译通过）；不回归既有 REST/MCP 能力。
- UI 反馈：连接/执行/保存等耗时动作有加载或状态提示。
- 健壮：会话未就绪的任何操作不崩溃（控制器返回 false/null 时给出提示）。
- 语言：界面文案中文；代码注释中文风格与现状一致。
- 不落盘敏感信息：新文档/日志一律用占位 IP（`192.168.x.x`）与 `<token>`。

## 7. Assumptions & Constraints
- Compose(Material3) 为本 UI 里程碑目标栈，且 spike 编译通过；若后续回归，回退纯 View（不作为本需求目标）。
- 「停止」为尽力而为：通过调度器取消闹钟 + 运行时写停止信号让 `:node` runner 放弃当前脚本；不保证能硬中断正在执行的 JS 长任务。
- 分辨率生效需重建会话（修改后下次 `连接并建立会话` 生效）。
- 沿用 `ManagedBridge`/`ManagedRuntime`/`screen` 控制器进程内调用，无跨进程新增契约（除 runner 停止信号用文件，与现有文件 IPC 风格一致）。

## 8. Dependencies
- `libs.compose.bom/ui/material3/activity-compose` → 本需求在 `app` 首次启用 Compose。
- `core:controller`：`ScreenController.back()`（新增，最小支撑）。
- `core:server`：`ServerConfig`（新增分辨率字段）。
- `core:script`：`TaskStore`/`ScriptTask`/`TaskScheduler`（复用）。
- 真机：联调验证（仅本机使用，不留存真实地址）。
