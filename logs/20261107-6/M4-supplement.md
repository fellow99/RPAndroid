# M4 补充任务（评审 Important 项）验证

| # | 项目 | 实现 | 真机验证 |
|---|------|------|---------|
| I-1 | MJPEG 并发限流 | `MAX_STREAM_CLIENTS=8` + AtomicInteger，超限 503 | 代码就位（并发实测受环境限制） |
| I-2 | SSE session 与 JSON-RPC 关联 | sessionId→Channel；POST ?sessionId= 返回 202，响应经 SSE `event: message` 推回 | ✅ `sid=16fa...` POST=202，SSE 收到 `{"jsonrpc":"2.0","id":9,"result":{}}` |
| I-3 | `?token=` 兜底 | 鉴权同时读 header 与 query | ✅ no-token=401，query-token=200 |
| I-4 | `ui_read` / `GET /ui` | 新增 AccessibilityService 读控件树 + REST + MCP 工具 | ✅ 200/8401B 元素；⚠️ 仅默认 display（ROM 限制，见 integration.md §5） |

## 已知限制（I-4）
MIUI/Android 13 的 `AccessibilityService.windows` 不含自建虚拟屏（App 在前台/后台均只读到 display 0）。
虚拟屏内容可先用 `screen_capture` 截图；后续可探索 shell 侧定向读取。
