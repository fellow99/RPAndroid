# 007-webrtc Technical Plan

> Requirement: 007-webrtc ｜ 对应 [spec.md](./spec.md) ｜ [design.md](./design.md)
> 分支：不改动（master，沿用现有提交规范）

## 1. Technical Context
### 1.1 Runtime Environment
- 主进程（App / `ManagedServer`）：WebRTC 推流、信令、控制台。
- `app_process`（shell UID，`VdHelper`）：持有虚拟屏创建 + 输入注入（沿用文件 IPC 命令通道）。
- 帧在 App 进程（FrameHub 的 ImageReader），Surface 跨进程交给助手建屏。

### 1.2 Dependencies（新增）
| 依赖 | 用途 |
|---|---|
| `org.webrtc:google-webrtc:1.0.32006` | WebRTC 栈（aliyun public 可解析，2021 稳定版，仅 arm64） |
| `io.ktor:ktor-server-websockets:2.3.12` | 浏览器信令 WebSocket + 控制台 WS 通道 |
| `core:webrtc`（新模块） | 隔离 WebRTC AAR 的 JNI/打包；依赖 `core:controller` |
| `core:server → core:webrtc` | server 组装信令/控制台/输入注入 |

## 2. Constitution Compliance
- C-1 单入口：WebRTC 输入 → ScreenController（不直连 shell）。✅
- C-3 依赖单向：`server → webrtc → controller → shell`。✅
- C-4 AGP 内置 Kotlin：新模块只 apply `android.library`。✅
- C-6 arm64：webrtc AAR 仅 arm64-v8a 打包。✅
- C-7 优雅降级：无会话/初始化失败 → 控制台提示，不崩溃。✅
- C-12 安全：页面/WS 用现有 token；文档不落真实 IP/token/凭据。✅

## 3. Data Model
- `FrameHub`：{ width=720, height=1560, 最新 RGBA Bitmap + PNG 字节缓存 + 时间戳 }；静态复用最近帧。
- `WebRtcPush`：{ peerId, PeerConnection, videoTrack, dataChannel, StateFlow<status> }。
- DataChannel 消息：`{"t":"tap","nx":..,"ny":..}`｜`{"t":"swipe","nx1":..,"ny1":..,"nx2":..,"ny2":..,"durMs":..}`｜`{"t":"key","code":..}`（nx/ny 为 0..1 归一化坐标）。

## 4. Interface Contracts
### 4.1 Surface 交接（App ↔ 助手，取代"帧"方向的文件 IPC）
- `VdSessionImpl.start()`：创建 FrameHub（ImageReader RGBA×2）→ 建 `LocalServerSocket`(abstract `mfa-surface-<非零>`)→ 启动 VdHelper 时追加启动参数 `--surface-sock <name>` → 助手连接并读取一帧 **Parcel{SURFACE}** → `createVirtualDisplay(w,h,dpi, surface, flags)`。
- 兼容：未带 `--surface-sock` 时助手沿用自持 ImageReader（legacy 降级路径）。
- 输入/控制命令（launch/tap/swipe/key/quit）仍走现有文件 IPC `cmd.txt/result.txt`（不动）。

### 4.2 帧源（FrameHub，core:controller）
- `captureFrame(): ByteArray?`：返回最新一帧 PNG；静态画面复用缓存（不重复编码）。
- 供 WebRTC：`latestRgbaPixels(): IntArray/RGBA 字节 + w/h/stride`（第⑤阶段转换 I420 用）。

### 4.3 WebRTC（core:webrtc）
- 初始化：`PeerConnectionFactory.initialize(...)`（1.0.32006 经典 API）+ 单例 factory。
- 每 peer：`PeerConnection`（UNIFIED_PLAN，无 STUN/TURN，仅 host ICE）→ 共享 `VideoSource`/`VideoTrack`（H.264 硬件编码）。
- `FramePusher`：独立线程 ~15FPS，取 FrameHub 最新帧 → RGBA→I420（`JavaI420Buffer`）→ `videoSource`。
- DataChannel `"input"`：解析消息 → `ScreenController.tap/swipe/pressKey`（坐标已由 server 内换算为虚拟屏像素，见 §4.5）。
- 生命周期：peer 数上限 2；断开/异常自动清理并回收编码器。

### 4.4 信令 + 控制台（core:server，Ktor WebSocket）
- `GET /webrtc?token=` → 控制台 HTML/JS（`RTCPeerConnection` + `<video autoplay playsinline muted>` + 指针事件→DataChannel）。
- `GET /webrtc/signaling?token=` → WebSocket：JSON `{type:offer|answer|ice}` 双向转发。
- 鉴权：WS 握手校验 token（复用 TokenAuth 判定逻辑）；页面靠查询参数 `?token=`。

### 4.5 坐标映射（控制台 JS + server）
- 页面：video 元素 `object-fit:contain` → 按 `videoWidth/videoHeight` vs 元素尺寸换算 letterbox → 归一化 0..1 坐标。
- server：`nx × FrameHub.width`、`ny × FrameHub.height`（取整、越界裁剪）→ ScreenController。

## 5. Implementation Strategy
### 5.1 Architecture Pattern
- 帧源重构（FrameHub）先行，MJPEG 先切换真源回归；再加 WebRTC 数据面/信令/UI。
- WebRTC 全部封装在 `core:webrtc`，server 只做信令装配与输入转发。

### 5.2 Key Algorithms
- FrameHub：`acquireLatestImage()` 单线程消费，RGBA 拷贝（rowStride 处理）→ 最近帧；PNG 缓存 + 时间戳。
- RGBA→I420：Y/U/V 平面插值；尺寸 720×1560（H.264 对齐问题按 §7 风险处置）。
- 保活：静态帧以较低速率（如 1FPS）重复推送，保证 WebRTC 画面不冻结。

### 5.3 Error Handling
- 无会话 / PeerConnection 初始化失败 / WebSocket 鉴权失败 → 控制台明确提示；server 不崩溃。
- 会话重建（stop→start）时 FrameHub/编码器整体重建。

### 5.4 Performance
- 单路编码粒度为 peer 内共享；帧流不因 MJPEG 客户端数翻倍。
- FrameHub PNG 缓存避免静态画面重复编码。

## 6. Testing Considerations
- 真机冒烟：初始化 + 建连 + 观看 + 点击反馈 + MJPEG/`/frame` 回归 + 延迟/帧率记录。
- 控制台需在局域网浏览器手动验证（无法 Playwright 连手机的 WebRTC，按测试清单人工验收）。
- 详见 [test-cases.md](./test-cases.md)。

## 7. Risks & Mitigations
| 风险 | 缓解 |
|---|---|
| 2021 AAR 在 Android 15 JNI 不稳 | 独立模块隔离 + 先冒烟 |
| 720×1560 非 H.264 16 倍数 | 先试 I420 直喂；失败改编码高度对齐 + 裁剪 |
| 静态画面无新帧 | FrameHub 保活重推 |
| 浏览器 HTTP 非安全上下问 WebRTC 受限 | localhost/adb forward / 自签 TLS，真机验证 |
| Surface 跨进程交接 ROM 兼容 | 保留 legacy 自持 ImageReader 路径 |

## 8. File Inventory（新增/改动）
| File | Purpose |
|---|---|
| `core/webrtc/build.gradle.kts`、`core/webrtc/src/…/WebRtcPush.kt`、`FramePusher.kt` | WebRTC 模块 |
| `core/controller/src/…/FrameHub.kt` | 共享帧源（新增） |
| `core/controller/src/…/VdSessionImpl.kt`、`core/shell/src/…/vd/VdHelper.kt` | Surface 交接改造 |
| `core/server/src/…/ManagedServer.kt`、`ConsolePage.kt`/html | 信令 + 控制台 |
| `gradle/libs.versions.toml`、`settings.gradle.kts` | 依赖与模块注册 |
| `docs/WebRTC.md` | 接入文档（占位符） |
