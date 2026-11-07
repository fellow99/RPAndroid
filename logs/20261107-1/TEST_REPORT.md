# TEST_REPORT.md — 000-scaffold 设备验证

**日期：** 2026-11-07
**设备：** 8f155833（Android 11 / SDK 30 / arm64-v8a）
**APK：** `app/build/outputs/apk/debug/app-debug.apk`（debug，约 91 MB，含 libnode*）

## 结果

| # | 用例 | 结果 | 证据 |
|---|------|------|------|
| 000-B-001 | 全工程 `:app:assembleDebug` 编译通过 | ✅ PASS | 构建 SUCCESSFUL |
| 000-B-002 | debug APK 含 libnode.so / libnode_bridge.so / libnode_host_client.so / libc++_shared.so | ✅ PASS | unzip -l 确认 |
| 000-I-001 | 真机安装 | ✅ PASS（首次被设备策略拒后重装成功） | `adb install -r` → Success |
| 000-I-002 | 真机启动 MainActivity | ✅ PASS | `pidof`=7350；mResumedActivity=…MainActivity；无 FATAL |
| 000-I-003 | 界面渲染 | ✅ PASS | `screencap` 捕获 51KB 非空帧（screen.png） |

## 说明

- 首次安装被 `INSTALL_FAILED_USER_RESTRICTED` 拦住（设备"通过 USB 安装"需确认），用户允许后重装成功。
- 设备为 **Android 11（SDK 30）**：满足 minSdk 24，可安装/运行；但按设计，**托管操作能力（M1 建屏/注入）在 11 上为降级档**，完整托管建议 Android 12+ / 推荐 14+。
- 本 APK 为 Scaffold，未触发 Node 引擎调用（无业务），因此未做 node 运行冒烟——留待 M3。

## 结论

000-scaffold 真机可安装、可启动、无崩溃。达到完成标准。
