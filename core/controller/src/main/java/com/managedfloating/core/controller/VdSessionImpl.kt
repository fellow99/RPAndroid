package com.managedfloating.core.controller

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.managedfloating.core.shell.MfaHandoff
import com.managedfloating.core.shell.NoRootShell
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * [VdSessionImpl] —— 把 [VdSession] 接到免 root 助手（[VdHelper]）。
 *
 * 经 [NoRootShell] 连 adbd、用 held pty 拉起 `app_process ... VdHelper`（CLASSPATH 用 App 的 base.apk）。
 *
 * **帧方向（007-webrtc）**：App 进程持有 [FrameHub]（ImageReader）；助手经 App 导出的 ContentProvider
 * （[MfaHandoff]）跨 UID Binder 事务拉取 FrameHub 的 Surface 建虚拟屏 → 帧**落在 App 进程**，
 * 不再逐帧文件 IPC。交接不成功时助手内部回退旧路径（自持 ImageReader），本类据 `surface_status.txt`
 * 决定 [captureFrame] 走帧源还是文件 IPC。
 *
 * **控制方向（不变）**：launch/tap/swipe/key 仍经文件 IPC（`cmd.txt/result.txt`）下发。
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
    // 用时间戳作基线，保证每次会话重建后 seq 仍单调递增且大于任何历史/残留指令，
    // 避免 helper 端 lastSeq 被高 seq“污染”后永久忽略新指令。
    private val seq = AtomicInteger(((System.currentTimeMillis() / 1000) % 1_000_000).toInt())

    /**
     * 文件 IPC 只有一对 cmd.txt/result.txt，必须串行：多个 HTTP 请求在不同 IO 线程并发调用时，
     * 否则会互相覆盖指令/读到对方的结果，表现为偶发 no frame、ok=false。
     */
    private val ipcLock = Any()

    @Volatile private var started = false

    /** App 进程侧帧源；非 null 表示 Surface 交接成功（帧直达 App）。 */
    @Volatile private var hub: FrameHub? = null

    override fun frameSource(): FrameSource? = hub

    /** 连 adbd 并拉起助手，交接 Surface 建虚拟屏，等到 ready.txt（返回 displayId）成功为止。 */
    fun start(): Boolean = runBlocking {
        ipcDir.mkdirs()
        readyFile.delete()
        if (!shell.connect(connectPort)) return@runBlocking false
        val apk = apkPath() ?: return@runBlocking false
        val baseCmd = "rm -f ${readyFile.absolutePath} ${cmdFile.absolutePath} ${resultFile.absolutePath} ${File(ipcDir, "surface_status.txt").absolutePath}; " +
            "CLASSPATH=$apk exec app_process /system/bin com.managedfloating.core.shell.vd.VdHelper " +
            "${ipcDir.absolutePath} $width $height $dpi"

        // 首选：Surface 交接（Mika 式）——先建 FrameHub 并把 Surface 交给 provider，再拉起助手。
        val fh = FrameHub(width, height)
        val nonce = MfaHandoff.prepare(fh.surface)
        shell.startHelper("$baseCmd --nonce $nonce")
        if (waitReady()) {
            // 助手据 surface_status.txt 决定是否用上了 App 的 Surface
            val status = surfaceStatus()
            android.util.Log.d("VdSession", "start: ready, surface_status=$status")
            started = true
            hub = if (status == "ok") {
                waitFirstFrame()
                fh
            } else {
                fh.release()
                null
            }
            return@runBlocking true
        }
        fh.release()
        false
    }

    private fun surfaceStatus(): String = try {
        val f = File(ipcDir, "surface_status.txt")
        if (f.exists()) f.readText().trim() else "missing"
    } catch (e: Exception) {
        "unreadable"
    }

    private fun waitReady(): Boolean {
        val deadline = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < deadline) {
            if (readyFile.exists() && readyFile.readText().trim().toIntOrNull() != null) {
                return true
            }
            Thread.sleep(200)
        }
        return false
    }

    /** 交接模式：等首帧（虚拟屏内容渲染产生）；超时不算失败。 */
    private fun waitFirstFrame() {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            if (hub?.hasFrame == true) return
            Thread.sleep(100)
        }
    }

    private fun apkPath(): String? {
        return try {
            val info = pm.getApplicationInfo(context.packageName, 0)
            info.sourceDir
        } catch (e: Exception) {
            null
        }
    }

    private fun command(op: String, vararg args: String): String? =
        synchronized(ipcLock) {
            if (!started) return@synchronized null
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
                            return@synchronized if (parts.getOrNull(1) == "ok") parts.getOrNull(2) ?: "" else null
                        }
                    }
                    Thread.sleep(50)
                }
                null
            } catch (e: Exception) {
                null
            } finally {
                // 确保下一条指令不会读到本次残留
                runCatching { cmdFile.delete() }
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
        // 首选：App 进程帧源（静态画面返回缓存 PNG，不重复编码/文件往返）。
        val h = hub
        if (h != null) return h.latestPng()
        // 降级旧路径：文件 IPC 让助手截帧。
        val path = File(ipcDir, "frame.png")
        repeat(3) { attempt ->
            path.delete()
            val out = command("frame", path.absolutePath)
            android.util.Log.d("VdSession", "frame attempt=$attempt out=$out exists=${path.exists()} len=${path.length()}")
            if (out != null && path.exists() && path.length() > 0) {
                return path.readBytes()
            }
            if (attempt < 2) Thread.sleep(300)
        }
        return null
    }

    override fun tap(x: Int, y: Int): Boolean = command("tap", x.toString(), y.toString()) != null

    override fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durMs: Int): Boolean =
        command("swipe", x1.toString(), y1.toString(), x2.toString(), y2.toString(), durMs.toString()) != null

    override fun pressKey(keyCode: Int): Boolean = command("key", keyCode.toString()) != null

    override fun closeApp(packageName: String): Boolean = command("closeapp", packageName) != null

    /** 释放帧源等本会话持有的资源（新会话接管前调用，避免线程/读取器泄漏）。 */
    fun release() {
        started = false
        val h = hub
        hub = null
        h?.release()
    }

    private fun resolveLauncher(pkg: String): String? {
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(pkg)
        val ri = pm.queryIntentActivities(i, 0).firstOrNull() ?: return null
        return "${ri.activityInfo.packageName}/${ri.activityInfo.name}"
    }
}
