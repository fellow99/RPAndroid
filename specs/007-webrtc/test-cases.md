# 007-webrtc 测试用例（test-cases.md）

> 与 [spec.md](./spec.md) 验收场景对应。真机（Android）验证为主；浏览器控制台需局域网手工验收。

## TC1 帧源重构回归（MJPEG//frame 底层更换为 FrameHub）
- TC1-1 建会话后 `GET /frame` 返回有效 PNG；无会话返回 404（回归）。
- TC1-2 `GET /stream`（MJPEG）可推流；≈每 200ms 一帧；可 `ffplay`/浏览器 `<img>` 播放（回归）。
- TC1-3 静态画面下 `/frame` 快速连续请求仍返回画面（缓存复用，不重复编码）。
- TC1-4 `/action/tap`、`/action/swipe` 生效（注入后画面变化）。
- TC1-5 会话 stop→start 重建后以上接口恢复可用。

## TC2 WebRTC 推流（浏览器）
- TC2-1 打开 `/webrtc/?token=…` 控制台 → 信令完成、`onaddstream` 触发、`<video>` 播放虚拟屏画面。
- TC2-2 从打开到出现画面首帧 ≤3s（局域网，记录实测）。
- TC2-3 静态画面 ≥1s 不冻结（保活生效）。
- TC2-4 虚拟屏动态变化（切到桌面/滚动列表）画面持续更新。

## TC3 反向输入（DataChannel）
- TC3-1 页面上点击画面某处 → 被托管 App 收到对应点击（坐标换算正确）。
- TC3-2 页面上拖动 → 被托管 App 收到滑动（等效 `input swipe`）。
- TC3-3 letterbox 场景（视频显示区域有黑边）坐标仍换算正确（点黑边外区域不触发越界注入）。
- TC3-4 点击后画面端到端反馈 ≤500ms 记录。

## TC4 鉴权与异常
- TC4-1 未带/带错 token 打开控制台或连 WS → 401/拒绝，页面提示。
- TC4-2 未建会话时打开控制台 → 明确提示，App 不崩溃。
- TC4-3 WebRTC 初始化失败/无摄像头编码器 → 优雅提示，server 其余端点存活。

## TC5 并存与回归
- TC5-1 `/stream`（MJPEG）与 WebRTC 同时在线互不影响。
- TC5-2 REST `/status`、`/apps`、`/ui`、任务 CRUD 全量回归通过。
- TC5-3 MCP `screen_capture`/`screen_tap` 等工具回归通过。
- TC5-4 并发浏览器 peer=2 时正常；>2 拒绝并提示。
- TC5-5 构建 `assembleDebug` 通过；安装运行无异常日志。

## 记录格式
每个通过项：`[PASS] TCx-x 描述`；失败项：`[FAIL] …（现象/日志，注意不落真实 IP/token）`。
