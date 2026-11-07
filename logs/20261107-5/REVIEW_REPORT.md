# REVIEW_REPORT.md — M2 + M3 Code Review

**日期：** 2026-11-07
**范围：** M2（`a19db3f`）、M3（`046615e`）
**方式：** requesting-code-review（orchestrator 自审）+ receiving-code-review（逐项核对）

## 结论
M2（三呈现模式/token/会话恢复）与 M3（Node `:node` 进程脚本 + 调度）已实现并在 Android 13 真机验证。

## Critical（已在本轮修复）
| # | 问题 | 修复 |
|---|------|------|
| C-1 | `Application.onCreate` 在 `:node` 进程也启动 Ktor → 端口冲突 BindException → 进程崩溃 | `ManagedRuntime.start` 仅主进程执行（`Application.getProcessName()==pkg`）（`046615e`） |

## Important（记录，未阻塞）
| # | 位置 | 发现 | 建议 |
|---|------|------|------|
| I-1 | `VdSessionImpl` | `sequence`/`frame` 依赖 50ms 轮询文件 IPC（每命令 ~1s） | M4/后续改 Binder 直连 |
| I-2 | `ScriptService` | `storeFile()` 用 `applicationInfo.dataDir` 拼路径，与主进程 TaskStore 需严格一致 | 统一由 `TaskStore.defaultFile(ctx)` 提供 |
| I-3 | `ScriptService` | 前台通知缺 `POST_NOTIFICATIONS` 请求（Android 13 通知可能不显示） | UI 首次进入请求权限 |
| I-4 | `VdSessionImpl` | 连接端口仍需外部提供（hidepid 限制） | 配对时同时取端口并缓存 |
| I-5 | 全体 | MIUI 后台杀 App → 会话靠 auto-resume 恢复（已实现） | 引导用户加省电白名单 |
| I-6 | `ScriptService` | `awaitResult` 未被 REST 使用（异步返回 ok，不返回执行结果） | 可扩展 /tasks/{id}/run 等待结果 |

## Minor
- M-1 `Cron` 未做单测（逻辑简单，建议补 JVM 测试）。
- M-2 `FloatingService` 帧率固定 400ms；可按需调整。
- M-3 `MainActivity` 纯 View 列表较长时滚动体验一般（后续 Compose）。

## 验证核对（receiving）
- ✅ M2：`/frame` 200 + PNG（99244B）；token 无头→401、有头→200、/health 开放；session auto-resume；三模式代码齐全（常规 ScreenActivity / 浮窗 FloatingService / headless）。
- ✅ M3：Node v26.13.0 在 `:node` 进程执行 JS；`host.invoke('screen.tap')`→ok；INTERVAL 8s 自动 tick；任务 CRUD/run；runCount/lastRunAt 更新。
- ✅ 无敏感信息落盘（token 仅测试用，未写入仓库）。

## 剩余里程碑
- **M4**：MCP(SSE) + MJPEG-over-HTTP 视频流 + adb reverse 接入文档。
- **M5**：前台服务常驻、端口自动发现、Compose UI、单元测试。
