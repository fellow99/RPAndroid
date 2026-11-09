# 010-display-mode Plan — 虚拟屏承载 / 主屏承载（Mode A ⇄ Mode B）

> 状态：Proposed（2026-11-09）｜ 依据：`spec.md` + `docs/010-主屏承载可行性方案.md` §8/§9
> 宪法约束：C-1 单一操作入口、C-2 模块单一职责、C-3 依赖单向、C-7 优雅降级、C-10 提交规范、C-12 安全底线。

## 1. 概述

首版以「可行切片」为验收：**实体上主屏前置 + 投影帧 + a11y 读树/手势 + status.mode + 双向切换 +
运行任务禁切**。本应用 UI 不迁入虚拟屏（`FLAG_SECURE`），“本应用搬屏”降级为可选优化。

改动集中在 4 处新增/改造，消费方（MJPEG/WebRTC/本地预览）零改动。

## 2. 架构决策

### 2.1 承载模式抽象（ModeController）

新增 `DisplayMode` 枚举与 `ModeController`（放 `core:controller`），作为模式状态机唯一入口，遵守 C-1。

- `enum class DisplayMode { VIRTUAL, MAIN }`（对外序列化 `"A"|"B"`）。
- `ModeController` 职责：
  - 持有当前 `mode`（初值读 `ServerConfig`，默认 A）。
  - 授权态 `ProjectionAuth { NONE, GRANTED, FAILED }`。
  - `switchTo(virtual|main)` 守卫：`runningTaskId != null` → 拒绝。
  - 往返动作：`enterMain()`（触发 MediaProjection 授权 → 建投影帧源 → 前置承载 App 主屏）、
    `revertToVirtual()`（释放投影帧源 → 承载 App 回虚拟屏 → 恢复推流）。

### 2.2 帧源抽象（关键：消费方零改动）

现状 `ScreenController.frameSource() = vdSession?.frameSource()` 只暴露私有屏帧源。
改造为**按 mode 返回**：

```kotlin
override fun frameSource(): FrameSource? = when (displayMode) {
    DisplayMode.VIRTUAL -> vdSession?.frameSource()
    DisplayMode.MAIN    -> projection?.projectionFrameSource   // MediaProjection 帧源
}
```

- MJPEG `GET /stream`/`GET /frame`、WebRTC `copyRgba`、本地预览全部只认 `FrameSource` 接口 → **不改**。
- `ProjectionFrameSource` 复用 `FrameHub` 的 Surface+ImageReader+rowStride 收包逻辑；因 `FrameHub` 构造即建
  ImageReader/线程，投影场景新建一个 `FrameHub(w,h)`，用其 `surface` 给投影 VirtualDisplay，把 `FrameHub`
  直接作为 `FrameSource` 返回（`FrameHub` 已实现 `FrameSource`）。

### 2.3 MediaProjectionHost（app 层）

MediaProjection 需要 Activity 结果（授权弹窗）+ 前台服务（API 34+），故放 `app` 层：

- `MediaProjectionHost(context, onFrameChanged)`：
  - `prepareIntent(): Intent` → `MediaProjectionManager.createScreenCaptureIntent()`。
  - `start(resultCode, data, w, h)`：拿 token → 启动 `ProjectionService`（持 token）→ 在其中
    `mediaProjection.createVirtualDisplay(..., frameHub.surface, ...)` → 返回投影帧源。
  - `stop()`：`mediaProjection.stop()` + 释放 `FrameHub` + token 归零。
- `ProjectionService`：`foregroundServiceType="mediaProjection"`（manifest），承载 MediaProjection 生命周期，
  兼容 API<34（无强制类型）。`AppController`/UI 经其回调获取就绪状态。
- Android 14+：manifest 增加 `<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION"/>`
  与 `android:foregroundServiceType="mediaProjection"`。

### 2.4 无障碍手势（a11y）

`MfaAccessibilityService`：
- `accessibility_service_config.xml` 增加 `android:canPerformGestures="true"`。
- 新增 `tap(x,y)` / `swipe(...)`：`dispatchGesture(GestureDescription...)` 注入到当前前台窗口（主屏）。
- 读树 `dumpUi()` 已遍历 `windows()` 主屏 → Mode B 下天然读到被托管 App（问题 001 消失），不加改。

### 2.5 操作分派（AppScreenController）

`AppScreenController` 增加 mode 分派（遵守 C-1，仍为唯一入口）：

| 操作 | Mode A | Mode B |
|---|---|---|
| frameSource | vdSession 私有帧源 | MediaProjection 投影帧源 |
| tap/swipe | `input -d <vd>` | a11y `dispatchGesture` → 失败回退 `input -d 0` |
| back/pressKey | `input -d <vd> keyevent` | `input -d 0 keyevent` |
| text | 走 vdSession（ASCII input text / 剪贴板粘贴） | 同上，目标主屏（`input -d 0 text` / 剪贴板+PASTE） |
| launch | `am start --display <vd>` | `am start -n <pkg>/<act>`（主屏前置） |
| app 前置切换 | 虚拟屏承载 | 主屏承载（同会话实例换屏，不销毁） |

### 2.6 对外契约

- `status`：`statusProvider` 增 `mode`（`"A"|"B"`）+ B 下表主屏宽高（调用方按 `status.w/h` 换算坐标的逻辑不变）。
- `POST /mode`（REST，body `{"mode":"A"|"B"}`）+ MCP `switch_mode`：共用同一 `ModeController`。
- 前台通知反映当前承载模式（`虚拟屏承载` / `主屏承载`），UI 同步（006-config-ui）。首版通知仅展示模式文案，
  通知内点击切换（P2，见 tasks）。

### 2.7 隐私（安全底线 C-12）

- 进入 B 前系统授权弹窗；本应用窗口 `FLAG_SECURE`；UI 明示「将投录整块主屏」。
- 全程不落盘测试 URL/用户/密码/凭证。

## 3. 文件结构（新增 / 修改）

```
core:controller/src/main/java/com/managedfloating/core/controller/
  DisplayMode.kt                      (新增)  enum + ProjectionAuth
  ModeController.kt                   (新增)  模式状态机（守卫/往返）
  AppScreenController.kt              (修改)  mode 分派、frameSource 按 mode
app/src/main/java/com/managedfloating/app/
  proj/MediaProjectionHost.kt         (新增)  授权 intent / start / stop
  proj/ProjectionService.kt           (新增)  mediaProjection 前台服务（API34+）
  a11y/MfaAccessibilityService.kt     (修改)  canPerformGestures + dispatchGesture tap/swipe
  present/...ModeUi.kt                (修改)  「主屏」按钮 + FLAG_SECURE + 模式状态展示
  ManagedRuntime.kt / ManagedService.kt (修改) 注入 ModeController/MediaProjectionHost、通知模式文案
core/server/src/main/java/com/managedfloating/core/server/
  ManagedServer.kt                    (修改)  /status.mode、/mode 端点
  McpServer.kt                        (修改)  status 增 mode、switch_mode 工具
  ServerConfig.kt                     (修改)  持久化 mode
app/src/main/AndroidManifest.xml      (修改)  FOREGROUND_SERVICE_MEDIA_PROJECTION + ProjectionService
app/src/main/res/xml/accessibility_service_config.xml (修改) canPerformGestures
docs/{API,MCP,Client}.md              (修改)  Mode B 契约文档（不含敏感信息）
```

## 4. 宪法校验

- C-1：所有操作/模式切换收敛到 `ScreenController`/`ModeController`，UI/MCP/REST/脚本不直连 shell ✓
- C-2/C-3：`DisplayMode`/`ModeController` 在 controller；`MediaProjectionHost`/`ProjectionService` 在 app（依赖 controller）；server 仅经 controller 暴露 ✓
- C-7：API<34 免 mediaProjection 前台类型；无授权/失效降级 Mode A ✓
- C-10：提交用 Conventional Commits 前缀 ✓
- C-12：敏感信息不入档 ✓

## 5. 顺序

1. 数据/枚举 + `ServerConfig.mode` 持久化
2. `ModeController` 状态机（守卫/往返）纯逻辑
3. manifest + `ProjectionService` + `MediaProjectionHost`（授权/建投影/释放）
4. `ProjectionFrameSource`(=FrameHub) 接入 `AppScreenController.frameSource()` 按 mode 返回
5. a11y 手势 + 操作分派（tap/swipe/key/text/launch 按 mode）
6. `status.mode` + `POST /mode` + MCP `switch_mode` + 通知模式文案 + UI「主屏」按钮/`FLAG_SECURE`
7. 文档（API/MCP/Client 增 Mode B）
8. 真机联调 → 依据实测推导缺失工具 → 补充规划与实现
