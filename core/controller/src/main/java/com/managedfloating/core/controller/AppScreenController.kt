package com.managedfloating.core.controller

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
        val s = vdSession ?: return LaunchResult(false, message = HELPER_UNAVAILABLE)
        return try {
            s.launch(packageName, component)
        } catch (e: Exception) {
            LaunchResult(false, message = e.message ?: "launch error")
        }
    }

    override fun captureFrame(): ByteArray? {
        val s = vdSession ?: return null
        return try { s.captureFrame() } catch (e: Exception) { null }
    }

    override fun tap(x: Int, y: Int): Boolean {
        val s = vdSession ?: return false
        return try { s.tap(x, y) } catch (e: Exception) { false }
    }

    override fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durMs: Int): Boolean {
        val s = vdSession ?: return false
        return try { s.swipe(x1, y1, x2, y2, durMs) } catch (e: Exception) { false }
    }

    override fun back(): Boolean = pressKey(android.view.KeyEvent.KEYCODE_BACK)

    override fun pressKey(keyCode: Int): Boolean {
        val s = vdSession ?: return false
        return try { s.pressKey(keyCode) } catch (e: Exception) { false }
    }

    override fun readUi(): String? = runCatching { uiReader?.invoke() }.getOrNull()

    override fun frameSource(): FrameSource? = vdSession?.frameSource()

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
 * 承载/操作会话：把对虚拟屏的"承载/截帧/注入"委托给 shell 助手。
 * 由免 root 自举（M1-2）或开发期部署填充实现。
 */
interface VdSession {
    fun launch(packageName: String, component: String?): LaunchResult
    fun captureFrame(): ByteArray?
    fun tap(x: Int, y: Int): Boolean
    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durMs: Int): Boolean
    fun pressKey(keyCode: Int): Boolean
    /** App 进程侧的共享帧源；无会话/未就绪返回 null。 */
    fun frameSource(): FrameSource?
}
