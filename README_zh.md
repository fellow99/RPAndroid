# RP自动化（RPAuto）

> **免 Root 的 Android App 托管与自动化平台** — 以 [MIT License](LICENSE) 开源。

RP自动化 在你的 Android 设备上创建一块**虚拟屏（VirtualDisplay）**（无需 root），承载并控制第三方
App，并通过 REST、MCP 与视频流（MJPEG / WebRTC）对外提供**屏幕查看、输入注入、应用承载和自动化服务**。
仅面向**局域网或 USB**，不提供公网/跨网服务。

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![Platform: Android](https://img.shields.io/badge/Platform-Android-3DDC84.svg)]()
[![API: 24+](https://img.shields.io/badge/API-24+-blue.svg)]()
[![arch: arm64](https://img.shields.io/badge/arch-arm64--v8a-lightgrey.svg)]()

## 核心能力

- **免 root**：利用无线调试配对获得的 shell 上下文创建 `trusted VirtualDisplay` 并注入输入。
- **双承载模式**
  - **Mode A（虚拟屏承载，默认）**：被托管 App 运行在私有虚拟屏上。
  - **Mode B（主屏承载）**：经 MediaProjection 将 App 前置到主屏，解锁结构化读屏与稳定手势
    （对部分 ROM 虚拟屏无障碍读树不可用的情况，如 MIUI）。
- **承载并操控任意 App**：截图、点击、滑动、按键注入、文本输入（含中文）、读取界面
  （扁平树 / 层级树 / XPath / resourceId）。
- **多种对外接口**：REST API、MCP server（Streamable HTTP + SSE）、MJPEG 视频流、WebRTC 控制台、
  以及浏览器播控客户端（`/client`）。
- **自动化服务**：独立 `:node` 前台 Service，内置 Node 引擎（v26, arm64）。编写 JS 脚本，
  支持**立即 / 间隔 / 固定时间 / cron** 执行。
- **Token 鉴权**：`Authorization: Bearer`、`X-Auth-Token` 或 `?token=` 三通道。

## 接入文档

| 文档 | 内容 |
|------|------|
| [docs/API.md](docs/API.md) | 网络接入、鉴权、会话前置、REST 端点、任务 API、界面读取 |
| [docs/MCP.md](docs/MCP.md) | MCP Streamable HTTP / SSE、JSON-RPC 方法、工具清单与调用示例 |
| [docs/Client.md](docs/Client.md) | 浏览器播控/控制客户端（`/client`） |
| [docs/WebRTC.md](docs/WebRTC.md) | 低延迟 WebRTC 控制台（`/webrtc`） |
| [docs/MJPEG.md](docs/MJPEG.md) | MJPEG 视频流端点、鉴权、各播放器接入与并发限制 |
| [docs/TASK.md](docs/TASK.md) | 自动化任务完整 JS 脚本接口（`host.invoke`） |

## 快速开始

```bash
# 1) USB 端口转发（开发推荐）
adb forward tcp:8099 tcp:8099

# 2) 健康检查（无需 token）
curl http://localhost:8099/health

# 3) 建立托管会话（首次需先完成一次无线调试配对）
curl -X POST http://localhost:8099/debug/session/start \
  -H 'Content-Type: application/json' \
  -d '{"connectPort":<adbd端口>}'

# 4) 查看状态
curl -H "Authorization: Bearer <token>" http://localhost:8099/status

# 5) 承载 App 并截图
curl -X POST http://localhost:8099/session/launch \
  -H "Authorization: Bearer <token>" -H 'Content-Type: application/json' \
  -d '{"pkg":"com.android.settings"}'
curl -H "Authorization: Bearer <token>" http://localhost:8099/frame -o screen.png
```

> **首次使用**：在设备「开发者选项 → 无线调试」中开启，用配对码**配对一次**，然后建立会话；
> App 重启后会用缓存的 connectPort 自动恢复会话。
>
> **不要使用 `adb reverse`**：它会让 adbd 占用手机侧端口，导致 App 绑定失败（BindException）。

### LLM / Agent 接入（MCP）

在 MCP 客户端中加入：

```json
{
  "mcpServers": {
    "RPAuto": {
      "url": "http://192.168.x.x:8099/mcp",
      "headers": { "Authorization": "Bearer <token>" }
    }
  }
}
```

共暴露 23 个工具（`tools/list` 实时为准），如 `screen_capture`、`screen_tap`、`ui_query`（XPath）、
`app_launch`、`task_create`、`task_run`、`logs`。让大模型生成脚本前，建议先调用 `task_docs`
获取权威签名与完整示例。

### 自动化脚本示例

```js
await host.invoke('screen.launch', { pkg: 'com.android.settings' });
await new Promise(r => setTimeout(r, 2000));
const info = await host.invoke('screen.info', {});
await host.invoke('screen.swipe', {
  x1: info.w / 2, y1: info.h * 0.75,
  x2: info.w / 2, y2: info.h * 0.25, durMs: 500
});
const f = await host.invoke('screen.frame', {});
await host.invoke('log', { msg: 'captured ok=' + f.ok });
```

## 工程模块

| 模块 | 职责 |
|------|------|
| `app` | 配置 UI、呈现模式、自动化服务、运行时装配 |
| `core/shell` | 免 root ADB 客户端、shell 助手、虚拟屏与输入注入 |
| `core/controller` | `ScreenController` 统一写入口（托管会话、屏幕控制） |
| `core/server` | Ktor REST、MCP、MJPEG、WebRTC、播控客户端、统一日志 |
| `core/script` | 自动化任务模型、cron 解析、Node bridge |
| `core/webrtc` | WebRTC 推流 / 信令 |

所有对外模块与呈现模式都经过同一个 `ScreenController`，因此用户触摸、REST、MCP 与自动化脚本
操作的是**同一实例**（一致性强）。

## 构建

```bash
./gradlew :app:assembleDebug
```

- minSdk 24，compileSdk 36，**仅 arm64-v8a**。
- 签名密钥**不入库**：将 `keystore.properties.sample` 复制为 `keystore.properties` 并配置你自己的
  密钥（见 `docs/release-signing.md`）。

## 约束与说明

- 仅局域网（同一 Wi-Fi）或 USB `adb forward`——**无公网、无 TURN/中继**。
- 自动化服务运行在独立 `:node` 进程；设备重启后不自动启动，需打开 App 或由闹钟/接口触发。
- Mode A 下部分 ROM（如 MIUI）虚拟屏无障碍读树不可用——可回退 `screen.frame` + 坐标，或切换
  Mode B 进行结构化读屏。
- 真实 token、设备 IP、凭据等敏感信息一律不提交本仓库。

## 开源与许可

本工程以 [MIT License](LICENSE) 开源。你可以自由地使用、修改、分发，甚至用于商业用途，
只需保留原始版权与许可声明。

> 请在你有权控制的设备上、并遵守所在地法律法规的前提下使用。作者不对任何滥用行为负责。
