# TECH.md — 技术选型

> 状态：000-scaffold。已落地项标 ✅，规划/待验证项标 ⏳。

| 类别 | 选型 | 版本 | 用途 | 状态 |
|------|------|------|------|------|
| 语言 | Kotlin | 2.2.10（AGP 内置 Kotlin） | 主开发语言 | ✅ |
| 构建 | Android Gradle Plugin | 9.2.1（builtInKotlin 默认） | 编译构建 | ✅ |
| Gradle | 9.4.1 | wrapper | 构建工具链 | ✅ |
| 编译 SDK | android-36 | — | compileSdk | ✅ |
| 最低 SDK | minSdk 24 | — | 安装与基础 UI | ✅ |
| 目标 SDK | 36 | — | targetSdk | ✅ |
| ABI | arm64-v8a | NDK 27.2.12479018 | Node 引擎仅此 ABI | ✅ |
| UI | Jetpack Compose | BOM 2025.06.01 | 配置/呈现界面 | ⏳（000 用纯 View，里程碑接 Compose） |
| AndroidX | core-ktx 1.16.0 / activity-compose 1.10.1 / lifecycle 2.9.0 | — | 基础 | ✅（部分） |
| Node 嵌入 | `io.github.fellow99:node-bridge` | 0.2.0 | JS 脚本执行器 embed API | ✅（依赖） |
| Node 引擎 | `io.github.fellow99:node-runtime` | 26.13.0（runtimeOnly） | libnode.so（arm64） | ✅（依赖+进 APK） |
| HTTP 服务 | Ktor | 待定 | REST / MCP / 视频 | ⏳ |
| MCP | MCP SDK（SSE / streamable HTTP） | 待定 | agent 工具接入 | ⏳ |
| 视频一期 | MJPEG-over-HTTP | 自实现 | 局域网实时帧 | ⏳ |
| 视频二期 | WebRTC（`org.webrtc:google-webrtc`） | 待定 | 低延迟双向 | ⏳ |
| 无障碍 | `AccessibilityService` | 平台 | 读控件树 + 手势 | ⏳ |
| shell | 无线调试配对 / 自实现 ADB 协议；root 可选 `su` | — | 虚拟屏 / 注入 | ⏳ |
| 序列化 | kotlinx.serialization / Jackson | 待定 | API 数据契约 | ⏳ |

## 关键选型理由

1. **AGP 9 内置 Kotlin（不单独应用 KGP）**：AGP 9.x `builtInKotlin` 默认开，再应用 `org.jetbrains.kotlin.android` 会与 AGP 争夺名为 `kotlin` 的 extension（参照工程实测）。故所有模块只用 `android.application` / `android.library` 插件。
2. **Compose 后置**：000-scaffold 用纯 View 占位以降低构建风险；Compose 属目标栈，待里程碑接 UI 时按 AGP9 内置 Kotlin 的 compose 集成方式另行验证再启用。
3. **Node 引擎 arm64-only**：`runtimeOnly` 引入、`abiFilters arm64-v8a` 限定，APK 打 `libnode.so`（约 87MB）。
4. **仓库镜像**：阿里云 google/public 前置，`google()`/`mavenCentral()` 回退——因本机访问 google maven 偶发不稳。
5. **Node 进程模型**：`node::Start` 一次/进程且崩溃即终态 ⇒ JS 执行器跑独立 `:node` 进程，调度 service（主进程）负责重启与编排（见 `core:script`）。

## 依赖清单（gradle/libs.versions.toml）

- **插件**：android-application 9.2.1、android-library 9.2.1
- **库**：core-ktx、lifecycle-runtime-ktx、compose-bom/ui/material3/activity-compose、node-bridge 0.2.0、node-runtime 26.13.0
- ⏳ 待里程碑：Ktor、MCP SDK、WebRTC、第三方 serialization

## 环境需求

| 项 | 要求 |
|----|------|
| 构建 JDK | 17+（本机 JDK 21 / Android Studio JBR） |
| Android SDK | android-36 平台、build-tools、NDK 27.2（arm64 交叉） |
| 真机目标 | arm64、Android 12+ 推荐（托管能力门控见 overall-plan） |
