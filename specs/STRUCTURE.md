# STRUCTURE.md — 工程目录结构

> 状态：000-scaffold（骨架）。随里程碑实现持续更新。
> 说明：`build/`、`.gradle/`、`local.properties` 不入库。

## 顶层

```
ManagedFloatingApp/
├── settings.gradle.kts          # 多模块声明 + 仓库镜像（gradle 插件 / google / public）
├── build.gradle.kts             # 根插件声明（AGP 9.2.1 built-in Kotlin）
├── gradle.properties            # 构建参数（并行、缓存、AndroidX）
├── local.properties             # sdk.dir（不入库）
├── .gitignore
├── gradle/
│   ├── libs.versions.toml       # 版本目录（依赖与插件版本集中管理）
│   └── wrapper/                 # Gradle wrapper（9.4.1）
├── docs/
│   └── 000-工程规划.md           # 工程规划（brainstorm 落盘）
├── specs/                       # 规范文档（本套件，见 README.md 索引）
└── {app,core}/{...}             # 源码模块（见下）
```

## 源码模块

| 模块 | 命名空间 | 职责（架构） | 状态 |
|------|---------|-------------|------|
| `app` | `com.managedfloating.app` | 入口 / 配置 UI / 呈现模式（F + C） | 骨架（纯 View 占位） |
| `core:shell` | `com.managedfloating.core.shell` | 免 root 托管基座（A） | 骨架（接口占位） |
| `core:controller` | `com.managedfloating.core.controller` | ScreenController 统一操作入口（B） | 骨架（接口占位） |
| `core:server` | `com.managedfloating.core.server` | REST / MCP / 视频流对外接口（D） | 骨架（接口占位） |
| `core:script` | `com.managedfloating.core.script` | ScriptExecutorService + Node 运行时（E） | 骨架（接口占位 + 引擎依赖） |

### app（主应用）
```
app/
├── build.gradle.kts
└── src/main/
    ├── AndroidManifest.xml       # 应用清单（仅 MainActivity + 主题）
    ├── java/com/managedfloating/app/
    │   └── MainActivity.kt        # 入口（纯 View 占位）
    └── res/values/
        ├── strings.xml            # app_name
        └── themes.xml             # Theme.ManagedFloatingApp（NoActionBar）
```

### core/*
```
core/shell/
  ├── build.gradle.kts
  └── src/main/
      ├── AndroidManifest.xml
      └── java/com/managedfloating/core/shell/ShellController.kt   # 接口占位

core/controller/
  ├── build.gradle.kts
  └── src/main/
      ├── AndroidManifest.xml
      └── java/com/managedfloating/core/controller/ScreenController.kt

core/server/
  ├── build.gradle.kts
  └── src/main/
      ├── AndroidManifest.xml
      └── java/com/managedfloating/core/server/ManagedServer.kt

core/script/
  ├── build.gradle.kts            # 引入 node-bridge(implementation) + node-runtime(runtimeOnly)
  └── src/main/
      ├── AndroidManifest.xml
      └── java/com/managedfloating/core/script/ScriptExecutorService.kt
```

## 关键配置位置

| 文件 | 用途 |
|------|------|
| `gradle/libs.versions.toml` | 依赖/插件版本 |
| `app/build.gradle.kts` | 应用 id、minSdk 24、abiFilters arm64、node-runtime 透传 |
| `core/script/build.gradle.kts` | node-bridge / node-runtime 引入处（arm64-only） |
| `settings.gradle.kts` | 仓库镜像（阿里云前置 + google/mavenCentral 回退） |

## 依赖方向（模块间）

```
app ──▶ core:controller ──▶ core:shell
   └──▶ core:server ──▶ core:controller
   └──▶ core:script          （独立，经 service/binder 调用 controller）
```
