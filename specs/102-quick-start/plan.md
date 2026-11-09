# 102-quick-start 实现计划（plan.md）

> 依据 `spec.md`。目标：首次使用（未配对）时在「应用 / 自动化」两栏显示无术语配对向导，配对成功后自动连接会话并打开无线调试。

## 1. 变更范围

| 模块 | 文件 | 变更 |
|---|---|---|
| core:server | `…/core/server/ServerConfig.kt` | 新增 `paired: Boolean`（持久化 `mfa_server`） |
| app | `…/app/ui/PairingWizard.kt` | 新增：向导 UI + 状态机（Step1–Step5） |
| app | `…/app/ui/AppsScreen.kt` | 改：`needsWizard` 分支渲染向导（TP 前置） |
| app | `…/app/ui/AutomationScreen.kt` | 改：同上前置向导 |
| app | `…/app/ManagedRuntime.kt` | 改：`startSession` 成功后写 `adb_wifi_enabled` |
| app/ui | `…/app/ui/SessionGates.kt` | 改（可选）：`PortNeededContent` 文案微调，或新增共用向导入口组件 |

## 2. 关键技术决策

1. **向导状态机（纯 UI 内）**：向导自身有 step 状态（开发者选项 / USB 调试 / 无线调试 / 配对码 / 完成），配对进度放后台线程，主线程回调刷新。向导组件 `PairingWizard(onDone)`，`onDone` 后上层重算 `needsWizard`/会话状态。
2. **配对端口发现**：复用 `NsdAdbServiceFinder(context).findPorts(AdbServiceTypes.PAIRING, timeout)`（core/shell 已有）；后台线程发现，超时（约 6s）后显示"可选手动端口"输入。
3. **配对执行**：`ManagedRuntime.shell()?.pair("127.0.0.1", pairPort, code)`（`NoRootShell.pair` 内部已做本机地址校验），后台线程执行，主线程刷新结果。
4. **会话建立**：配对成功后 `ManagedRuntime.startSession(connectPort = null, w, h, d)` —— `VdSessionImpl.start()` 内部 `NoRootShell.connect(null)` 会依次试缓存/mDNS/属性/扫描自动发现连接端口；成功后回写 `ServerConfig.connectPort`。
5. **自动打开无线调试**：`ManagedRuntime.startSession` 成功分支加
   `runCatching { sh.run("settings put global adb_wifi_enabled 1") }`（shell 权限，失败仅记 MfaLog）。自动恢复路径同样受益。
6. **`paired` 落盘时机**：配对 + 会话成功后在 UI 侧写 `cfg.paired = true`（单一消费者，避免 core 反向依赖 UI 语义）。
7. **兼容判定**：`needsWizard = !cfg.paired && cfg.connectPort == 0`。向导完成后 `connectPort` 已由 startSession 写回，故即使 paired 写入延迟也不残留向导。

## 3. 实现顺序（依赖关系）

1. core:server `ServerConfig.paired`（可独立编译）。
2. app `PairingWizard` 组件（Step1–4 文案 + 配对执行 + 完成）→ 先能独立展示。
3. `AppsScreen` / `AutomationScreen` 前置向导分支。
4. `ManagedRuntime.startSession` 写 `adb_wifi_enabled`。
5. 编译 + 真机联调（全新设备路径 + 旧用户不回归路径）。

## 4. 风险与对策

- **配对码窗口超时**：失败提示引导"再去无线调试点一次『使用配对码配对设备』"。
- **mDNS 发现不到配对端口**：手动端口输入兜底（仅高级场景显示，文案用大白话）。
- **`connectPort` 回写竞态**：startSession 成功时同步写 `cfg.connectPort`（既有逻辑），`needsWizard` 随即为 false，双保险。
- **写 `adb_wifi_enabled` 失败**：仅日志，不阻塞会话（尽力而为）。
