# 001-shell Specification — 托管基座

> Module: 001-shell
> Status: Design（000-scaffold 提供接口占位）
> Last Updated: 000-scaffold
> 对应源码：`core/shell/`

## 1. Module Overview

### 1.1 Purpose
提供"被托管 App 能跑在自有虚拟屏上并被注入输入"的**免 root 托管基座**——这是整个系统可行性的根基（普通 App 没有 `CREATE_VIRTUAL_DISPLAY` / `INJECT_EVENTS` 权限）。

### 1.2 Problems Solved
- 普通应用无法在自己窗口内承载并操作别的 App。
- 通过 shell 身份（无线调试配对或 root）创建虚拟屏、挪入目标 App、注入输入，从平台层面解决。

### 1.3 Scope
- 建虚拟屏 / 启动与挪入 App / 输入注入 / 帧出口 / 方向与焦点处理。
- 不含：操作语义（属 controller）、对外协议（属 server）、脚本（属 script）。

## 2. User Stories
- 作为系统，我可以创建一块自有虚拟屏并承载指定 App（FR-A-001）。
- 作为系统，我可以向虚拟屏注入点击/滑动/按键（FR-A-002）。
- 作为上层，我可以持续取得虚拟屏的帧（FR-A-003）。
- 作为用户，我可以选择免 root（无线调试配对）或 root（可选加速）两条路径（FR-A-004）。

## 3. Functional Requirements
### 3.1 生命周期与启动
- FR-A-001：系统 MUST 能创建一块可承载任意目标 App 的虚拟屏（VirtualDisplay）。
- FR-A-004：系统 MUST 支持经无线调试配对启动 shell 助手（shell UID）；SHOULD 支持 root `su` 直连作为可选加速。
- FR-A-005：系统 MUST 在 Android 14+ 提供完整焦点与方向处理；SHOULD 在低版本优雅降级。

### 3.2 承载与操作
- FR-A-002：系统 MUST 能向虚拟屏注入输入（点击/滑动/按键）。
- FR-A-003：系统 MUST 能把虚拟屏的帧（Surface）提供给 app 进程直接读取。
- FR-A-006：系统 MUST 能把目标 App 启动/挪入虚拟屏（startActivity --display / WindowManager）。

### 3.3 回收与健壮
- FR-A-007：系统 SHOULD 在会话结束/取消配对时释放虚拟屏与会话相关资源。
- FR-A-008：助手进程崩溃/断开 SHOULD 可检测并可恢复。

## 4. Key Entities
| Entity | Description | Key Attributes |
|--------|-------------|----------------|
| VirtualDisplay | 承载目标 App 的虚拟屏 | displayId, width, height, state |
| ShellSession | 与 shell 助手的连接会话 | ready, identity, transport |
| InjectedInput | 一次注入动作 | type, coordinates, duration |

## 5. Acceptance Scenarios
### Scenario: 建屏并承载 App
- Given 无线调试已配对 / root 可用
- When 上层请求创建会话并启动某 App
- Then 创建虚拟屏、App 出现在该屏且帧可达上层

### Scenario: 输入注入
- Given 会话正在运行
- When 上层提交一次点击坐标
- Then 该点击作用于虚拟屏上的目标 App

## 6. Non-Functional Requirements
- 稳定性：断开/崩溃可恢复，不拖垮主进程。
- 兼容：版本间能力门控，失败降级。

## 7. Assumptions & Constraints
- 依赖平台 shell 级权限（无线调试配对或 root）。
- 焦点行为与 Android 版本强相关（<12 降级、12–13 提示、14+ 完整）。

## 8. Dependencies
- 无上游；供 controller（core:controller）使用。
