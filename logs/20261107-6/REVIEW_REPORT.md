# REVIEW_REPORT — M4（含补充任务）

**范围：** M4 全部（`e6b0a86`、`ca4d0cc`）
**结论：** MCP（streamable HTTP + SSE + session 关联）、MJPEG（限流）、`?token=`、`ui_read` 全部实现并验证。

## Critical
- 无。

## Important —— 本轮全部关闭
| # | 项目 | 状态 |
|---|------|------|
| I-1 | MJPEG 并发限流 | ✅ 已实现（8 路，超限 503） |
| I-2 | SSE session 与 JSON-RPC 关联 | ✅ 已实现并验证（202 + SSE message 回推） |
| I-3 | `?token=` 兜底 | ✅ 已实现并验证（401/200） |
| I-4 | `ui_read` / `GET /ui` | ✅ 已实现（AccessibilityService）；⚠️ ROM 限制：仅默认 display |

## 遗留（转入 M5）
| # | 发现 | 建议 |
|---|------|------|
| R-1 | 无障碍 `windows` 不含虚拟屏（MIUI/Android 13 实测） | M5：shell 侧定向读取（uiautomator/反射）或换 ROM 验证 |
| R-2 | MJPEG 用阻塞 IO + Thread.sleep，每路占线程 | M5：改协程 `writeFully` + 背压 |
| R-3 | MCP 未实现 resources/prompts（仅 tools） | 视需要 |
| R-4 | 前台服务通知在 Android 13 需 POST_NOTIFICATIONS | M5 加权限请求 |

## 验证核对
- ✅ MCP initialize / tools/list / tools/call（screen_tap ok、app_list、ui_read）
- ✅ SSE：endpoint 事件 + POST 202 + `event: message` 回推
- ✅ token：header 与 query 均 401/200 正确；/health 开放
- ✅ MJPEG：200 + multipart 头 + 持续帧
- ✅ /ui：200 + 元素 JSON（默认 display）
- ✅ 无敏感信息落盘
