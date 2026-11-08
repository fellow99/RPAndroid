# 008-client REVIEW_REPORT（requesting/receiving-code-review）

> 评审范围：`ebdae52..HEAD`（008-client：服务端桥 + /client 页面 + TaskRuntime）
> 提交：56cff93, e1ff548, 9415ea0, 59cbf14, 4a61dd8（+ 本回合 code-review-fix）
> 评审方式：requesting-code-review 语境构造（Spec/需求 + diff），receiving-code-review 逐条核验后处理。

## 评审对象
- 后端：`ManagedServer`（/client、/apps/{pkg}/icon、/action/key、/tasks/{id}/stop、/health.authEnabled、TokenAuth 放行 /client）、`TaskApi.stop`、`AppIconCache`、`TaskRuntime`、`TaskApiImpl`
- 前端：`ClientPage`（单文档 SPA：token 引导/错误浮窗、状态栏、视频回显 RTC/MJPEG 降级、四大菜单抽屉、自动化互斥/编辑、日志）

## 结论：发现并已修复 2 Important + 1 Minor；无 Critical
联调阶段已在真机发现并修复 3 处（stale outcome 误判、drawer-log 缺 id、bindEvents 未先绑、/client 未放行——均合入以上提交并复测通过）。

## Findings
### Critical
- 无。

### Important
1. **`TaskApiImpl.run()` 未兜底异常 → 运行态卡死**
   位置：`TaskApiImpl.run`（`scheduler.runNow(id)` 若抛异常（如后台 startForegroundService 受限），异常沿路由上抛 → 500，且 `TaskRuntime.runningTaskId` 永不释放（无结果文件可消费）。
   核验：代码确实无 try/catch；验证方式：其它异常场景类比 + 代码路径确认。
   修复：`runCatching { scheduler.runNow(id); true }.getOrElse { TaskRuntime.end(id); false }`。
2. **`AppIconCache` 缓存按条数（上限 64 条 × ≤1MB = 最坏 64MB）**
   核验：`LruCache<String, ByteArray>(64)` 默认 sizeOf=1/条；单图标实测 ~50KB，但最坏 64MB 对移动端不友好。
   修复：按字节计 `sizeOf=value.size`，`MAX_BYTES=8MB`；超大单条自动不入缓存（LruCache 立即淘汰）；注释已说明。

### Minor
3. `ClientPage.wireData()` 空函数死代码 → 已删除。
4. 双运行态源（App UI `PresentationHub` vs 服务端 `TaskRuntime`）——跨"App UI / REST"同时执行不互斥（各自内部互斥）。008 范围仅客户端侧，已在 TaskRuntime KDoc 明确记录为已知限制，不改（避免扩大本次改动面）。
5. stop 后的“cancelled”结果不写回 `lastStatus`（watchdog 在 `end()` 后不再跟踪）；残留结果文件由下次 `begin()` 清理，自愈。验收语义（停止恢复按钮）已满足，不做额外回写。

## 与需求/Spec 一致性
- FR-C-001..025 逐条核对通过（实现登记见 TEST_REPORT / DEV_CHECKLIST）。
- C-1 单入口（操作统一走 ScreenController）、C-3 依赖单向、C-7 降级、C-10 提交前缀、C-12 不落敏感信息：均满足。

## 处置
- 已按 receiving-code-review 处理：逐条核验 → 修 Important 1、2 + Minor 3 → 真机回归（run→running 跟踪→互斥→完成回写→stop→清理 全通过）。
- commit: `code-review-fix: ...`（见 git log）。
