# ManagedFloatingApp

在 Android 设备上创建自有虚拟屏，承载并控制第三方 App，通过 REST、MCP 与 MJPEG 对外提供屏幕查看、输入注入、应用承载和自动化服务。仅面向局域网或 USB，不提供公网服务。

## 核心能力

- 免 root 借助 shell 上下文创建 trusted VirtualDisplay
- 在虚拟屏承载第三方 App，支持截图、点击、滑动、界面读取
- 提供 REST API、MCP（Streamable HTTP + SSE）和 MJPEG 视频流
- 自动化服务：独立 `:node` 前台 Service，内置 Node v26.13.0，支持立即/间隔/cron 执行 JS 任务
- Token 鉴权（`Authorization: Bearer`、`X-Auth-Token` 或 `?token=`）

## 第三方接入文档

| 文档 | 内容 |
|------|------|
| [docs/API.md](docs/API.md) | 网络接入、鉴权、会话前置、REST 端点、任务 API、界面读取 |
| [docs/MCP.md](docs/MCP.md) | MCP Streamable HTTP / SSE、JSON-RPC 方法、9 个工具与调用示例 |
| [docs/MJPEG.md](docs/MJPEG.md) | 视频流端点、鉴权、浏览器/VLC/ffplay/HTML 接入、并发限制 |

## 快速开始

```bash
# USB 端口转发
adb forward tcp:8099 tcp:8099

# 健康检查（无需 token）
curl http://localhost:8099/health

# 建立托管会话
curl -X POST http://localhost:8099/debug/session/start \
  -H 'Content-Type: application/json' \
  -d '{"connectPort":<adbd端口>}'

# 查看运行状态
curl -H "Authorization: Bearer <token>" http://localhost:8099/status
```

## 工程模块

| 模块 | 职责 |
|------|------|
| `app` | 配置 UI、呈现模式、自动化服务、运行时装配 |
| `core/shell` | 免 root ADB 客户端、shell 助手、虚拟屏与输入注入 |
| `core/controller` | 托管会话、屏幕控制、文件 IPC |
| `core/server` | Ktor REST、MCP、MJPEG、统一日志 |
| `core/script` | 自动化任务模型、cron 解析、Node bridge 依赖 |

## 约束

- minSdk 24，compileSdk 36，仅 `arm64-v8a`
- 自动化服务运行在独立 `:node` 进程；设备重启后不自动启动，需打开 App 或由闹钟/接口触发
- 文档与示例不提交真实 token、设备 IP 等敏感信息
