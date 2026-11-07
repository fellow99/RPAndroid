# 004-server Specification — 对外接口

> Module: 004-server
> Status: Design（000-scaffold 提供接口占位）
> Last Updated: 000-scaffold
> 对应源码：`core/server/`

## 1. Module Overview
### 1.1 Purpose
把 ScreenController 与任务调度能力开放给下游：**REST API、MCP server、实时画面**，支持局域网直连与 USB(adb reverse)。

### 1.2 Problems Solved
- 让智能体/外部系统无需触碰 App 内部即可读取与操作被托管 App。
- 提供标准化"工具集"（MCP）供 LLM agent 使用。

### 1.3 Scope
- REST API：列 App / 启动 / 点击 / 滑动 / 截图 / 读 UI / 任务。
- MCP server（SSE / streamable HTTP）：封装操作与调度为 agent 工具。
- 视频流：一期 MJPEG-over-HTTP，二期 WebRTC。
- 绑定本机端口；`adb reverse` 映射；局域网直连；可加 token（M4）。

## 2. User Stories
- 作为智能体，我可以通过 MCP 工具操作被托管 App 与调度任务（FR-D-002）。
- 作为外部系统，我可以通过 REST API 集成（FR-D-001）。
- 作为观看方，我可以获得实时画面（FR-D-003）。
- 作为开发，我可以经局域网直连 / adb reverse 接入（FR-D-004）。

## 3. Functional Requirements
- FR-D-001：系统 MUST 提供 REST API（列 App / 启动 / 点击 / 滑动 / 截图 / 读 UI / 任务调度）。
- FR-D-002：系统 MUST 提供 MCP server（SSE 或 streamable HTTP），把操作与调度封装为 agent 工具。
- FR-D-003：系统 MUST 提供实时画面：一期 MJPEG-over-HTTP；二期 SHOULD 增加 WebRTC。
- FR-D-004：系统 MUST 绑定本机端口，支持局域网直连与 USB adb reverse 映射。

## 4. Key Entities
| Entity | Description | Key Attributes |
|--------|-------------|----------------|
| ServerConfig | 服务配置 | host, port, token, enabled |
| RestAction | REST 操作参数 | 见各端点 |

## 5. Acceptance Scenarios
### Scenario: REST 截图
- Given 服务运行
- When 外部调用截图端点
- Then 返回被托管 App 当前帧

### Scenario: MCP 工具
- Given 服务运行
- When 智能体经 MCP 调用"点击"工具
- Then 操作作用于被托管 App 并返回结果

## 6. Non-Functional Requirements
- 本地端口默认仅本机/局域网；可加 token 鉴权。
- MJPEG 满足局域网实时（低延迟）。
- 无公网需求。

## 7. Assumptions & Constraints
- 网络模型：局域网 Wi-Fi 为主 + USB adb reverse 兼容；无公网。
- token 鉴权为 M4 加固项。

## 8. Dependencies
- 上游：core:controller（操作）；调度（core:script）。
