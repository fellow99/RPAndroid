# 006-config-ui Specification — 配置界面

> Module: 006-config-ui
> Status: Design（归属于 app 模块）
> Last Updated: 000-scaffold

## 1. Module Overview
### 1.1 Purpose
提供本应用自身的配置与运维界面：选择被托管 App、配置任务与自动化脚本、切换呈现模式、查看状态与日志。

### 1.2 Problems Solved
- 让用户能自己配置、调度、监控托管与自动化脚本，而不必依赖任何外部系统。

### 1.3 Scope
- 被托管 App 选择。
- 任务 CRUD（App、自动化脚本、开关、执行时间：立即/间隔/cron）。
- 自动化脚本管理（编辑/上传）。
- 呈现模式切换。
- 状态与日志查看。

## 2. User Stories
- 作为用户，我可以选择要托管的 App（FR-F-001）。
- 作为用户，我可以创建/编辑/启停任务并设置 立即/间隔/cron（FR-F-001）。
- 作为用户，我可以管理自动化脚本（FR-F-001）。
- 作为用户，我可以查看运行状态与日志（FR-F-002）。

## 3. Functional Requirements
- FR-F-001：系统 MUST 提供 UI 选择被托管 App、配置任务、开关、执行时间与自动化脚本。
- FR-F-002：系统 MUST 提供状态与日志查看。

## 4. Key Entities
- 复用 overall-data-model 的 Task / Schedule / ScriptRunRecord。

## 5. Acceptance Scenarios
### Scenario: 配置并启停任务
- Given 用户打开配置界面
- When 用户选择 App、填写自动化脚本、设 cron、打开启用
- Then 任务按调度执行，界面可启停/查看日志

## 6. Non-Functional Requirements
- 易用、清晰；状态实时反映调度/Node/shell 助手状态。

## 7. Assumptions & Constraints
- 000-scaffold 为纯 View 占位；后续里程碑接入 Compose。

## 8. Dependencies
- core:controller（选择 App / 状态）、core:script（任务/自动化脚本/调度）。
