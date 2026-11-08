# 规格文档索引

**项目名称：** ManagedFloatingApp
**版本：** 0.1.0（000-scaffold）
**技术栈：** Kotlin / AGP 9.2.1（builtInKotlin）/ Compose（规划）/ Ktor, MCP, MJPEG, WebRTC（规划）/ Node.js(in-process) —— 详见 [TECH.md](./TECH.md)
**文档生成时间：** 000-scaffold
**最后更新：** 000-scaffold

---

## 一、文档总览

| 层级 | 分类 | 文档数量 | 说明 |
|------|------|---------|------|
| 整体 | 项目级顶层文档 | 9 | 架构、技术、宪法、结构、检查清单 |
| 整体 | 整体规格文档 | 4 | overall-spec / plan / data-model / test-cases |
| 模块 | 托管与控制 | 4 | 001-shell、002-controller（各 spec+plan） |
| 模块 | 呈现与服务 | 4 | 003-presentation、004-server（各 spec+plan） |
| 模块 | 自动化脚本与界面 | 4 | 005-script、006-config-ui（各 spec+plan） |
| **合计** | **6 目录 / 22 文件** | | |

> 注：按要求不生成 API.md / overall-api.md（REST/MCP 契约在 004-server 文档内描述）。

---

## 二、项目级顶层文档

全局性的架构、技术、宪法等文档，定义项目基线和开发准则。

| 文档 | 路径 | 说明 |
|------|------|------|
| **方案总纲** | [ARCHITECTURE.md](./ARCHITECTURE.md) | 系统整体架构设计（分区/数据流/进程边界/ADR） |
| **技术选型** | [TECH.md](./TECH.md) | 核心技术栈、版本、选型理由 |
| **宪法原则** | [constitution.md](./constitution.md) | 开发原则、编码规范、治理规则（C-1…C-12） |
| **项目结构** | [STRUCTURE.md](./STRUCTURE.md) | 源码目录结构、模块地图、依赖方向 |
| **检查清单** | [SPECS_CHECKLIST.md](./SPECS_CHECKLIST.md) | 规格文档完成度追踪 |
| **工程规划** | [../docs/000-工程规划.md](../docs/000-工程规划.md) | brainstorm 落盘（需求与决策基线） |

### 整体规格文档

描述跨模块的全局规格、方案和数据模型。

| 文档 | 路径 | 说明 |
|------|------|------|
| **整体规格** | [overall-spec.md](./overall-spec.md) | 系统级功能规格（FR-A…F / NFR） |
| **整体方案** | [overall-plan.md](./overall-plan.md) | 系统级技术方案（里程碑 M0–M5） |
| **数据模型** | [overall-data-model.md](./overall-data-model.md) | 全局数据实体与状态机 |
| **测试用例索引** | [overall-test-cases.md](./overall-test-cases.md) | 全模块测试用例索引总览 |

---

## 三、托管与控制（001–002）

### 001 — 托管基座（shell）

> 免 root（无线调试配对 / 可选 root）创建虚拟屏承载目标 App 并注入输入的根基。

| 文档 | 链接 | 说明 |
|------|------|------|
| 功能规格 | [001-shell/spec.md](./001-shell/spec.md) | 托管基座功能需求（FR-A） |
| 技术方案 | [001-shell/plan.md](./001-shell/plan.md) | 虚拟屏/注入/帧出口实现方案 |

### 002 — 操作入口（controller）

> 读取与操作被托管 App 的唯一入口 ScreenController（REST/MCP/自动化脚本/触摸共用）。

| 文档 | 链接 | 说明 |
|------|------|------|
| 功能规格 | [002-controller/spec.md](./002-controller/spec.md) | 操作入口功能需求（FR-B） |
| 技术方案 | [002-controller/plan.md](./002-controller/plan.md) | 读帧/读树/写操作实现方案 |

---

## 四、呈现与服务（003–004）

### 003 — 呈现模式（presentation）

> 同一会话的浮窗 / 常规布局 / headless 三模式，触摸回写虚拟屏。

| 文档 | 链接 | 说明 |
|------|------|------|
| 功能规格 | [003-presentation/spec.md](./003-presentation/spec.md) | 呈现模式功能需求（FR-C） |
| 技术方案 | [003-presentation/plan.md](./003-presentation/plan.md) | SurfaceView/浮窗/坐标换算方案 |

### 004 — 对外接口（server）

> REST / MCP / 视频流（一期 MJPEG，二期 WebRTC），局域网直连 + adb reverse。

| 文档 | 链接 | 说明 |
|------|------|------|
| 功能规格 | [004-server/spec.md](./004-server/spec.md) | 对外接口功能需求（FR-D） |
| 技术方案 | [004-server/plan.md](./004-server/plan.md) | Ktor/MCP/MJPEG 实现方案 |

---

## 五、自动化脚本与界面（005–006）

### 005 — 自动化服务任务（script）

> 前台 Service + Node（独立 `:node` 进程）执行 JS 自动化脚本；立即/间隔/cron 调度。

| 文档 | 链接 | 说明 |
|------|------|------|
| 功能规格 | [005-script/spec.md](./005-script/spec.md) | 自动化服务任务功能需求（FR-E） |
| 技术方案 | [005-script/plan.md](./005-script/plan.md) | node-bridge 集成与调度实现方案 |

### 006 — 配置界面（config-ui）

> 选择被托管 App、配置任务/自动化脚本/调度、切换呈现模式、查看状态与日志。

| 文档 | 链接 | 说明 |
|------|------|------|
| 功能规格 | [006-config-ui/spec.md](./006-config-ui/spec.md) | 配置界面功能需求（FR-F） |
| 技术方案 | [006-config-ui/plan.md](./006-config-ui/plan.md) | Compose UI 实现方案 |

---

## 六、模块编号一览

| 编号 | 模块名 | 英文名 | 分类 |
|------|--------|--------|------|
| 001 | 托管基座 | shell | 托管与控制 |
| 002 | 操作入口 | controller | 托管与控制 |
| 003 | 呈现模式 | presentation | 呈现与服务 |
| 004 | 对外接口 | server | 呈现与服务 |
| 005 | 自动化服务任务 | script | 自动化脚本与界面 |
| 006 | 配置界面 | config-ui | 自动化脚本与界面 |

> 注：无跳号。

---

## 七、模块文档结构规范

每个模块目录 `NNN-name/` 下包含以下标准文档：

| 文件 | 命名 | 说明 |
|------|------|------|
| 功能规格 | `spec.md` | 功能需求、用户故事、验收标准（FR-xxx） |
| 技术方案 | `plan.md` | 技术实现方案、架构决策、实现策略 |

> 后期按需扩展：`tasks.md`、`data-model.md`、`test-cases.md`、`pages.md`。

---

## 八、快速导航

| 目标读者 | 推荐阅读顺序 |
|---------|-------------|
| **新加入开发者** | constitution.md → STRUCTURE.md → overall-spec.md → 具体模块 spec.md |
| **架构师 / Tech Lead** | ARCHITECTURE.md → TECH.md → overall-plan.md |
| **Android 开发** | STRUCTURE.md → 对应模块 spec.md + plan.md |
| **自动化脚本/自动化开发** | 005-script/spec.md + plan.md → 004-server/spec.md |
| **测试 / QA** | overall-test-cases.md → SPECS_CHECKLIST.md |
| **产品经理** | overall-spec.md → 对应模块 spec.md |

---

**文档维护者：** ManagedFloatingApp 开发团队
