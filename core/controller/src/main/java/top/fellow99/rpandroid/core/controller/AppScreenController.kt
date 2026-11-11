package top.fellow99.rpandroid.core.controller

import android.content.Context
import android.content.pm.PackageManager
import java.io.File

/**
 * [AppScreenController] —— [ScreenController] 的应用内实现。
 *
 * - [listApps]：经 [PackageManager] 枚举（即时可用，不依赖 shell）。
 * - 承载/截帧/注入：依赖免 root shell 助手的会话（[VdSession]）。开发期可由电脑 adb 部署助手
 *   并经共享目录通道工作；正式免 root 自举（M1-2）后由应用自身拉起助手填充会话。
 *   会话未就绪时返回明确的 `helper_unavailable`，不崩溃。
 */
class AppScreenController(private val context: Context) : ScreenController {

    private val pm = context.packageManager

    /** 共享目录通道：助手的帧与命令往返位置（开发期 adb 部署助手时使用）。 */
    private val channelDir: File
        get() = context.getExternalFilesDir(null) ?: File(context.filesDir, "shared")

    @Volatile var vdSession: VdSession? = null

    /** 界面读取器（由 app 注入无障碍服务实现）。 */
    @Volatile var uiReader: (() -> String?)? = null

    // ===== 010-display-mode：承载模式（A 虚拟屏 / B 主屏）=====
    /** 当前承载模式；首启默认虚拟屏承载（A）。 */
    @Volatile var displayMode: DisplayMode = DisplayMode.VIRTUAL

    /** MediaProjection 授权态（Mode B 前置）。 */
    @Volatile var projectionAuth: ProjectionAuth = ProjectionAuth.NONE

    /** Mode B 的主屏帧源（MediaProjection 捕获），由 app 层 MediaProjectionHost 注入。 */
    @Volatile var projectionFrameSource: FrameSource? = null

    /** Mode B 主屏操作注入（a11y 手势 + input -d 0），由 app 层注入。 */
    @Volatile var mainOps: MainScreenOps? = null

    /** 模式变化回调（app 层发布到 UI / 通知 / 持久化）。 */
    @Volatile var onDisplayModeChanged: ((DisplayMode) -> Unit)? = null

    /** 持久化回调（app 层接 ServerConfig.displayMode；符合 C-3，controller 不依赖 server）。 */
    @Volatile var persistDisplayMode: ((DisplayMode) -> Unit)? = null

    /** 正在运行自动化任务的判定（app 层注入 TaskRuntime.runningTaskId）。 */
    @Volatile var hasRunningTask: (() -> Boolean)? = null

    /** 离开主屏承载（Mode B）时的投影释放钩子（app 层注入，停止 ProjectionService/清帧源）。 */
    @Volatile var releaseProjection: (() -> Unit)? = null

    /**
     * 切换承载模式：先做守卫（运行自动化任务时禁止切换，FR-010-005），再更新模式并持久化/通知。
     * 离开 MAIN 时经 [releaseProjection] 释放 MediaProjection（避免 B→A 后仍后台捕获主屏）。
     * 返回 null 表示可切换；否则返回拒绝原因（调用方在切换前应以此判断）。
     */
    fun switchDisplayMode(target: DisplayMode): String? {
        if (target == displayMode) return null
        if (hasRunningTask?.invoke() == true) {
            return "拒绝切换：自动化任务正在运行（runningTaskId != null）"
        }
        val old = displayMode
        displayMode = target
        persistDisplayMode?.invoke(target)
        if (old == DisplayMode.MAIN && target != DisplayMode.MAIN) {
            releaseProjection?.invoke()
        }
        onDisplayModeChanged?.invoke(target)
        return null
    }

    /** 当前承载的 App（由远程/UI 任意来源经 [launch] 成功后更新）；会话/主状态同步用。 */
    @Volatile var hostedPkg: String? = null
        private set
    @Volatile var hostedLabel: String? = null
        private set

    /** 承载状态变化回调（由 app 层注入，把变化发布到 UI 侧，供 /client 与 App UI 同步）。 */
    @Volatile var onHostChanged: (() -> Unit)? = null

    /** 当前承载的 App；未承载返回 null。 */
    fun hosted(): AppInfo? = hostedPkg?.let { AppInfo(it, hostedLabel ?: it) }

    /** 会话被清理/重建时调用，重置承载状态并通知 UI。 */
    fun clearHosted() {
        val changed = hostedPkg != null || hostedLabel != null
        hostedPkg = null
        hostedLabel = null
        if (changed) onHostChanged?.invoke()
    }

    /**
     * 关闭虚拟屏上当前承载的应用（force-stop，保留会话/虚拟屏），并把承载状态清空、
     * 返回“应用列表”状态（App UI / /client 均回到选择列表）。
     */
    fun closeHostedApp() {
        val pkg = hostedPkg
        val session = vdSession
        if (pkg != null && session != null) {
            runCatching { session.closeApp(pkg) }
                .onFailure { android.util.Log.w("MFA/Controller", "close app $pkg failed: ${it.message}") }
        }
        clearHosted()
    }

    override fun listApps(): List<AppInfo> {
        val intent = android.content.Intent(IntentAction.ACTION_MAIN).apply { addCategory(IntentCat.CATEGORY_LAUNCHER) }
        val resolved = pm.queryIntentActivities(intent, 0)
        val seen = LinkedHashMap<String, String>()
        for (ri in resolved) {
            val pkg = ri.activityInfo.packageName
            if (!seen.containsKey(pkg)) {
                seen[pkg] = ri.loadLabel(pm).toString()
            }
        }
        if (seen.isEmpty()) {
            // 兜底：至少列出系统已安装应用
            for (pi in pm.getInstalledApplications(0)) {
                val pkg = pi.packageName
                if (!seen.containsKey(pkg)) {
                    seen[pkg] = pi.loadLabel(pm).toString()
                }
            }
        }
        return seen.entries.map { (pkg, label) -> AppInfo(pkg, label) }.sortedBy { it.label }
    }

    override fun launch(packageName: String, component: String?): LaunchResult {
        if (displayMode == DisplayMode.MAIN) {
            val ops = mainOps ?: return LaunchResult(false, message = HELPER_UNAVAILABLE)
            return try {
                val r = ops.launch(packageName, component)
                if (r.ok) {
                    hostedPkg = packageName
                    hostedLabel = runCatching { pm.getApplicationInfo(packageName, 0).loadLabel(pm).toString() }.getOrNull()
                    onHostChanged?.invoke()
                }
                r
            } catch (e: Exception) {
                LaunchResult(false, message = e.message ?: "launch error")
            }
        }
        val s = vdSession ?: return LaunchResult(false, message = HELPER_UNAVAILABLE)
        return try {
            val r = s.launch(packageName, component)
            if (r.ok) {
                hostedPkg = packageName
                hostedLabel = runCatching { pm.getApplicationInfo(packageName, 0).loadLabel(pm).toString() }.getOrNull()
                onHostChanged?.invoke()
            }
            r
        } catch (e: Exception) {
            LaunchResult(false, message = e.message ?: "launch error")
        }
    }

    override fun captureFrame(): ByteArray? {
        val s = vdSession ?: return null
        return try { s.captureFrame() } catch (e: Exception) { null }
    }

    override fun tap(x: Int, y: Int): Boolean {
        if (displayMode == DisplayMode.MAIN) {
            val ops = mainOps ?: return false
            return try { ops.tap(x, y) } catch (e: Exception) { false }
        }
        val s = vdSession ?: return false
        return try { s.tap(x, y) } catch (e: Exception) { false }
    }

    override fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durMs: Int): Boolean {
        if (displayMode == DisplayMode.MAIN) {
            val ops = mainOps ?: return false
            return try { ops.swipe(x1, y1, x2, y2, durMs) } catch (e: Exception) { false }
        }
        val s = vdSession ?: return false
        return try { s.swipe(x1, y1, x2, y2, durMs) } catch (e: Exception) { false }
    }

    override fun back(): Boolean = pressKey(android.view.KeyEvent.KEYCODE_BACK)

    override fun pressKey(keyCode: Int): Boolean {
        if (displayMode == DisplayMode.MAIN) {
            val ops = mainOps ?: return false
            return try { ops.pressKey(keyCode) } catch (e: Exception) { false }
        }
        val s = vdSession ?: return false
        return try { s.pressKey(keyCode) } catch (e: Exception) { false }
    }

    /**
     * 文本注入：
     * - ASCII：交给目标显示执行 `input text`（可靠、无剪贴板副作用）；
     * - 非 ASCII（中文等）：写系统剪贴板 + 注入 KEYCODE_PASTE（
     *   部分 ROM 无障碍 ACTION_SET_TEXT 不可用，故走粘贴）。
     */
    override fun text(text: String): Boolean {
        if (text.isEmpty()) return true
        if (displayMode == DisplayMode.MAIN) {
            val ops = mainOps ?: return false
            return try {
                if (text.all { it.code in 0x20..0x7E }) ops.text(text)
                else pasteViaClipboard(text) && ops.pressKey(android.view.KeyEvent.KEYCODE_PASTE)
            } catch (e: Exception) { false }
        }
        val s = vdSession ?: return false
        return try {
            if (text.all { it.code in 0x20..0x7E }) {
                s.text(text)
            } else {
                pasteViaClipboard(text) && s.pressKey(android.view.KeyEvent.KEYCODE_PASTE)
            }
        } catch (e: Exception) {
            android.util.Log.w("MFA/Controller", "text inject failed: ${e.message}")
            false
        }
    }

    /** 把文本写入系统剪贴板（供粘贴键消费）；不记日志正文，避免敏感信息落盘。 */
    private fun pasteViaClipboard(text: String): Boolean = try {
        val cm = context.getSystemService(android.content.ClipboardManager::class.java)
        cm.setPrimaryClip(android.content.ClipData.newPlainText("mfa", text))
        true
    } catch (e: Exception) {
        android.util.Log.w("MFA/Controller", "clipboard write failed: ${e.message}")
        false
    }

    override fun readUi(): String? = runCatching { uiReader?.invoke() }.getOrNull()

    override fun frameSource(): FrameSource? = when (displayMode) {
        DisplayMode.MAIN -> projectionFrameSource
        DisplayMode.VIRTUAL -> vdSession?.frameSource()
    }

    override fun isSessionActive(): Boolean = vdSession != null

    /** 会话通道目录（供嵌入的助手高效使用）。 */
    fun channel(): File = channelDir

    companion object {
        const val HELPER_UNAVAILABLE = "helper_unavailable: 未检测到 shell 助手会话（需免 root 自举或部署助手）"
        internal object IntentAction { const val ACTION_MAIN = "android.intent.action.MAIN" }
        internal object IntentCat { const val CATEGORY_LAUNCHER = "android.intent.category.LAUNCHER" }
    }
}

/**
 * Mode B 主屏操作注入（010-display-mode）。
 * 由 app 层以「a11y dispatchGesture + input -d 0」实现，供 [AppScreenController] 在
 * [`displayMode == MAIN`] 时分派（遵守 C-1 单一操作入口）。
 */
interface MainScreenOps {
    fun tap(x: Int, y: Int): Boolean
    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durMs: Int): Boolean
    fun pressKey(keyCode: Int): Boolean
    /** 注入文本（ASCII 走 input text；中文由 [AppScreenController.text] 走剪贴板+PASTE）。 */
    fun text(text: String): Boolean
    fun launch(packageName: String, component: String?): LaunchResult
}

/**
 * 承载/操作会话：把对虚拟屏的"承载/截帧/注入"委托给 shell 助手。
 * 由免 root 自举（M1-2）或开发期部署填充实现。
 */
interface VdSession {
    fun launch(packageName: String, component: String?): LaunchResult
    fun captureFrame(): ByteArray?
    fun tap(x: Int, y: Int): Boolean
    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durMs: Int): Boolean
    fun pressKey(keyCode: Int): Boolean
    /** 注入文本（仅 ASCII；中文由 [AppScreenController.text] 走剪贴板）。 */
    fun text(text: String): Boolean
    /** 关闭虚拟屏上的 [packageName] 应用（保留会话；用于“返回应用列表”）。 */
    fun closeApp(packageName: String): Boolean
    /** App 进程侧的共享帧源；无会话/未就绪返回 null。 */
    fun frameSource(): FrameSource?
}
