# 007-webrtc 设计决策记录（brainstorm 落盘）

> Status: **Approved**
> 需求编号：007-webrtc ｜ 需求名称：WebRTC 界面推流 ｜ 分支：不改动（master）
> 关联：`docs/000-工程规划.md`（二期 WebRTC）、`docs/MJPEG.md`（现存推流）、[spec.md](./spec.md)

## 1. 背景

当前虚拟屏界面推流为 **MJPEG-over-HTTP**（`GET /stream`，PNG 帧、约 5FPS），实测卡顿。
用户要求：先勘察参考工程（`d:\GitHub\AI\Mika`）的推流实现，再以头脑风暴决策是否/如何引入 WebRTC。

## 2. 勘察结论（参考工程的推流实现 = 无网络视频推流）

阅读 `d:\GitHub\AI\Mika` 源码后确认：

- 参考工程的“实时画面”全部在**手机本机**：虚拟屏由 shell 助手（shell UID）创建，但输出 **Surface 属于参考工程 App 进程**
  （`AgentDisplay.createDisplay(reader.surface, …)` 经其 Binder 接口传 Surface），帧直达 App 进程，
  限速 ≤10FPS，本地悬浮/界面 live view 消费；`latestFramePng()` 按需 PNG 快照供 MCP/OCR。
- 参考工程**没有** WebRTC / MJPEG-over-HTTP / 任何网络视频推流（依赖中无 webrtc）。
- 结构差异：参考工程帧先在 App 进程（无跨进程逐帧搬运）；我们为避免 root 把捕获放在独立 `app_process` 助手进程，
  帧经**文件 IPC**（写 cmd.txt/读 result.txt + PNG 文件）逐帧往返 → 结构性卡顿来源。

## 3. 关键洞察

参考工程的“Surface 跨进程挂到虚拟屏”机制（App 建缓冲、助手建屏、帧落 App）**可直接复刻**，
既可根治 MJPEG 卡顿，又是 WebRTC 低延迟数据面的前提。

## 4. 头脑风暴决策（用户确认）

| 决策点 | 结论 |
|---|---|
| Q1 要解决什么 | **交互延迟优先**（A > B 流畅度 > C 资源占用）：点一下看到反馈 |
| Q2 消费方形态 | WebRTC 只服务**浏览器**（人眼/人工操作）；agent/MCP 不走 WebRTC，继续 REST/MCP 快照 |
| Q3 反向控制 | **完整双向**：浏览器点按/拖动 → **WebRTC DataChannel** → 注入虚拟屏 |
| Q4 与 MJPEG 关系 | **并存**：MJPEG `/stream` 全部接口保留（降级/其它价值），`/frame` 保留 |
| Q5 执行范围 | 用 specs-based-devflow **自动执行**后续阶段（spec→开发→评审→测试→回归） |

## 5. 方案对比与选择

| 方案 | 说明 | 结论 |
|---|---|---|
| **A. 参考工程式重排（选中）** | App 进程建 ImageReader，Surface 经本地 socket 交接给助手建虚拟屏，帧落 App；单一帧源喂 WebRTC/MJPEG//frame/预览 | 帧零冗余拷贝、MJPEG 连带根治、延迟最优。中等改动 |
| B. 助手持续推原始帧 | 保留现捕获，助手 socket 推 I420 帧给 App 编码 | 改动小但环路带宽/CPU 高、MJPEG 仍卡。作 A 的 fallback |
| C. 助手内编码再回传 | 助手跑编码器再 relay | 复杂度最高，不采用 |

## 6. 目标架构

```
浏览器控制台(RTCPeerConnection)
   │ WebSocket 信令(SDP/ICE)         ┌──────── 主进程（App）────────
   │ DataChannel 输入(tap/swipe)     ▼
   ▼                           ┌──────────────────────────────┐
 /webrtc/signaling (Ktor WS) → │ WebRtcPush (core:webrtc)      │
 /webrtc 控制台页面            │  ├ VideoSource/VideoTrack(H.264)│
                              │  ├ FramePusher RGBA→I420        │──▶ ScreenController（唯一写入口）
                              │  └ DataChannel→输入映射          │
                              └──────────┬───────────────────┘
                                          │ 共享帧源 FrameHub (core:controller)
                                          │  ImageReader(720×1560) ←── Surface 跨进程
                    ┌─────────────────────┼─────────────────────┐
                    │  MJPEG /stream(保留) │  /frame(保留)       │  本地预览(present,不改)
  app_process(VdHelper, shell UID)        │                     │
    createVirtualDisplay(appSurface, …) ──┘                     │
    输入注入(launch/tap/swipe/key 文件IPC 保留) ◀─────────────────┘
```

- **依赖单向**（C-3）：`core:server → core:webrtc → core:controller`；`core:controller → core:shell`。
- **C-1 单入口**：WebRTC 的输入仍收敛到 `ScreenController.tap/swipe/pressKey`，不直连 shell。
- **C-6 arm64**：`org.webrtc:google-webrtc` 仅打包 arm64-v8a。
- **C-12 安全**：页面/WS 沿用现有 token 三通道鉴权；文档一律占位符，不落真实 IP/token/凭据。

## 7. 里程碑切分

| 里程碑 | 内容 |
|---|---|
| D1 帧源落 App | FrameHub + Surface 交接助手建屏；MJPEG//frame 改读 FrameHub（回归并验证不卡） |
| D2 WebRTC 推流 | core:webrtc：PeerConnection + H.264 + FramePusher（RGBA→I420） |
| D3 信令+控制台 | Ktor WS 信令 + `/webrtc` 控制台页面 + DataChannel 坐标映射注入 |
| D4 真机联调 | 浏览器观看/点击反馈、MJPEG 并存、延迟/帧率测量 |
| D5 文档与回归 | `docs/WebRTC.md`、code review、回归测试 |

## 8. 风险簿（真机验证项）

| 风险 | 缓解 |
|---|---|
| `google-webrtc:1.0.32006`（2021 AAR）在 Android 15/targetSdk36 的 JNI 稳定性 | 独立 `core:webrtc` 隔离；先做初始化+建连冒烟 |
| 720×1560 H.264 高度非 16 倍数，部分编码器拒收 | 先试 I420 直喂；失败则编码高度对齐 + 裁剪/letterbox；真机定夺 |
| 虚拟屏静态画面无新帧（SurfaceFlinger 不重绘） | FrameHub 复用最近帧；WebRTC 保活推送 |
| 浏览器 HTTP 非安全上下文的 WebRTC 限制 | localhost/adb forward 或后续自签 TLS；真机浏览器验证 |
| 多浏览器客户端并发 → 多硬件编码器 | 本期并发 peer 上限 2；文档说明 |
| Surface 跨进程 Parcel 交接失败（权限/ROM） | 保留助手自持 ImageReader 的 legacy 启动参数作降级；参考工程已验证同机制可用 |
