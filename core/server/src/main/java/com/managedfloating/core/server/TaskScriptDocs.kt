package com.managedfloating.core.server

/**
 * [TaskScriptDocs] —— 自动化服务任务的脚本文档（供 MCP `task_docs` 返回给大模型）。
 *
 * 内容与 [com.managedfloating.app.script.ScriptService.registerHost] 注册的宿主接口严格对应：
 * 脚本运行在独立 :node 进程的 Node 引擎中，通过 `await host.invoke('<api>', {...})` 调用应用层能力。
 */
object TaskScriptDocs {

    /** 返回完整脚本文档（Markdown 纯文本）。 */
    fun text(): String = """
# 自动化服务任务 · JS 脚本文档

## 运行环境
- 脚本运行在 App 独立 `:node` 进程的 Node 引擎中（Node v26，arm64），与主进程崩溃隔离。
- 脚本为 async 函数体，可直接使用 `await`；通过全局 `host.invoke(api, args)` 调用应用层接口。
- 所有调用都是异步的，统一返回一个 JSON 对象；坐标基于被托管虚拟屏（默认 720x1560）。
- 屏幕类接口需要先建立托管会话（`/status` 的 sessionActive=true），否则返回 ok=false。

## 任务对象字段
| 字段 | 类型 | 说明 |
|------|------|------|
| id | string | 唯一标识；创建时可省略（自动生成），修改时不可变 |
| name | string | 任务名称 |
| packageName | string? | 目标 App 包名（可选，用于任务前自动承载） |
| script | string | JS 脚本内容（见下） |
| enabled | boolean | 是否启用；false 时撤销其定时调度 |
| kind | string | 调度方式：IMMEDIATE（立即）/ INTERVAL（间隔）/ CRON（cron 表达式） |
| intervalMs | number | kind=INTERVAL 时的间隔毫秒，需 >0 |
| cron | string | kind=CRON 时的 5 字段 cron（分 时 日 月 周） |

## JS 可调用的应用层接口（host.invoke）

### 1. screen.tap —— 点击
- 参数：`{ x: number, y: number }`
- 返回：`{ ok: boolean }`
- 示例：
  ```js
  const r = await host.invoke('screen.tap', { x: 360, y: 500 });
  if (!r.ok) await host.invoke('log', { msg: 'tap failed' });
  ```

### 2. screen.swipe —— 滑动
- 参数：`{ x1, y1, x2, y2, durMs? }`，durMs 可选，默认 300
- 返回：`{ ok: boolean }`
- 示例：
  ```js
  await host.invoke('screen.swipe', { x1: 360, y1: 1200, x2: 360, y2: 400, durMs: 400 });
  ```

### 3. screen.launch —— 承载 App 到虚拟屏
- 参数：`{ pkg: string, component?: string }`（注意字段名是 pkg，不是 packageName）
- 返回：`{ ok: boolean }`
- 示例：
  ```js
  await host.invoke('screen.launch', { pkg: 'com.android.settings' });
  ```

### 4. screen.frame —— 截屏
- 参数：无（传 `{}`）
- 返回：成功 `{ ok: true, base64: string }`（PNG 的 base64）；无帧 `{ ok: false }`
- 示例：
  ```js
  const shot = await host.invoke('screen.frame', {});
  if (shot.ok) await host.invoke('log', { msg: 'frame bytes(b64)=' + shot.base64.length });
  ```

### 5. apps.list —— 列出可启动应用
- 参数：无（传 `{}`）
- 返回：`{ ok: true, apps: [{ packageName, label }, ...] }`
- 示例：
  ```js
  const r = await host.invoke('apps.list', {});
  for (const a of r.apps) await host.invoke('log', { app: a.packageName });
  ```

### 6. log —— 写日志
- 参数：任意可序列化对象，会进入统一日志通道（REST GET /logs，tag=Script）
- 返回：`{ ok: true }`
- 示例：
  ```js
  await host.invoke('log', { msg: 'task step done', ts: Date.now() });
  ```

> 说明：`script.next` / `script.done` 为框架内部调度协议，脚本作者不要调用。

## 完整脚本示例
```js
// 打开设置 → 等待 → 滑动 → 截图 → 记录
await host.invoke('screen.launch', { pkg: 'com.android.settings' });
await new Promise(r => setTimeout(r, 2000));
await host.invoke('screen.swipe', { x1: 360, y1: 1200, x2: 360, y2: 400, durMs: 500 });
const frame = await host.invoke('screen.frame', {});
await host.invoke('log', { msg: 'captured ok=' + frame.ok });
```

## 注意事项
- 仅支持上述 6 个应用层接口；目前未向脚本开放 ui_read、keyevent、任务管理、多屏选择。
- 异常应在脚本内 try/catch；未捕获异常会被框架标记为执行失败（ok=false, error）。
- 坐标必须落在虚拟屏分辨率范围内；静态画面截屏会返回最后一帧。
""".trimIndent()
}
