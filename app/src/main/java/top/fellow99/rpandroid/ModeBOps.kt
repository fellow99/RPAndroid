package top.fellow99.rpandroid

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import top.fellow99.rpandroid.core.controller.LaunchResult
import top.fellow99.rpandroid.core.controller.MainScreenOps
import top.fellow99.rpandroid.core.shell.NoRootShell
import kotlinx.coroutines.runBlocking

/**
 * [ModeBOps] —— Mode B（主屏承载）的[MainScreenOps]实现（010-display-mode）。
 *
 * 操作作用于**主屏**（displayId=0）：
 * - tap/swipe：优先 a11y `dispatchGesture`（更稳，不依赖 shell）；失败回退 `input -d 0`。
 * - pressKey/text(ASCII)：`input -d 0 keyevent` / `input -d 0 text`。
 * - launch：`startActivity` 前置目标 App 到主屏（`am start -n`）。
 *
 * 由 [ManagedRuntime] 注入到 [top.fellow99.rpandroid.core.controller.AppScreenController].mainOps。
 */
class ModeBOps(
    private val context: Context,
    private val shell: NoRootShell,
) : MainScreenOps {

    private val pm = context.packageManager

    override fun tap(x: Int, y: Int): Boolean {
        if (top.fellow99.rpandroid.a11y.MfaAccessibilityService.dispatchTap(x, y)) return true
        return shellInput("input -d 0 tap $x $y")
    }

    override fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durMs: Int): Boolean {
        if (top.fellow99.rpandroid.a11y.MfaAccessibilityService.dispatchSwipe(x1, y1, x2, y2, durMs)) return true
        return shellInput("input -d 0 swipe $x1 $y1 $x2 $y2 ${durMs.coerceAtLeast(60)}")
    }

    override fun pressKey(keyCode: Int): Boolean = shellInput("input -d 0 keyevent $keyCode")

    override fun text(text: String): Boolean {
        val escaped = text.replace("'", "'\\''")
        return shellInput("input -d 0 text '$escaped'")
    }

    override fun launch(packageName: String, component: String?): LaunchResult {
        val intent: Intent? = try {
            if (component != null) {
                Intent(Intent.ACTION_MAIN).setClassName(packageName, component)
                    .addCategory(Intent.CATEGORY_LAUNCHER)
            } else {
                pm.getLaunchIntentForPackage(packageName)
            }
        } catch (e: Exception) {
            null
        } ?: return LaunchResult(false, message = "no launch intent for $packageName")
        return try {
            intent!!.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            LaunchResult(true, displayId = 0)
        } catch (e: Exception) {
            LaunchResult(false, message = e.message ?: "launch error")
        }
    }

    private fun shellInput(command: String): Boolean = try {
        runBlocking { shell.run(command) } != null
    } catch (e: Exception) {
        false
    }
}

/** 便捷：从上下文取 MediaProjectionManager（供授权弹窗）。 */
fun projectionManager(context: Context): MediaProjectionManager? =
    context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
