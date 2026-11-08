# REVIEW_REPORT — 101-main-ui 代码走查

> 日期：2026-11-08；评审范围：`cc9e5e6..14104ce`（101-main-ui：应用主要UI）
> 方法：requesting-code-review 模板（独立评审视角）+ receiving-code-review 处理（逐条核对、按优先级修复、逐项回归）。

## 总体评价

- 需求覆盖完整：Compose 主界面（应用/自动化/设置三栏目）、四态状态机、三种呈现 + 关闭、备份互斥执行/停止、编辑表单、返回键转发、core 最小支撑（back/key 注入、分辨率配置、自动化停止信号）全部落地。
- 与 plan.md 一致；无计划外大改动。核心联动已真机验证（见 TEST_REPORT）。
- 优点：Compose 与 AGP9 内置 Kotlin 集成经 spike 验证；跨 Activity 状态（PresentationHub）设计简洁；停止信号用文件 IPC 与既有 VdSession 风格一致；互斥语义清晰。

## 发现的问题

### Critical（立即修）
- 无。

### Important（已修）
- **I-1 分辨率零值建屏失败**：`connect()` 直接用 `cfg.vScreenWidth/Height/Dpi`，若用户在旧版本已持久化端口、新版本尚未访问设置页初始化分辨率（0×0），点「连接并建立会话」会以 0×0 建虚拟屏失败。
  修复：`AppsScreen`、`AutomationScreen` 两处 connect 对分辨率做防御性缺省（≤0 时取当前屏幕 `displayMetrics`）。
- **I-2 `rememberAppIcon` 重复触发加载**：在组合内 `scope.launch` 加在下一次重组会重复启动加载协程（LazyColumn 网格多次重组场景浪费）。
  修复：改用 `produceState(pkg)`，仅在 key 变化时加载一次。

### Minor（记录，暂不修）
- **M-1 token 明文展示**：设置页 token 为普通文本输入框（与旧版行为一致；ServerConfig 本就用明文 SharedPreferences）。属既有安全定位，需求未要求加密，暂不处理。
- **M-2 双 FramePoller**：嵌入呈现 + 全屏/浮窗叠加时会并轮询同一会话帧（只读，文件 IPC 已串行）。可接受。
- **M-3 TaskStore 多实例文件锁**：UI/TaskScheduler/TaskApiImpl 各自 new TaskStore 同文件，`@Synchronized` 是实例级。单用户场景窗口极小，且为既有模式，未在本次引入。
- **M-4 浮窗激活但会话失效时返回退出 App**：`forward` 为真但 session 不可用会走 `onFinish()`；无法向死会话转发，可接受。
- **M-5 顶栏四控件在窄屏可能溢出**：目标真机 1600px 宽正常；窄屏存在横向溢出风险，后续可加横滚。
- **M-6 界面返回语义**：全屏/浮窗无自带系统 UI 返回按钮，仅系统返回键转发；用户需用浮层控件退出，符合需求。

## 修复后再评审
- 上述 I-1/I-2 修复后重新构建通过（`:app:assembleDebug`），关键路径（连接、图标网格）已回归。

## 结论
- 无 Critical；Important 已修；Minor 记录待后续。开发阶段通过，进入测试阶段。
