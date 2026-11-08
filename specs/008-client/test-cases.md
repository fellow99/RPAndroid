# 008-client 测试用例（test-cases.md）

> 与 [spec.md](./spec.md) 验收场景对应。真机（Android）+ 桌面浏览器（adb forward localhost:8099）验证。
> 注意：记录一律不落真实 IP/token/账号/密码/凭证；URL 用占位符 `<host>`。

## TC1 新端点回归（curl）
- [ ] TC1-1 `GET /health` 返回 `{ok:true,service,authEnabled}`；设置 token 后 `authEnabled=true`。
- [ ] TC1-2 `GET /apps/{pkg}/icon?token=` 返回 200 image/png（真实 PNG 魔数）；未知 pkg 404；无 token 401。
- [ ] TC1-3 `POST /action/key {"code":4}` 无会话返回 `{ok:false}` 不崩溃；有会话注入生效（可与 /frame 前后 diff）。
- [ ] TC1-4 `POST /tasks/{id}/stop` 停止后不再运行（nextRun 消除/执行中断），返回 `{ok:true}`。
- [ ] TC1-5 `POST /tasks/{id}/run` 互斥：运行 A 时 run B 返回 `ok:false`（服务端 TaskRuntime）。
- [ ] TC1-6 `GET /status` 含 `runningTaskId`（空为 null）；执行时=A，完成后回 null 且 `/tasks` 内 `lastStatus=ok|error:` 回写。
- [ ] TC1-7 回归：`/frame`、`/stream`(MJPEG)、`/action/tap`、`/action/swipe`、`/apps`、`/tasks` CRUD、MCP `screen_capture/task_list` 行为不变。

## TC2 页面引导（Playwright 桌面浏览器 + adb forward localhost）
- [ ] TC2-1 服务异常（服务停）→ 全屏错误浮窗 + 刷新按钮（`location.reload` 可达）。
- [ ] TC2-2 服务设置了 token 且页面不带 token → 全屏 token 输入浮窗（遮挡业务界面）。
- [ ] TC2-3 输入错误 token → 提示「token 无效」，浮窗保留。
- [ ] TC2-4 输入正确 token → 进入主界面，标题栏右显示 /status 状态摘要。
- [ ] TC2-5 服务未设 token → 直接进入主界面，无浮窗。

## TC3 视频回显与降级
- [ ] TC3-1 有会话：`/frame` 有图 → 页面进入 WebRTC（`RTC:connected`）或 MJPEG `<img>` 显示画面（桌面 headless 优先验证 MJPEG 降级路径）。
- [ ] TC3-2 WebRTC 握手失败/超时 → 自动降级 MJPEG，`<img>` 显示实时帧。
- [ ] TC3-3 无会话：`/frame` 404 → 页面显示「暂无会话」提示，无视频流。
- [ ] TC3-4 模式徽标正确（RTC / MJPEG / 暂无会话）。
- [ ] TC3-5 视频区点击 → 注入生效（真机浏览器人工验收：点击后 /frame md5 变化）。

## TC4 应用抽屉
- [ ] TC4-1 打开「应用」→ 右侧半屏抽屉，图标经 /apps/{pkg}/icon 加载显示。
- [ ] TC4-2 点击图标 → 会话切换到该 App（/status 或 /frame 画面变化）；成功后抽屉关闭。
- [ ] TC4-3 启动失败（无会话）→ 抽屉内提示错误，不崩溃。

## TC5 自动化抽屉
- [ ] TC5-1 「自动化」→ 右侧半屏抽屉，每项显示图标+名称+执行时间设置+启用 Switch。
- [ ] TC5-2 执行 A → A 显示停止按钮，其它启用项按钮隐藏；`/status.runningTaskId=A`。
- [ ] TC5-3 执行 B（A 运行中）→ 被拒提示。
- [ ] TC5-4 停止 A → 其它项按钮恢复显示。
- [ ] TC5-5 启用项才显示执行/停止按钮；禁用项只显示编辑。
- [ ] TC5-6 编辑 → 表单修改（名称/执行方式/脚本/启用）保存 → `/tasks` 刷新生效。
- [ ] TC5-7 任务完成后该行恢复普通态，显示「上次：ok / error: …」。

## TC6 日志抽屉
- [ ] TC6-1 「日志」→ 右侧半屏抽屉展示最近日志（时间/级别/标签/消息）。
- [ ] TC6-2 级别过滤（E/W/I/D）与清空生效。

## TC7 安全与稳定性
- [ ] TC7-1 页面 HTML 中无硬编码真实 IP/token/账号/密码（grep 检查）。
- [ ] TC7-2 未授权访问业务端点返回 401（含 /client 各内联 API 调用按 query token 鉴权）。
- [ ] TC7-3 `assembleDebug` 通过；安装运行正常；页面连续轮询 2 分钟无内存异常（服务端侧观察）。
- [ ] TC7-4 文档（docs/Client.md、spec、TEST_REPORT）不含真实 IP/token/凭据。

## 记录格式
每个通过项：`[PASS] TCx-x 描述`；失败项：`[FAIL] …（现象/日志，注意不落真实 IP/token）`。
