# M5 打磨 —— 真机验证（Android 13 / MIUI / 免 root）

## 交付项
| 任务 | 实现 | 验证 |
|------|------|------|
| 统一日志通道 | `MfaLog`（logcat tag `MFA/*` + 500 条环形缓冲），接入 Runtime/Server/Script | ✅ `/logs` 读到 `debug session started`、失败 `W` 告警 |
| 状态端点 | `GET /status`（sessionActive/authEnabled/sdkInt/device/accessibility/cachedPort/streamClients） | ✅ 无会话/有会话两次返回正确 |
| 错误恢复 | `POST /debug/session/stop` 清理失效会话/助手 + 操作失败记日志 | ✅ stop 后 tap 返回 ok=false 且 `/logs?level=W` 有告警 |
| 通知权限 | Android 13+ 运行时 `POST_NOTIFICATIONS` 请求 + 无障碍引导按钮 | ✅ 构建安装通过（权限弹窗） |
| 单元测试 | `CronTest` 9 例 + `MfaLogTest` 5 例（JVM，无设备依赖） | ✅ 14/14 通过 |
| 低版本门控 | 复核 VdHelper flags（SDK33/34 分支 + SecurityException 回退）、a11y displayId(30)、通知(33)、前台服务(26/34)、精确闹钟(31) | ✅ 已有覆盖 |
| 端口自动发现 | 复核 `AdbConnectPort`：缓存→属性→mDNS→本地扫描→手动，TLS 重试 | ✅ 已完整接线（NoRootShell.connect） |

## /status 实测
无会话：`{"sessionActive":false,...,"sdkInt":33,"device":"Xiaomi M2105K81C","accessibilityEnabled":false,"cachedConnectPort":0}`
有会话：`"sessionActive":true`

## /logs 实测
- I Server debug session started port=37577
- W Server session stopped via /debug/session/stop
- W Server tap failed (1,1) - session inactive?

## 单测
```
CronTest   tests=9  fail=0   （*/n、定时、跨天、月/日、周0=7、非法输入、范围/列表）
MfaLogTest tests=5  fail=0   （顺序、级别过滤、500 环形淘汰、clear、异常消息）
ALL UNIT TESTS: 14 failures: 0
```

## 范围说明（未做整套 Compose 重写）
M5 定位「打磨/稳定」，现有 View 配置界面已覆盖全部功能；本轮以**低风险增量**补齐状态/日志/权限/测试，
未做整套 Compose 重写（避免大改回归风险）。Compose UI 留作独立增强。MJPEG 仍为阻塞 IO（8 路上限保护），协程化列为后续优化。
