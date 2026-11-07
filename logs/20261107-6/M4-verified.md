# M4 对外协议 —— 真机验证通过（Android 13）

## MCP（Streamable HTTP）
```
initialize  → {"result":{"protocolVersion":"2024-11-05","capabilities":{"tools":{}},"serverInfo":{...}}}
tools/list  → 6 tools (screen_capture/screen_tap/screen_swipe/app_list/app_launch/task_*)
tools/call screen_tap {x:360,y:500} → {"content":[{"type":"text","text":"ok=true"}]}   ← 真实操作虚拟屏
tools/call app_list                 → App 列表 JSON
```

## MCP SSE
```
GET /mcp/sse →
event: endpoint
data: /mcp?sessionId=<uuid>

: ping
```

## MJPEG 视频流
```
GET /stream → 200, Content-Type: multipart/x-mixed-replace; boundary=frame
3 秒采样 ~453KB（多帧持续推流）
```

## 接入文档
`specs/004-server/integration.md`（REST/MCP/MJPEG + adb forward/reverse + token）。
