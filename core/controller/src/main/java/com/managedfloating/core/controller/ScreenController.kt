package com.managedfloating.core.controller

/**
 * [ScreenController] —— 读取与操作被托管 App 的唯一入口（架构 B）。
 *
 * 被 REST / MCP / 自动化服务 / 用户触摸共用。实现（[AppScreenController]）把操作委托给
 * 免 root shell 助手（建虚拟屏 / 承载 / 注入 / 截帧），001-shell 提供底层机制。
 */
interface ScreenController {

    /** 列出系统已安装、可启动的 App。 */
    fun listApps(): List<AppInfo>

    /** 把 [packageName]/[component] 承载到自有虚拟屏。 */
    fun launch(packageName: String, component: String?): LaunchResult

    /** 返回当前被托管 App 的最新一帧（PNG），无则 null。 */
    fun captureFrame(): ByteArray?

    /** 在被托管屏（displayId）上注入点击。 */
    fun tap(x: Int, y: Int): Boolean

    /** 在被托管屏上注入滑动。 */
    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durMs: Int): Boolean

    /** 读取被托管 App 界面元素（JSON 数组字符串）；不可用时 null。 */
    fun readUi(): String?

    /** 当前是否已有可用的承载会话（虚拟屏 + 助手）。 */
    fun isSessionActive(): Boolean
}

data class AppInfo(
    val packageName: String,
    val label: String,
)

data class LaunchResult(
    val ok: Boolean,
    val displayId: Int? = null,
    val message: String = "",
)
