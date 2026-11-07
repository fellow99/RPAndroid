# M3 脚本任务系统 —— 真机验证通过（Android 13, 免 root）

## 端到端证据（logcat）
```
MfaScript: Node 启动: Starting
MfaScript: {"msg":"hello from node"}        ← JS 在 :node 独立进程执行（Node v26.13.0）
MfaScript: {"tap":"{\"ok\":true}"}          ← host.invoke('screen.tap') → Binder → 主进程 ScreenController → 虚拟屏
MfaScript: {"tick":...563998}               ← INTERVAL 8s 自动调度
MfaScript: {"tick":...564556}               ← 间隔 ~8.2s 再次触发✅
```
任务状态：`t1 runCount=2, lastRunAt=1794065532425` ✅

## 架构
- `ScriptService`（`android:process=":node"`，前台服务）：Node 常驻（`node::Start` 每进程一次），
  appEntry=`runner.js` 循环 `host.invoke('script.next')` 拉脚本并 eval。
- 宿主能力 `screen.tap/swipe/launch/frame`、`apps.list`、`log` 经 **IScreenControl(AIDL)** Binder
  回调主进程 ScreenController —— 脚本进程崩溃不影响主进程。
- `TaskStore`（files/tasks.json，跨进程）、`TaskScheduler`（AlarmManager setExactAndAllowWhileIdle）
  + `AlarmReceiver`；`Cron`（5 字段解析 → 下次触发）。
- REST：`GET/POST /tasks`、`DELETE /tasks/{id}`、`POST /tasks/{id}/run`。

## 关键踩坑
- **Application.onCreate 在每个进程都跑**：`:node` 进程也启动了 Ktor → 端口冲突 BindException → 进程崩溃。
  修复：`ManagedRuntime.start` 只在主进程（`Application.getProcessName()==packageName`）启动服务器。
