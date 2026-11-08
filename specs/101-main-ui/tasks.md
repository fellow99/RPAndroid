# 101-main-ui 任务清单（tasks.md）

> 依赖顺序执行。每个回合计一个 commit（Conventional Commits）。

## T1 最小 core 支撑
- [ ] `ScreenController` 增加 `back(): Boolean = pressKey(KEYCODE_BACK)`；`VdSession` 增加 `pressKey(code): Boolean`。
- [ ] `AppScreenController` 实现 `back`/`pressKey`（委托 VdSession）；`VdSessionImpl` 增加 `command("key", code)`。
- [ ] `ServerConfig` 增加 `vScreenWidth/vScreenHeight/vScreenDpi`（默认当前屏幕）。
- [ ] `TaskScheduler.stop(taskId)`：取消已排闹钟 + 写停止信号文件。
- [ ] `ScriptService` runner.js：检查停止信号（拉取后 + 每次 host.invoke 前），命中则中止并上报。
- [ ] 编译：`./gradlew assembleDebug` 通过。

## T2 app 启用 Compose
- [ ] `app/build.gradle.kts`：`kotlin.compose` 插件 + `buildFeatures.compose=true` + compose 依赖。
- [ ] 编译基线通过（保留 spike 验证）。

## T3 导航壳 + 全局状态
- [ ] `PresentationHub` 单例（Compose state）：hostedApp、presentationMode、runningTaskId。
- [ ] `MainActivity` 改为 Compose：`AppShell`（Scaffold + NavigationBar：应用/自动化/设置）。
- [ ] 返回键转发骨架（FR-101-9）。
- [ ] commit: `feat(ui): add compose app shell with bottom navigation and back forwarding`

## T4 设置栏目
- [ ] 端口编辑（写 `ServerConfig.connectPort`）。
- [ ] token 编辑（写 `ServerConfig.token`）。
- [ ] 分辨率编辑（宽/高/dpi，默认当前屏，保存提示重建会话）。
- [ ] 无障碍启用跳转 + 当前状态显示。
- [ ] commit: `feat(ui): implement settings tab (port/token/resolution/a11y)`

## T5 应用栏目
- [ ] 四态状态机（S1 提示/S2 连接/S3 网格/S4 呈现）。
- [ ] 图标网格（LazyVerticalGrid，后台加载图标）。
- [ ] 呈现区域：嵌入画帧 + TouchMapper 回写 + 右上角 嵌入/全屏/浮窗/关闭。
- [ ] 全屏/浮窗起停与状态同步。
- [ ] commit: `feat(ui): implement apps tab with 4-state flow and icon grid`

## T6 呈现改造（ScreenActivity / FloatingService / PresentationHost）
- [ ] `PresentationHost` 嵌入渲染组件。
- [ ] `ScreenActivity`：返回转发 + 顶部悬浮控制（嵌入/浮窗/关闭）。
- [ ] `FloatingService`：`isActive` 静态标记，随起停更新。
- [ ] commit: `feat(ui): add back forwarding and mode controls to fullscreen/floating presentation`

## T7 自动化栏目
- [ ] 状态机复用（S1/S2）。
- [ ] 任务列表渲染（图标+名称、时间设置、启用开关）。
- [ ] 互斥执行/停止（runningTaskId 时其余隐藏）。
- [ ] 编辑表单（名称/App/启用/立即/间隔/cron/JS 脚本）+ 校验。
- [ ] commit: `feat(ui): implement automation tab with exclusive run/stop and edit form`

## T8 联调与打磨
- [ ] 真机编译安装运行；四态、呈现切换、返回转发、互斥执行、表单保存逐项验证。
- [ ] 会话未就绪路径不崩溃。
- [ ] commit: `fix(ui): polish error paths and session-edge cases`（如需要）

## T9 回归与文档
- [ ] 帖子代码走查（requesting-code-review），修复 Critical/Important（receiving-code-review）。
- [ ] 更新 `specs/101-main-ui/test-cases.md` 执行记录 + `logs/20261108-1/` 报告。
- [ ] commit: `test(ui): record main-ui test results`（如需要）
