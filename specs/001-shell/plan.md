# 001-shell Technical Plan (As-Built / Design)

> 本文档描述托管基座的技术方案（000 为设计；随 M1 实现校正为 as-built）。
> Module: 001-shell
> Corresponding spec: [spec.md](./spec.md)
> 源码：`core/shell/`（现仅有 `ShellController.kt` 接口占位）

## 1. Technical Context
### 1.1 Runtime Environment
- 运行于主进程（管理）与 shell 助手进程（shell UID，承担高权限操作）之间。
- 免 root 路径：无线调试配对（6 位码，本机完成）→ 经 ADB 协议启动 `app_process` 作为 shell 助手。
- root 路径（可选）：`su` 直连同一种 shell 助手。

### 1.2 Dependencies
- 参考编码：参考工程 `core/adb/`（`d:\GitHub\AI\Mika`，自实现 ADB 协议 + 配对）。不引入第三方 ADB 库。
- 平台接口：`DisplayManager.createVirtualDisplay`（需 CREATE_VIRTUAL_DISPLAY）、`IWindowManager`（焦点/方向）、`startActivity --display` / `wm`。

## 2. Constitution Compliance
- C-2 单一职责：✅（仅托管基座）。
- C-6 arm64-only：✅（整个工程 arm64）。
- C-7 优雅降级：✅（版本门控）。

## 3. Research Findings
- 关键约束：第三方 App 无 `CREATE_VIRTUAL_DISPLAY`/`INJECT_EVENTS` ⇒ 必须走 shell 身份。
- 帧跨进程：app 进程创建 `ImageReader`→`Surface`，把 Surface 交给 shell 助手 `setSurface`，帧直接落入 app 进程（无需逐帧 Binder 传输）。
- 方向/焦点：Android 14 起 `STEAL_TOP_FOCUS_DISABLED` 与定向冻结；低版本需降级处理。

## 4. Data Model
- 主要见 [overall-data-model.md](../overall-data-model.md) 的 ManagedSession / ShellSession。

## 5. Interface Contracts
### 5.1 Provided
- `ShellController`（app 内接口，定位：状态查询）。
- 计划接口：`createSession`、`launchApp`、`moveToDisplay`、`injectInput`、`provideSurface`、`releaseSession`（经 Binder 与助手通信）。

### 5.2 Consumed
- 平台 shell 命令与 Binder 系统接口。

## 6. Implementation Strategy
### 6.1 Architecture Pattern
- 主进程持有 ShellController 门面；真正高权限操作委托 shell 助手进程（Binder）。

### 6.2 Key Algorithms
- 配对握手（ADB RSA-2048）、端口发现（复用参考工程经验：缓存端口/系统属性/mDNS/本地扫描）。

### 6.3 Error Handling
- 能力探测失败 → 明确错误码（如 `not_paired` / `not_supported`），上层降级。

### 6.4 Performance
- 帧经 Surface 直达 app 进程，避免逐帧拷贝。

## 7. Testing Considerations
- 见 [overall-test-cases.md](../overall-test-cases.md)（M1：配对/建屏/启动/截图/注入）。
- 建议：记录配对失败、断线自动重试、低版本降级路径用例。

## 8. File Inventory
| File | Purpose | Lines |
|------|---------|-------|
| `core/shell/src/main/java/com/managedfloating/core/shell/ShellController.kt` | 接口占位 | ~20 |
