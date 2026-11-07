# 001-shell tasks.md — M1 托管 POC（✅ 全部完成）

> 里程碑 M1：打通"建虚拟屏 → 启动 App → 截图 → 注入点击"一条链路（headless + REST 最小集）。
> 目标设备：Android 13（8f155833，免 root）+ Android 16 交叉验证。
> **状态：M1-1 / M1-2 / M1-3 / M1-4 全部完成并在真机验证。**

## 任务与结果

### M1-1 建虚拟屏 + 承载 App + 截帧 —— ✅ 完成
- 助手以 shell UID 建**可信虚拟屏**（trusted, flags 0x3dc9@SDK33 / 0x17dc9@SDK34+）。
- 关键解法：公开 API `DisplayManager(com.android.shell context).createVirtualDisplay(...)` + 全套隐藏 flags + **ImageReader 截帧**（`screencap -d` 对虚拟屏不可靠）。
- 验证：Android 13 id=2 / Android 16 id=10，承载 Settings 并截 720×1560 帧。

### M1-2 免 root 自举（ADB 客户端 + 配对）—— ✅ 完成
- 移植 Mika `core/adb/`（SPAKE2/Ed25519 配对、TLS、CNXN/AUTH）+ `AndroidAdb`（Keystore 包装钥匙）+ 服务发现。
- `NoRootShell`：`pair(host,pairPort,code)` → `connect(port)` → 拉起助手。
- **关键平台约束**：本机（MIUI/Android 13）adbd 会话结束即杀进程组，**`setsid` 无效**；唯一可靠方式是 **App 持有 adbd `shell:`(pty) 流 + 命令前台常驻**（`startHelper`）。
- 验证：App 自主配对成功、连接 `uid=2000(shell)`、经 pty 拉起助手建屏 id=5。

### M1-3 输入注入 —— ✅ 完成
- `input -d <displayId> tap/swipe/keyevent` 精确命中虚拟屏上的 App。
- 验证：back 导致 App 退出→空帧；swipe 前后帧 md5 变化（Android 13/16 均复现）。

### M1-4 ScreenController + Headless + REST —— ✅ 完成
- `VdHelper`（**打进 APK**，用 App base.apk 作 CLASSPATH 加载）+ `VdSessionImpl`（文件 IPC）。
- REST（Ktor+Jackson）：`/apps`、`/session/launch`、`/frame`(PNG)、`/action/tap`、`/action/swipe`。
- 验证（Android 13）：`/session/launch` Settings→ok、`/frame`→200/99244B、`/action/tap`+`/swipe`→ok。

## 遗留/后续（M1 之后，非阻塞）
- 连接端口自动发现：`/proc/net/tcp` 在 App 内不可见（hidepid）；mDNS/扫描未命中 → 当前需提供连接端口（配对后缓存可复用）。
- 配对 UI：目前经 `/debug/pair` 手动；正式需配置界面引导（读码可由无障碍自动）。
- 服务前台化（Android 13+ 后台限制）、token 鉴权、MJPEG/WebRTC、MCP、脚本调度（M2–M4）。
