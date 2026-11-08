# ARCHITECTURE.md — 系统整体架构

> 状态：000-scaffold（结构已确立，各模块为占位；实现按里程碑推进）。

## 架构总览

ManagedFloatingApp 是 Android 上的 **App 托管与自动化控制服务**。核心模型：**单一"被托管 App 会话" = 一块虚拟屏（VirtualDisplay）**。所有"看到什么、操作什么"都收敛到这块屏；上层（呈现模式 / REST / MCP / 视频流 / JS 自动化脚本）共用同一个 ScreenController 作为唯一操作入口。

```
┌────────────── ManagedFloatingApp (Android, arm64) ──────────────┐
│                                                                │
│  A. core:shell   Shell 助手进程 (shell UID)                     │
│     - VirtualDisplay 虚拟屏 + 启动/挪入目标 App                  │
│     - 输入注入 (tap/swipe/key/IWindowManager)                   │
│     - 方向/焦点处理 (14+ 完整)                                   │
│        │ ImageReader 帧(Surface 跨进程给 app) + 控制/注入        │
│  B. core:controller  ScreenController (唯一操作入口)             │
│     - 读: 截帧→PNG/JPEG/视频编码; 无障碍控件树→元素列表           │
│     - 写: 点击/滑动/长按/输入/按键/返回/启动/切换               │
│  C. app(呈现模式)  浮窗 / 常规布局 / headless                     │
│     - 同一帧 → SurfaceView / 流; 触摸→坐标换算→注入虚拟屏        │
│  D. core:server   REST API / MCP server / 视频流 (复用 B)       │
│  E. core:script   ScriptExecutorService + Node(:node 进程)      │
│     - host.register 代理 → 主进程 ScreenController               │
│  F. app(配置UI)   选App/任务/开关/时间/自动化脚本/状态日志              │
└─────────────────────────────────────────────────────────────────┘
```

## 分层与数据流

### 依赖方向（模块图）
```
app ──▶ core:controller ──▶ core:shell
   └──▶ core:server   ──▶ core:controller
   └──▶ core:script    （独立进程，经 Binder 代理调用 controller）
```
呈现模式（app 内）与对外接口（server）都只依赖 ScreenController，不直接触碰 shell。

### 主数据流
1. **读取路径**：虚拟屏帧（shell）→ ImageReader（app 进程）→ ScreenController → **呈现模式渲染** 与 **视频流/截图**；无障碍读控件树 → ScreenController → **元素列表**（REST/MCP/自动化脚本消费）。
2. **写入路径**：REST/MCP/自动化脚本/用户触摸 → ScreenController 坐标/动作 → 输入注入（shell）→ 虚拟屏上的目标 App。
3. **自动化服务路径**：ScriptExecutorService（主进程）→ Node 宿主能力（`host.invoke`，经 Binder 代理）→ ScreenController → 虚拟屏；执行状态/结果回传。

### 进程边界
| 进程 | 内容 | 原因 |
|------|------|------|
| 主进程 | App UI、ScreenController、server、调度 service | 常规 |
| shell 助手进程（shell UID） | 虚拟屏创建、注入、帧出口 | 需要 shell 权限（免 root 无线调试） |
| `:node` 进程（独立） | JS 执行 | `node::Start` 一次/进程且崩溃即终态 ⇒ 崩溃隔离 + 可重启 |

## 关键技术决策（ADR 摘要）

| # | 决策 | 备选 | 理由 |
|---|------|------|------|
| A-1 | 免 root：无线调试配对 + 内置 shell 助手 | root | 不要求用户 root；root 可作可选加速 |
| A-2 | 独立 VirtualDisplay 承载目标 App | MediaProjection | 唯一能"在自有窗口里承载并操作任意 App"的可行路径 |
| A-3 | ScreenController 作为唯一写入口 | 各层直连 shell | 收敛一致性（用户触摸与远程 agent 看到/操作同一实例） |
| A-4 | 单一会话 = 一块虚拟屏，多呈现模式 | 每模式建屏 | 模式切换不中断正在运行的任务/自动化脚本 |
| A-5 | Node 独立 `:node` 进程 | 同进程 | 崩溃隔离 + 可重启（node 引擎约束） |

## 跨模块关注点

- **错误处理**：能力探测失败优雅降级（不崩溃）；低版本功能门控。
- **鉴权**：本地端口服务默认仅本机/局域网，可加 token（后续里程碑）。
- **安全**：不把测试 URL、账号、密码、凭证等敏感信息落盘文档。
- **可观测**：状态/日志视图（配置 UI），供调试。

## 部署拓扑

- 手机（Android，arm64）：承载全部模块。
- 下游：局域网 Wi-Fi 直连手机本地端口；USB 用 `adb reverse tcp:PORT tcp:PORT` 映射到电脑 `localhost`。
- 无公网/跨网需求。

## 与规划文档的对应

架构细节与取舍依据见 [../docs/000-工程规划.md](../docs/000-工程规划.md)。每模块的规格（spec）与实现方案（plan）见对应模块文档。
