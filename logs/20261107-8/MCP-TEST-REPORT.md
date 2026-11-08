# MCP 工具 ManagedFloatingApp —— 完整测试报告

设备：Xiaomi M2105K81C / Android 13 (SDK33) / MIUI / arm64 / 免 root
传输：adb forward tcp:8099；鉴权 Authorization: Bearer <token>

## 一、发现并修复的 3 个真实缺陷

### BUG-1 截图花屏/横向撕裂（Critical）
- 现象：screen_capture 返回的 PNG 有横向错位条纹。
- 根因：ImageReader plane 的 rowStride 可能 > width*4（行尾 padding），
  原代码直接 copyPixelsFromBuffer 未处理 stride。
- 修复：VdHelper.renderPng() 按 rowStride/pixelStride 逐行拷贝去 padding。
- 验证：修复后截图清晰完整（设置界面文字/图标全部正常）。

### BUG-2 静态画面连续截图返回 no frame（Critical）
- 现象：第 1 张成功，第 2 张起持续 no frame（静态画面下必现）。
- 根因：静态画面 SurfaceFlinger 不重绘，ImageReader.acquireLatestImage() 无新 buffer。
- 修复：helper 缓存最后成功编码的帧（lastFrame），无新帧时返回缓存帧；
  缩短等待（60→10 次）。交互产生新帧时自动刷新（验证滑动后 92738→76643 字节变化，非冻结）。
- 验证：连续 10 次截图 10/10 成功。

### BUG-3 文件 IPC 并发踩踏 + seq 污染（High）
- 现象：多请求并发时偶发 ok=false/no frame；外部高 seq 残留会让 helper 永久忽略新指令。
- 根因：① cmd.txt/result.txt 单文件对无并发保护；② helper 仅认 seq>lastSeq。
- 修复：
  - VdSessionImpl 加 ipcLock 串行化所有指令；seq 改时间戳基线（防会话重建冲突）；finally 清 cmd。
  - VdHelper 加 WRAP_THRESHOLD，seq 大幅回退时判定新会话并重置基线。
- 验证：6 个并发 capture 全部成功；干净会话连续 tap 5/5 ok。

## 二、协议/工具回归（全通过）
| 项 | 结果 |
|----|------|
| initialize / ping / notifications/initialized(202) | ✅ |
| tools/list | ✅ 9 个工具 |
| app_list | ✅ 15 个应用（中文 label 正常，UTF-8） |
| app_launch | ✅ ok=true，缺参数给明确错误 + isError:true |
| screen_capture | ✅ 连续 10/10，PNG 有效，并发 6/6 |
| screen_tap / screen_swipe | ✅ ok=true |
| ui_read（无障碍关） | ✅ 优雅降级提示 |
| task_create/run/list/delete | ✅ 全链路，runCount 递增 |
| 未知工具/方法/坏 JSON | ✅ -32601 / -32700 标准错误码 |
| 错误 token | ✅ 401 |
| SSE 通道 | ✅ endpoint → POST 202 → event:message 回推 |
| isError 标记 | ✅ 失败 true / 成功 false |

## 三、单元测试
14/14 通过（Cron 9 + MfaLog 5），无回归。

## 四、说明
- 测试期 Python 在 Windows GBK 控制台解析 UTF-8 报错，属测试环境编码问题，非服务端缺陷（设 PYTHONIOENCODING=utf-8 后正常）。
- 早期 tap ok=false 系手动注入高 seq 污染 helper 所致，已由 BUG-3 修复根治。

## 五、最终自动化冒烟（Python urllib，14 项全过）
```
PASS ping
PASS tools/list=9
PASS screen_tap
PASS screen_swipe
PASS capture#1..#5 （连续 5 张，静态复用 + 新帧刷新均正常，69-71KB PNG）
PASS app_list (15 apps)
PASS missing arg isError=true
PASS ui_read graceful
PASS unknown method -32601
PASS bad token 401
=== FINAL: 14 PASS / 0 FAIL ===
```
（早先 shell 版冒烟的 3 个 "FAIL" 系 grep 匹配脚本问题，Python 权威回归全绿。）
