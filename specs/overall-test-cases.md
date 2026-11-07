# overall-test-cases.md — 测试用例索引总览

> 状态：000-scaffold。本文档是各模块测试用例的**索引**；000 阶段仅有构建/引擎冒烟项，后续里程碑逐步登记到各模块 `test-cases.md`。

## 测试分层

| 层级 | 说明 | 执行位置 |
|------|------|---------|
| U 单元 | 纯逻辑（坐标换算、调度语义、序列化） | JVM |
| I 集成 | 跨模块链路（shell→controller→server） | 真机 |
| E 接口 | REST / MCP 对 localhost / adb reverse 回归 | 真机+电脑 |
| S 脚本 | Node 执行器冒烟（host.invoke 回环） | 真机 |
| B 构建 | 编译/打包/依赖入包 | 本地 |

## 模块测试用例编号约定
`<模块>-<层级>-<序号>`，如 `ctl-U-001`、`shl-I-001`。

## 当前（000-scaffold）用例

| 编号 | 层级 | 用例 | 归属模块 | 状态 |
|------|------|------|---------|------|
| 000-B-001 | B | 全工程 `:app:assembleDebug` 编译通过 | app | ✅ 通过 |
| 000-B-002 | B | debug APK 内包含 libnode.so / libnode_bridge.so / libnode_host_client.so / libc++_shared.so（arm64） | core:script | ✅ 通过 |

## 规划用例清单（各里程碑登记）

- **shell（M1）**：无线调试配对、建虚拟屏、启动 App、截图、注入点击（shl-B/I）。
- **controller（M1–M2）**：读帧/读树、写操作、坐标校验（ctl-B/U）。
- **server（M1/M4）**：REST 各端点、MCP 工具、MJPEG 帧（srv-E）。
- **script（M3）**：任务调度（立即/间隔/cron）、Node 冒烟、崩溃重启（scr-S/U）。
- **app 呈现（M2）**：三模式切换、触摸回写（app-I）。

> 说明：以上为规划清单骨架；每个里程碑实现时在本文件与对应模块 `test-cases.md` 中登记实际用例与结果。
