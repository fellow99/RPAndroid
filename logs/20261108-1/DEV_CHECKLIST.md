# DEV_CHECKLIST — 101-main-ui

> 日期：2026-11-08；需求：应用构建主要UI；分支：master（不改动）

## 达成目标
- [x] T1 core 支撑：back 注入 / 虚拟屏分辨率配置 / 自动化停止信号
- [x] T2 app 启用 Compose（AGP9 内置 Kotlin + kotlin.compose，spike 验证）
- [x] T3 导航壳 + 全局状态 + 返回转发
- [x] T4 设置栏目（端口/token/分辨率/无障碍/接入信息）
- [x] T5 应用栏目（S1/S2/S3/S4 + 图标网格 + 呈现区域）
- [x] T6 呈现改造（ScreenActivity Compose 化 + 返回转发；FloatingService isActive）
- [x] T7 自动化栏目（互斥执行/停止 + 编辑表单 + 执行结果回传）
- [x] T8 真机联调打磨（四态、三呈现、返回转发、互斥、表单、REST 回归；失败路径不崩溃）
- [x] T9 代码走查（REVIEW_REPORT.md：0 Critical / 2 Important 已修 / 6 Minor 记录）+ 测试记录（TEST_REPORT.md）

## 联调记录
- 真机：Xiaomi M2105K81C；四态/三呈现/返回转发/自动化互斥/编辑表单/设置项全部真机验证，详见 TEST_REPORT.md。
- 敏感信息：连接过程真实 WiFi 名、token 等原始 dump 已清理，不落库。
