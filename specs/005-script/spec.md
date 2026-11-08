# 005-script Specification — 自动化服务任务系统

> Module: 005-script
> Status: Design（000-scaffold 提供接口占位 + 引入 Node 依赖）
> Last Updated: 000-scaffold
> 对应源码：`core/script/`

## 1. Module Overview
### 1.1 Purpose
提供**基于 JS 自动化脚本的自动化任务执行**：用户配置任务（目标 App、自动化脚本、启用、执行时间），系统按 **立即 / 间隔 / cron** 调度，通过宿主能力操作被托管 App。执行器为**前台 Service**，Node 跑在独立 `:node` 进程。

### 1.2 Problems Solved
- 让"跑稳定、可调度的自动化"不依赖 AI/外部系统。
- 通过 Node 引擎在应用内直接运行 JS 自动化脚本（非 WebView、非远程服务）。
- 通过独立进程隔离自动化脚本崩溃。

### 1.3 Scope
- 任务编排与调度（立即/间隔/cron）。
- JS 执行器（node-bridge + node-runtime，`:node` 进程）。
- 宿主能力（host.invoke）代理到 ScreenController。
- 前台 Service 生命周期与崩溃重启。

## 2. User Stories
- 作为用户，我可以新建/启停一个引用某 App 的自动化服务任务（FR-E-001/002）。
- 作为用户，我可以按 立即/间隔/cron 执行（FR-E-002）。
- 作为自动化脚本作者，我可以在 JS 里操作被托管 App（FR-E-003）。
- 作为系统，自动化脚本崩溃不拖垮应用并可重启（FR-E-004）。

## 3. Functional Requirements
- FR-E-001：系统 MUST 提供执行 JS 自动化脚本的前台 Service。
- FR-E-002：系统 MUST 支持 立即 / 间隔 / cron 三种执行方式，并尊重启用开关。
- FR-E-003：系统 MUST 提供宿主能力供自动化脚本操作被托管 App（host.invoke → ScreenController）。
- FR-E-004：系统 MUST 将 JS 执行器部署在独立 `:node` 进程（崩溃隔离 + 可重启）。

## 4. Key Entities
见 [overall-data-model.md](../overall-data-model.md)：Task、Schedule、ScriptRunRecord。

## 5. Acceptance Scenarios
### Scenario: 立即执行
- Given 任务已启用且配置了自动化脚本
- When 触发"立即执行"
- Then Node 进程运行自动化脚本，宿主能力可操作被托管 App，记录运行状态

### Scenario: cron
- Given 任务 cron 到点
- When 时间到达
- Then 唤醒并执行，跳过不可运行（如未启用）

### Scenario: 崩溃隔离
- Given JS 自动化脚本抛出致命错误
- When Node 进程崩溃
- Then 应用/调度 service 存活，可记录失败并按策略重启

## 6. Non-Functional Requirements
- 稳定性：独立进程隔离崩溃。
- 可观测：运行记录与日志可见。
- 调度健壮：进程被杀可由前台 Service + AlarmManager 恢复。

## 7. Assumptions & Constraints
- Node 引擎 arm64-only；`node::Start` 一次/进程且崩溃即终态。
- 宿主能力经 Binder 代理到主进程 ScreenController（跨进程往返有开销，操作频度可接受）。

## 8. Dependencies
- node-bridge 0.2.0（嵌入 API）、node-runtime 26.13.0（引擎）。
- core:controller（操作）。
