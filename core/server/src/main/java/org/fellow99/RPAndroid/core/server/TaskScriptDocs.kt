package org.fellow99.RPAndroid.core.server

/**
 * [TaskScriptDocs] —— 自动化服务任务的脚本文档（供 MCP `task_docs` 返回给大模型）。
 *
 * 内容与 [org.fellow99.RPAndroid.script.ScriptService.registerHost] 注册的宿主接口严格对应：
 * 脚本运行在独立 :node 进程的 Node 引擎中，通过 `await host.invoke('<api>', {...})` 调用应用层能力。
 */
object TaskScriptDocs {

    /** 返回完整脚本文档（Markdown 纯文本）。 */
    fun text(): String = """
# 自动化服务任务 · JS 脚本文档

## 运行环境
- 脚本运行在 App 独立 `:node` 进程的 Node 引擎中（Node v26，arm64），与主进程崩溃隔离。
- 脚本为 async 函数体，可直接使用 `await`；通过全局 `host.invoke(api, args)` 调用应用层接口。
- 所有调用都是异步的，统一返回一个 JSON 对象。
- **坐标口径**：坐标基于被托管虚拟屏的当前分辨率。分辨率随设备方向/设置变化，请先调用
  `screen.info`（或 MCP `status`）读取 `w/h`，再按比例算坐标；不要把 720x1560 之类的默认值写死。
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
| cron | string | kind=CRON 时的 cron 表达式：5 字段 `分 时 日 月 周`，或 6 字段 `秒 分 时 日 月 周` |

> cron 6 字段用于“每分钟第 00 秒”这类需求：`0 * * * * *`。

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

### 3. screen.launch —— 承载 App 到虚拟屏
- 参数：`{ pkg: string, component?: string }`（注意字段名是 pkg，不是 packageName）
- 返回：`{ ok: boolean }`

### 4. screen.frame —— 截屏
- 参数：无（传 `{}`）
- 返回：成功 `{ ok: true, base64: string }`（PNG 的 base64）；无帧 `{ ok: false }`
- 提示：base64 很大，不要在循环里频繁调用。

### 5. screen.key —— 注入按键
- 参数：`{ code: number }` 或 `{ name: string }`（code 优先）
- 常用 name：`back` / `home` / `enter` / `del` / `paste` / `tab` / `esc` / `space` /
  `up` / `down` / `left` / `right` / `volume_up` / `volume_down` / `power` / `menu` / `search`
- 返回：`{ ok: boolean, code: number }`
- 示例：
  ```js
  await host.invoke('screen.key', { name: 'back' });
  ```

### 6. screen.text —— 输入文本
- 参数：`{ text: string }`（支持中文；ASCII 走系统 input text，非 ASCII 走剪贴板+粘贴）
- 返回：`{ ok: boolean }`
- 前置：目标输入框已获得焦点（先 `screen.tap` 点输入框）。
- 示例：
  ```js
  await host.invoke('screen.tap', { x: 800, y: 300 });
  await host.invoke('screen.text', { text: '北京' });
  await host.invoke('screen.key', { name: 'enter' });
  ```

### 7. screen.info —— 屏幕/会话信息
- 参数：无（传 `{}`）
- 返回：`{ ok, sessionActive, w, h, dpi, hosted, runningTaskId }`
- 示例：
  ```js
  const info = await host.invoke('screen.info', {});
  if (info.ok) await host.invoke('log', { msg: 'screen ' + info.w + 'x' + info.h });
  ```

### 8. apps.list —— 列出可启动应用
- 参数：无（传 `{}`）
- 返回：`{ ok: true, apps: [{ packageName, label }, ...] }`

### 9. log —— 写日志
- 参数：任意可序列化对象，进入主进程统一日志通道（REST `GET /logs`、MCP `logs`，tag=Script）
- 返回：`{ ok: true }`
- 示例：
  ```js
  await host.invoke('log', { msg: 'task step done', ts: Date.now() });
  ```

> 说明：`script.next` / `script.done` 为框架内部调度协议，脚本作者不要调用。

## 完整脚本示例
### 示例 A：打开设置 → 等待 → 滑动 → 截图
```js
await host.invoke('screen.launch', { pkg: 'com.android.settings' });
await new Promise(r => setTimeout(r, 2000));
const info = await host.invoke('screen.info', {});
const midX = Math.round(info.w / 2);
await host.invoke('screen.swipe', { x1: midX, y1: Math.round(info.h * 0.75), x2: midX, y2: Math.round(info.h * 0.25), durMs: 400 });
const frame = await host.invoke('screen.frame', {});
await host.invoke('log', { msg: 'captured ok=' + frame.ok });
```

### 示例 B：计算器连续按键（先 C，再每 30ms 点 1 共 10 次）
```js
await host.invoke('screen.launch', { pkg: 'com.miui.calculator' });
await new Promise(r => setTimeout(r, 1500));
const info = await host.invoke('screen.info', {});
// 按键坐标按 1600x2560 基准换算（不同分辨率按 info.w/info.h 等比）
const sx = info.w / 1600, sy = info.h / 2560;
await host.invoke('screen.tap', { x: Math.round(797 * sx), y: Math.round(1650 * sy) }); // C
for (let i = 0; i < 10; i++) {
  await host.invoke('screen.tap', { x: Math.round(797 * sx), y: Math.round(2214 * sy) }); // 1
  await new Promise(r => setTimeout(r, 30));
}
```

## 注意事项
- 异常应在脚本内 try/catch；未捕获异常会被框架标记为执行失败（ok=false, error）。
- 坐标必须落在虚拟屏分辨率范围内；静态画面截屏会返回最后一帧。
- 停止为尽力而为：`task_stop` 写停止信号，runner 在下一次 `host.invoke` 前检查并中止。
""".trimIndent()
}
