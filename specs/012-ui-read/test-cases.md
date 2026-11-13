# 012-ui-read Test Cases — 界面读取能力

> 状态：Proposed（2026-11-11）｜ 依据：`spec.md` FR-012-001~042
> 执行环境：真机（免 root、局域网/USB）。**测试 URL/用户/密码/凭证仅命令行使用，不落盘。**
> `Actual Result` / `Status` 在 Testing 阶段填写（PASS/FAIL/SKIP）。

## TC-012-001：dumpUi 层级树（FR-012-001/010/012）
- **Priority**: P1 ｜ **Type**: Functional ｜ **Precondition**: 会话就绪 + a11y 启用 + Mode B
- **Steps**: 1) 承载目标 App 2) 调 MCP `ui_dump`（或 REST `GET /ui`）
- **Expected**: 返回带 `children` 的层级树 JSON；节点含 `resourceId`、`bounds`、`text` 等字段
- **Status**: **PASS（真机：/ui 返回层级树，2 windows、128 节点，含 children+resourceId）**

## TC-012-002：readUi 扁平数组（FR-012-002/010）
- **Priority**: P1 ｜ **Type**: Functional ｜ **Precondition**: 同上
- **Steps**: 1) 调 MCP `ui_read`
- **Expected**: 返回扁平 JSON 数组（节点无 `children`），字段齐全，与旧语义兼容
- **Status**: **PASS（真机：ui_read 返回扁平数组 57 节点，含 bounds/resourceId）**

## TC-012-003：queryUi XPath 定位（FR-012-003；013）
- **Priority**: P1 ｜ **Type**: Functional ｜ **Precondition**: 同上
- **Steps**: 1) 调 MCP `ui_query`，`xpath='//android.widget.TextView[@text="<已知文本>"]'`
- **Expected**: 返回命中节点扁平数组（含 bounds），中心点可用于 tap
- **Status**: **PASS（真机：//*[@text=5] 与 //android.widget.TextView[@text=5] 命中含 bounds；中心点 tap 使显示变化）**

## TC-012-004：queryUi 无匹配 / 非法 XPath（FR-012-003；013）
- **Priority**: P2 ｜ **Type**: Edge Case ｜ **Precondition**: 同上
- **Steps**: 1) 查不存在的 xpath 2) 传非法 xpath 语法
- **Expected**: 无匹配返回空数组；非法语法返回明确错误（不崩溃）
- **Status**: **PASS（真机：非法 XPath/无前置/缺参均返回明确错误或空数组，不崩溃）**

## TC-012-005：getUi 按 resourceId（FR-012-004/011）
- **Priority**: P1 ｜ **Type**: Functional ｜ **Precondition**: 同上
- **Steps**: 1) 调 MCP `ui_get`，`resourceId='com.<pkg>:id/<某控件>'`
- **Expected**: 返回该节点（含 text/bounds）；不存在返回空数组
- **Status**: **PARTIAL（真机：空匹配返回[]、缺参报错正确；本机 MIUI 界面无 resourceId，正向匹配无法真机验证，逻辑与扁平遍历同源+013单测覆盖）**

## TC-012-006：REST /ui 树形（FR-012-020/021）
- **Priority**: P1 ｜ **Type**: Functional ｜ **Precondition**: 会话 + a11y
- **Steps**: 1) `GET /ui`
- **Expected**: 返回层级树 JSON（带 children + resourceId）；无查询参数约定
- **Status**: **PASS（真机：GET /ui 返回层级树）**

## TC-012-007：MCP 四工具缺参错误（FR-012-022）
- **Priority**: P2 ｜ **Type**: Edge Case ｜ **Precondition**: MCP 已连
- **Steps**: 1) `ui_query` 不传 `xpath` 2) `ui_get` 不传 `resourceId`
- **Expected**: 返回明确缺参错误
- **Status**: **PASS（真机：ui_query/ui_get 缺参返回 error: missing required argument）**

## TC-012-008：脚本定位→触发闭环（FR-012-023；005-script）
- **Priority**: P1 ｜ **Type**: Integration ｜ **Precondition**: Mode B + 自动化任务可建
- **Steps**: 1) 建自动化任务脚本：`ui.query` 定位→取 bounds 中心点→`screen.tap` 2) 用 MCP `task_run` 执行
- **Expected**: 目标控件被点击，界面状态变化可观测
- **Status**: **PASS（真机：脚本 ui.query→bounds→screen.tap，计算器显示追加数字，lastStatus=ok，日志成功）**

## TC-012-009：a11y 未启用降级（FR-012-005）
- **Priority**: P2 ｜ **Type**: Edge Case ｜ **Precondition**: 关闭无障碍服务
- **Steps**: 1) 调 `ui_dump`/`ui_read`
- **Expected**: 返回明确降级提示/503（非崩溃）
- **Status**: **SKIP（未单独关闭无障碍验证；下降级由 null→503 承接，代码覆盖）**

## TC-012-010：task_docs 含 XPath 支持范围与正例（FR-012-040）
- **Priority**: P2 ｜ **Type**: Documentation ｜ **Precondition**: 文档完成
- **Steps**: 1) 调 MCP `task_docs` 2) 读 `docs/TASK.md`
- **Expected**: 含 XPathEvaluator 支持范围、语法说明、至少一个查询正例
- **Status**: **PASS（真机：task_docs 含 XPath 支持范围与正例；docs/TASK.md 已建）**

## TC-012-011：Mode A 回退语义（FR-012-031）
- **Priority**: P2 ｜ **Type**: Functional ｜ **Precondition**: Mode A（若 ROM 读不到虚拟屏）
- **Steps**: 1) Mode A 下调 `ui_dump`
- **Expected**: 空/不可用时返回降级语义；调用方（MCP agent）回退 `screen_capture`+坐标
- **Status**: **SKIP（本轮主测 Mode B；Mode A 回退为既有链路）**

## TC-012-012：文档无敏感信息（FR-012-042；C-12）
- **Priority**: P2 ｜ **Type**: Security ｜ **Precondition**: 文档更新完成
- **Steps**: 1) 检查 `docs/API.md`、`docs/MCP.md`、`docs/TASK.md` 与本次提交
- **Expected**: 无测试 URL/账号/密码/凭证落盘；日志无文本正文
- **Status**: **PASS（文档无测试URL/账号/密码/凭证）**
