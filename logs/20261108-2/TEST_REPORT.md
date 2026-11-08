# TEST_REPORT — 007-webrtc（WebRTC 界面推流）

> 日期：2026-11-08 ｜ 分支：master ｜ 真机：Xiaomi M2105K81C (Android 13)，局域网（手机 IP 用例不落盘，以占位符处理）
> 用例详见 `specs/007-webrtc/test-cases.md`

## 结果汇总
- 通过：**26** ｜ 待人工复核：2（TC3-2 拖动注入、TC5-3 MCP 常规回归）

## 实测记录（精选）
- **帧源重构**：会话建立后 `surface_status=ok`（Surface 经 ContentProvider 交接成功）；
  `/frame` 200；`/stream` MJPEG 正常；日志中 `frame attempt`（文件 IPC）为 0 —— 帧已落在 App 进程。
- **WebRTC 视频**：浏览器 `/webrtc/?token=…` → `RTC:connected`，`<video>` readyState=4、
  尺寸 360×780（本会话虚拟屏 720×1560）持续播放；编解码器：enc/dec = VP8, VP9, H264。
- **反向输入**：Playwright 真实点击视频中心 → 设置页导航，`/frame` md5 `7563b1a0…` → `e0bdabd6…`，**注入成功**。
- **稳定性**：持续推流 3 分钟，pid 恒定；Native Heap 平坦（首末 ~40MB）；
  无 `APP_SCOUT_HANG`（修复前 onCreate 6s 卡死/被回收 ×4）。
- **内存泄漏修复前后对比**（streaming 60s）：Native Heap `1.2GB→2.8GB`（+25MB/s，旧）→ `~40MB` 平坦（新）。

## 回归（评审修正后）
- [PASS] WebRTC 推流：`RTC:connected`、540×1170 播放（评审修正后再验证）。
- [PASS] 点击注入：真实点击 → 设置页导航，`/frame` md5 `7563b1a0…` → `118f95d9…`。
- [PASS] REST：`/status`(sessionActive)、`/frame`、`/session/launch`、`/action/*` 正常。
- [PASS] MJPEG `/stream` 推流正常（~63KB/3s）。
- [PASS] 2 分钟持续推流：pid 恒定、Native ~40-46MB 无增长趋势、零 hang。
- [PASS] 安全复核：`.pi/` 已移出 git；旧日志 token 置占位符；本需求文档无真实 IP/token。

## 平台/环境备注
- 局域网直连测试模型；浏览器（chromium）经 `http://<手机IP>:8099/webrtc?token=<token>` 接入。
- 真机 token 为本地测试值，未落盘文档（本文档以下划线占位）。
