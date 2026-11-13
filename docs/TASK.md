# TASK.md — 自动化服务任务 · JS 脚本文档（静态版）

> 本文档与自动化服务 `task_docs`（MCP）返回的内容一一对应，供离线查阅/生成脚本。
> 相关：`docs/MCP.md`（工具）、`specs/005-script`（自动化任务）、`specs/012-ui-read` + `specs/013-xpath-evaluator`（界面读取/XPath）。
> 安全：本文档不含任何测试 URL、账号、密码、证书；实测凭据仅命令行使用，不入档。

## 运行环境

- 脚本运行在 App 独立 `:node` 进程的 Node 引擎中（Node v26，arm64），与主进程崩溃隔离。
- 脚本为 async 函数体，可直接 `await`；通过全局 `host.invoke(api, args)` 调用应用层接口。
- 所有调用都是异步的，统一返回一个 JSON 对象。
- **坐标口径**：坐标基于被托管屏的当前分辨率（Mode A 虚拟屏 / Mode B 主屏）。分辨率随设备方向变化，请先调用
  `screen.info`（或 MCP `status`）读取 `w/h`，再按比例算坐标；不要写死 720x1560 之类的默认值。
- 屏幕类接口需要先建立托管会话（`/status` 的 sessionActive=true），否则返回 ok=false。
- 界面读取（`ui.*`）需要启用无障碍服务（设置 → 无障碍 → ManagedFloatingApp）。

## 任务对象字段

| 字段 | 类型 | 说明 |
|------|------|------|
| id | string | 唯一标识；创建时可省略（自动生成），修改时不可变 |
| name | string | 任务名称 |
| packageName | string? | 目标 App 包名（可选，用于任务前自动承载） |
| script | string | JS 脚本内容 |
| enabled | boolean | 是否启用 |
| kind | string | IMMEDIATE / INTERVAL（间隔秒，intervalSec）/ SCHEDULED（固定时间，runAt）/ CRON（保留） |
| intervalSec | number | kind=INTERVAL 时（秒，需 >0） |
| runAt | number | kind=SCHEDULED 时（epoch 毫秒，需晚于当前） |
| cron | string | kind=CRON（保留，未在 UI 暴露）：5 字段 `分 时 日 月 周` 或 6 字段 `秒 分 时 日 月 周`；“每分钟第 00 秒”用 `0 * * * * *` |

## JS 可调用的应用层接口（host.invoke）

### 1. screen.tap —— 点击
- 参数：`{ x, y }`；返回 `{ ok }`
- 示例：`await host.invoke('screen.tap', { x: 360, y: 500 });`

### 2. screen.swipe —— 滑动
- 参数：`{ x1, y1, x2, y2, durMs? }`（durMs 默认 300）；返回 `{ ok }`

### 3. screen.launch —— 承载 App
- 参数：`{ pkg, component? }`（字段名是 pkg）；返回 `{ ok }`

### 4. screen.frame —— 截屏
- 参数：无；返回 `{ ok, base64 }`（PNG base64，体积大勿循环频繁调用）

### 5. screen.key —— 按键
- 参数：`{ code }` 或 `{ name }`（name：`back/home/enter/del/paste/tab/esc/space/up/down/left/right/volume_up/volume_down/power/menu/search`）；返回 `{ ok, code }`

### 6. screen.text —— 输入文本
- 参数：`{ text }`（支持中文；ASCII 走 `input text`，非 ASCII 走剪贴板+粘贴）；前置：目标输入框已获焦点

### 7. screen.info —— 屏幕/会话信息
- 参数：无；返回 `{ ok, sessionActive, w, h, dpi, hosted, runningTaskId }`

### 8. apps.list —— 列可启动应用
- 参数：无；返回 `{ ok, apps: [{packageName,label}] }`

### 9. log —— 写日志
- 参数：任意可序列化对象；进入 REST `/logs`、MCP `logs`（tag=Script）

### 10. ui.read —— 读扁平界面树
- 参数：无；返回 JSON 数组（节点含 `text/className/packageName/clickable/editable/scrollable/displayId/resourceId/bounds`，无 `children`）；不可用返回 `null`

### 11. ui.dump —— 读层级界面树
- 参数：无；返回 JSON 数组，节点含 `children` 递归 + `resourceId`；不可用返回 `null`

### 12. ui.query —— 按 XPath 子集查询节点
- 参数：`{ xpath }`；返回命中节点数组（**host.invoke 已自动解析**，含 `bounds`）；非法 XPath 返回 `{"error":"..."}`；不可用返回 `null`

**XPathEvaluator 支持范围**（详见 `specs/013-xpath-evaluator`）：
- 轴：`/`（直接子级）、`//`（任意深度后代，含根自身）
- 节点测试：`*`、className 简写（`TextView`）或全名（`android.widget.TextView`）
- 谓词：`[@text='..']`、`[@resourceId='..']`、`[@className='..']`、`[@packageName='..']`、
  `[@clickable=true/false]`、`[@editable=..]`、`[@scrollable=..]`、`[@index=N]`、位置 `[N]`（该步匹配集第 N 个，1 基）
- 支持 `=` / `!=`；**不支持** `../`、轴全称、`|`、`@*`、函数、`[@id]` 存在性简写

**正例：查询定位并触发**
```js
const hit = await host.invoke('ui.query', { xpath: "//android.widget.TextView[@text='确定']" });
if (Array.isArray(hit) && hit.length && hit[0].bounds) {
  const b = hit[0].bounds;
  const cx = Math.round((b.l + b.r) / 2), cy = Math.round((b.t + b.b) / 2);
  await host.invoke('screen.tap', { x: cx, y: cy });
}
```

### 13. ui.get —— 按 resourceId 定位
- 参数：`{ resourceId }`；返回命中节点 JSON 数组（可多个）；无匹配为空数组；不可用返回 `null`
- 正例：`const r = await host.invoke('ui.get', { resourceId: 'com.android.settings:id/search' });`

> 说明：`script.next` / `script.done` 为框架内部调度协议，脚本作者不要调用。

## 完整脚本示例

### 示例 A：打开设置 → 滑动 → 截图
```js
await host.invoke('screen.launch', { pkg: 'com.android.settings' });
await new Promise(r => setTimeout(r, 2000));
const info = await host.invoke('screen.info', {});
const midX = Math.round(info.w / 2);
await host.invoke('screen.swipe', { x1: midX, y1: Math.round(info.h * 0.75), x2: midX, y2: Math.round(info.h * 0.25), durMs: 400 });
const frame = await host.invoke('screen.frame', {});
await host.invoke('log', { msg: 'captured ok=' + frame.ok });
```

### 示例 B：按 XPath 定位并点击（Mode B 主屏承载）
```js
// 前置：已切到主屏承载、目标 App 在前台
const hit = await host.invoke('ui.query', { xpath: "//*[@text='确定']" });
if (!Array.isArray(hit) || !hit.length) throw new Error('node not found');
const b = hit[0].bounds;
await host.invoke('screen.tap', { x: Math.round((b.l + b.r) / 2), y: Math.round((b.t + b.b) / 2) });
```

### 示例 C：计算器连续按键（坐标等比换算）
```js
await host.invoke('screen.launch', { pkg: 'com.miui.calculator' });
await new Promise(r => setTimeout(r, 1500));
const info = await host.invoke('screen.info', {});
const sx = info.w / 1600, sy = info.h / 2560;
for (let i = 0; i < 10; i++) {
  await host.invoke('screen.tap', { x: Math.round(797 * sx), y: Math.round(2214 * sy) }); // 1
  await new Promise(r => setTimeout(r, 30));
}
```

## 注意事项

- 异常应在脚本内 try/catch；未捕获异常会被框架标记为执行失败（ok=false）。
- 坐标必须落在当前承载屏分辨率范围内；静态画面截屏返回最后一帧。
- `ui.*` 在 Mode B（主屏承载）下返回被托管 App 控件树；Mode A（虚拟屏）在部分 ROM 上可能为空，可回退 `screen.frame` + 坐标。
- 停止为尽力而为：`task_stop` 写停止信号，runner 在下一次 `host.invoke` 前检查并中止。
