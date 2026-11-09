# 003-presentation Technical Plan (As-Built / Design)

> Module: 003-presentation
> Corresponding spec: [spec.md](./spec.md)
> 源码归属：`app/`（呈现相关）

## 1. Technical Context
### 1.1 Runtime Environment
- 主进程；浮窗模式经 `SYSTEM_ALERT_WINDOW` 覆盖层，常规模式经 App 内 Activity。

### 1.2 Dependencies
- core:controller（帧 + 操作）。
- 后续：Compose（`SurfaceView`/`AndroidView` 承载帧）、`WindowManager` 浮窗 API。

## 2. Constitution Compliance
- C-8 呈现无关控制：✅（操作逻辑在 controller，本模块只渲染 + 触摸换算）。
- C-7 优雅降级：✅（低版本可回退 headless/全屏）。

## 3. Research Findings
- 参考工程 `AgentDisplay`/`DisplayFrame`（`d:\GitHub\AI\Mika`）：单帧转正方向、短边限 360 预览。
- 浮窗参考工程无障碍浮层（`OverlayHost`），但本工程首选 `SYSTEM_ALERT_WINDOW`。

## 4. Data Model
- PresentationMode 枚举：Floating / Regular / Headless。

## 5. Interface Contracts
### 5.1 Provided
- `PresentationManager`（计划）：`setMode(mode)`、`attachSurface(surface)`、`onTouch(x,y)`。
### 5.2 Consumed
- `ScreenController.captureFrame` / `ScreenController.tap`（触摸回写）。

## 6. Implementation Strategy
### 6.1 Architecture Pattern
- 单一会话 + 可插拔渲染宿主：三种模式只是把同一 Surface 挂到不同视图/流。

### 6.2 Key Algorithms
- 触摸坐标换算：呈现视图尺寸 → 虚拟屏尺寸（含缩放与锚点）。

### 6.3 Error Handling
- 浮窗权限未授予 → 提示并回退常规/headless。

### 6.4 Performance
- 复用最近帧；浮窗低帧率渲染以省电。

## 7. Testing Considerations
- 真机：三模式切换、触摸回写准确性、模式切换任务不中断。

## 8. File Inventory
- 000-scaffold 无独立文件（呈现逻辑待 M2 在 `app/` 实现）。
