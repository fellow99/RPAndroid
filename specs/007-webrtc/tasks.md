# 007-webrtc 任务清单（tasks.md）

> 依赖顺序执行；每个回合计一个 commit（Conventional Commits，遵循 git-commit 技能前缀：feat/fix/docs/chore/build/refactor/test）。
> 分支：不改动（master）。

## T1 构建：新增 core:webrtc 模块与依赖
- [x] `gradle/libs.versions.toml`：`webrtc = "1.0.32006"`、`ktorServerWebsockets = "2.3.12"` + 对应 library 条目。
- [x] `settings.gradle.kts`：`include(":core:webrtc")`。
- [x] `core/webrtc/build.gradle.kts`（android.library，只 apply android.library 插件）：依赖 `libs.webrtc` + `:core:controller` + coroutines；ABI 仅 arm64（`ndk { abiFilters += "arm64-v8a" }`）。
- [x] 编译 `./gradlew assembleDebug` 通过。
- [x] commit: `build(project): add core:webrtc module with google-webrtc dependency`

## T2 帧源落 App：FrameHub + Surface 交接
- [x] `core/controller/FrameHub.kt`：ImageReader(RGBA×2) + HandlerThread；消费最新帧（处理 rowStride）；`latestPng()` 缓存（静态复用）；暴露 `rgba` 供 WebRTC 与 w/h；`release()`。
- [x] `core/shell/vd/VdHelper.kt`：新增 `--surface-sock` 改造 → **最终采用参考工程式：经 App 导出 ContentProvider 跨 UID Binder 事务拉取 App 帧 Surface** 建虚拟屏；未提供/失败回退 legacy 自持 ImageReader。
- [x] `core/controller/VdSessionImpl.kt`：创建 FrameHub → `MfaHandoff.prepare` 待取 Surface → 助手 `--nonce` 建屏；`captureFrame()` 读 FrameHub；据 `surface_status.txt` 决定握手 vs legacy。
- [x] `AppScreenController` / `ScreenController`：新增 `frameSource(): FrameSource?`（读路径暴露）。
- [x] 编译 + 真机回归：`surface_status=ok`，MJPEG `/stream`、`/frame`、`/action/*` 行为不变且不再走文件 IPC。
- [x] commit: `feat(shell): hand off app frame surface via content provider for in-app capture`

## T3 WebRTC 推流：core:webrtc
- [x] `WebRtcPush`：PeerConnectionFactory 初始化（显式 EglBase + 编解码工厂）→ VideoSource/VideoTrack；**offerer 拓扑**：addTrack 后 `SEND_ONLY` + 带 msid，浏览器 answer。
- [x] `FramePusher`：独立线程 ~15FPS；FrameHub 新帧 RGBA→I420(`JavaI420Buffer`)→ capturerObserver；静态保活；**帧 finally 释放（修 ~25MB/s native leak）**。
- [x] DataChannel `"input"`：App 侧创建，onMessage → 输入回调。
- [x] 生命周期：peer 上限 2；释放/断开回收；`stop()` 整体清理。
- [x] 真机：`RTC:connected`、浏览器 360×780 视频播放、输入通道已连接。
- [x] commit: `feat(webrtc): add peer connection H.264 push…` / `feat(webrtc): send-only offerer…`

## T4 信令 + 控制台 + 输入注入：core:server
- [x] `libs.versions.toml`/`core:server` 增加 `ktor-server-websockets`，install WebSockets。
- [x] `GET /webrtc/?token=` 控制台页面（HTML/JS：RTCPeerConnection answerer、video、指针事件→DataChannel、letterbox 归一化坐标）。
- [x] `GET /webrtc/signaling?token=` WebSocket：offer/answer/ice 双向转发；WS 握手鉴权（复用 token 逻辑）。
- [x] server 内 `WebRtcWire.handleInput`：归一化 × FrameSource 尺寸 → `ScreenController.tap/swipe/pressKey`。
- [x] 无会话/初始化失败 → 关闭/提示，不崩溃。
- [x] 真机联调：浏览器观看 + **真机点击注入成功（设置页导航，前后帧 diff）**。
- [x] commit: `feat(server): add webrtc signaling websocket, browser console, and data-channel input`

## T5 联调、测量与 MJPEG 并存验证
- [x] 真机：浏览器控制台打开→首帧即出；点击→反馈正常（记录见 test-cases.md）。
- [x] 静态画面保活不冻结验证。
- [x] MJPEG `/stream`、`/frame` 回归；并发 peer 上限验证（代码层）。
- [x] 会话重建后 WebRTC 自动恢复（多次 stop/start 均可用）。
- [x] **稳定性根治**：`fix(runtime)` 非阻塞启动（消除 onCreate 6s 主线程卡顿/被 MIUI 杀）；`fix(webrtc)` 帧释放（消除 ~25MB/s native leak 导致设备 mem-pressure 回收）。3 分钟持续推流内存平坦。
- [x] commit: `fix(runtime): …`

## T6 文档、评审与回归
- [x] `docs/WebRTC.md` 接入文档（端到端示例、鉴权、限制；一律占位符，不落真实 IP/token/凭证）。
- [x] requesting-code-review → `logs/20261108-2/REVIEW_REPORT.md`；receiving-code-review 修 Critical/Important → commit: `code-review-fix: …`。
- [x] 回归：REST/MJPEG/WebRTC 推流/点击注入全量抽验 + 2min 稳定性；填 [test-cases.md](./test-cases.md) 执行记录；安全复核（`.pi/` 移出 git、旧日志 token 置占位符）。
- [x] commit: `docs(webrtc): add WebRTC integration guide and test records` / 回归记录随 TEST_REPORT 提交。
