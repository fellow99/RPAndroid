# REVIEW_REPORT — M4（MCP + MJPEG）

**范围：** M4（`e6b0a86`）
**结论：** MCP（streamable HTTP + SSE）与 MJPEG 视频流已实现并真机验证。

## Critical
- 无（构建期转义问题已在提交前修复）。

## Important（记录）
| # | 位置 | 发现 | 建议 |
|---|------|------|------|
| I-1 | `ManagedServer /stream` | 帧率固定 200ms；`respondOutputStream` 阻塞线程，多客户端会耗线程 | M5 改协程/限流 + 并发上限 |
| I-2 | `McpServer` | 无会话状态（SSE sessionId 仅保活，不与 JSON-RPC 绑定） | 完整 MCP 可实现 session 关联 |
| I-3 | 鉴权 | MCP/MJPEG 也走 token（除 /health、/debug/*）；SSE 客户端需带 header 或扩展 query token | 可加 `?token=` 兜底 |
| I-4 | `McpServer` | `ui.read`（无障碍元素）未提供 | M5 补（需 AccessibilityService） |

## 验证核对
- ✅ MCP initialize / tools/list / tools/call（screen_tap → ok=true、app_list）
- ✅ SSE endpoint 事件
- ✅ MJPEG 200 + multipart header + 持续推流（~453KB/3s）
- ✅ 接入文档 specs/004-server/integration.md
- ✅ 无敏感信息落盘（token/IP 用占位符）
