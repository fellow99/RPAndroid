# 003-presentation Specification — 呈现模式

> Module: 003-presentation
> Status: Design（归属于 app 模块）
> Last Updated: 000-scaffold

## 1. Module Overview
### 1.1 Purpose
为同一"被托管 App 会话"提供多种观看/操作形态：**浮窗 / 常规布局 / headless**。

### 1.2 Problems Solved
- 让用户既能浮窗边看边操作、又能全屏常规使用、还能完全不渲染只走接口（headless）。
- 模式切换不中断正在运行的会话与任务。

### 1.3 Scope
- 浮窗渲染（SYSTEM_ALERT_WINDOW + SurfaceView）。
- 常规布局（App 内 Activity/SurfaceView 全屏或分屏）。
- headless（不渲染，仅截帧 + 流 + 控制）。
- 触摸回写：把呈现画面上的触摸换算成虚拟屏坐标交给 controller。

## 2. User Stories
- 作为用户，我可以用浮窗边操作其它 App 边托管被托管 App（FR-C-001）。
- 作为用户，我可以切换到常规布局全屏操作（FR-C-001）。
- 作为系统/外部调用方，我可以只走 headless 模式（FR-C-001）。
- 作为用户，我可以在模式间切换且不中断任务（FR-C-002）。
- 作为用户，我触摸呈现画面即可操作被托管 App（FR-C-003）。

## 3. Functional Requirements
- FR-C-001：系统 MUST 支持浮窗 / 常规布局 / headless 三种呈现模式。
- FR-C-002：系统 MUST 支持模式间切换且不中断运行任务/脚本。
- FR-C-003：用户触摸呈现画面时，系统 MUST 将触摸换算为虚拟屏坐标并回写操作。

## 4. Key Entities
| Entity | Description | Key Attributes |
|--------|-------------|----------------|
| PresentationMode | 呈现模式枚举 | Floating / Regular / Headless |
| SurfaceViewHost | 承载虚拟屏帧的视图 | surface, transform, interactive |

## 5. Acceptance Scenarios
### Scenario: 浮窗
- Given 会话运行中
- When 用户选择浮窗模式并触摸画面
- Then 出现可拖动浮窗，触摸被回写到被托管 App

### Scenario: 无中断切换
- Given 会话 + 脚本任务运行中
- When 用户从浮窗切到 headless
- Then 任务继续运行，仅停止画面渲染

## 6. Non-Functional Requirements
- 触摸延迟低（局域网级）。
- 模式切换连贯，无重建会话。

## 7. Assumptions & Constraints
- 浮窗需要 `SYSTEM_ALERT_WINDOW` 权限（后续申请）。
- headless 不含任何渲染。

## 8. Dependencies
- 上游：core:controller（帧 + 操作）。
