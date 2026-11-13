package top.fellow99.rpandroid.core.controller

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

    /** 在被托管屏上注入返回键（keyevent BACK）。 */
    fun back(): Boolean

    /** 在被托管屏上注入任意 keyevent（keycode）。 */
    fun pressKey(keyCode: Int): Boolean

    /**
     * 向被托管屏当前焦点输入框写入文本（支持中文等非 ASCII）。
     *
     * 实现策略：ASCII 走系统 `input text`；非 ASCII 走“系统剪贴板 + 粘贴键（KEYCODE_PASTE）”。
     * 返回是否已成功下发（不等同于目标 App 一定插入成功）。
     */
    fun text(text: String): Boolean

    /** 读取被托管 App 界面元素（JSON 数组字符串）；不可用时 null。 */
    fun readUi(): String?

    /** 读取被托管 App 界面层级树（JSON，含 children + resourceId）；不可用时 null。 */
    fun dumpUi(): String?

    /** 按 XPath 子集查询界面节点（JSON 扁平命中数组）；非法 XPath 抛 [IllegalArgumentException]。 */
    fun queryUi(xpath: String): String?

    /** 按 resourceId 定位界面节点（JSON 扁平命中数组）；不可用时 null。 */
    fun getUi(resourceId: String): String?

    /** 共享帧源（App 进程捕捉到的虚拟屏画面），供视频推流/预览；无会话返回 null。 */
    fun frameSource(): FrameSource?

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
