# 008-client DEV_CHECKLIST

日期：2026-11-08 ｜ 需求：008-client（播控客户端 /client） ｜ 分支：master（不改动）

## T1 服务端桥
- [x] /health 增加 authEnabled
- [x] AppIconCache（core:server，LruCache PNG）
- [x] GET /apps/{pkg}/icon
- [x] POST /action/key
- [x] TaskRuntime（app.script：互斥 begin/end + outcome watchdog 回写 lastStatus）
- [x] TaskApi +stop(id)；TaskApiImpl run 互斥 begin、stop 实现
- [x] GET /tasks/{id}/stop；/status.runningTaskId（statusProvider）
- [x] 编译 assembleDebug 通过（commit 56cff93 / e1ff548）

## T2 客户页面 ClientPage
- [x] 单文档 HTML（无 CDN）
- [x] 引导：/health + token 浮窗 + 错误浮窗；localStorage/地址栏 token
- [x] 标题栏（左标题/中菜单/右状态）
- [x] 主区域：/frame 探会话 → WebRTC(WS answerer+dc) → 降级 MJPEG；模式徽标
- [x] 操作注入（RTC:dc / MJPEG:REST tap/swipe/key；点击/拖动/按键）
- [x] 播控：关其它抽屉
- [x] 应用抽屉（图标 + /session/launch 切换）
- [x] 自动化抽屉（列表/互斥执行/停止/编辑/新建表单；runningTaskId+lastStatus 轮询）
- [x] 日志抽屉（/logs + 级别过滤 + 清空）
- [x] 编译通过；无 `$` 冲突（commit e1ff548）

## T3 联调/真机
- [x] 真机安装 + 建会话 + curl 新端点回归（/health.authEnabled、/apps/{pkg}/icon、/action/key、/tasks run/stop 互斥、/frame、/stream、/logs）
- [x] 桌面浏览器（adb forward localhost）：token 引导（无/错/对）、RTC 连接、MJPEG 降级、应用抽屉、自动化互斥（执行/停止/编辑）、日志抽屉、播控关抽屉、无会话「暂无会话」、错误浮窗
- [x] 联调修复：TaskRuntime 陈旧结果文件误判（清 stale）；/client 匿名放行（token 引导可达）；drawer-log→drawer-logs；bindEvents 提前（token 浮窗按钮可点）；favicon；header z-index
- [x] 产物清理（测试任务已删；只留用户原 t1/t2）

## T4 文档/评审/回归
- [ ] docs/Client.md
- [ ] docs/API.md 端点表补充
- [ ] code review（requesting/receiving）
- [ ] 回归 + 安全复核（不落真实 IP/token/凭据）
