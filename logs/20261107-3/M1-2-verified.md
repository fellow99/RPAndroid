# M1-2 免 root 自举 —— 端到端验证通过

**设备**：8f155833（Android 13 / SDK 33 / arm64 / MIUI，**免 root**）
**结论**：App **完全自主**（不依赖电脑）完成：配对 → TLS 连接本机 adbd → pty 启动 shell-UID 助手 → 建可信虚拟屏 → 承载 Settings → 截帧。

## 验证输出（App 触发，见 noroot_helper.log）
```
== HelperMain v2 uid=2000
== shellCtx pkg=com.android.shell
== CREATED_VIRTUAL_DISPLAY id=5 trusted=true flags=0x3dc9 valid=true flagsInfo=1920
== MFA_VD_ID=5
== exec: am start --display 5 -n com.android.settings/.Settings
== FRAME_SAVED /data/local/tmp/mfa_before.png 720x1560
```
- 帧证据：`noroot_app_frame.png`（99244B，Settings 在虚拟屏上的真实帧）。

## 关键机制（踩坑记录，务必保留）
1. **配对**：`AdbPairing(tls).pair(host, pairPort, "6位码")`（SPAKE2 + 公钥交换）→ 成功后钥匙经 Android Keystore AES 包装存 App 私有目录。
2. **连接端口发现**：本机 `service.adb.tls.port` 为空；连接端口在 `/proc/net/tcp` 里是 **uid=2000(shell/adbd) 的监听端口**（本次 0x92C9=37577）。也可用 mDNS（NsdAdbServiceFinder）。
3. **连接**：`AdbConnection.open("127.0.0.1", port, tls)` —— 已配对钥匙做 TLS 认证。
4. **拉起助手（关键）**：
   - 本机 adbd 在**会话结束时会杀掉该会话进程组，`setsid` 无法脱离**（实测：USB/App 均可复现 sleep 被杀）。
   - 唯一可靠方式：**App 持有 adb 的 `shell:`(pty) 流不关闭 + 命令前台常驻**，会话存活则助手存活（心跳日志 4→7 持续增长证实）。
   - 故 `NoRootShell.startHelper(command)` 开 pty 流、命令**前台**跑 `app_process`，并把流保存在 `helperStream` 不关闭。
5. `exec:` 服务无此保活能力（流关闭即杀），必须用 `shell:`(pty)。

## 尚未完成（M1 之后）
- 把 `hold pty` 机制固化为正式 API（`VdSession` 接线 → `/session/launch`、`/frame`、`/action/*` 真正生效）。
- 助手代码需并入 APK 或随 App 部署（当前用 `/data/local/tmp/helper.dex` 验证）。
- 连接端口自动发现（无固定端口）与配对引导 UI。
