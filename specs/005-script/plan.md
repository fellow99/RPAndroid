# 005-script Technical Plan (As-Built / Design)

> Module: 005-script
> Corresponding spec: [spec.md](./spec.md)
> 源码：`core/script/`（现仅有 `ScriptExecutorService.kt` 接口占位 + node 依赖）

## 1. Technical Context
### 1.1 Runtime Environment
- 前台 Service（main 进程）+ 独立 `:node` 进程（JS 执行）。
- Node 引擎：`io.github.fellow99:node-runtime:26.13.0`（arm64，runtimeOnly 已入 APK）。
- 嵌入 API：`io.github.fellow99:node-bridge:0.2.0`（`NodeRuntime` / `rt.host.register` / `rt.start`）。

### 1.2 Dependencies
- node-bridge 0.2.0（implementation）、node-runtime 26.13.0（runtimeOnly）。
- 调度：`AlarmManager`（setExactAndAllowWhileIdle，cron/间隔唤醒）。

## 2. Constitution Compliance
- C-5 Node 独立进程：✅（`:node` 进程 + 调度 service 负责重启）。
- C-2 单一职责：✅。

## 3. Research Findings
- node-bridge 关键约束：`node::Start` 一次/进程且崩溃即终态 ⇒ 重启需独立进程。
- `assets` 载荷须物化到 `filesDir`（桥已处理）。
- JIT/W^X 需真机验证（arm64、模块化 targetSdk）。
- host.register 运行在主线程、返回 JSON（跨进程代理到 ScreenController 需用 Kotlin 协程 + Binder 往返）。

## 4. Data Model
- 见 [overall-data-model.md](../overall-data-model.md)：Task、Schedule、ScriptRunRecord。

## 5. Interface Contracts
### 5.1 Provided
- `ScriptExecutorService`（定位：运行态查询）。
- 计划接口：`submit`、`schedule`（立即/间隔/cron）、`cancel`、`observeStatus`。
- 宿主能力：`screen.tap/swipe/type/back`、`fs.*`、`http.*` 等（经 host.register + Binder 代理）。

### 5.2 Consumed
- `ScreenController`（主进程，经 Binder 代理回 Node 宿主调用）。

## 6. Implementation Strategy
### 6.1 Architecture Pattern
- 调度 service（主进程）编排；`:node` 进程运行单个任务 run；经 Bound Service/AIDL 双向通信。
- 宿主 `host.invoke` → 主进程 ScreenController。

### 6.2 Key Algorithms
- cron 解析 + 下次触发计算；错过后是否补跑（策略待定）。
- Node 崩溃检测与重启（指数退避）。

### 6.3 Error Handling
- 脚本异常 → Node 进程退出码/日志 → 记录为 failed 并可重启。

### 6.4 Performance
- 跨进程宿主调用有往返开销；对 UI 操作频度可接受；日志异步落盘。

## 7. Testing Considerations
- JVM：cron 解析、调度状态机。
- 真机：Node 冒烟（host.invoke 回环）、崩溃重启、cron 到点执行。

## 8. File Inventory
| File | Purpose | Lines |
|------|---------|-------|
| `core/script/src/main/java/com/managedfloating/core/script/ScriptExecutorService.kt` | 接口占位 | ~20 |
| `core/script/build.gradle.kts` | node-bridge/node-runtime 依赖 | — |
