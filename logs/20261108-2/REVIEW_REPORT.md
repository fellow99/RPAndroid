# REVIEW_REPORT — 007-webrtc（WebRTC 界面推流）

> 日期：2026-11-08 ｜ 分支：master ｜ 评审范围：`67858a1..HEAD`（23 文件 / +1458 行）
> 方式：requesting-code-review 模板（同意/receiving-code-review 流程），对已提交 diff 做只读评审，逐条核验后处理。

## Strengths
- 帧源架构（FrameHub + App 进程持 Surface）与 Mika 机制互证，彻底去掉逐帧文件 IPC；MJPEG 由此连带受益。
- WebRTC 数据面收敛到 ScreenController（C-1 单入口）；`core:webrtc` 模块隔离 AAR/JNI，依赖单向（server→webrtc→controller→shell）。
- 两处生产级问题在评审前已真机定位并修复：Main 线程启动卡顿（被 MIUI 回收）、每帧 I420 缓冲区泄漏（25MB/s → 设备 mem-pressure 回收）。
- 鉴权复用现有 token；文档一律占位符。

## Issues

### Critical（Must Fix）—— 已修复
1. **Native 内存泄漏**（`FramePusher`→`WebRtcPush.sink`）
   - 现象：streaming 60s Native Heap 1.2GB→2.8GB（+25MB/s），触发系统 `mem-pressure-event` 把前台 App 回收（复现 4 次）。
   - 根因：`capturerObserver.onFrameCaptured(frame)` 不接管所有权，自建 `JavaI420Buffer` 未释放。
   - 修复：sink 内 `finally { frame.release() }`。实测 Native 平坦（~40MB），3 分钟稳定。✅
2. **启动主线程卡顿**（`ManagedRuntime.start/startSession`）
   - 现象：`MainActivity.onCreate` 被类级 `@Synchronized` 锁卡 6s+（MIUI `APP_SCOUT_HANG`），App 被系统回收。
   - 修复：去类锁 + 快速路径 + 会话恢复移到后台线程；`onCreate` 不再 hang。✅

### Important（Should Fix）—— 已修复
3. **`PeerConnectionFactory.initialize` 全局一次性**（`WebRtcPush.ensureInitialized`）
   - stop() 后重建 factory 再 initialized 会抛“already initialized” → WebRTC 永久失效。
   - 修复：进程级 `globalInitDone` 守卫，仅首次 initialize。✅
4. **推流线程在 0 peer 时也启动**（`ensureInitialized` 内 `pusher.start()`）
   - peer 创建失败/缩容后仍持续编码占 CPU。
   - 修复：`pusher.start()` 移到确有 peer 后（`startPeer` 成功 add 后）；空则停。✅

### Minor（Nice to Have）—— 部分修复
5. 输入坐标越界一像素（`nx=1.0 → x=w`）→ `px()` 钳制到 `w-1/h-1`。✅ 已修
6. `VdHelper.readFully` 未使用 → 已删。✅ 已修
7. `MfaHandoff.prepend` 的待取 Surface 若助手不连会滞留（进程级单例）——低风险，记录待后续接入超时清理。⏳
8. `handleAnswer` 成功发送 `"connected"` 状态措辞略误导（实为 setRemote 成功）——低影响，保留（页面另有 RTC:connected 真值）。⏳

### Security（C-12）
- 评审扫出**历史遗留**真实 IP/token 已入库：`.pi/mcp.json`（设备 IP + Bearer token）、`logs/20261107-8/MCP-TEST-REPORT.md`（token）。
- 处理：`.pi/` 移出 git（`git rm --cached` + `.gitignore`），旧日志 token 置占位符。✅
- 本需求新增文档/代码均无真实 IP/token（`<token>`/`<手机IP>` 占位）。✅

## Recommendations
- WebRTC 依赖为 2021 稳定 AAR：若未来对更激进浏览器 SDP 支持有要求，可评估升级 google-webrtc（注意 API 兼容）。
- 拖动注入（TC3-2）与 MCP 常规回归建议在下一次全量回归补测。
- `MfaHandoff` 待取 Surface 建议加超时清理，避免极端场景滞留。

## Assessment

**Ready to merge: With fixes（截至本报告，Critical/Important 已全修）**

**Reasoning:** 功能在真机上全链路通过（推流 + 注入 + 稳定 3 分钟），两处致命问题（内存泄漏、启动 ANR）已定位修复并复测；剩余为 Minor/流程项。
