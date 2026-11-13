# 012-ui-read Specification — 界面读取能力（dumpUi / readUi / queryUi / getUi）

> Module: 012-ui-read
> Status: Proposed（2026-11-11）
> 需求来源：本会话对既有「界面读取」能力（`GET /ui`、MCP `ui_read`）的考察与增强设计。
> 关联：`010-display-mode`（Mode B 主屏承载 → 结构化读屏）、`009-mcp-tools`（工具拆分）、`001-shell`（虚拟屏基座）、`004-server`（对外接口）、`005-script`（自动化脚本）。
> 约束沿用总纲：免 root、arm64-v8a、minSdk 24、仅局域网/USB、**敏感信息（测试 URL/用户/密码/凭证）不入档**。

## 1. Module Overview

### 1.1 Purpose

在既有扁平界面读取（`GET /ui`、MCP `ui_read`）基础上，把「界面读取」扩展成一套**结构化、可寻址**的能力：

- `dumpUi`：输出**层级树 + resourceId**（`children` 递归），支持 DOM 式结构化理解。
- `readUi`：输出**扁平数组**（无层级，兼容既有调用方）。
- `queryUi`：按 **XPath 子集**查询节点（方案 B 自研求值器，见 `013-xpath-evaluator`）。
- `getUi`：按 **resourceId** 定位节点。

其目标是把「读取界面」与「触发操作」在坐标口径上打通：查询命中节点（含 `bounds`）→ 算中心点 → `tap/swipe` 同屏触发。

### 1.2 Problems Solved

问题 001（记录于 `009-mcp-tools`）：私有虚拟屏（Mode A）在部分 ROM（MIUI）上 **a11y 窗口列表不含自建虚拟屏**，`ui_read` 读不到被托管 App ⇒ Mode A 只能靠「截图 + 坐标」。`010-display-mode` 引入 **Mode B**（主屏承载）后，a11y `windows()` 直接读主屏控件树，**结构化读屏在 Mode B 天然可用**。

本模块在 010 的基础上：
- 把「扁平 json 数组」升级为「**既能扁平又能层级**」的双形态读取。
- 提供 **resourceId 寻址**与 **XPath 子集查询**，供脚本/智能体做元素级定位，避免肉眼数坐标。
- 明确**双模式语义**：Mode B 读主屏（脚本直接触发首选）；Mode A 读虚拟屏（ROM 允许则读，否则回退坐标/截图）。

### 1.3 Scope

包含：
- a11y 服务四产出：`dumpUi` / `readUi` / `queryUi` / `getUi`。
- XPath 子集求值器 `XPathEvaluator`（独立模块规范 `013-xpath-evaluator`）。
- 对外契约：REST `/ui` 改为 `dumpUi` 树形；MCP 拆 `ui_read` / `ui_dump` / `ui_query` / `ui_get`。
- 脚本环境：`ui.dump` / `ui.read` / `ui.query` / `ui.get`（经 `IScreenControl` AIDL 代理回主进程）。
- 文档：`task_docs` 补充 XPath 支持范围与正例；新增静态文档 `docs/TASK.md`。

不包含（明确排除）：
- **长按 / 双击 / 多指手势**（本需求明确忽略长按）。
- 完整 W3C XPath 标准（只实现稳定子集，见 013）。
- REST 侧 `query`/`get` 查询参数（用户决策：REST 只提供 `/ui` 树形，其它查询由使用端自行实现）。
- 跨窗口树合并 / 语义角色重构。

## 2. User Stories

- 作为外部智能体，我可以在 Mode B 下用 XPath 定位控件并读取其内容，从而拿到 bounds 触发操作。
- 作为外部智能体，我可以按 resourceId 直接找到按钮/输入框。
- 作为脚本作者，我可以在自动化任务脚本里 `ui.query` → 取 bounds → `screen.tap` 完成「定位→操作」闭环。
- 作为 REST 调用方，我可以通过 `GET /ui` 得到带 children 的层级树做结构化理解。
- 作为用户，我在 Mode A（虚拟屏）下仍可回退到截图+坐标方案（ROM 限制时）。

## 3. Functional Requirements

### 3.1 a11y 四产出

- **FR-012-001**：系统 MUST 提供 `dumpUi()`：返回**层级树** JSON。根为（多个）`window`，节点经 `children` 递归，字段同 3.2。
- **FR-012-002**：系统 MUST 提供 `readUi()`：返回**扁平数组** JSON（无 `children`），字段同 3.2；语义与既有 `GET /ui`（旧）一致（兼容回退）。
- **FR-012-003**：系统 MUST 提供 `queryUi(xpath)`：按 XPath 子集求值（见 `013-xpath-evaluator`），返回**命中节点扁平数组**。
- **FR-012-004**：系统 MUST 提供 `getUi(resourceId)`：按 `viewIdResourceName` 精确匹配，返回命中节点扁平数组（可多个）。
- **FR-012-005**：无障碍服务未启用 / 未就绪时，四产出 MUST 返回明确的降级提示（非崩溃），沿用既有 `503`/`ui unavailable` 语义。

### 3.2 节点统一字段

- **FR-012-010**：每个节点 MUST 至少含：`text / className / packageName / clickable / editable / scrollable / displayId / bounds(l,t,r,b) / resourceId`。
- **FR-012-011**：`resourceId` MUST 取自 `AccessibilityNodeInfo.viewIdResourceName`（形如 `com.android.settings:id/title`）；无则空串。
- **FR-012-012**：`dumpUi` 的节点 MUST 额外带 `children`（子节点数组），形成层级。
- **FR-012-013**：`bounds` 为屏幕坐标（l/t/r/b整数），与 `tap/swipe` 同一坐标口径。

### 3.3 对外契约（REST / MCP / 脚本）

- **FR-012-020**：REST `GET /ui` MUST 改为输出 `dumpUi`（层级树 + resourceId）；`503` 语义不变。
- **FR-012-021**：REST **不提供** query/get 查询参数（使用端自行过滤/解析）。
- **FR-012-022**：MCP 工具列表 MUST 拆分为 `ui_read`（扁平）、`ui_dump`（层级）、`ui_query`（入参 `xpath`）、`ui_get`（入参 `resourceId`）。
- **FR-012-023**：脚本环境 MUST 提供 `ui.dump` / `ui.read` / `ui.query` / `ui.get`（经 `IScreenControl` 代理回主进程 a11y）。
- **FR-012-024**：REST、MCP、脚本 MUST 共用同一底层 a11y 产出（沿用 C-1 单一入口 → `ScreenController`）。

### 3.4 双模式语义

- **FR-012-030**：**Mode B（主屏承载）** MUST 读取主屏被托管 App 控件树——脚本「定位→触发」首选路径。
- **FR-012-031**：**Mode A（虚拟屏承载）** MUST 仍经 `windows()` 尽力读虚拟屏；ROM 受限读取失败时，调用方（MCP agent）MUST 回退 `screen_capture` + 坐标。
- **FR-012-032**：四产出对外不区分 mode（同一接口），mode 语义由 `010-display-mode` 的 `status.mode` 承载。

### 3.5 文档与诊断

- **FR-012-040**：`task_docs` MUST 明确 XPathEvaluator 在脚本环境中的**支持范围**与**正例**（引用 013）。
- **FR-012-041**：MUST 提供静态文档 `docs/TASK.md`，与 `task_docs` 内容对应。
- **FR-012-042**：任何测试 URL、账号、密码、凭证 MUST NOT 落盘文档/提交/日志正文。

## 4. Key Entities

| 实体 | 说明 | 关键属性 |
|---|---|---|
| a11y 服务产出 | 读树结果 | `dumpUi`(树) / `readUi`(扁平) / `queryUi`(xpath→数组) / `getUi`(id→数组) |
| 节点记录 | 单个控件 | `text/className/packageName/clickable/editable/scrollable/displayId/bounds/resourceId` |
| XPathEvaluator | XPath 子集求值器 | 见 `013-xpath-evaluator` |
| ScreenController 桥 | 单一操作入口 | `uiReader/uiDumper/uiQuerier/uiGetter` 四 lambda |

## 5. Acceptance Scenarios

### 场景 A：Mode B 定位并触发（脚本）

- Given Mode B 就绪、目标 App 在主屏
- When 脚本 `ui.query('//android.widget.Button[@text="确定"]')`
- Then 返回命中节点（含 bounds）；脚本算中心点 `screen.tap` 触发成功

### 场景 B：按 resourceId 定位

- Given Mode B 就绪
- When `ui.get('com.android.settings:id/search')`
- Then 返回该节点（含 text/bounds），可继续触发

### 场景 C：REST 层级树

- Given 会话就绪 + a11y 启用
- When `GET /ui`
- Then 返回带 `children` 的层级树 JSON，含 resourceId

### 场景 D：MCP 四工具

- Given MCP 客户端已连
- When 调用 `ui_dump`/`ui_read`/`ui_query`/`ui_get`
- Then 各返回对应形态的结果；`ui_query`/`ui_get` 需必填参数，缺失返回明确错误

### 场景 E：Mode A 回退

- Given Mode A + ROM 无虚拟屏 a11y 窗口
- When `ui_dump` 返回空/不可用
- Then MCP agent 回退 `screen_capture` + 坐标完成操作

## 6. Non-Functional Requirements

- **兼容**：`readUi` 与既有 `ui_read`/`GET /ui`（旧扁平）语义一致；既有消费者不破坏。
- **健壮**：a11y 未启用 / 查询无匹配 / XPath 非法 MUST 显式处理（返回空数组或明确错误），不崩溃。
- **安全**：不新增公网暴露；文档不含真实地址与凭据；日志不记正文（沿用 C-12）。
- **性能**：`dumpUi` 单次构建；`queryUi` 对已遍历节点串行求值，禁止循环内重复截帧。
- **可诊断**：解析失败/无匹配有可观测日志。

## 7. Assumptions & Constraints

- XPath 采用**自研子集**（用户决策「方案 B」），不用 JAXP/DOM（真机 a11y 树大，DOM 开销高）。
- REST 只提供 `/ui` 树形；query/get 由使用端自行实现（用户决策）。
- Mode A 虚拟屏在部分 ROM 读不到是**固有平台限制**，验收以 Mode B 为主，Mode A 走回退链路。
- 忽略长按需求（用户决策）。

## 8. Dependencies

- 上游：`010-display-mode`（Mode B 读主屏）、`001-shell`（虚拟屏）、`004-server`（REST/MCP）、`005-script`（脚本 host）、a11y 服务。
- 下游：`013-xpath-evaluator`（queryUi 求值器）、外部 MCP 客户端、自动化任务脚本。

## 9. 已知限制（首版）

- XPath 仅支持稳定子集（轴 `/`、`//`；`*`；className 简写/全名；`@text/@resourceId/@className/@clickable/@editable/@scrollable/@index`；位置 `[N]`）。
- `queryUi` 返回扁平命中数组，不做跨层级祖先返回。
- Mode A 部分 ROM 读不到虚拟屏 ⇒ 结构化读屏以 Mode B 为准。
