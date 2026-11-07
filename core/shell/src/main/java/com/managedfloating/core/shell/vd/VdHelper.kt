package com.managedfloating.core.shell.vd

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.os.Looper
import java.io.File
import java.io.FileOutputStream

/**
 * [VdHelper] —— 以 shell UID(app_process) 运行的虚拟屏助手（打进 App 的 dex，用 App 的 base.apk 作 CLASSPATH 加载）。
 *
 * 由 App 经免 root adb 的 pty 流前台拉起（App 持有该流以维持会话）。通过**文件 IPC** 与 App 通信：
 *   <ipc>/cmd.txt    App 写入：`<seq>|<op>|<args...>`
 *   <ipc>/result.txt 助手写回：`<seq>|<ok|err>|<payload>`
 * 支持操作：create、launch、frame、tap、swipe、quit。
 *
 * 启动参数：`<ipcDir> <width> <height> <dpi>`。
 */
object VdHelper {

    private const val FLAG_PUBLIC = 1 shl 0
    private const val FLAG_OWN_CONTENT_ONLY = 1 shl 3
    private const val FLAG_SUPPORTS_TOUCH = 1 shl 6
    private const val FLAG_ROTATES_WITH_CONTENT = 1 shl 7
    private const val FLAG_DESTROY_CONTENT_ON_REMOVAL = 1 shl 8
    private const val FLAG_TRUSTED = 1 shl 10
    private const val FLAG_OWN_DISPLAY_GROUP = 1 shl 11
    private const val FLAG_ALWAYS_UNLOCKED = 1 shl 12
    private const val FLAG_TOUCH_FEEDBACK_DISABLED = 1 shl 13
    private const val FLAG_OWN_FOCUS = 1 shl 14
    private const val FLAG_STEAL_TOP_FOCUS_DISABLED = 1 shl 16

    @JvmStatic
    fun main(args: Array<String>) {
        val ipcDir = File(args.getOrElse(0) { "/sdcard/Android/data/com.managedfloating.app/files/mfa" })
        val w = args.getOrElse(1) { "720" }.toInt()
        val h = args.getOrElse(2) { "1560" }.toInt()
        val dpi = args.getOrElse(3) { "400" }.toInt()

        ipcDir.mkdirs()
        val cmd = File(ipcDir, "cmd.txt")
        val result = File(ipcDir, "result.txt")
        val ready = File(ipcDir, "ready.txt")
        cmd.delete(); result.delete(); ready.delete()

        Looper.prepareMainLooper()

        val sdk = android.os.Build.VERSION.SDK_INT
        var flags = FLAG_PUBLIC or FLAG_OWN_CONTENT_ONLY or FLAG_SUPPORTS_TOUCH or
            FLAG_ROTATES_WITH_CONTENT or FLAG_DESTROY_CONTENT_ON_REMOVAL or FLAG_TOUCH_FEEDBACK_DISABLED
        if (sdk >= 33) {
            flags = flags or FLAG_TRUSTED or FLAG_OWN_DISPLAY_GROUP or FLAG_ALWAYS_UNLOCKED
            if (sdk >= 34) flags = flags or FLAG_OWN_FOCUS or FLAG_STEAL_TOP_FOCUS_DISABLED
        }

        val reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        val dm = newDisplayManager()
        val vd: VirtualDisplay = try {
            dm.createVirtualDisplay("mfa-agent", w, h, dpi, reader.surface, flags)
        } catch (e: SecurityException) {
            val f2 = FLAG_PUBLIC or FLAG_OWN_CONTENT_ONLY or FLAG_SUPPORTS_TOUCH or
                FLAG_ROTATES_WITH_CONTENT or FLAG_DESTROY_CONTENT_ON_REMOVAL or FLAG_TOUCH_FEEDBACK_DISABLED
            dm.createVirtualDisplay("mfa-agent", w, h, dpi, reader.surface, f2)
        }
        val displayId = vd.display.displayId
        ready.writeText(displayId.toString())
        println("MFA_HELPER_READY id=$displayId")

        var lastSeq = 0
        while (true) {
            val text = try { if (cmd.exists()) cmd.readText().trim() else "" } catch (e: Exception) { "" }
            if (text.isNotEmpty()) {
                val parts = text.split("|")
                val seq = parts.getOrNull(0)?.toIntOrNull() ?: 0
                if (seq > lastSeq) {
                    val op = parts.getOrNull(1) ?: ""
                    val a = parts.drop(2)
                    val payload = try {
                        when (op) {
                            "launch" -> { exec("am start --display $displayId -n ${a[0]}"); "ok" }
                            "frame" -> { captureFrame(reader, a[0]); a[0] }
                            "tap" -> { exec("input -d $displayId tap ${a[0]} ${a[1]}"); "ok" }
                            "swipe" -> { exec("input -d $displayId swipe ${a[0]} ${a[1]} ${a[2]} ${a[3]} ${a.getOrElse(4) { "300" }}"); "ok" }
                            "key" -> { exec("input -d $displayId keyevent ${a[0]}"); "ok" }
                            "quit" -> { result.writeText("$seq|ok|bye"); break }
                            else -> { result.writeText("$seq|err|unknown op $op"); lastSeq = seq; cmd.delete(); continue }
                        }
                    } catch (e: Exception) {
                        result.writeText("$seq|err|${e.message}")
                        lastSeq = seq
                        cmd.delete()
                        continue
                    }
                    result.writeText("$seq|ok|$payload")
                    lastSeq = seq
                    cmd.delete()
                }
            }
            Thread.sleep(80)
        }
    }

    private fun newDisplayManager(): DisplayManager {
        return try {
            val thread = Class.forName("android.app.ActivityThread").getMethod("systemMain").invoke(null)
            val sysCtx = thread.javaClass.getMethod("getSystemContext").invoke(thread) as Context
            val shellCtx = sysCtx.createPackageContext("com.android.shell", 0)
            DisplayManager::class.java.getDeclaredConstructor(Context::class.java).newInstance(shellCtx)
        } catch (e: Exception) {
            // 兜底：直接构造（可能失败）
            throw e
        }
    }

    private fun captureFrame(reader: ImageReader, path: String) {
        var img = reader.acquireLatestImage()
        var tries = 0
        while (img == null && tries < 60) { Thread.sleep(50); img = reader.acquireLatestImage(); tries++ }
        if (img == null) throw IllegalStateException("no frame")
        try {
            val pw = img.width; val ph = img.height
            val plane = img.planes[0]
            val bmp = Bitmap.createBitmap(pw, ph, Bitmap.Config.ARGB_8888)
            bmp.copyPixelsFromBuffer(plane.buffer)
            FileOutputStream(path).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally {
            img.close()
        }
    }

    private fun exec(command: String) {
        val p = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
        p.waitFor()
    }
}
