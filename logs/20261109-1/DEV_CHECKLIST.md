# DEV_CHECKLIST — 20261109-1（101-main-ui / MCP 工具补全）

## 环境
- [x] 真机在线（USB + 无线调试；adb devices 可见）
- [x] App 服务可达（health ok，authEnabled=true，token 用 `<token>` 占位）
- [x] 虚拟屏会话已建立：1600×2560 dpi=360，sessionActive=true
- [x] 基线 `assembleDebug` 通过（改动前）

## 基线测试（改动前，12 工具）
- [x] TC-A 计算器：点按 `1+2×3−4÷55 =` → `6.9272727…`（截图 04）
- [x] TC-B 天气：`app_launch` + `screen_tap` 热点城市「北京市」→ 北京天气详情（截图 08）
  - 注：未能用"搜索框输入"完成（无文本工具，G2）
- [ ] TC-C 日历：需中文文本输入 → 阻塞（G2）
- [x] TC-D 任务：`task_create` + `task_run` → 计算器显示 `1111111111`（截图 11）
  - 发现：`runCount/lastRunAt` 未更新（G8）；`task_run` 无结果（G5）；无 `task_stop`（G4）；脚本日志不可见（G7）

## 实现
- [ ] T1 核心：按键/文本/秒级 cron
- [ ] T2 脚本宿主 + 跨进程日志
- [ ] T3 MCP 工具（12→20）+ REST `/action/text`
- [ ] T4 调度统计修复 + 文档
- [ ] T5 覆盖安装 + 四用例复测
- [ ] T6 代码走查 + 回归

## 安全
- [x] 文档/测试记录仅用占位符（`192.168.x.x`、`<token>`）
- [x] 不卸载应用，仅 `adb install -r`
- [ ] 日志不含明文 token / IP
