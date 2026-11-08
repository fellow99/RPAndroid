# 006-config-ui Technical Plan (As-Built / Design)

> Module: 006-config-ui
> Corresponding spec: [spec.md](./spec.md)
> 源码归属：`app/`（现在 `MainActivity.kt` 为纯 View 占位）

## 1. Technical Context
### 1.1 Runtime Environment
- 主进程；UI 在 MainActivity。
- 000-scaffold 用纯 View 占位以降低构建风险；后续里程碑接入 Compose。

### 1.2 Dependencies
- core:controller、core:script（数据源）。
- Compose（待接入，BOM 2025.06.01）。

## 2. Constitution Compliance
- C-2 单一职责：✅（UI 只做展示与编排）。
- C-9 规范文档为真相：✅（UI 呈现状态来自核心接口）。

## 3. Research Findings
- Compose 属目标栈；AGP9 内置 Kotlin 的 compose 集成需在接入时验证（plan 阶段留作验证项）。

## 4. Data Model
- 复用 overall-data-model 的 Task / Schedule / ScriptRunRecord / ServerStatus。

## 5. Interface Contracts
### 5.1 Consumed
- App 配置（默认呈现模式、端口、token 开关）。
- 任务/自动化脚本/调度能力（core:script）。
- 会话与状态（core:controller + core:shell）。

## 6. Implementation Strategy
### 6.1 Architecture Pattern
- Compose UI + ViewModel；状态来自核心模块 flow。

### 6.2 Key Algorithms
- 无复杂算法；主要是表单校验与列表展示。

### 6.3 Error Handling
- 表单校验提示；任务失败在日志视图显式展示。

## 7. Testing Considerations
- 手工/真机 UI 验收；任务 CRUD 与调度状态回显。

## 8. File Inventory
| File | Purpose | Lines |
|------|---------|-------|
| `app/src/main/java/com/managedfloating/app/MainActivity.kt` | 入口（纯 View 占位） | ~40 |
| `app/src/main/res/values/{strings,themes}.xml` | 基础资源 | — |
