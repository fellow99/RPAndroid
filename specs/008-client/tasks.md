# 008-client 任务清单（tasks.md）

> 依赖顺序执行；每个回合计一个 commit（Conventional Commits，遵循 git-commit 技能前缀：feat/fix/docs/build/refactor/test）。
> 分支：不改动（master）。

## T1 服务端桥：/health.authEnabled + 图标端点 + /action/key + /tasks/{id}/stop + TaskRuntime
- [ ] `/health` 响应增加 `authEnabled`（authToken.isNotEmpty()），保持 ok/service 兼容。
- [ ] `AppIconCache`（core:server）：按 pkg 的 PNG 内存缓存（LruCache，进程内单例）。
- [ ] `GET /apps/{pkg}/icon`：appContext.packageManager.getApplicationIcon(pkg)→Bitmap→PNG；未命中→404；无 appContext→503；IO 调度。
- [ ] `POST /action/key`：Body `{"code":N}` → `controller.pressKey(code)` → `{ok}`。
- [ ] `TaskApi` 接口 + `TaskRuntime`（app.script）：runningTaskId 单例、begin(id) 互斥（已运行返回 false）、end(id)、outcome watchdog（1s 轮询 takeRunOutcome → 写 store lastStatus → end）。
- [ ] `TaskApiImpl`：`run(id)` 先 `TaskRuntime.begin`（false→返回失败）；`stop(id)` → `TaskRuntime.end` + `TaskScheduler.stop`。
- [ ] `GET /tasks/{id}/stop` 路由；`/status` 经 statusProvider 输出 `runningTaskId`。
- [ ] 编译 `./gradlew :app:assembleDebug` 通过。
- [ ] commit: `feat(server): add client endpoints - health auth flag, app icon, action key, task stop` / `feat(script): add runtime task mutual exclusion and status writeback`（按实际拆分）。

## T2 客户页面 ClientPage（/client）
- [ ] `core/server/ClientPage.kt`：单文档 HTML（UTF-8，无 CDN）。
- [ ] 引导：/health→错误浮窗(authEnabled=false 直接进)；token 浮窗（强制、遮挡全屏、localStorage+地址栏同步、401 提示）。
- [ ] 标题栏：左=ManagedFloatingApp 客户端；中=菜单「播控/应用/自动化/日志」；右=/status 摘要 + runningTaskId。
- [ ] 主区域：/frame 探会话（无图→「暂无会话」）；有图→WebRTC(WS 信令 answerer + DataChannel 输入)；6s 未出流/失败→MJPEG `<img>`+REST 操作；模式徽标。
- [ ] 操作：点击/拖动→归一化坐标注入（RTC:dc.send；MJPEG:POST tap/swipe）；按键（Backspace/Enter/Space/Home）→dc key 或 POST /action/key。
- [ ] 播控菜单：关闭其它抽屉。
- [ ] 应用抽屉（~50vw 右侧）：/apps + /apps/{pkg}/icon；点击→/session/launch→关闭抽屉/失败提示。
- [ ] 自动化抽屉（~50vw 右侧）：/tasks 列表（图标+名称+执行时间+启用 Switch）；启用项执行状态及按钮（播放/停止，互斥隐藏其它项）+编辑；编辑/新建表单（name/pkg/script/enabled/kind/intervalMs/cron → POST /tasks）；runningTaskId+lastStatus 轮询刷新。
- [ ] 日志抽屉（~50vw 右侧）：/logs + 级别过滤 + 清空。
- [ ] 编译通过；页面在桌面 Chromium（adb forward）打开无 JS 错误。
- [ ] commit: `feat(server): add /client broadcast-control single-file page`

## T3 联调与真机验证
- [ ] 真机安装 APK、启动服务、建立会话（/status sessionActive=true）。
- [ ] curl 回归：/health(authEnabled)、/apps/{pkg}/icon、/action/key、/tasks/{id}/stop、/tasks/{id}/run 互斥、/frame、/stream。
- [ ] 桌面浏览器（adb forward localhost:8099）验证：token 引导分支、视频回显（RTC 或 MJPEG 降级）、应用切换、自动化互斥/停止/编辑、日志。
- [ ] 无会话场景显示「暂无会话」。
- [ ] commit: `test(client): record 008-client integration and regression results`（填 [test-cases.md](./test-cases.md)）。

## T4 文档、评审与回归
- [ ] `docs/Client.md` 播控客户端接入文档（端到端示例、鉴权、功能；一律占位符，不落真实 IP/token/凭证）。
- [ ] `docs/API.md` 端点总览补充：/client、/apps/{pkg}/icon、/action/key、/tasks/{id}/stop、/health.authEnabled、/status.runningTaskId。
- [ ] requesting-code-review → `logs/20261108-3/REVIEW_REPORT.md`；receiving-code-review 修 Critical/Important → `code-review-fix:` commit。
- [ ] 回归：REST/MJPEG/WebRTC/MCP 抽验 + 稳定性；填 [test-cases.md](./test-cases.md)；安全复核（无真实 IP/token/凭据入库）。
- [ ] commit: `docs(client): add /client integration guide and API additions`
