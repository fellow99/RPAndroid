# 007-webrtc Specification — WebRTC 界面推流

> Requirement: 007-webrtc（WebRTC 界面推流）
> Status: Design（已批准方案 A）
> 对应源码：`core/webrtc/`（新增）、`core/controller/`、`core/server/`、`core/shell/`
> 关联设计：[design.md](./design.md)

## 1. Module Overview
### 1.1 Purpose
为**浏览器端操作者**提供低延迟（所见即所得）的虚拟屏实时画面推流，并支持在页面上直接点击/拖动操作被托管 App。
**Agent/MCP 侧不接入 WebRTC**，继续使用现有 REST/MCP 的快照通道。

### 1.2 Problems Solved
- 现有 MJPEG-over-HTTP 帧率低（约 5FPS）、延迟高（约 0.3~1s+），交互式操作反馈差。
- 浏览器侧需要一个与屏幕交互反馈几乎实时的观看+操作入口。

### 1.3 Scope
- **做**：
  - WebRTC 视频推流（H.264 硬件编码）到浏览器。
  - 浏览器反向输入：DataChannel → 坐标映射 → 虚拟屏注入（点击/滑动）。
  - WebSocket 信令 + 浏览器控制台页面。
  - 帧源重构（App 进程持有虚拟屏输出 Surface），与 MJPEG 共享帧。
- **不做**：Audio；TURN/公网穿透；agent/MCP 的 WebRTC 客户端；MJPEG 接口变更（全部保留）。

## 2. User Stories
- 作为**浏览器操作者**，我打开控制台页面即可低延迟观看虚拟屏画面，并在画面上直接点击/拖动操作被托管 App（US-W-001）。
- 作为**操作者**，画面与操作反馈接近实时（点击后尽快看到界面响应）（US-W-002）。
- 作为**开发者/降级通道**，现有 MJPEG `/stream` 与 `/frame` 接口继续可用（US-W-003）。

## 3. Functional Requirements
### 3.1 推流
- FR-W-001：系统 MUST 通过 WebRTC 向浏览器推送虚拟屏实时视频，编码为 H.264。
- FR-W-002：系统 MUST 在虚拟屏静态画面不变时持续输出有效帧（复用最近帧保活），避免画面冻结。
- FR-W-003：系统 MUST 支持同时连接多个浏览器 peer（本期上限 2），视频共享同一帧源。

### 3.2 反向输入
- FR-W-004：系统 MUST 通过 WebRTC DataChannel 接收浏览器的点击与滑动指令。
- FR-W-005：系统 MUST 将指令坐标按视频显示区域换算为虚拟屏坐标后注入被托管 App。
- FR-W-006：系统 MUST 的注入操作收敛到 ScreenController 单一入口（与 REST/MCP 一致）。

### 3.3 信令与控制台
- FR-W-007：系统 MUST 提供浏览器可访问的信令通道（WebSocket），完成 offer/answer/ICE 交换。
- FR-W-008：系统 MUST 提供控制台页面（HTTP），内嵌视频播放与输入事件发送能力。
- FR-W-009：控制台与信令 MUST 复用现有 token 鉴权机制（页面 `?token=`、WS 查询参数）。

### 3.4 兼容与降级
- FR-W-010：系统 MUST 保持现有 `GET /stream`（MJPEG）与 `GET /frame` 接口行为不变。
- FR-W-011：在 WebRTC 不可用（无会话/初始化失败）时 MUST 优雅降级（明确提示），不崩溃。

## 4. Success Criteria
- SC-1：浏览器控制台从打开到出现画面首帧 ≤ 3s（局域网）。
- SC-2：点击后到画面反映操作的端到端延迟 ≤ 500ms（局域网，交互优先度量）。
- SC-3：虚拟屏静态画面下画面不冻结（≥1 秒保持有效输出）。
- SC-4：页面点击/拖动能正确触发被托管 App 的对应操作（坐标换算正确）。
- SC-5：`/stream` 与 `/frame` 回归通过；任一场景异常均不崩溃。

## 5. Key Entities
| Entity | Description | Key Attributes |
|--------|-------------|----------------|
| FrameHub | App 进程共享帧源（ImageReader 最新帧 + PNG 缓存） | width, height, latestPng, 保活 |
| WebRtcPush | 每个浏览器 peer 的推流+输入通道 | peerId, videoTrack, dataChannel, 状态 |
| 控制台页面 | 浏览器操作台 | wsUrl, token, 视频元素, 输入映射 |

## 6. Acceptance Scenarios
### Scenario: 浏览器观看与操作
- Given 已建立托管会话（sessionActive=true）且配置了 token
- When 操作者在浏览器打开控制台页面并完成信令
- Then 页面显示虚拟屏实时画面；在画面上点击某处
- Then 被托管 App 收到对应点击并发生界面响应

### Scenario: MJPEG 并存
- Given 服务运行中
- When 调用 `GET /stream`（MJPEG）或 `GET /frame`
- Then 行为与 007 之前一致（回归）

## 7. Non-Functional Requirements
- 局域网直连（Wi-Fi / adb forward）；无公网，无 TURN。
- 延迟优先：交互反馈低延迟，其次画面流畅，资源占用最后。
- 敏感信息（测试 URL/账号/密码/凭证/密钥）不落盘文档与提交；文档一律占位符。

## 8. Assumptions & Constraints
- 使用 `org.webrtc:google-webrtc`（2021 稳定 AAR），仅 arm64-v8a（C-6）。
- 浏览器为现代 Chromium/WebKit 内核（支持标准 RTCPeerConnection + H.264）。
- 虚拟屏分辨率沿用 720×1560（构件可配置）。
- 复用 ScreenController 与现有 token 鉴权；不引入新的鉴权体系。

## 9. Dependencies
- 上游：`core:controller`（ScreenController + FrameHub）、`core:shell`（助手建屏/注入）。
- 为表内新增：`org.webrtc:google-webrtc`、Ktor WebSocket（server）。
