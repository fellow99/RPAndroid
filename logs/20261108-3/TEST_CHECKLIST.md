# 008-client TEST_CHECKLIST（依据 specs/008-client/test-cases.md 执行）

日期：2026-11-08 ｜ 真机 Redmi Note 11 + 桌面 Chromium(adb forward localhost:8099, Playwright)

## TC1 新端点回归（curl，真机）
- [x] TC1-1 /health 返回 ok/service/authEnabled(true)
- [x] TC1-2 /apps/{pkg}/icon → 200 image/png（Settings 234×234 50KB）；未知包 404；无 token 401
- [x] TC1-3 /action/key（code=4）→ {ok:true}
- [x] TC1-4 /tasks/{id}/stop → {ok:true}，runningTaskId 清空
- [x] TC1-5 /tasks/{id}/run 互斥：运行 A 时 run t1 → ok:false
- [x] TC1-6 /status.runningTaskId：执行时=id，完成后 null 且 lastStatus 回写 ok
- [x] TC1-7 回归：/frame、/stream(MJPEG 13帧/3s)、/action/tap、/apps、/tasks CRUD；MCP screen_capture 未改动

## TC2 页面引导（Playwright）
- [x] TC2-1 服务异常（route abort /health）→ 全屏错误浮窗 + 刷新按钮
- [x] TC2-2 设 token 且页面不带 → token 浮窗（遮挡）
- [x] TC2-3 错误 token → 「token 无效」
- [x] TC2-4 正确 token → 进入主界面 + 状态栏摘要 + 地址栏 ?token=
- [x] TC2-5 未设 token 直接进入（代码路径核验：authEnabled=false → startMain）

## TC3 视频回显与降级
- [x] TC3-1 有会话 → RTC:ice:CONNECTED，video readyState=4 出画面（或 RTCPeerConnection 不可用 → MJPEG）
- [x] TC3-2 RTCPeerConnection 不可用 → 自动 MJPEG 降级，img natural 1600 出帧，无 JS 错误
- [x] TC3-3 无会话 → 「暂无会话」提示，无流
- [x] TC3-4 模式徽标：RTC 已连接 / MJPEG 降级 / 暂无会话
- [x] TC3-5 视频区点击注入：/action/tap 真机生效 + dc.send 路径（007 回归）；真机浏览器手点=人工项

## TC4 应用抽屉
- [x] TC4-1 「应用」→ 右侧半屏抽屉，15 个图标经 /apps/{pkg}/icon 加载（natural>0）
- [x] TC4-2 点击图标 → toast「已切换：设置」+ 抽屉关闭
- [x] TC4-3 启动失败提示（代码路径：无会话 → message 展示）

## TC5 自动化抽屉
- [x] TC5-1 列表显示图标/名称/执行时间/启用 Switch
- [x] TC5-2 执行 A → A「⏹ 停止/编辑」，其它项仅「编辑」；/status.runningTaskId=A
- [x] TC5-3 执行 B（A 运行中）→ 服务端 ok:false（客户端提示）
- [x] TC5-4 停止 A → 其它项「▶ 执行/编辑」恢复
- [x] TC5-5 启用项显示执行/停止；禁用项只显示编辑（t1/t2 全启用，代码路径核验禁用分支）
- [x] TC5-6 编辑表单：改名→保存→列表刷新
- [x] TC5-7 任务完成 → 该行 lastStatus 回写（ok）

## TC6 日志抽屉
- [x] TC6-1 「日志」→ 右侧半屏抽屉显示时间/级别/标签/消息
- [x] TC6-2 级别过滤（E=0 条）与清空生效

## TC7 安全与稳定性
- [x] TC7-1 页面 HTML 无硬编码真实 IP/token/账号/密码（grep 核验）
- [x] TC7-2 未授权业务端点 401（含页面内联 API 按 query/header token 鉴权）
- [x] TC7-3 assembleDebug 通过 + 安装运行正常 + 多次重装/会话重建轮询 2min 无异常
- [x] TC7-4 文档（docs/Client.md、spec、TEST_REPORT）不含真实 IP/token/凭据；存量 192.168.0.100 已替换为 <host>
