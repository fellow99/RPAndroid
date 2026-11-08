# constitution.md — 工程宪法（原则）

> 状态：000-scaffold 确立。后续发展与演化需符合本宪法；违反处需显式记录并说明理由。

## 一、架构与模块

- **C-1 会话单一 & 操作单一入口**：同一时刻对被托管 App 的会话是"一块虚拟屏"；所有写操作（REST/MCP/自动化脚本/用户触摸）必须收敛到 **ScreenController**，不逐层直连 shell。
- **C-2 模块单一职责**：`core:shell`（托管基座）、`core:controller`（操作入口）、`core:server`（对外接口）、`core:script`（自动化脚本执行）、`app`（UI/呈现）。模块间只经明确定义的接口通信。
- **C-3 依赖单向**：`app → controller → shell`；`server → controller`；`script` 独立（经 Binder 代理）。禁止反向/循环依赖。

## 二、技术约束

- **C-4 AGP 内置 Kotlin**：所有模块只应用 `android.application` / `android.library` 插件；**不单独 apply `org.jetbrains.kotlin.android`**（AGP 9 builtInKotlin 默认，避免 extension 冲突）。
- **C-5 Node 引擎约束**：`node::Start` 一次/进程且崩溃即终态 ⇒ JS 执行器部署在独立 `:node` 进程，由调度 service 负责重启；不把 Node 跑在主进程。
- **C-6 arm64-only**：Node 引擎仅 arm64-v8a；全工程 ABI 限定 arm64。

## 三、能力与兼容

- **C-7 优雅降级**：平台能力探测失败必须降级而非崩溃；托管能力按 Android 版本门控（<12 降级、12–13 提示、14+ 完整）。
- **C-8 呈现无关控制**：操作逻辑不依赖呈现模式；浮窗/常规/headless 只是同一会话的不同渲染方式。

## 四、质量与工程

- **C-9 规范文档为真相**：功能/架构判断以 `specs/` 与 `docs/000-工程规划.md` 为准；实现反向同步完善规范文档。
- **C-10 提交规范**：提交信息使用 Conventional Commits 前缀（`feat`/`fix`/`docs`/`chore`/`build`/`refactor`/`test` 等），不写其它格式。
- **C-11 代码评审门禁**：里程碑完成 + 关键功能实现须经 code review（requesting/receiving-code-review）后方可推进。
- **C-12 安全底线**：**任何测试 URL、账号、密码、凭据、密钥都不得落盘文档/提交**；本地端服务默认仅本机/局域网并考虑 token 鉴权。

## 五、KPI / 验收基调

- 每个里程碑以"可编译构建 + 通过评审 + 真机可测"为完成标准（scope：000 为"可编译 + node 引擎入包"）。
