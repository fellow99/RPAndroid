# 010-display-mode Tasks — 主屏承载（Mode A ⇄ Mode B）

> 状态：Proposed（2026-11-09）｜ 依据：`plan.md` + `spec.md`
> 标记：`[x]` 完成 / `[ ]` 待办。依赖序执行。真机联调后按实测结果追加 `T0xx` 工具推导任务。

## Phase 1 — 基础（Setup / Foundational）

- [ ] **T001** 新增 `core/controller/DisplayMode.kt`：`enum DisplayMode { VIRTUAL, MAIN }` + 映射 `"A"|"B"`；`enum ProjectionAuth { NONE, GRANTED, FAILED }`。
- [ ] **T002** `core/server/ServerConfig.kt` 增 `mode` 持久化字段（key `display_mode`，默认 `"A"`）。

## Phase 2 — 模式状态机

- [ ] **T003** 新增 `core/controller/ModeController.kt`：
  - 持有 `mode`（初值读 ServerConfig）、`auth`（ProjectionAuth）。
  - `canSwitch(): String?`（`runningTaskId != null` → 返回拒绝原因；否则 null）。
  - `enterMain(host)` / `revertToVirtual()` 往返动作；`onChanged` 回调发布到 UI/通知。
  - 守卫：**运行自动化任务时禁止切换（FR-010-005）**。

## Phase 3 — MediaProjection 宿主（app 层）

- [ ] **T004** `AndroidManifest.xml`：增加 `<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION"/>`；注册 `ProjectionService`（`android:foregroundServiceType="mediaProjection"`）。
- [ ] **T005** 新增 `app/proj/ProjectionService.kt`：`foregroundServiceType=mediaProjection`（API 34+），承载 MediaProjection token 与投影 VirtualDisplay；API<34 兼容。
- [ ] **T006** 新增 `app/proj/MediaProjectionHost.kt`：`prepareIntent()`（MediaProjectionManager.createScreenCaptureIntent）；`start(resultCode,data)` → 启动 ProjectionService → 建投影帧源；`stop()`（`mediaProjection.stop()` + FrameHub 释放 + token 归零）。

## Phase 4 — 帧源抽象（消费方零改动）

- [ ] **T007** `AppScreenController.frameSource()` 改为按 mode 返回：`VIRTUAL → vdSession?.frameSource()`；`MAIN → 投影帧源`（复用 `FrameHub` 作 `ProjectionFrameSource`）。
- [ ] **T008** 投影 VirtualDisplay 用 `FrameHub(w,h).surface` 建，帧直接落 App 进程；MJPEG/WebRTC/本地预览消费方不改。

## Phase 5 — 无障碍手势与操作分派

- [ ] **T009** `accessibility_service_config.xml` 增 `android:canPerformGestures="true"`。
- [ ] **T010** `MfaAccessibilityService` 增 `tap(x,y)`/`swipe(...)`：`dispatchGesture` 注入前台主屏。
- [ ] **T011** `AppScreenController` 按 mode 分派操作：`tap/swipe`（B：a11y 手势 → 回退 `input -d 0`）、`back/pressKey`（B：`input -d 0 keyevent`）、`text`（目标主屏）、`launch`（B：`am start -n` 主屏前置）。

## Phase 6 — 对外契约 + UI + 通知

- [ ] **T012** `ManagedServer` `/status` 增 `mode` 与 B 表主屏分辨率；新增 `POST /mode`（body `{"mode":"A"|"B"}`）走 ModeController。
- [ ] **T013** `McpServer` `status` 增 `mode`；新增 `switch_mode` 工具（复用同一状态机，C-1）。
- [ ] **T014** 前台通知反映当前承载模式（`虚拟屏承载`/`主屏承载`）；UI 同步。
- [ ] **T015** `应用呈现` 右上角新增「主屏」按钮 → 触发授权/切 Mode B；Mode B 下 `FLAG_SECURE` 保护本应用窗口；模式状态展示。

## Phase 7 — 文档

- [ ] **T016** `docs/API.md`、`docs/MCP.md`、`docs/Client.md` 增 Mode B 契约（`status.mode`、`/mode`、`switch_mode`、坐标口径、隐私明示）。**不含任何测试 URL/账号/密码/凭证。**

## Phase 8 — 真机联调 + 工具推导（依实测）

> 依据 spec.md「先以方案 → 真机测试 → 推导缺失工具 → 规划实现」执行，结果在本节追记。

- [ ] **T101** 真机：Mode A → 授权 → Mode B 全流程（FR-010-001~005）
- [ ] **T102** 真机：B 下 `ui_read` 读到被托管 App 控件树（问题 001 验证）
- [ ] **T103** 真机：B 下手势 tap/swipe/key/text 生效
- [ ] **T104** 真机：切回 A、拒绝/失效回滚、运行任务禁切
- [ ] **T105** 依据实测**推导所需缺失工具**，追加 `T0xx` 任务并实现。

## Done 标准

- 可编译构建 + 通过 requesting/receiving-code-review（C-11）+ 真机可测（Scope：Mode B 全流程 + 回归 Mode A）。
