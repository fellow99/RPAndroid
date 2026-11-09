# 102-quick-start Specification — 首次使用（配对向导）

> Module: app（UI），core/shell（最小支撑：配对端口发现、自动打开无线调试）。
> Status: 设计（2026-11-09，specs-based-devflow）
> 参考：Mika `PhoneSetupFlow` / `WirelessDebuggingAutoOn`（只借鉴流程与文案思路，不引入聊天/智能体）；不落盘任何真实 IP/token。

## 1. Module Overview

### 1.1 Purpose
App 首次使用（未配对）时，在「应用」与「自动化」两个栏目**不显示技术配置项**，而是显示一个**简单、无术语**的向导，引导用户完成一次性的无线调试配对，之后自动进入既有会话/呈现流程。

首配难是当前最大上手门槛：现状 S1 只提示"请先配置 adbd 连接端口"（纯技术文案 + 手动），没有配对引导、没有配对状态、也没有配对码输入 UI。本需求把"第一次怎么用起来"讲清楚。

### 1.2 Problems Solved
- 首次使用无引导，用户不知道要开开发者选项/USB 调试/无线调试/输配对码。
- 无线调试有"重启 / 换 Wi-Fi 后系统自动关闭"的坑；本轮配对完成后自动写 `adb_wifi_enabled`，让用户**配好就不用再手动开**。
- 配对状态无持久化；配对成功后自动发现连接端口并建立会话，无需手动填端口。

### 1.3 Scope
- 新增「未配对」状态（`ServerConfig.paired`），`应用` / `自动化` 两栏未配对时显示向导。
- 向导步骤（文案无术语）：
  1. 打开开发者选项（连点版本号 7 次，一次性）。
  2. 打开 USB 调试 与 无线调试。
  3. 输入 6 位配对码（自动发现配对端口，找不到时可手动填端口）→ 完成配对（拿 RSA 钥匙）。
  4. 配对成功后：自动发现连接端口 → 建立会话 → 写 `adb_wifi_enabled`（自动打开无线调试，重启不丢）。
- `ManagedRuntime.startSession` 成功后（含自动恢复场景）尽力写 `adb_wifi_enabled`。
- 已配对用户（`paired=true` 或 `connectPort!=0`）不走向导，维持既有 S1–S4 流程，不回归。

### 1.4 不做（明确排除）
- 不阻塞已配置用户的既有流程（一旦 `paired=true` 或已有 `connectPort`，一律正常流程）。
- 不做"解除配对/重新配对"入口（后续再排）；不做 root 路径引导。
- 不做向导详细诊断（配对失败给一条可执行的"怎么办"即可）。
- 不落盘敏感信息（真实 IP / token / 配对端口一律占位或运行期变量）。

## 2. User Stories / 功能点

- 作为全新用户，打开「应用」或「自动化」栏目，看到的不再是"请配置 adbd 端口"，而是一步步教我怎么配的向导。
- 作为全新用户，我可以照着向导：连点版本号 → 开 USB 调试 → 开无线调试 → 在系统弹出的「使用配对码配对」里看到 6 位码 → 填进 App → 自动配对、连上、进到下一屏。
- 作为全新用户，我配完一次后，重启手机 / 换 Wi-Fi 也不用再手动开无线调试（App 自动替我打开）。
- 作为已配置用户，我照旧用（不被打扰，直接看应用/自动化原有内容）。

## 3. Functional Requirements

### FR-102-1 未配对判定与向导触发
- 新增 `ServerConfig.paired: Boolean`（默认 false）。
- `应用` / `自动化` 两栏判定：`needsWizard = !cfg.paired && cfg.connectPort == 0`。
  - `true` → 显示向导（替换现状 S1 的"去设置"入口）。
  - `false` → 走既有 S1/S2/S3/S4 流程（兼容旧用户：已有 connectPort 视为已配置，不弹向导）。
- 初始化默认：`paired=false`；旧版本升级用户因 `connectPort!=0` 自动跳过向导（不回归）。

### FR-102-2 向导步骤与文案（无术语，中文）
向导为**多步**：开发者选项 → USB 调试 → 无线调试 → 配对码 → 完成。每步对应系统操作 + 用户确认/输入；提供「跳过去改」的系统设置入口按钮（跳开发者选项 / 关于手机）。

- **Step 1 开发者选项**：文案"打开开发者选项：进入 设置 › 关于手机，连续点击『版本号』7 次（只做一次）"；按钮「去关于手机」。
- **Step 2 USB 调试**：文案"回到 设置 › 开发者选项，打开『USB 调试』"；按钮「去开发者选项」。
- **Step 3 无线调试**：文案"在同一页继续打开『无线调试』；然后点『使用配对码配对设备』，屏幕上会出现一个 6 位数字配对码"；按钮「去开发者选项」。
- **Step 4 输入配对码**：6 位数字输入框 + 「完成配对」；自动发现配对端口，发现不了时显示可选的"配对端口"高级输入（文案仍尽量简单："如果自动找不到，把屏幕上显示的端口号填在这里"）。
- **Step 5 完成**：配对+连接+会话成功 → 提示"已准备好，可以开始使用了"，回到栏目正常内容（应用/自动化）。

每步底部都有「我已经做好了」(去下一步) 与「上一步」；配对过程显示加载态。

### FR-102-3 配对执行
- 配对端口：优先自动发现（mDNS `_adb-tls-pairing`，`NsdAdbServiceFinder`）；发现不到走手动输入（可选）。
- 执行：`NoRootShell.pair("127.0.0.1", pairPort, code)`（配对仅连本机 adbd）。
- 配对成功 → `ManagedRuntime.startSession(connectPort=null)`（内部自动发现连接端口并建立会话，成功后把实际端口写入 `ServerConfig.connectPort`）。
- 配对失败：显示一句"怎么办"（如"请确认屏幕上的 6 位码没写错、配对窗口还开着，再试一次"），不崩溃，可重试。

### FR-102-4 自动打开无线调试
- **根因**：Android 在重启/换 Wi-Fi 后会关闭无线调试（`adb_wifi_enabled` 变 0）；而“经已配对 shell 会话补写”会死锁（没会话→连不上→写不了）。
- **解法**：用户用电脑**一次性**授权（不弹框、不需 root）：
  `adb shell pm grant com.managedfloating.app android.permission.WRITE_SECURE_SETTINGS`
  授权后 App 在启动自动恢复与建立会话前先调 `WirelessDebugging.ensureEnabled()`，直接把 `adb_wifi_enabled` 写回 1（`Settings.Global`）。
- 未授权/无 Wi-Fi/被系统拨回时静默降级（返回 NO_WIFI / NO_PERMISSION / REFUSED），由界面引导手动开启。
- 注意：重启无线调试后**连接端口会变**，需自动发现或用户手动填（见 §7）。

### FR-102-5 配对状态持久化
- 配对成功 + 会话建立后写 `ServerConfig.paired = true`。
- 重进 App / 重启后：`needsWizard` 判定依赖 `paired` 与 `connectPort`，已配对用户不掉进向导。
- 向导完成后，`应用` / `自动化` 两栏按既有状态机继续（S2 已建会话会直接进 S3 网格/呈现）。

### FR-102-6 兼容既有流程
- 未配对时，`设置` 栏目**照常可用**（端口/token/分辨率/无障碍/接入信息），供已配置用户/高级用户使用，不被向导替代。
- `paired=true` 的用户在设置里不显示向导相关 UI（除非后续做"重新配对"）。

## 4. Key Entities / 数据契约

- `ServerConfig.paired: Boolean` —— 新增，持久化到既有 SharedPreferences（`mfa_server`）。
- `needsWizard = !cfg.paired && cfg.connectPort == 0` —— 两栏共用的向导触发器。
- `<PairCodeInput>`：6 位数字字符串；配对端口可选（自动发现优先）。
- `NoRootShell.pair(host, pairPort, code): Boolean`、`ManagedRuntime.startSession(connectPort:Int?, w,h,d): Boolean` —— 复用现有 API。
- `NsdAdbServiceFinder.findPorts(PAIRING, timeout)` —— 配对端口发现（复用现有 core/shell）。

## 5. Acceptance Scenarios

### Scenario 1：新手机（未安装过本 App）
- Given 全新设备安装本 App，`paired=false、connectPort=0`
- When 打开 `应用`（或 `自动化`）
- Then 显示**配对向导**（非"请配置端口"），无列表/按钮等其它 UI
- When 依次照做：开开发者选项／开 USB 调试／开无线调试／填 6 位配对码
- Then 配对成功 → 自动连接会话 → 栏目进入正常内容（应用=图标网格；自动化=任务列表）
- When 重启 App/手机
- Then `paired=true` 不再弹向导；会话可自动恢复

### Scenario 2：配对失败与重试
- Given 向导停在配对码步
- When 填错 6 位码点「完成配对」
- Then 提示配不上（一句怎么做），不崩溃；可重填重试
- When 配不上且没自动发现端口
- Then 显示可选的配对端口输入

### Scenario 3：旧用户不回归
- Given 已有 `connectPort!=0` 的用户升级
- When 打开 `应用`
- Then 不弹向导，走既有 S1/S2/S3/S4（维持现行为）
- Given `paired=true` 且会话未建
- When 打开 `应用`
- Then 显示「连接并建立会话」（S2），不进向导

### Scenario 4：自动打开无线调试
- Given 配对成功、会话已建
- When 检查系统 `adb_wifi_enabled`
- Then 已被写为 1（重启/换 Wi-Fi 后再次起会话也会补写）

## 6. Non-Functional Requirements

- 文案遵循：**尽量简单、无技术术语**（不出现 adb、shell、RSA、端口等词；配对端口仅在自动发现失败的高级输入里出现，带大白话解释）。
- 构建稳定：沿用现状 Compose/Material3；`./gradlew assembleDebug` 通过。
- 健壮：配对/连接任何分支不崩溃；失败可重试友好提示。
- 语言：界面文案中文；代码注释中文风格与现状一致。
- 不落盘敏感信息：新文档/日志一律占位（`192.168.x.x` / `<token>` / 端口为变量）。

## 7. Assumptions & Constraints

- 配对只连**本机** adbd（`127.0.0.1`），沿用 `AdbHosts.ownSocketAddress` 的本地校验（core/shell 既有）。
- **配对端口 ≠ 连接端口**：配对窗口里的 IP:端口是**配对端口**（仅配对时用）；无线调试页**顶部**显示的 IP:端口是**连接端口**（会话连接用）。两者会随开窗口/重启变化。配对成功后若自动发现连接端口失败，向导提供手动「无线调试连接端口」输入 + 重新连接。
- **配对成功即完成**：只要设备「已配对设备」里信任了本 App 的钥匙，`paired=true` 即落盘；后续连接失败不再回退/误报为配对失败。
- "自动打开无线调试"走**已配对后的 shell 权限**执行系统设置写入；Web 不需要开放新接口。
- `paired` 与 `connectPort` 判定以 `ServerConfig` 为准，是首次使用状态机的唯一事实来源。
- 不做 root；不做配对窗口自动读取（用户手动填 6 位码，符合"尽量简单"）。

## 8. Dependencies
- `core:shell`：`NoRootShell.pair/connect/run`、`NsdAdbServiceFinder`、`AdbServiceTypes.PAIRING`（已有）。
- `core:server`：`ServerConfig`（新增 `paired`）。
- `app`：`ManagedRuntime.startSession`、`AppShell`（应用/自动化两栏接线）、`SessionGates`（向导组件放 `ui/`）。
- 真机：联调验证（仅本机使用，不留存真实地址/码）。

## 9. 已知问题（备案）
- **系统「使用配对码配对」窗口存活时间短（实测约 20 秒）**：停在"输入配对码"一步时若用户超时，重试前先提醒"如果配对失败，回到无线调试再点一次『使用配对码配对设备』"；每次重开窗口端口会变。
- **mDNS 配对端口发现在某些网络/系统（组播被拦）可能失效**；以手动输入端口作为兜底（已在真机 Xiaomi/Android16 复现：自动发现『没有自动找到配对入口』，手动输入配对端口成功）。
- **连接端口自动发现不可靠**（实测 Xiaomi/Android16 上 mDNS/属性有时读不到）：配对成功后先自动 `startSession(null)`；失败则显示「无线调试连接端口」输入（无线调试页顶部端口）+ 重新连接。
