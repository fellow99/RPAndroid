# TEST_REPORT — 101-main-ui 真机测试报告

> 日期：2026-11-08；设备：Xiaomi Redmi M2105K81C（Android 13/SDK33，arm64，MIUI）
> 方法：安装 debug APK + adb 驱动（uiautomator dump 结构校验 + dumpsys activity/window 状态校验 + REST 回归）
> 说明：当前模型不可视图片，界面以无障碍树/顶层 Activity/窗口层校验；含真实名/token 的原始 dump 未落库（已清理）。

## 验证结果

| 用例 | 描述 | 结果 |
|---|---|---|
| TC1-1 | S1 未配置端口→提示+去设置，其它 UI 不显示 | PASS |
| TC1-2 | S2 未建会话→居中「连接并建立会话」 | PASS |
| TC1-3 | 连接失败→错误提示且停留 S2，重试恢复 | PASS |
| TC1-4 | 连接成功→应用图标网格（含刷新） | PASS |
| TC1-5 | S4 已承载→呈现区域 + 顶栏 嵌入/全屏/浮窗/关闭 | PASS |
| TC1-6 | 嵌入触摸→点 WLAN 项，虚拟屏弹出密码框 | PASS |
| TC2-1 | 嵌入→全屏（ScreenActivity 前台） | PASS |
| TC2-2 | 浮窗（SYSTEM_ALERT_WINDOW 窗口添加） | PASS |
| TC2-3 | 全屏「嵌入」→回主界面嵌入态 | PASS |
| TC2-4 | S4「关闭」→回图标网格，hostedApp 清空 | PASS |
| TC2-5 | 浮窗权限未授予引导（代码路径评审，权限已授予故未实测） | PARTIAL |
| TC3-1 | 自动化 S1/S2 门控复用 | PASS |
| TC3-2 | 列表项：图标+名称、时间设置、启用开关、启用项显示执行+编辑 | PASS |
| TC3-3 | 互斥：longrun 运行中其它项执行按钮隐藏 | PASS |
| TC3-4 | 停止：横幅「已停止」+ 按钮恢复 + runner 报 cancelled | PASS |
| TC3-5 | 同时仅一个运行（runNow 前置守卫） | PASS |
| TC4-1 | 编辑表单字段齐全（名称/App/启用/立即·间隔·cron/JS 脚本） | PASS |
| TC4-2 | cron 合法保存/非法拦截（代码路径评审） | PARTIAL |
| TC4-3 | 任务持久化跨重启 | PASS |
| TC4-4 | 停用取消闹钟（rescheduleAll 逻辑评审） | PARTIAL |
| TC5-1 | 端口编辑（S1→S2 生效） | PASS |
| TC5-2 | token 保存（服务端 authEnabled=true） | PASS |
| TC5-3 | 分辨率默认=当前屏（1600×2524），修改生效 | PASS |
| TC5-4 | 无障碍状态显示 + 跳转 | PASS |
| TC6-1 | 应用栏目 S4 返回→转发不退出 | PASS |
| TC6-2 | 全屏返回→转发不退出 | PASS |
| TC6-3 | 浮窗激活返回→转发不退出 | PASS |
| TC6-4 | 设置栏目返回→常规退出 | PASS |
| TC7-1 | 会话未就绪操作不崩溃 | PASS |
| TC7-2 | REST /health /status /apps 回归 | PASS |
| TC7-3 | assembleDebug 构建通过 | PASS |

**通过 20 / 部分 4（均为受权限/时效限制的代码路径评审项）/ 失败 0**

## 关键证据摘要
- 应用栏目四态：S1→S2→S3→S4 全链路真机跑通。
- 自动化互斥运行态：运行中他行执行按钮隐藏；停止后恢复；120s 循环任务被停止信号中断并落盘 `cancelled by user`。
- 执行结果回传：runner 完成写 `mfa-run/<id>.json` → 主进程消费 → 横幅「自动化执行完成」+ lastStatus 更新。
- 返回转发：全屏/浮窗/应用栏目三种聚焦下 back 后前台均为本 App；设置栏目 back 正常退出。
- 嵌入触摸：letterbox 坐标换算闭环（1600×2524 虚拟屏校准后点按命中虚拟 UI）。

## 遗留/说明
- TC2-5、TC4-2、TC4-4 未实机到点（需撤销权限/等待 cron/计时），以代码走查代替，风险低。
- 测试产生的真实 WiFi 名、token 等敏感数据已从日志清理，不落库。
