# REVIEW_REPORT.md — M1（托管 POC）Code Review

**日期：** 2026-11-07
**范围：** M1-1 / M1-3 / M1-4 已实现提交（`0f53752`、`836bde7`、`d683385`）
**方式：** requesting/receiving-code-review（orchestrator 自审）

## 结论
M1 平台机制（建屏/承载/截帧/注入）已在 **Android 16（MIUI）免 root 真机上验证通过**；App 侧 REST 服务器 + controller 已构建并在真机运行 `/health`、`/apps` 验证通过。**M1-2（免 root 自举 ADB 客户端）未实现** —— 见"遗留"。

## Critical
- 无。

## Important
| # | 位置 | 发现 | 状态 |
|---|------|------|------|
| I-1 | `ManagedServer` | **服务绑定 0.0.0.0 无鉴权**，局域网内任何人可访问 | 设计上 token 属 M4；M1 可接受，但应尽早在 M4 前加上。记录。 |
| I-2 | `ManagedService` | 用普通后台 `startService`，Android 12+（尤其 MIUI）可能被杀；长驻须升级前台服务 | M3 升前台时处理。记录。 |
| I-3 | `AppScreenController` | launch/capture/tap/swipe 依赖 `VdSession`，当前恒为 null → 返回 `helper_unavailable`（结构完备但不可用） | 属 **M1-2 依赖**（免 root 自举/部署助手），未完成。 |

## Minor
| # | 位置 | 发现 |
|---|------|------|
| M-1 | `ManagedServer` | 用 `receive<String>`+Jackson 手工解析，可改 content-negotiation 自动反序列化（非阻塞） |
| M-2 | `AppScreenController.listApps` | 兜底分支用 `getInstalledApplications`，可能列出大量系统包；可加过滤 |
| M-3 | `tools/vdisplay` | 助手为独立编译产物，正式应并入 `core:shell` |

## 验证核对（receiving）
- ✅ M1-1：shell-UID 助手建可信虚拟屏（id 10, trusted, flags 0x17dc9），`am start --display` 承载 Settings，ImageReader 截帧 720×1560 PNG。
- ✅ M1-3：`input -d <id> tap/swipe/keyevent` 精确命中虚拟屏 App（back 退出→空帧；滑动→帧 md5 变化）。
- ✅ M1-4：App 内 Ktor(CIO) 服务器启动（独立线程避 CIO accept 取消），真机 `/health`→ok、`/apps`→应用列表。根因踩坑：`adb reverse` 占设备端口→改用 `adb forward`。
- ✅ 无敏感信息落盘。

## 遗留（关键，如实写明）
- **M1-2 免 root 自举（ADB 客户端 + 无线调试配对）未实现**：这是"App 无需电脑/无外设即可拉起 shell 助手"的完整闭环，也是 M1-4 的 launch/capture/action 能真正生效的前提（I-3）。属于独立大块工程量。
- 无线真机 `192.168.0.100:42215` 会话中断，Android 16 上的 M1-4 端到端（launch→frame→tap）尚未连跑；平台上各环节已分别验证。

## 建议下一步
1. 优先落实 **M1-2（免 root 自举）**，或在开发期先用电脑 adb 驱动助手填入 `VdSession`，打通 App 的 launch→frame→action 端到端。
2. 恢复 Android 16 无线真机连接后连跑 M1-4 全链路。
3. M4 前补 token 鉴权（I-1）。
