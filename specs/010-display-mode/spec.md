# 010-display-mode Specification — 虚拟屏承载 / 主屏承载（Mode A ⇄ Mode B）

> Module: 010-display-mode
> Status: Proposed（2026-11-09）
> 需求来源：`docs/010-主屏承载可行性方案.md`（设计提案 / 已评估可行，§12 决策记录）
> 关联：`002-controller`（操作入口）、`001-shell`（虚拟屏基座）、`004-server`（对外接口）、`006-config-ui`（配置/呈现）、`009-mcp-tools`（工具/状态）
> 约束沿用总纲：免 root、arm64-v8a、minSdk 24、仅局域网/USB、**敏感信息（测试 URL/用户/密码/凭证）不入档**。

## 1. Module Overview

### 1.1 Purpose

在既有 **虚拟屏承载（Mode A，默认，后台私有虚拟屏）** 基础上，新增 **主屏承载（Mode B）**：
把被托管 App 前置到用户主屏（displayId=0），用 **MediaProjection 捕获主屏帧** + **无障碍读树/手势** 实现
结构化读屏与稳定操作。**双模式并存**，可在受限条件下互切，且**不破坏 Mode A 现有链路**。

### 1.2 Problems Solved

问题 001（`specs/009-mcp-tools` 记录）：私有虚拟屏在部分 ROM（MIUI）上**无障碍读树不可用**
（`getWindows()` 只返回主屏，`ui_read` 读不到被托管 App）。Mode A 只能依赖「截图 + 视觉定位 + 坐标注入」，
无法做元素级寻址。Mode B 把被托管 App 放到主屏，从而解锁：
- **结构化读屏**：a11y `windows()` 直接读到目标 App 控件树（`ui_read` 天然可用，问题 001 消失）。
- **稳定手势**：`dispatchGesture`（不依赖脆弱的 `input -d <虚拟屏>`），`input -d 0` 兜底。
- 面向"用户在场、AI 协同操作前台 App"的场景（代点外卖 / 查天气 / 演示）。

### 1.3 Scope

包含：
- **承载模式抽象**：`DisplayMode`（A 虚拟屏 / B 主屏）+ 全局唯一承载模式状态机 + 授权态（未授权/已授权）。
- **MediaProjection 帧源**：`ProjectionFrameSource`（复用 `FrameHub` 的 Surface+ImageReader 逻辑），MJPEG/WebRTC/本地预览消费方**不变**。
- **授权流程**：`MediaProjectionManager.createScreenCaptureIntent()` ActivityResult 系统弹窗；授权带 `mediaProjection` 前台服务类型（Android 14+ 必须）；拒绝/失败回滚 Mode A。
- **操作分派**：`tap/swipe/back/key/text/launch` 按 mode 分派（B：a11y 手势优先 + `input -d 0` 兜底；A：沿用 `input -d <vd>`）。
- **对外契约**：`status` 增 `mode` 与主屏尺寸；REST/MCP 目标显示参数化；脚本可读 mode。
- **安全**：本应用 UI 首版**不迁移**，用 `FLAG_SECURE` 让其在投影录制里显示黑块；Mode B 仅限局域网/USB + token。

不包含（明确排除，首版）：
- **本应用整屏迁入虚拟屏**（降级为可选优化，仅当"常驻助手 UI 又不能进录制"时才需要）。
- 自动化**运行中禁止切换**以外的复杂并发控制。
- 多显示器 / 多会话并行（仍为单会话单运行态）。
- 无人值守后台跑 Mode B（产品不建议，文档明示）。

## 2. User Stories

- 作为用户，我可以在 `应用呈现` 右上角点「主屏」按钮，将被托管 App 前置到我的主屏（Mode B）。
- 作为外部智能体，我可以在 Mode B 下读取被托管 App 的**结构化控件树**（`ui_read`），做元素级寻址。
- 作为外部智能体，我可以在 Mode B 下用**手势**点击/滑动前台 App，不再依赖脆弱的 `input -d`。
- 作为用户，我可以随时切回 Mode B → Mode A（被托管 App 回虚拟屏、恢复虚拟屏推流）。
- 作为外部智能体，我可以通过 `status.mode` 得知当前承载模式与对应的坐标口径。
- 作为系统，我必须在自动化任务**运行中拒绝模式切换**，避免在错误界面操作。
- 作为用户，我必须明确知悉 Mode B 会**投录整块主屏**，并在授权时看到系统「开始录制」确认框。

## 3. Functional Requirements

### 3.1 承载模式状态机（Display Mode）

- **FR-010-001**：系统 MUST 抽象 `DisplayMode` 枚举：`A`（虚拟屏承载，默认）/ `B`（主屏承载）。
- **FR-010-002**：任意时刻 MUST 只存在一个全局承载模式；`ServerConfig` 持久化 `mode`，首启默认 A。
- **FR-010-003**：切换至 B 前 MUST 处于授权态（未授权/已授权）；仅「主屏」入口可触发授权。
- **FR-010-004**：授权被拒 / 授权失败 / 投影 token 失效 / 投影被系统回收时 MUST 自动回滚 Mode A 并提示，不得停留在半失效状态。
- **FR-010-005**：存在**正在运行自动化任务**（`runningTaskId != null`）时 MUST 拒绝切换模式。

### 3.2 MediaProjection 帧源（ProjectionFrameSource）

- **FR-010-010**：系统 MUST 提供基于 MediaProjection 的帧源 `ProjectionFrameSource`，实现既有 `FrameSource` 接口（`latestPng`/`copyRgba`/`hasFrame`）。
- **FR-010-011**：投影帧源 MUST 经 `mediaProjection.createVirtualDisplay(..., ImageReader.surface, ...)` 捕获**主屏**画面，分辨率与主屏一致。
- **FR-010-012**：MJPEG `GET /stream`、`GET /frame`、WebRTC、本地预览 MUST 通过 `ScreenController.frameSource()` 统一取帧，**Mode B 切换时不改任何消费方代码**（帧源抽象）。
- **FR-010-013**：Android 14+ （API 34+）MUST 在 `foregroundServiceType="mediaProjection"` 的前台服务内创建投影 VirtualDisplay，并声明 `FOREGROUND_SERVICE_MEDIA_PROJECTION` 权限。
- **FR-010-014**：投影停止 / 释放后 MUST 调用 `mediaProjection.stop()` 并清理 token，避免泄漏。

### 3.3 无障碍读屏与手势（a11y）

- **FR-010-020**：a11y 服务配置 MUST 增加 `android:canPerformGestures="true"`。
- **FR-010-021**：Mode B 下 `ui_read`/`/ui` MUST 返回被托管 App（主屏）控件树；Mode A 保持现状（可能为空，因 ROM 限制）。
- **FR-010-022**：Mode B 下 `tap`/`swipe` MUST 优先经 a11y `dispatchGesture` 注入（作用于前台主屏），失败时回退 `input -d 0`。
- **FR-010-023**：Mode B 下按键（back/key）MUST 经 `input -d 0 keyevent` 注入（或 `dispatchGesture` 焦点点击兜底）。
- **FR-010-024**：文本注入行为 MUST 与 mode 无关：ASCII 走 `input text`，非 ASCII 走系统剪贴板 + `KEYCODE_PASTE`，目标显示随 mode 切换。

### 3.4 操作分派与坐标口径

- **FR-010-030**：`ScreenController.tap/swipe/back/pressKey/text/launch` MUST 按当前 mode 分派目标显示（B：主屏；A：私有虚拟屏）。
- **FR-010-031**：`status`/MCP `status` MUST 增加 `mode` 字段与对应分辨率；`screen_capture`/坐标换算按 `status.w/h` 等比换算逻辑不变，天然适配（沿用 009 脚本约定）。
- **FR-010-032**：`launch` 在 Mode B 下 MUST `am start -n <pkg>/<act>`（主屏前置）；Mode A 沿用 `am start --display <vd>`。
- **FR-010-033**：Mode A 会话/虚拟屏在 Mode B 期间 MUST 保持存活（以便快速回 A），投影帧源与私有屏帧源 MUST 严格不串（状态机区分）。

### 3.5 隐私与安全

- **FR-010-040**：进入 Mode B 前 MUST 经系统 MediaProjection 授权弹窗，用户知晓后响应启动（无法静默）。
- **FR-010-041**：本应用自身窗口在 Mode B 下 MUST 以 `FLAG_SECURE` 保护，使投影录制中不泄漏本应用 UI（首版不迁移本应用到虚拟屏）。
- **FR-010-042**：Mode B 投录整块主屏（含通知/输入法/用户自开应用），MUST 在 UI 明示该隐私语义；仅限局域网/USB + token（沿用既有）。
- **FR-010-043**：任何测试 URL、账号、密码、凭证 MUST NOT 落盘文档/提交/日志正文（日志只记长度/状态）。

### 3.6 对外契约（REST / MCP / 通知）

- **FR-010-050**：REST `/status` 与 MCP `status` MUST 返回 `mode`（`"A"|"B"`）与当前生效分辨率。
- **FR-010-051**：系统 MUST 提供「切换模式」入口：REST `POST /mode`（body `{mode:"A"|"B"}`）或 MCP `switch_mode` 工具，两者共用底层同一状态机。
- **FR-010-052**：前台通知 MUST 反映当前承载模式（`虚拟屏承载` / `主屏承载`），并向 UI 同步。
- **FR-010-053**：REST 与 MCP MUST 共用同一 `ScreenController`/模式状态机，行为一致（沿用 C-1）。

## 4. Key Entities

| 实体 | 说明 | 关键属性 |
|---|---|---|
| 承载模式（DisplayMode） | 当前承载语义 | `A`（虚拟屏）/ `B`（主屏）、持久化、全局唯一 |
| 授权态（ProjectionState） | MediaProjection 生命周期 | 未授权 / 已授权、token、投影 VirtualDisplay |
| 投影帧源（ProjectionFrameSource） | Mode B 的帧源 | `FrameSource`（latestPng/copyRgba）、分辨率、是否就绪 |
| 模式状态机（ModeController） | 模式切换与守卫 | 当前 mode、锁定标记（运行任务禁切）、迁移动作 |
| 前台投影服务 | Android 14+ 承载 MediaProjection | `foregroundServiceType=mediaProjection`、token 生命周期 |

## 5. Acceptance Scenarios

### 场景 A：从 Mode A 切换到 Mode B（授权 + 前置 + 结构化读屏）

- Given Mode A 就绪、被托管 App 已在虚拟屏
- When 用户点「主屏」按钮 → 系统弹 MediaProjection 授权框 → 用户允许
- Then 承载 App 前置主屏；`status.mode == "B"`；`ui_read`/`/ui` 返回被托管 App 控件树（元素级可寻址）；截帧分辨率为主屏尺寸

### 场景 B：Mode B 下手势操作

- Given Mode B 就绪
- When 外部智能体调用 `screen_tap`/`swipe`（按主屏坐标）
- Then 前台被托管 App 被操作（`dispatchGesture` 生效），无需 `input -d` 虚拟屏

### 场景 C：切回 Mode A

- Given Mode B 就绪
- When 用户点「切回虚拟屏承载」（UI/通知）
- Then 投影帧源释放、承载 App 回私有虚拟屏、恢复虚拟屏推流、`status.mode == "A"`；虚拟屏内会话保持

### 场景 D：拒绝 / 失效回滚

- Given Mode B 就绪 或 授权被拒
- When 用户拒绝授权，或投影 token 失效 / 服务被杀
- Then 系统提示并自动回滚 Mode A，Mode A 链路完好（推流/操作可用）

### 场景 E：运行任务禁切

- Given 自动化任务正在执行（`runningTaskId != null`）
- When 外部请求切换模式
- Then 拒绝并返回明确错误，承载模式不变

### 场景 F：隐私明示

- Given 准备进入 Mode B
- When 触发授权
- Then UI 明示「将投录整块主屏」，且系统授权弹窗出现；本应用 UI 在录制中显示黑块

## 6. Non-Functional Requirements

- **兼容**：Mode A 全部既有链路（虚拟屏/截帧/注入/REST/MCP/WebRTC）行为保持不变；minSdk 24 下 Mode B 能力按 API 门控（<34 免 mediaProjection 前台服务类型）。
- **健壮**：授权失败、token 失效、服务被杀、运行任务中切换等路径 MUST 显式处理，不得崩溃或悬在半失效态。
- **安全**：不新增公网暴露；Mode B 仅局域网/USB + token；文档不含真实地址与凭据；日志不记正文。
- **性能**：帧源切换为 O(1)，不阻塞消费方；投影捕获帧率与 Mode A 一致。
- **可诊断**：模式切换有开始/结束日志；授权/失效事件可见。
- **国际化**：界面文案中文；工具描述英文（面向通用客户端）。

## 7. Assumptions & Constraints

- Mode B 面向"用户在场"协同操作；无人值守后台自动化仍用 Mode A（文档明示）。
- MediaProjection 无法静默授权，必须用户在场点一次；token 在重启/杀进程/投影停止后失效需重新授权。
- Android 14+ 的 `createVirtualDisplay` 必须在 `mediaProjection` 前台服务内调用（manifest + 权限分支）。
- 主屏被用户操作/来电/键盘会改变内容 → Mode B 下 `ui_read`/视频流反映"当时主屏"，隔离丢失是固有运营风险（文档明示）。
- a11y 手势受前台窗口焦点影响；`dispatchGesture` 失败时以 `input -d 0` 兜底。

## 8. Dependencies

- 上游：托管会话（虚拟屏/注入）、操作入口（ScreenController）、无障碍服务（读树/手势）、对外服务（REST/MCP）。
- 下游：外部 MCP 客户端、播控页面（REST）、应用内呈现 UI。
- 平台：MediaProjection、前台服务（mediaProjection 类型，API 34+）、无障碍手势、`FLAG_SECURE`。

## 9. 已知限制（首版）

- 本应用 UI 不迁入虚拟屏，用 `FLAG_SECURE` 避免泄漏进录制（不做"本应用整屏搬屏"）。
- 主屏承载下隔离丢失：无人值守可能读取/操作错误界面（禁无人值守后台用 B）。
- `dispatchGesture` 依赖 a11y 服务开启且前台窗口在焦点；极端情况下回退 `input -d 0`。
- 旋转/分屏/来电导致主屏内容与分辨率变化时，投影需重建（状态机处理"重新授权/重建投影"为 P2 细化）。
