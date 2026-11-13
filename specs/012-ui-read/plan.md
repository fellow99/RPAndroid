# 012-ui-read Plan — 界面读取能力增强

> 状态：Proposed（2026-11-11）｜ 依据：`spec.md` + 本会话设计讨论
> 技术方向（用户决策已确认）：
> - **XPath 用方案 B（自研 XPath 子集求值器）**，不用 JAXP/DOM。
> - **REST 只提供 `/ui`（树形）**，query/get 由使用端自行实现。
> - 忽略**长按**需求。
> - Mode B（主屏）读主屏结构供**脚本直接触发**；Mode A（虚拟屏）走**坐标/截图兜底**供 MCP agent。

## 1. 技术背景

既有链路：`MfaAccessibilityService.dumpUi()`（app 模块，a11y 遍历 windows）→ `ManagedRuntime.controller.uiReader` →
`AppScreenController.readUi()` → REST `/ui` 与 MCP `ui_read`。脚本侧（`:node`）经 `IScreenControl` AIDL 调主进程，
但**没有读树接口**。

## 2. 设计

### 2.1 统一树模型 + a11y 四产出

引入**纯 Kotlin 树模型 `UiNode`**（`core:script`，无 Android 依赖，可离线 JVM 单测）：
字段 `text/className/packageName/clickable/editable/scrollable/resourceId/bounds/indexInParent/children`。

- a11y 服务把 `windows` 的 `AccessibilityNodeInfo` 构建一次成 `List<UiNode>`（每次调用现建，避免长驻失效节点）。
- 四产出统一由该 `UiNode` 树派生（单一遍历）：
  - `dumpUi(): String` → 层级树：`[{window...children:[...]}]`（节点带 `children`，含 `resourceId`）。
  - `readUi(): String` → 扁平数组：节点记录（无 `children`，兼容旧语义）。
  - `queryUi(xpath): String` → `XPathEvaluator.query(roots, xpath)` → 命中节点扁平数组。
  - `getUi(resourceId): String` → 层序遍历匹配 `resourceId` → 命中节点扁平数组。

节点序列化统一函数 `nodeJson(n): JSONObject`，字段：`text/className/packageName/clickable/editable/scrollable/displayId/bounds(l,t,r,b)/resourceId`。

### 2.2 XPathEvaluator（独立 013 模块）

纯 Kotlin 类，直接遍历 `AccessibilityNodeInfo` 树求值（详见 `013-xpath-evaluator`）。设计要点：
- 解析路径为段列表；`/`=子轴，`//`=任意深度后代。
- 节点测试：`*`、className 简写/全名。
- 谓词：`[@text=..]`、`[@resourceId=..]`、`[@className=..]`、`[@clickable=bool]`、`[@editable=bool]`、`[@scrollable=bool]`、`[@index=N]`、位置 `[N]`。
- 命中序列化复用 `nodeJson`。

### 2.3 ScreenController 桥（core/controller）

`AppScreenController` 增 lambda：`uiDumper/uiQuerier/uiGetter`（`uiReader` 保留为 flat）。对应方法：
`dumpUi()/queryUi(xpath)/getUi(resourceId)`（`readUi()` 保留）。

### 2.4 脚本桥（:node）

- `IScreenControl.aidl` 增：`String readUi(); String dumpUi(); String queryUi(String xpath); String getUi(String resourceId);`
- `ManagedService.binder` 实现转发 `ctl()`。
- `ScriptService.registerHost` 注册 `ui.read / ui.dump / ui.query / ui.get`，返回主进程 JSON 字符串。

### 2.5 对外契约

- `ManagedServer` `GET /ui` → `dumpUi()`（树形 + resourceId）。
- `McpServer`：
  - 工具拆 `ui_read`（扁平）/ `ui_dump`（层级）/ `ui_query`（入参 `xpath`*）/ `ui_get`（入参 `resourceId`*）。
  - `callTool` 增对应分支，`ui_query`/`ui_get` 缺参返回明确 error。

### 2.6 文档

- `task_docs` 增 XPathEvaluator 支持范围 + 正例（引用 013）。
- 新增 `docs/TASK.md`（对应 task_docs 的静态文档）。
- 更新 `docs/API.md`、`docs/MCP.md`（四工具与 `/ui` 语义）。**不含敏感信息。**

## 3. 文件改动清单

| 文件 | 改动 |
|---|---|
| `app/a11y/MfaAccessibilityService.kt` | 重构：构建 UiNode 树 + `dumpUi/readUi/queryUi/getUi` |
| `core/script/.../UiNode.kt`（新建） | 纯 Kotlin 树模型 |
| `core/script/.../XPathEvaluator.kt`（新建） | XPath 子集求值器（纯 Kotlin） |
| `core/script/src/test/.../XPathEvaluatorTest.kt`（新建） | 013 的 JVM 离线测试 |
| `app/ManagedRuntime.kt` | 接线 4 个 lambda |
| `core/controller/AppScreenController.kt` | `dumpUi/queryUi/getUi` 方法 + lambda |
| `app/aidl/IScreenControl.aidl` | 增 4 方法 |
| `app/ManagedService.kt` | binder 转发 |
| `app/script/ScriptService.kt` | `ui.*` host 注册 |
| `core/server/McpServer.kt` | 工具拆分 + callTool 分支 |
| `core/server/ManagedServer.kt` | `/ui` → dumpUi |
| `core/server/TaskScriptDocs.kt` | XPath 支持范围+正例 |
| `docs/TASK.md`（新建） | 静态脚本任务文档 |
| `docs/API.md`、`docs/MCP.md` | 契约更新 |

## 4. 测试要点

- 单元级：XPathEvaluator 语法/匹配（013 提供可运行脚本测试）。
- 集成级：MCP 四工具、REST `/ui` 树形、脚本 `ui.*` 定位→点击闭环。
- 真机：Mode B（主屏）为主链路。

## 5. 风险

- a11y 树节点多时 `dumpUi` 序列化开销；`queryUi` 不重复截帧。
- Mode A（虚拟屏）ROM 限制 → 真机主测 Mode B，Mode A 走回退。
