# SPECS_CHECKLIST.md — 规格文档完成清单

> 每个文档生成后即时更新。`README.md` 在全部模块文档完成后生成（Phase 3）。

## 项目级文档

| # | 文档 | 路径 | 状态 |
|---|------|------|------|
| P-01 | 目录结构 | [STRUCTURE.md](./STRUCTURE.md) | ✅ Done |
| P-02 | 技术选型 | [TECH.md](./TECH.md) | ✅ Done |
| P-03 | 系统架构 | [ARCHITECTURE.md](./ARCHITECTURE.md) | ✅ Done |
| P-04 | 宪法原则 | [constitution.md](./constitution.md) | ✅ Done |
| P-05 | 整体规格 | [overall-spec.md](./overall-spec.md) | ✅ Done |
| P-06 | 整体方案 | [overall-plan.md](./overall-plan.md) | ✅ Done |
| P-07 | 数据模型 | [overall-data-model.md](./overall-data-model.md) | ✅ Done |
| P-08 | 测试用例索引 | [overall-test-cases.md](./overall-test-cases.md) | ✅ Done |
| P-09 | 文档索引 | [README.md](./README.md) | ✅ Done |

## 模块级文档（每个含 spec.md + plan.md）

| # | 模块 | spec.md | plan.md |
|---|------|---------|---------|
| M-01 | 001-shell 托管基座 | [spec.md](./001-shell/spec.md) | [plan.md](./001-shell/plan.md) |
| M-02 | 002-controller 操作入口 | [spec.md](./002-controller/spec.md) | [plan.md](./002-controller/plan.md) |
| M-03 | 003-presentation 呈现模式 | [spec.md](./003-presentation/spec.md) | [plan.md](./003-presentation/plan.md) |
| M-04 | 004-server 对外接口 | [spec.md](./004-server/spec.md) | [plan.md](./004-server/plan.md) |
| M-05 | 005-script 自动化服务任务 | [spec.md](./005-script/spec.md) | [plan.md](./005-script/plan.md) |
| M-06 | 006-config-ui 配置界面 | [spec.md](./006-config-ui/spec.md) | [plan.md](./006-config-ui/plan.md) |
| M-07 | 007-webrtc 界面推流 | [spec.md](./007-webrtc/spec.md) | [plan.md](./007-webrtc/plan.md) |
| M-08 | 008-client 播控客户端 | [spec.md](./008-client/spec.md) | [plan.md](./008-client/plan.md) |
| M-09 | 101-main-ui 主界面 | [spec.md](./101-main-ui/spec.md) | [plan.md](./101-main-ui/plan.md) |
| M-10 | 009-mcp-tools MCP 工具完善 | [spec.md](./009-mcp-tools/spec.md) | [plan.md](./009-mcp-tools/plan.md) |


## 里程碑实现状态（as-built）

| 里程碑 | 状态 | 证据 |
|--------|------|------|
| M1 托管 POC（建屏/承载/截帧/注入） | ✅ 完成 | logs/20261107-2, 20261107-3 |
| M2 呈现与操作（浮窗/常规/headless + token + 会话恢复） | ✅ 完成 | logs/20261107-4 |
| M3 自动化服务任务（Node :node 进程 + 立即/间隔/cron） | ✅ 完成 | logs/20261107-5 |
| M4 对外协议（MCP + MJPEG 视频流） | ✅ 完成（MCP 19 工具） | logs/20261109-1 |
| M5 打磨（前台服务常驻/端口自动发现/文档） | ⏳ 部分 | — |
