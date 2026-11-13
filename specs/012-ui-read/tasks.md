# 012-ui-read Tasks — 界面读取能力增强

> 状态：Development（2026-11-13）｜ 依据：`plan.md` + `spec.md`
> 标记：`[x]` 完成 / `[ ]` 待办。依赖序执行。真机联调后按实测结果追加 `T1xx` 任务。
> 依赖：`013-xpath-evaluator`（queryUi 求值器）。

## Phase 1 — 统一树模型 + a11y 四产出（core:script + app）

- [x] **T001** 新建 `core/script/.../UiNode.kt`：纯 Kotlin 树模型（`text/className/packageName/clickable/editable/scrollable/resourceId/bounds/indexInParent/displayId/children`）。
- [x] **T002** 重构 `MfaAccessibilityService.kt`：把 `windows` 构建成 `List<UiNode>`；`dumpUi()` 输出层级树（`children` 递归）；`readUi()` 输出扁平数组（兼容旧语义）；`nodeJson(n)` 统一序列化（含 `resourceId`、`bounds`）。
- [x] **T003** `getUi(resourceId)`：层序遍历匹配 `resourceId` → 命中节点扁平数组。
- [x] **T004** `queryUi(xpath)`：调用 `XPathEvaluator.query(roots, xpath)` → 命中节点扁平数组。

## Phase 2 — XPathEvaluator（013 模块，core:script 纯 Kotlin）

- [x] **T005** 新建 `core/script/.../XPathEvaluator.kt`：解析 `/[//]tag[@attr='v'][N]` 子集并求值（详见 013）。
- [x] **T006** 新建 `core/script/src/test/.../XPathEvaluatorTest.kt`，覆盖 TC-013-001~011（离线 JVM）。

## Phase 3 — ScreenController 桥（core/controller）

- [x] **T007** `AppScreenController` 增 lambda `uiDumper/uiQuerier/uiGetter` 与方法 `dumpUi()/queryUi(xpath)/getUi(resourceId)`（`readUi()` 保留）；`ScreenController` 接口同步扩展。

## Phase 4 — 脚本桥（:node）

- [x] **T008** `IScreenControl.aidl` 增 `readUi/dumpUi/queryUi/getUi` 四方法。
- [x] **T009** `ManagedService.binder` 实现四方法转发 `ctl()`（queryUi 捕获非法 XPath → `{"error":...}`）。
- [x] **T010** `ScriptService.registerHost` 注册 `ui.read / ui.dump / ui.query / ui.get`。

## Phase 5 — 对外契约

- [x] **T011** `ManagedServer` `GET /ui` 改调 `dumpUi()`（树形 + resourceId）。
- [x] **T012** `McpServer` 工具拆分 `ui_read/ui_dump/ui_query/ui_get`；`callTool` 增分支（query/get 需必填参数，缺参返回 error）。

## Phase 6 — 文档

- [x] **T013** `TaskScriptDocs`（task_docs）增 XPathEvaluator 支持范围 + 正例。
- [x] **T014** 新建 `docs/TASK.md`（静态文档，与 task_docs 对应）。
- [x] **T015** 更新 `docs/API.md`、`docs/MCP.md`（四工具与 `/ui` 树形语义）。**不含任何测试 URL/账号/密码/凭证。**

## Phase 7 — 构建 / Code Review

- [x] **T016** 全量编译构建通过（gradlew assemble）。
- [x] **T017** 通过 requesting/receiving-code-review（C-11）。

## Phase 8 — 真机联调（Mode B 主链路）

> 依据用户指示：选应用并切到「主屏」承载模式测试；可用 MCP 添加/执行自动化任务。结果在本节追记。

- [ ] **T101** 真机：Mode B 承载目标 App，`ui_dump` 返回层级树（含 resourceId、children）。
- [ ] **T102** 真机：`ui_read` 返回扁平数组（兼容）。
- [ ] **T103** 真机：`ui_query` 按 XPath 定位控件（正例），返回含 bounds 命中节点。
- [ ] **T104** 真机：`ui_get` 按 resourceId 定位。
- [ ] **T105** 真机：自动化任务脚本 `ui.query → bounds 中心点 → screen.tap` 闭环。

## Done 标准

- 可编译构建 + 通过 code review（C-11）+ 真机可测（Mode B 全链路 + Mode A 回退）。
- 无敏感信息落盘（C-12）。
