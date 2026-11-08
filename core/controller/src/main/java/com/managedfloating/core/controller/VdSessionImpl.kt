package com.managedfloating.core.controller

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.LocalServerSocket
import android.os.Parcel
import android.view.Surface
import com.managedfloating.core.shell.NoRootShell
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread
import java.util.concurrent.atomic.AtomicInteger

/**
 * [VdSessionImpl] —— 把 [VdSession] 接到免 root 助手（[VdHelper]）。
 *
 * 经 [NoRootShell] 连 adbd、用 held pty 拉起 `app_process ... VdHelper`（CLASSPATH 用 App 的 base.apk）。
 *
 * **帧方向（T2 重构，007-webrtc）**：App 进程持有 [FrameHub]（ImageReader），其 [Surface] 经
 * 抽象本地 socket 以 Parcel 交接给助手；助手用该 Surface 建虚拟屏 → 帧**落在 App 进程**，
 * 不再逐帧文件 IPC。交接失败时降级回旧路径（助手自持 ImageReader + 文件 IPC）。
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
        val baseCmd = "rm -f ${readyFile.absolutePath} ${cmdFile.absolutePath} ${resultFile.absolutePath}; " +
            "CLASSPATH=$apk exec app_process /system/bin com.managedfloating.core.shell.vd.VdHelper " +
            "${ipcDir.absolutePath} $width $height $dpi"

        // 1) 首选：Surface 交接（帧落 App）
        val handoff = handoffStart(baseCmd)
        if (handoff != null) {
            hub = handoff
            // 等就绪
            if (waitReady()) {
                started = true
                // 等首帧：助手建屏后 frames 应很快到达；个别 ROM 延迟，不阻塞会话就绪。
                waitFirstFrame()
                return@runBlocking true
            }
            hub?.release(); hub = null
            return@runBlocking false
        }
        // 2) 降级：助手自持 ImageReader（旧路径，帧走文件 IPC）
        shell.startHelper(baseCmd)
        if (waitReady()) {
            started = true
            return@runBlocking true
        }
        false
    }

    /** 尝试 Surface 交接：监听抽象 socket → 助手连入 → Parcel 装 Surface → 等 ready。失败返回 null。 */
    private suspend fun handoffStart(baseCmd: String): FrameHub? {
        val hub = FrameHub(width, height)
        val sockName = "mfa-surf-" + (Math.abs(java.util.concurrent.ThreadLocalRandom.current().nextLong()) % 1_000_000_000L)
        val server = try {
            LocalServerSocket(sockName)
        } catch (t: Throwable) {
            hub.release()
            return null
        }
        try {
            shell.startHelper("$baseCmd --surface-sock $sockName")
            // accept 无超时：放独立线程，配合截止时间防止助手起不来时挂死。
            val holder = java.util.concurrent.atomic.AtomicReference<android.net.LocalSocket?>(null)
            val acceptFailed = java.util.concurrent.atomic.AtomicBoolean(false)
            thread(start = true, isDaemon = true) {
                try {
                    holder.set(server.accept())
                } catch (t: Throwable) {
                    acceptFailed.set(true)
                }
            }
            val deadline = System.currentTimeMillis() + 15_000
            while (System.currentTimeMillis() < deadline && holder.get() == null && !acceptFailed.get()) {
                Thread.sleep(100)
            }
            val client = holder.get() ?: run {
                hub.release()
                return null
            }
            try {
                client.soTimeout = 10_000
                writeSurface(client.outputStream, hub.surface)
            } finally {
                runCatching { client.close() }
            }
            return hub
        } catch (t: Throwable) {
            hub.release()
            return null
        } finally {
            runCatching { server.close() }
        }
    }

    /** 装 Surface 到 Parcel 并经 socket 写入（4 字节小端长度前缀 + parcel 字节）。 */
    private fun writeSurface(out: java.io.OutputStream, surface: Surface) {
        val p = Parcel.obtain()
        try {
            p.writeParcelable(surface, 0)
            val blob = p.marshall()
            val head = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(blob.size).array()
            out.write(head)
            out.write(blob)
            out.flush()
        } finally {
            p.recycle()
        }
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
