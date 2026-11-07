# 001-shell tasks.md — M1 托管 POC

> 里程碑 M1：打通"建虚拟屏 → 启动 App → 截图 → 注入点击"一条链路（headless + REST 最小集）。
> 目标设备：Android 16 / arm64（192.168.0.100:42215）。
> 方法：分步实现，每步可编译 + 真机可测；完成后再 review。

## 背景事实（设备探测）
- 虚拟屏**只能**由 shell-UID 的 `app_process` 助手调用 `DisplayManager.createVirtualDisplay` 创建（`wm` 无 create-virtual）。
- 免 root 让 App 自拉起 shell 助手 ⇒ 需自实现 ADB 客户端（配对 + 回环连 adbd + `app_process`）。

## 任务拆解（含依赖）

### M1-1 机制证明（shell 助手建屏，电脑 adb 驱动）
- [ ] 写最小 `app_process` 助手（shell UID），反射 `IDisplayManager` 创建虚拟屏。
- [ ] 用电脑 adb `app_process` 启动助手，创建 1440×3200 虚拟屏。
- [ ] `am start --display <id>` 把真实 App（如 Settings/浏览器）挪上该屏。
- [ ] `screencap -d <id>` 从电脑截取该屏帧 → 验证"承载 + 截图"可行。
- 依赖：无。产出：mechanism 证明 + 复用的助手核心。

### M1-2 免 root 自举（ADB 客户端 + 配对）
- [ ] App 内实现 ADB 客户端：TLS 认证、回环连接本机 adbd、`app_process` 启动 M1-1 助手。
- [ ] 无线调试配对流程（配对码 → 钥匙交换 → 保存 RSA 钥匙）。
- [ ] 断开/重连恢复。
- 依赖：M1-1（复用助手）。产出：App 无需电脑即可起助手。

### M1-3 帧出口 + 输入注入
- [ ] 助手把虚拟屏 `setSurface`（app 侧 ImageReader Surface 跨进程）→ app 直收帧。
- [ ] 注入输入：`input tap/swipe -d <id>`（经助手/`IWindowManager`）。
- 依赖：M1-1。产出：帧可读 + 可注入。

### M1-4 ScreenController 最小集 + Headless + REST
- [ ] `ScreenController`：captureFrame / tap / swipe / 启动 App / read 状态。
- [ ] headless：仅截帧，无 UI 渲染。
- [ ] Ktor REST 最小集：`/apps`、`/session/launch`、`/frame`、`/action/tap`、`/action/swipe`。
- 依赖：M1-2、M1-3。产出：外部可经 REST 操作被托管 App。

## 本次屏幕建议
- **第一步优先 M1-1（机制证明）**：风险最低、信息量最大，直接验证本设备能否承载 + 截图。
- M1-2（免 root 自举 ADB 客户端）工程量最大，成功后 M1-4 可用。"电脑兼任 S1 期间先不依赖它"（用电脑 adb 驱动助手测 M1-3/M1-4），M1-2 作为独立子里程碑后补。

## 完成标准
- M1-5：手机端 App 可经 REST 对"虚拟屏上的真实 App"截图 + 注入点击。
