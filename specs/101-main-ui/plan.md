# 101-main-ui 实现计划（plan.md）

> 依据 `spec.md`。目标是：Compose 主界面（应用/自动化/设置）+ 三呈现 + 返回转发 + 最小 core 支撑。

## 1. 变更范围

| 模块 | 文件 | 变更 |
|---|---|---|
| app/build.gradle.kts | — | 启用 compose（plugin + buildFeatures + deps） |
| app/src/main/…/ui/MainActivity.kt | 重写 | Compose 壳 + 底部导航 + 返回转发 |
| app/src/main/…/ui/AppShell.kt | 新增 | 导航脚手架（NavigationBar + Scaffold） |
| app/src/main/…/ui/AppsScreen.kt | 新增 | 四态状态机 + 图标网格 + 呈现区域 |
| app/src/main/…/ui/PresentationHost.kt | 新增 | 嵌入画帧渲染 + TouchMapper 回写 |
| app/src/main/…/ui/AutomationScreen.kt | 新增 | 任务列表 + 互斥执行/停止 |
| app/src/main/…/ui/AutomationEditDialog.kt | 新增 | 编辑表单 |
| app/src/main/…/ui/SettingsScreen.kt | 新增 | 端口/token/分辩率/无障碍 |
| app/src/main/…/ui/PresentationHub.kt | 新增 | 跨 Activity 应用级状态（hostedApp/呈现模式/运行任务） |
| app/src/main/…/present/ScreenActivity.kt | 改 | 返回转发 + 顶部悬浮控制（嵌入/浮窗/关闭） |
| app/src/main/…/present/FloatingService.kt | 改 | 增加 isActive 标记 |
| core:controller ScreenController.kt | 改 | 新增 `back()`（→ pressKey(4)） |
| core:controller AppScreenController.kt | 改 | 实现 `back()`；`VdSession` 新增 `pressKey` |
| core:controller VdSessionImpl.kt | 改 | `command("key", code)` 实现 |
| core:server ServerConfig.kt | 改 | 新增 分辨率字段 |
| core:script ScriptService.kt | 改 | runner 支持停止信号（文件） |
| app/…/script/TaskScheduler.kt | 改 | 新增 `stop(taskId)`（取消闹钟 + 停止信号） |

## 2. 关键技术决策

1. **Compose 集成**：`alias(libs.plugins.kotlin.compose)` + `buildFeatures.compose=true` + BOM/ui/material3/activity-compose（spike 已验证通过）。
2. **跨 Activity 状态**：单例 `PresentationHub`（Compose `mutableStateOf`），ScreenActivity/FloatingService 与其同步（hostedApp、模式标记）。Activity 进出时同步。
3. **返回转发**：`MainActivity.onBackPressedDispatcher` 与 `ScreenActivity` 的 `BackHandler` 判断规则（FR-101-9）。`FloatingService.isActive` 供 MainActivity 判断。
4. **停止信号**：主进程写 `filesDir/mfa-stop/<taskId>.flag`，runner.js 在拉取任务后与每次 `host.invoke` 前检查目标停止文件，命中则跳过并报告 `cancelled`。
5. **分辨率默认**：App 首启/未配置时 `vScreenWidth/Height = displayMetrics` 当前值，启动会话时注入 `ManagedRuntime.startSession(port, vw, vh, vdpi)`。
6. **图标加载**：`packageManager.getApplicationIcon(pkg)` → Compose `Image`（`asImageBitmap`）。

## 3. 实现顺序（依赖关系）

1. core 支撑（back 注入、ServerConfig 分辨率、ScriptService 停止信号、TaskScheduler.stop）→ 可独立编译。
2. app 构建改造（Compose 启用）→ 编译基线。
3. `PresentationHub` + `MainActivity` 导航壳 + 返回转发骨架。
4. `SettingsScreen`（状态机依赖的配置入口先行）。
5. `AppsScreen`（四态 + 图标网格 + 呈现区域）。
6. `ScreenActivity`/`FloatingService` 改造；`PresentationHost` 嵌入渲染。
7. `AutomationScreen` + 编辑表单（互斥执行/停止）。
8. 真机联调 + 样式打磨。

## 4. 风险与对策

- **Compose 构建回退**：spike 已通过；若 AGP/内置 Kotlin 回归，保存隔离提交回滚点。
- **运行态跨进程可观测弱**：运行态以主进程 UI 时钟为准（符合互斥语义），停止用文件信号尽力而为。
- **图标/label 解析**：游标网格加载大数据量时列表化（LazyVerticalGrid），图标解码放后台。
- **会话判定竞态**：`isSessionActive()` + `hostedApp` 组合决定四态；会话失效时按钮/操作统一报错提示，不崩溃。
