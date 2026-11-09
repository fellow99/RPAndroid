# 002-controller Technical Plan (As-Built / Design)

> Module: 002-controller
> Corresponding spec: [spec.md](./spec.md)
> 源码：`core/controller/`（现仅有 `ScreenController.kt` 接口占位）

## 1. Technical Context
### 1.1 Runtime Environment
- 主进程；持有 shell 连接与无障碍服务能力，对上层暴露统一接口。

### 1.2 Dependencies
- AndroidX core-ktx。
- 上层：core:shell（帧 + 注入）；后续触发器：AccessibilityService（读树）。

## 2. Constitution Compliance
- C-1 单一操作入口：✅（本模块即该入口的实现位置）。
- C-8 呈现无关控制：✅（操作不依赖呈现模式）。

## 3. Research Findings
- 参考工程：`core/uistate/`（`d:\GitHub\AI\Mika`，界面状态、元素编号、动作效果）+ `AndroidScreen`（typeText/tap/scroll/back）。
- 读树依据无障碍事件；自绘界面需 OCR 补充（后续里程碑）。
- 触摸回写：呈现层把触摸坐标换算成虚拟屏坐标，交给 ScreenController 注入。

## 4. Data Model
- 见 [overall-data-model.md](../overall-data-model.md)：ActionResult、ScreenElement、ManagedSession。

## 5. Interface Contracts
### 5.1 Provided
- `ScreenController`（定位：激活状态）。
- 计划接口：`captureFrame`、`readUi`、`tap`、`swipe`、`longPress`、`typeText`、`pressKey`、`back`、`launchApp`、`switchApp`。

### 5.2 Consumed
- `ShellController`（帧 + 注入）。
- 无障碍服务（读控件树）。

## 6. Implementation Strategy
### 6.1 Architecture Pattern
- 门面 + 串行执行器：写操作入队串行执行，保证一致性（FR-B-003）。

### 6.2 Key Algorithms
- 触摸→虚拟屏坐标换算（含缩放/偏移，供呈现层）。
- 界面元素快照（无障碍树 → ScreenElement 列表）。

### 6.3 Error Handling
- 越界/不可用 → 明确错误码；调用方据此降级。

### 6.4 Performance
- 读帧用最近帧缓存；`readUi` 仅在有变化时刷新。

## 7. Testing Considerations
- 真实机：读/写回环（controller 直连 shell 验证）。
- 单测：坐标换算、结果码映射。

## 8. File Inventory
| File | Purpose | Lines |
|------|---------|-------|
| `core/controller/src/main/java/com/managedfloating/core/controller/ScreenController.kt` | 接口占位 | ~20 |
