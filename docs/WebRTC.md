# WebRTC 界面推流接入文档

> 面向第三方：低延迟查看虚拟屏画面并在页面上直接操作被托管 App。
> 相关文档：[REST API](./API.md) ｜ [MCP 接入](./MCP.md) ｜ [MJPEG 视频流](./MJPEG.md)
> 需求：`specs/007-webrtc/`

## 1. 定位

- **WebRTC / 浏览器控制台**：负责"低延迟观看 + 页面直接操作"（交互优先）。
- **REST/MCP**：agent/自动化继续用快照 + 操作接口，**不**走 WebRTC。
- **MJPEG**：全部保留，作为降级/调试通道。

## 2. 浏览器控制台（观看 + 操作）

```
GET http://<手机IP>:8099/webrtc?token=<token>
```

- 页面内 OWebRTC 拉流（H.264），并显示虚拟屏实时画面。
- 在画面上点击/拖动 = 在虚拟屏上点击/滑动（坐标自动换算，含 letterbox）。
- 快捷键：`Backspace`=返回 ｜ `Enter`=确认 ｜ `Space`=空格 ｜ `Home`=主页。
- 并发浏览器连接上限 **2**（`MAX_PEERS`，避免多路硬件编码器撑爆设备）。

### 信令（调试/自建客户端时）
```
WS  ws://<手机IP>:8099/webrtc/signaling?token=<token>
```
App 为 offerer：连接即推送 `{type:"offer", sdp}`；客户端回 `{type:"answer", sdp}` 与
`{type:"ice", sdpMid, sdpMLineIndex, candidate}`；服务端亦回推本地 `ice` 与 `state`。

### 输入协议（DataChannel `"input"`，归一化坐标 0..1）
```json
{"t":"tap","nx":0.52,"ny":0.31}
{"t":"swipe","nx1":0.1,"ny1":0.5,"nx2":0.9,"ny2":0.5,"durMs":300}
{"t":"key","code":4}
```

## 3. 鉴权

沿用现有 token 三通道：请求头 `Authorization: Bearer` / `X-Auth-Token` / 查询参数 `token`。
页面与 WS 信令都建议带 `?token=<token>`；未携带/错误返回 `401` 或直接关闭连接。

## 4. 与 MJPEG 并存

- 视频：WebRTC 为主，延迟低；`GET /stream`（MJPEG）保留作降级调试。
- 单帧快照：`GET /frame` 依旧可用（页面/agent 通用）。

## 5. 限制与说明

- 仅**局域网**（Wi-Fi 同网段 / USB adb forward 到电脑 localhost），无公网、无 TURN。
- 浏览器对 HTTP 非安全上下文的 WebRTC 限制：若受限，可用 USB `adb forward` 走 localhost，
  或需求方自签 TLS 后访问。
- 目标帧率约 15FPS（按帧源产出自适应；静态画面低频保活）。
- 依赖 `org.webrtc:google-webrtc`（2021 稳定版），仅 arm64-v8a。
- 敏感信息（真实 IP/token/账号/口令）不写入本文档与仓库，示例一律占位符。
