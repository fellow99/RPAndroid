# 004-server Technical Plan (As-Built / Design)

> Module: 004-server
> Corresponding spec: [spec.md](./spec.md)
> 源码：`core/server/`（现仅有 `ManagedServer.kt` 接口占位）

## 1. Technical Context
### 1.1 Runtime Environment
- 主进程；Ktor 内嵌 HTTP 服务监听本机端口。

### 1.2 Dependencies
- Ktor（待接入）；MCP SDK（待定，SSE / streamable HTTP）。
- core:controller（操作）、core:script（调度）。
- 序列化：kotlinx.serialization / Jackson（待定）。

## 2. Constitution Compliance
- C-1 单入口：✅（server 复用 ScreenController，不直连 shell）。
- C-12 安全底线：✅（端口鉴权 + 敏感信息不入档）。

## 3. Research Findings
- MCP over SSE 适合长连工具调用；adb reverse 把手机本机端口映射到电脑 localhost，便于开发。
- MJPEG-over-HTTP 局域网极简；WebRTC 二期引入 `org.webrtc:google-webrtc`。

## 4. Data Model
- ServerConfig：{ host, port, token?, enabled }。

## 5. Interface Contracts
### 5.1 Provided
- `ManagedServer`（定位：start/stop）。
- 计划 REST 端点：`GET /apps`、`POST /session/launch`、`POST /action/*`、`GET /frame`、`GET /ui`、任务 CRUD。
- MCP 工具名：`app.list`、`app.launch`、`action.tap/swipe/type/back`、`screen.capture`、`ui.read`、`task.*`。
- 视频：`GET /mjpeg`。

### 5.2 Consumed
- `ScreenController`、调度服务（core:script）。

## 6. Implementation Strategy
### 6.1 Architecture Pattern
- Ktor 路由层 → 用例层 → ScreenController/调度。

### 6.2 Key Algorithms
- MJPEG multipart 分帧；坐标透传（不做换算，由调用方给虚拟屏坐标——或由 server 提供换算调用，待定）。

### 6.3 Error Handling
- 统一错误模型（ok/code/detail），映射 HTTP 状态。

### 6.4 Performance
- 帧缓存 + MJPEG 限帧率。

## 7. Testing Considerations
- curl 回归各端点（localhost / adb reverse）；MCP 客户端冒烟。见 [overall-test-cases.md](../overall-test-cases.md)。

## 8. File Inventory
| File | Purpose | Lines |
|------|---------|-------|
| `core/server/src/main/java/com/managedfloating/core/server/ManagedServer.kt` | 接口占位 | ~20 |
