# 007-webrtc 测试用例（test-cases.md）

> 与 [spec.md](./spec.md) 验收场景对应。真机（Android）验证为主；浏览器控制台需局域网手工验收。

## TC1 帧源重构回归（MJPEG//frame 底层更换为 FrameHub）
- [PASS] TC1-1 建会话后 `GET /frame` 返回有效 PNG（真机 200/若干 KB）；无会话 404（既有行为）。
- [PASS] TC1-2 `GET /stream`（MJPEG）可推流（约 200ms/帧）；日志确认**不再走文件 IPC**（`frame attempt` 0 条）。
- [PASS] TC1-3 静态画面连续 `/frame` 返回（PNG 缓存复用）；实测多次相同 md5。
- [PASS] TC1-4 `/action/tap`、`/action/swipe` 生效（注入后画面变化）。
- [PASS] TC1-5 会话 stop→start 重建后以上接口恢复可用（多次重建验证）。

## TC2 WebRTC 推流（浏览器）
- [PASS] TC2-1 打开 `/webrtc/?token=…` → 信令完成、`RTC:connected`、`<video>` 播放虚拟屏画面（ready=4、360×780）。
- [PASS] TC2-2 打开到出现画面首帧（12s 采样窗口内稳定出片，含信令+编码）。
- [PASS] TC2-3 静态画面不冻结（保活生效；长时间保持 ready=4）。
- [PASS] TC2-4 虚拟屏动态变化（设置页导航）画面持续更新。

## TC3 反向输入（DataChannel）
- [PASS] TC3-1 页面上点击画面 → 被托管 App 收到对应点击（真机浏览器真实点击 → 设置页导航，`/frame` md5 变化）。
- TC3-2 拖动→滑动：DataChannel 同一条链路（sendTap 同款 dc.send），未单独真机验证拖动（建议人工复核）。
- [PASS] TC3-3 letterbox 归一化坐标映射（norm() 计算经代码审查，越界返回 null 不外注入）。
- [PASS] TC3-4 点击后反馈即时（多次点击均正常响应）。

## TC4 鉴权与异常
- [PASS] TC4-1 未带/带错 token：REST 401；WS 无 token 关闭（`unauthorized` 分支）。
- [PASS] TC4-2 与会话无关的页面/WS 不崩溃（无会话时 `webrtc disabled` 处理）。
- [PASS] TC4-3 WebRTC 初始化失败被捕获，其余端点存活（`ensureInitialized` catch）。

## TC5 并存与回归
- [PASS] TC5-1 `/stream`（MJPEG）与 WebRTC 同在时可工作（不同接口互不影响）。
- [PASS] TC5-2 REST `/status`、`/apps`、`/frame`、`/action/*` 回归通过（真机）。
- TC5-3 MCP `screen_capture/screen_tap` 等：本回合未单独回归（代码未改动；建议常规回归时补测）。
- [PASS] TC5-4 WebRTC 并发 peer 上限 2（代码层 `MAX_PEERS`）。
- [PASS] TC5-5 `assembleDebug` 通过；安装运行正常；**3 分钟持续推流内存平坦（Native ~40MB）、无 home 线程 hang（修复后）**。

## 记录格式
每个通过项：`[PASS] TCx-x 描述`；失败项：`[FAIL] …（现象/日志，注意不落真实 IP/token）`。
