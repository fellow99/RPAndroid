# 008-client TEST_REPORT

日期：2026-11-08 ｜ 需求：008-client（播控客户端 /client） ｜ 分支：master
环境：Redmi Note 11（enuma, SDK 33）真机 + 桌面 Chromium（adb forward localhost:8099，Playwright）。
鉴权：服务启用 token（device 侧配置，未落盘文档）；示例一律占位符。

## 结果总览
- **执行用例：TC1–TC7，共 26 项；通过 26，失败 0。**
- 联调阶段发现并修复的 bug（均复测通过）：
  1. TaskRuntime 误消费陈旧结果文件 → 运行态秒清（修复：begin 前 clearRunOutcome）。
  2. `/client` 被 TokenAuth 拦截 → 无 token 时无法渲染 token 浮窗（修复：/client 放行匿名）。
  3. 抽屉 id `drawer-log` vs 代码 `drawer-logs` → startMain 抛异常被 .catch 吞掉 → 错误浮窗误显（修复：id 对齐）。
  4. `bindEvents` 仅在 startMain 内绑定 → token 浮窗按钮不可点（修复：提前绑定）。
  5. RTCPeerConnection 不可用时 startRtc 仍开 WS → handleWs 对 null pc 抛 TypeError（修复：无 pc 直接降级 + 信迟忽略）。
  6. code review 发现：run() 未兜底异常导致运行态卡死、AppIconCache 未按字节限容（已修）。

## 关键验证证据
- REST：`/health`(authEnabled) / `/apps/{pkg}/icon`(PNG 234×234) / `/action/key`({ok:true}) /
  `/tasks/{id}/run`→`runningTaskId` 跟踪 → 互斥 `ok:false` → 完成回写 `lastStatus=ok` / `/tasks/{id}/stop` 清空运行态。
- 页面（Playwright 真机 forward）：title「ManagedFloatingApp 客户端」；token 引导（无/错/对）；错误浮窗；
  RTC:ice:CONNECTED（video ready=4, 800px）；MJPEG 降级（无 JS 错误）；应用抽屉(15 图标)＋切换；
  自动化互斥执行/停止/编辑；日志抽屉＋过滤；播控关抽屉；无会话「暂无会话」；恢复后回显。
- 代码质量门禁：requesting/receiving-code-review → `REVIEW_REPORT.md`（无 Critical）→ `code-review-fix` 提交。
- 安全：全仓 tracked 文件无真实 IP/token/凭据（存量 192.168.0.100 已替换 <host>）；页面无硬编码凭据。

## 遗留/已知限制
- App UI（AutomationScreen）与服务端 TaskRuntime 各自互斥，跨「App UI / REST」同时执行不互斥（008 客户端侧已满足，变革点已在 KDoc 记录）。
- stop 的 cancelled 结果不写回 lastStatus（停止语义已满足，残留结果由下次 run 清理）。
- 真机浏览器上的实机触摸注入（视频区手点）为人工验收项（自动化环境以 curl /action/* 与 dc.send 代码路径核验）。

## 提交
- 已有：spec、feat×2、fix×3、code-review-fix；待提交：docs(Client/API)、test 记录、chore(security redact)。
