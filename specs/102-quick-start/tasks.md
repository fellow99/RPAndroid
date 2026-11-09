# 102-quick-start 任务清单（tasks.md）

> 依赖顺序执行。每个回合计一个 commit（Conventional Commits，git-commit 技能约定）。

## T1 core:server 配对状态
- [ ] `ServerConfig` 新增 `paired: Boolean`（KEY `"paired"`，默认 false）。
- [ ] commit: `feat(config): add paired flag for first-use wizard gating`

## T2 向导组件
- [ ] 新增 `app/…/ui/PairingWizard.kt`：
  - Step 状态枚举（DeveloperOptions / UsbDebugging / WirelessDebugging / PairCode / Done）。
  - Step1–3 文案 + 「去关于手机」「去开发者选项」系统设置入口（`Settings.ACTION_DEVICE_INFO_SETTINGS` / `ACTION_APPLICATION_DEVELOPMENT_SETTINGS`）。
  - Step4：6 位数字输入框 + 「完成配对」；后台线程发现配对端口（mDNS `PAIRING`），失败显示可选手动端口输入。
  - 配对执行：`ManagedRuntime.shell()?.pair("127.0.0.1", pairPort, code)` → 成功后 `ManagedRuntime.startSession(null, w, h, d)` → 成功后 `cfg.paired = true` + 回调 `onDone`。
  - Step5：完成提示。
  - 失败：一句"怎么办" + 可重试；加载态；上一步/下一步。
- [ ] commit: `feat(ui): add first-use pairing wizard with plain-language steps`

## T3 两栏接线
- [ ] `AppsScreen`：`when { needsWizard -> PairingWizard(onDone=…) ; … }`（放在 S1 之前）。
- [ ] `AutomationScreen`：同样前置向导。
- [ ] commit: `feat(ui): show pairing wizard on first use in apps and automation tabs`

## T4 自动打开无线调试
- [ ] `ManagedRuntime.startSession` 成功分支：`runCatching { shell.run("settings put global adb_wifi_enabled 1") }`（MfaLog 记录）。
- [ ] commit: `feat(runtime): auto re-enable wireless debugging after successful session`

## T5 真机联调与打磨
- [ ] 全新设备（未安装过）：装包 → 应用/自动化两栏都出现向导 → 逐步完成 → 配对成功自动进图标网格/任务列表 → 重启不弹向导。
- [ ] 旧路径不回归：预置 `connectPort` 后进两栏 → 不弹向导（S1/S2 原样）。
- [ ] 配对失败/超时提示友好；自动发现端口失败时手动输入兜底。
- [ ] 检查确认无敏感信息落入文档/日志。
- [ ] commit: `fix(ui): polish onboarding failure paths`（如需要）

## T6 代码走查与回归
- [ ] requesting-code-review：按 spec 走查 diff，修复 Critical/Important（receiving-code-review）。
- [ ] 补充/更新 `specs/102-quick-start/test-cases.md` 执行记录 + `logs/20261109-2/` 报告。
- [ ] commit: `test(ui): record quick-start wizard test results`（如需要）
