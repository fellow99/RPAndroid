# REVIEW_REPORT.md — 000-scaffold Code Review

**日期：** 2026-11-07
**评审范围：** 000-scaffold（工程骨架 + 规范文档），范围 `34cad64` .. `0a8708d`（脚手架 + docs 两次提交）
**评审方式：** requesting-code-review 方法（orchestrator 自审 diff）+ receiving-code-review 方法（逐项核对）

## 评审结论

评审对象为**纯脚手架**（无业务逻辑），因此无需功能性修复。以下为分类发现。

## Critical（须立即修复）

- 无。

## Important（推进前处理）

- 无阻塞项。

## Minor（记录，后续处理）

| # | 位置 | 发现 | 处理 |
|---|------|------|------|
| M1 | `gradle/libs.versions.toml` | compose 库条目与 `kotlin-compose`、`kotlin-serialization` 插件条目暂未被任何模块引用（Compose 后置于 M-M2） | 保留以记录选型意图；接入时再启用 ↩️ 留观 |
| M2 | `app/build.gradle.kts` + `core/script/build.gradle.kts` | `runtimeOnly(libs.node.runtime)` 两处重复声明 | 有意为之（注释已说明），保证引擎入包；留观 |
| M3 | `core/{shell,controller,server,script}/.../AndroidManifest.xml` | 空清单 | 占位所需，正常 |
| M4 | `core/server/.../ManagedServer.kt` | KDoc 用全限定名引用 controller.ScreenController | 正确，仅注释 |

## 验证核对（receiving 阶段）

- ✅ **无敏感信息落盘**：`git ls-files` 未含 `local.properties`（内含 sdk.dir，已 gitignore），无 .env/keystore。符合 C-12。
- ✅ **无陈旧引用**：`core:interface` / `com.managedfloating.core.interface` 无残留（已改名 `core:server`）。
- ✅ **构建通过**：`:app:assembleDebug` 成功；APK 含 `libnode.so` / `libnode_bridge.so` / `libnode_host_client.so` / `libc++_shared.so`（arm64）。
- ✅ **单元冒烟**：000-B-001 / 000-B-002 通过（见 overall-test-cases.md）。
- ✅ **文档链接**：specs/README.md 全链接指向真实文件；22 文档 / 6 模块目录计数准确。
- ✅ **Git**：提交均用 Conventional Commits（`chore:`、`docs:`）；分支未改动（master）。

## 变更评估

- 架构符合 ARCHITECTURE.md：单会话多呈现、ScreenController 单入口、shell/server/script 职责分离。
- 版本门控、node 独立进程等为规划（M1+ 实现），000 未实现属预期。

## 下一步建议

- 000-scaffold 达到完成标准（可编译 + node 引擎入包 + 规范文档套件）。
- 建议进入 M1（托管 POC）：免 root 无线调试配对 + 建虚拟屏 + 启动 App + 截图 + 注入点击。
