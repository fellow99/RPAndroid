# 007-webrtc 任务清单（tasks.md）

> 依赖顺序执行；每个回合计一个 commit（Conventional Commits，遵循 git-commit 技能前缀：feat/fix/docs/chore/build/refactor/test）。
> 分支：不改动（master）。

## T1 构建：新增 core:webrtc 模块与依赖
- [ ] `gradle/libs.versions.toml`：`webrtc = "1.0.32006"`、`ktorServerWebsockets = "2.3.12"` + 对应 library 条目。
- [ ] `settings.gradle.kts`：`include(":core:webrtc")`。
- [ ] `core/webrtc/build.gradle.kts`（android.library，只 apply android.library 插件）：依赖 `libs.webrtc` + `:core:controller` + coroutines；ABI 仅 arm64（`ndk { abiFilters += "arm64-v8a" }`）。
- [ ] 编译 `./gradlew assembleDebug` 通过。
- [ ] commit: `build(project): add core:webrtc module with google-webrtc dependency`

## T2 帧源落 App：FrameHub + Surface 交接
- [ ] `core/controller/FrameHub.kt`：ImageReader(RGBA×2) + HandlerThread；消费最新帧（处理 rowStride）；`latestPng()` 缓存（静态复用）；暴露 `rgba` 供 WebRTC 与 w/h；`release()`。
- [ ] `core/shell/vd/VdHelper.kt`：新增 `--surface-sock <name>` 分支——连接抽象 socket 读一帧 `Parcel{Surface}`，用该 Surface `createVirtualDisplay`；未带参数沿用 legacy 自持 ImageReader。
- [ ] `core/controller/VdSessionImpl.kt`：创建 FrameHub → 建 LocalServerSocket 传 Surface 名 → 启动助手追加 `--surface-sock`；`captureFrame()` 改为读 FrameHub（`latestPng()`）。
- [ ] `AppScreenController` / `ScreenController`：暴露或透出 FrameHub（供 server/webrtc 取帧与尺寸）。
- [ ] 编译 + 真机回归：MJPEG `/stream`、`/frame`、`/action/*` 行为不变且帧率/延迟改善。
- [ ] commit: `feat(controller): capture frames in app via FrameHub surface handoff`

## T3 WebRTC 推流：core:webrtc
- [ ] `WebRtcPush`：PeerConnectionFactory 初始化（1.0.32006 经典 API）→ VideoSource(VideoSink)/VideoTrack；PeerConnection（UNIFIED_PLAN、host ICE、H.264 优先）。
- [ ] `FramePusher`：独立线程 ~15FPS；FrameHub 最新帧 RGBA→I420(`JavaI420Buffer`)→ videoSource；静态保活重推。
- [ ] DataChannel `"input"`：解析 `{t,tap/swipe/key…}` → 回调输入 sink。
- [ ] 生命周期：peer 上限 2；释放/断开回收；`WebRtcPush.stop()` 整体清理。
- [ ] 720×1560 H.264 对齐风险：先 I420 直喂；真机验证，失败记录并采用对齐+裁剪。
- [ ] 编译通过。
- [ ] commit: `feat(webrtc): add peer connection H.264 push with data channel input`

## T4 信令 + 控制台 + 输入注入：core:server
- [ ] `libs.versions.toml`/`core:server` 增加 `ktor-server-websockets`，install WebSockets。
- [ ] `GET /webrtc/?token=` 控制台页面（HTML/JS：RTCPeerConnection、video、指针事件→DataChannel、letterbox 归一化坐标）。
- [ ] `GET /webrtc/signaling?token=` WebSocket：offer/answer/ice 双向转发；WS 握手鉴权（复用 token 逻辑）。
- [ ] server 内坐标映射：归一化 × FrameHub 尺寸 → `ScreenController.tap/swipe/pressKey`；数据通道挂载。
- [ ] 无会话/初始化失败 → 控制台明确提示，不崩溃。
- [ ] 编译 + 真机联调：浏览器观看 + 点击反馈。
- [ ] commit: `feat(server): add webrtc signaling websocket and browser console`

## T5 联调、测量与 MJPEG 并存验证
- [ ] 真机：浏览器控制台打开→首帧 ≤3s；点击→反馈 ≤500ms（记录）。
- [ ] 静态画面不冻结验证（保活）。
- [ ] MJPEG `/stream`、`/frame` 回归；并发 peer 2 上限验证。
- [ ] 会话重建（stop→start）后 WebRTC 自动恢复。
- [ ] commit: `fix(webrtc): polish session rebuild and edge cases`（如需要）

## T6 文档、评审与回归
- [ ] `docs/WebRTC.md` 接入文档（端到端示例、鉴权、限制；一律占位符，不落真实 IP/token/凭证）。
- [ ] requesting-code-review → `logs/<date>/REVIEW_REPORT.md`；receiving-code-review 修 Critical/Important → commit: `code-review-fix: …`。
- [ ] 回归：MJPEG/REST/MCP 全量抽查；填 [test-cases.md](./test-cases.md) 执行记录。
- [ ] commit: `docs(webrtc): add WebRTC integration guide and test records`（如需要）
