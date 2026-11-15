// RPAuto —— Gradle 多模块工程
//
// 本机访问 google maven 偶发不稳，故镜像前置（阿里云），404 时自动回退官方仓库；
// maven central 直连可用。

pluginManagement {
    repositories {
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        google()
        mavenCentral()
    }
}

rootProject.name = "RPAuto"

// 模块占位（000-scaffold）：
//   app               → 入口 / 配置界面 / 三种呈现模式（架构 F + C）
//   core:shell        → shell 助手进程 / 免 root 托管基座（架构 A）
//   core:controller   → ScreenController 统一操作入口（架构 B）
//   core:server    → REST / MCP / 视频流对外接口（架构 D）
//   core:script       → ScriptExecutorService + Node 运行时（架构 E）
include(":app")
include(":core:shell")
include(":core:controller")
include(":core:server")
include(":core:script")
include(":core:webrtc")
