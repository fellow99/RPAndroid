# REVIEW_REPORT.md — M1（托管 POC）Code Review — 最终

**日期：** 2026-11-07
**范围：** M1-1..M1-4 全部（至 `a033eea`）
**方式：** requesting-code-review（orchestrator 自审 diff）+ receiving-code-review（逐项核对）

## 结论
**M1 全部完成并在 Android 13 真机端到端验证**：App 免 root 完全自主完成 配对→TLS 连接 adbd→pty 拉起常驻助手→建可信虚拟屏→承载 App→REST 截图/操作。

## Critical
- 无。

## Important（记录，后续里程碑处理）
| # | 位置 | 发现 | 计划 |
|---|------|------|------|
| I-1 | `ManagedServer` | 服务绑定 0.0.0.0、无鉴权（局域网可访问） | M4 前加 token；建议尽快 |
| I-2 | `VdSessionImpl` | 连接端口需外部提供（`/proc/net/tcp` 在 App 内受 hidepid 不可见，mDNS/扫描未命中） | 后续：配对时一并获取连接端口并缓存；或让助手回报端口 |
| I-3 | `ManagedRuntime` | 服务器跑在 Application 线程 + 普通服务，Android 13+ 后台可能被杀 | 升前台服务（M3） |
| I-4 | `VdSessionImpl` | 文件 IPC 每命令轮询 50ms、无并发保护 | M2 改 Binder 或加锁/队列 |

## Minor
- M-1 `VdHelper` 依赖 App base.apk 作 CLASSPATH；App 更新后路径变化需重启 session。
- M-2 帧以 PNG 每帧写盘；高频截图可优化为流式。
- M-3 `tools/vdisplay` 为机制验证用，正式逻辑已在 `core/shell/vd`。

## 验证核对（receiving）
- ✅ M1-1 建屏/承载/截帧（Android 13 id=2、Android 16 id=10）
- ✅ M1-3 注入（back→空帧；swipe→帧 md5 变化）
- ✅ M1-2 免 root 自举（配对 true、连接 uid=2000、pty 拉起助手 id=5）
- ✅ M1-4 REST 端到端（launch ok / frame 200 99244B / tap ok / swipe ok）
- ✅ 关键平台约束已记录：adbd 会话杀进程组、setsid 无效 → hold pty + 前台常驻
- ✅ 无敏感信息落盘

## 技术债与风险
- **hidepid 端口发现**是当前最大体验缺口（用户需提供连接端口）。
- 助手生命周期绑定 App 进程（App 死则助手退）——可接受，M2 可评估 provider 保活。

## 下一步
- M2：呈现模式（浮窗/常规/headless）+ 触摸回写；补 token 鉴权。
- M3：ScriptExecutorService（Node `:node` 进程）+ 调度。
- M4：MCP(SSE) + MJPEG 视频流。
