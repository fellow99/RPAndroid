# overall-plan.md — 系统整体技术方案

> 状态：000-scaffold。对应 overall-spec.md，描述 HOW。

## 1. 技术上下文

- 运行时：Android（arm64-v8a），minSdk 24 / compileSdk 36 / targetSdk 36。
- 构建：AGP 9.2.1（builtInKotlin 默认）+ Gradle 9.4.1 + JDK 21。
- 关键依赖：node-bridge 0.2.0（嵌入 API）、node-runtime 26.13.0（引擎，runtimeOnly）；Ktor / MCP SDK / WebRTC / Compose（待里程碑接入，见 TECH.md）。
- 仓库镜像：阿里云 + google/mavenCentral 回退。

## 2. 宪法合规自查

| 条款 | 状态 | 说明 |
|------|------|------|
| C-1 单一操作入口 | ✅ 规划 | ScreenController 统一接入（000 仅接口占位） |
| C-2 模块单一职责 | ✅ | 五个模块职责清晰 |
| C-3 依赖单向 | ✅ | 模块图见 ARCHITECTURE.md |
| C-4 AGP 内置 Kotlin | ✅ 落地 | 各模块仅 android 插件 |
| C-5 Node 独立进程 | ✅ 规划 | `:node` 进程承载 JS 执行器 |
| C-6 arm64-only | ✅ 落地 | abiFilters arm64-v8a |
| C-7 优雅降级 | ✅ 规划 | 版本门控 + 能力探测 |
| C-8 呈现无关控制 | ✅ 规划 | 同一会话多呈现 |
| C-12 安全底线 | ✅ | 无凭据入档 |

## 3. 实施策略（里程碑）

| 里程碑 | 内容 | 完成标准 |
|--------|------|---------|
| **M0 000-scaffold** | 工程骨架 + 模块占位 + node 引擎入包 | 可编译；APK 含 libnode* |
| M1 托管 POC | 免 root 无线调试配对 + shell 助手建虚拟屏 + 启动 App + 截图 + 注入点击（headless + REST 最小集） | 真机可跑通一条链路 |
| M2 呈现与操作 | 浮窗/常规/headless 三模式；触摸回写；token 鉴权；会话自动恢复 | ✅ **已完成**（真机验证 /frame PNG、token 401/200） |
| M3 脚本任务 | ScriptService(:node 进程) + NodeRuntime + IScreenControl Binder + 立即/间隔/cron | ✅ **已完成**（JS 执行并操作虚拟屏，INTERVAL 8s 自动触发） |
| M4 对外协议 | MCP(SSE) + MJPEG 视频流 + adb reverse 接入 | agent/外部系统可调用 |
| M5 打磨 | 状态/日志、错误恢复、低版本门控、文档完善 | 稳定可用 |

## 4. 跨模块关注点

- **错误处理**：每层能力探测，失败返回明确错误码而非崩溃；调度失败可重试/记录。
- **日志**：统一日志通道，供配置 UI 查看与排查。
- **安全**：端服务默认本机/局域网，接 token 鉴权（M4）；敏感信息不入档。
- **并发/一致性**：ScreenController 串行化对虚拟屏的写操作，避免竞态。

## 5. 测试策略

- **单元**：纯逻辑（坐标换算、调度语义、序列化）JVM 单测。
- **集成**：真机 M1/POC 验证"建屏→启动→截图→注入"链路。
- **接口**：REST/MCP 用 curl / MCP 客户端对 localhost/adb reverse 地址回归。
- **脚本**：node 执行器冒烟（host.invoke 回环）。
- 详见 `overall-test-cases.md`。

## 6. 部署策略

- 手机（arm64）为唯一运行环境；下游经局域网直连或 USB adb reverse 接入。
- 无公网/跨网，无服务端部署。
