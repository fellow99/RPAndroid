# DEV_CHECKLIST — 007-webrtc（WebRTC 界面推流）

> 日期：2026-11-08 ｜ 分支：master（不改动） ｜ 真机：Xiaomi M2105K81C (Android 13, sdkInt 33, 免 root)

## 进度
| 任务 | 状态 | 说明 |
|---|---|---|
| T1 构建 core:webrtc + 依赖 | ✅ | `org.webrtc:google-webrtc:1.0.32006`（aliyun 可解析）、arm64 only |
| T2 帧源落 App（FrameHub + Surface 交接） | ✅ | 交接方式迭代：抽象 socket→loopback TCP→**App ContentProvider 跨 UID Binder**（Mika 式）。原因：Surface 含 Binder，`Parcel.marshall` 拒绝序列化；跨 UID socket 有 DAC/SELinux 限制 |
| T3 WebRTC 推流 | ✅ | offerer 拓扑 + SEND_ONLY + 带 msid；H.264 硬件编码 |
| T4 信令 + 控制台 + 输入注入 | ✅ | Ktor WS 信令、浏览器控制台、DataChannel→ScreenController |
| T5 稳定性与联调 | ✅ | 修复两处致命问题（见下） |
| T6 文档/评审/回归 | 进行中 | WebRTC.md 已完成；待 code review |

## 关键修复（本回合）
1. **`fix(runtime)`**：`ManagedRuntime.start/startSession` 去掉类级 `@Synchronized` 长锁——
   onCreate 主线程被卡 6s+（MIUI `APP_SCOUT_HANG`），导致 App 被系统回收。
2. **`fix(webrtc)`**：`FramePusher` 每帧 `JavaI420Buffer` 未释放 → ~25MB/s native 泄漏
   （60s 到 2.8GB），触发设备级 `mem-pressure-event` LMK 回收。修复后 Native 平坦（~40MB）。

## 遗留
- 会话自动恢复依赖无线调试端口（adbd）可用。
- 拖动注入（TC3-2）建议人工复核；MCP 常规回归待下一次全量。
