# TEST_CHECKLIST — 20261109-1（009-mcp-tools / MCP 工具补全）

> 环境：真机 Redmi M2105K81C / Android 13（MIUI），虚拟屏 1600×2560 dpi=360，
> 覆盖安装（`adb install -r`，**未卸载**）。所有地址/凭证用占位符。

## 0. 基线（改动前，12 工具）

- [x] MCP `tools/list` = 12
- [x] TC-A 计算器点按 → `6.9272727…`（改前已通）
- [x] TC-B 天气热点城市进入北京详情；**搜索框输入中文不可行**（无文本工具）
- [ ] TC-C 日历倒数日：阻塞（需中文输入）
- [x] TC-D 任务执行 10 次点击成功（`1111111111`）；但 `runCount=0`（G8）、无结果/停止工具

## 1. 新增工具单测（工具级）

- [ ] `status` 返回 sessionActive/hosted/vScreen/runningTaskId
- [ ] `screen_key`：`back` 注入成功（code=4）
- [ ] `screen_text`：中文写入天气搜索框并触发搜索
- [ ] `app_close`：关闭承载 App，`hostedApp=null`，会话保持
- [ ] `task_get` / `task_stop` / `task_run(waitMs)` 可用
- [ ] `logs` 读/清统一日志，脚本日志可见（tag=Script）
- [ ] REST `POST /action/text` 返回 ok

## 2. 验收用例（真机）

- [ ] TC-A 计算器 `1+2*3-4/55`
- [ ] TC-B 天气搜索「北京」
- [ ] TC-C 日历倒数日（2027-01-01 / 元旦到了 / 提前3天20:00 / 闹钟开）
- [ ] TC-D 任务「测试连续按键」（cron 每分钟 00 秒，C 后 30ms×10 点 1）
  - [ ] cron 精确在 :00 秒触发
  - [ ] `runCount/lastRunAt/lastStatus` 正确
  - [ ] 运行中 `runningTaskId` 可见
  - [ ] `task_stop` 可中止（部分点击后停止）
  - [ ] 停用后 `nextRunAt=0`

## 3. 回归

- [ ] TC-A~TC-D 在最终构建上复测通过
- [ ] `CronTest`（含 5/6 字段）通过
- [ ] `assembleDebug` 通过
- [ ] 重启/覆盖安装后 `BootReceiver` 重排（日志 `boot reschedule done`）
