package com.managedfloating.core.controller

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.managedfloating.core.shell.NoRootShell
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * [VdSessionImpl] —— 把 [VdSession] 接到免 root 助手（[VdHelper]）。
 *
 * 经 [NoRootShell] 连 adbd、用 held pty 拉起 `app_process ... VdHelper`（CLASSPATH 用 App 的 base.apk），
 * 之后通过**文件 IPC**（App external files 下的 mfa 目录）下发 launch/frame/tap/swipe 命令。
 */
class VdSessionImpl(
    private val context: Context,
    private val shell: NoRootShell,
    private val connectPort: Int? = null,
    private val width: Int = 720,
    private val height: Int = 1560,
    private val dpi: Int = 400,
) : VdSession {

    private val pm: PackageManager = context.packageManager
    private val ipcDir: File = File(context.getExternalFilesDir(null), "mfa")
    private val cmdFile = File(ipcDir, "cmd.txt")
    private val resultFile = File(ipcDir, "result.txt")
    private val readyFile = File(ipcDir, "ready.txt")
    private val seq = AtomicInteger(0)

    @Volatile private var started = false

    /** 连 adbd 并拉起助手，等到 ready.txt（返回 displayId）成功为止。 */
    fun start(): Boolean = runBlocking {
        ipcDir.mkdirs()
        readyFile.delete()
        if (!shell.connect(connectPort)) return@runBlocking false
        val apk = apkPath() ?: return@runBlocking false
        val command = "rm -f ${readyFile.absolutePath} ${cmdFile.absolutePath} ${resultFile.absolutePath}; " +
            "CLASSPATH=$apk exec app_process /system/bin com.managedfloating.core.shell.vd.VdHelper " +
            "${ipcDir.absolutePath} $width $height $dpi"
        shell.startHelper(command)   // 保持 pty 流，助手常驻
        // 等就绪
        val deadline = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < deadline) {
            if (readyFile.exists() && readyFile.readText().trim().toIntOrNull() != null) {
                started = true
                return@runBlocking true
            }
            Thread.sleep(200)
        }
        false
    }

    private fun apkPath(): String? {
        return try {
            val info = pm.getApplicationInfo(context.packageName, 0)
            info.sourceDir
        } catch (e: Exception) {
            null
        }
    }

    private fun command(op: String, vararg args: String): String? {
        if (!started) return null
        val s = seq.incrementAndGet()
        try {
            resultFile.delete()
            cmdFile.writeText((listOf(s.toString(), op) + args).joinToString("|"))
            val deadline = System.currentTimeMillis() + 8_000
            while (System.currentTimeMillis() < deadline) {
                if (resultFile.exists()) {
                    val line = resultFile.readText().trim()
                    val parts = line.split("|", limit = 3)
                    if (parts.getOrNull(0)?.toIntOrNull() == s) {
                        return if (parts.getOrNull(1) == "ok") parts.getOrNull(2) ?: "" else null
                    }
                }
                Thread.sleep(50)
            }
            return null
        } catch (e: Exception) {
            return null
        }
    }

    override fun launch(packageName: String, component: String?): LaunchResult {
        val target = when {
            component == null -> resolveLauncher(packageName)
            component.startsWith(".") -> "$packageName/$component"
            else -> component
        } ?: return LaunchResult(false, message = "no launcher activity for $packageName")
        val out = command("launch", target)
        return if (out != null) LaunchResult(true, message = target) else LaunchResult(false, message = "launch failed")
    }

    override fun captureFrame(): ByteArray? {
        val path = File(ipcDir, "frame.png")
        repeat(4) { attempt ->
            path.delete()
            val out = command("frame", path.absolutePath)
            android.util.Log.d("VdSession", "frame attempt=$attempt out=$out exists=${path.exists()} len=${path.length()}")
            if (out != null && path.exists() && path.length() > 0) {
                return path.readBytes()
            }
            if (attempt < 3) Thread.sleep(400)
        }
        return null
    }

    override fun tap(x: Int, y: Int): Boolean = command("tap", x.toString(), y.toString()) != null

    override fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durMs: Int): Boolean =
        command("swipe", x1.toString(), y1.toString(), x2.toString(), y2.toString(), durMs.toString()) != null

    private fun resolveLauncher(pkg: String): String? {
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(pkg)
        val ri = pm.queryIntentActivities(i, 0).firstOrNull() ?: return null
        return "${ri.activityInfo.packageName}/${ri.activityInfo.name}"
    }
}
