# 002-controller Specification — 操作入口

> Module: 002-controller
> Status: Design（000-scaffold 提供接口占位）
> Last Updated: 000-scaffold
> 对应源码：`core/controller/`

## 1. Module Overview
### 1.1 Purpose
提供**读取与操作被托管 App 的统一入口 ScreenController**，作为用户触摸、REST、MCP、脚本共用的唯一写入口，保证"看到/操作的永远是同一个 App 实例"。

### 1.2 Problems Solved
- 避免各调用方直连 shell 造成的语义分叉与竞态。
- 把"读画面/读界面"与"写操作"收敛为单一稳定接口。

### 1.3 Scope
- 读：单帧截图、视频帧源、界面元素列表（无障碍控件树）。
- 写：点击/滑动/长按/输入/按键/返回/启动/切换。
- 不含：呈现渲染（003）、对外协议（004）、脚本编排（005）。

## 2. User Stories
- 作为调用方，我可以读取被托管 App 的当前帧/截图（FR-B-001）。
- 作为调用方，我可以读取其界面元素列表以了解状态（FR-B-001）。
- 作为调用方，我可以点击/滑动/输入/按键/返回/切换（FR-B-002）。

## 3. Functional Requirements
- FR-B-001：系统 MUST 提供"读"能力：单帧截图、实时帧源、界面元素列表（经无障碍控件树）。
- FR-B-002：系统 MUST 提供"写"能力：点击、滑动、长按、输入、按键、返回、启动、切换。
- FR-B-003：系统 MUST 作为用户触摸、REST、MCP、脚本共用的唯一写入口。
- FR-B-004：系统 SHOULD 对写操作做坐标校验（越界返回 `out_of_bounds` 等明确语义）。

## 4. Key Entities
| Entity | Description | Key Attributes |
|--------|-------------|----------------|
| FrameDocument | 一帧画面 | bitmap, width, height, timestamp |
| ScreenElement | 界面元素 | id, bounds, text, class, clickable |
| Action | 写操作 | type, params, result |

## 5. Acceptance Scenarios
### Scenario: 读界面
- Given 会话运行中
- When 调用方请求读 UI / 截图
- Then 返回元素列表 / 帧

### Scenario: 写操作
- Given 会话运行中
- When 调用方提交点击
- Then 动作注入虚拟屏并返回结果码

## 6. Non-Functional Requirements
- 一致性：写操作串行化，避免竞态。
- 可用性：读/写失败返回明确错误码。

## 7. Assumptions & Constraints
- 依赖 shell 层提供的帧与注入能力（001）。
- 无障碍读树受 App 自绘影响，必要时结合 OCR（后续）。

## 8. Dependencies
- 上游：core:shell。供 app / core:server / core:script 使用。
