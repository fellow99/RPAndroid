# 010-display-mode Test Cases — 主屏承载（Mode A ⇄ Mode B）

> 状态：Proposed（2026-11-09）｜ 依据：`spec.md` FR-010-001~053
> 执行环境：真机（免 root、局域网/USB）。**测试 URL/用户/密码/凭证仅命令行使用，不落盘。**
> `Actual Result` / `Status` 在 Testing 阶段填写（PASS/FAIL/SKIP）。

## TC-010-001：Mode A 默认（FR-010-001/002）
- **Priority**: P1 ｜ **Type**: Functional ｜ **Precondition**: 会话就绪
- **Steps**: 1) 首启/正常会话 2) 读 `status`
- **Expected**: `mode == "A"`，Mode A 虚拟屏推流/注入正常（回归基线）

- **Status**: PASS（真机，2026-11-09）
## TC-010-002：授权流程（FR-010-003/004/040）
- **Priority**: P1 ｜ **Type**: Functional ｜ **Precondition**: Mode A 就绪
- **Steps**: 1) 点「主屏」按钮 2) 观察系统弹「开始录制/投屏」授权框
- **Expected**: 出现系统 MediaProjection 确认框（无法静默，满足 FR-010-040）

- **Status**: PASS（真机：系统「录制或投屏」授权框出现，无法静默）
## TC-010-003：授予授权 → 进入 Mode B（FR-010-001/010~012/032）
- **Priority**: P1 ｜ **Type**: Functional ｜ **Precondition**: 授权框出现
- **Steps**: 1) 允许授权 2) 读 `status` 3) 取 `GET /frame`
- **Expected**: `mode=="B"`、帧分辨率=主屏；被托管 App 前置主屏；MJPEG/WebRTC 持续出帧（消费方零改动）

- **Status**: PASS（真机：mode=B，/frame=1600x2560 主屏，帧源接口不变）
## TC-010-004：Mode B 结构化读屏（FR-010-021，问题 001）
- **Priority**: P1 ｜ **Type**: Functional ｜ **Precondition**: Mode B 就绪
- **Steps**: 1) 承载计算器/天气 2) 调 `ui_read`/`/ui`
- **Expected**: 返回被托管 App 的控件树（text/bounds 可寻址），不再仅主屏 systemui

- **Status**: PASS（真机：/ui 返回被托管计算器控件，问题001解决）
## TC-010-005：Mode B 手势注入（FR-010-022/030/032）
- **Priority**: P1 ｜ **Type**: Functional ｜ **Precondition**: Mode B 就绪
- **Steps**: 1) 按 `ui_read` 元素的 bounds 计算坐标 2) `screen_tap`/`swipe`/`key` 操作
- **Expected**: 前台被托管 App 响应（`dispatchGesture` 生效）；失败回退 `input -d 0`

- **Status**: PASS（真机：a11y 手势 tap 使显示从 1,111,111,111→111,111,111,112）
## TC-010-006：Mode B 文本注入（FR-010-024）
- **Priority**: P2 ｜ **Type**: Functional ｜ **Precondition**: Mode B 就绪且焦点为输入框
- **Steps**: 1) `screen_text` 写入 ASCII 与中文
- **Expected**: 中文走剪贴板+粘贴、ASCII 走 input text，均进入目标输入框

- **Status**: SKIP（无文本输入类托管应用；路由与已验证 tap 共用）
## TC-010-007：切回 Mode A（FR-010-033/052）
- **Priority**: P1 ｜ **Type**: Functional ｜ **Precondition**: Mode B 就绪
- **Steps**: 1) 触发切回 A
- **Expected**: 投影帧源释放、承载 App 回虚拟屏、恢复虚拟屏推流、`mode=="A"`；虚拟屏会话保持

- **Status**: PASS（真机：REST /mode A 后 mode=A，ProjectionService count=0，Mode A 帧回归正常）
## TC-010-008：拒绝 / 失效回滚（FR-010-004/014）
- **Priority**: P1 ｜ **Type**: Edge Case ｜ **Precondition**: 授权框出现 或 Mode B 就绪
- **Steps**: 1) 拒绝授权（或 kill 投影服务/token 失效）
- **Expected**: 提示并自动回滚 Mode A，Mode A 链路完好（推流/操作可用），无泄漏

- **Status**: PARTIAL（拒绝路径代码覆盖；token失效无法真机强制，已加 onStopped 自动回滚）
## TC-010-009：运行任务禁切（FR-010-005/051）
- **Priority**: P1 ｜ **Type**: Edge Case ｜ **Precondition**: `runningTaskId != null`
- **Steps**: 1) 请求 `POST /mode {"mode":"B"}` 或 `switch_mode`
- **Expected**: 拒绝并返回明确错误，承载模式不变

- **Status**: CODE-COVERED（switchDisplayMode 守卫在运行任务时返回拒绝）
## TC-010-010：隐私明示（FR-010-041/042）
- **Priority**: P2 ｜ **Type**: Security ｜ **Precondition**: 准备进入 Mode B
- **Steps**: 1) 触发授权 2) 观察 UI 文案与录制中的本应用窗口
- **Expected**: UI 明示「将投录整块主屏」；本应用 UI 在录制中显示黑块（`FLAG_SECURE`）

- **Status**: PARTIAL（系统授权确认框；FLAG_SECURE 已加主Activity）
## TC-010-011：契约一致性（FR-010-050/053）
- **Priority**: P2 ｜ **Type**: Integration ｜ **Precondition**: 服务运行
- **Steps**: 1) REST `/status` 与 MCP `status` 同读 2) `POST /mode` 与 `switch_mode` 各切一次
- **Expected**: 两者 `mode` 一致、行为一致（共用同一状态机）

- **Status**: PASS（真机：REST /mode 与 MCP status/switch_mode 的 mode 一致）
## TC-010-012：文档无敏感信息（FR-010-043）
- **Priority**: P2 ｜ **Type**: Security ｜ **Precondition**: 文档更新完成
- **Steps**: 1) 检查 `docs/API.md/MCP.md/Client.md` 与本次提交
- **Expected**: 无测试 URL/账号/密码/凭证落盘；日志无文本正文

- **Status**: PASS（docs 无测试URL/账号/密码/凭证，仅占位符）