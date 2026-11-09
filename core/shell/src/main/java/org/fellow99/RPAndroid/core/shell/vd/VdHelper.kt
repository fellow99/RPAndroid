package org.fellow99.RPAndroid.core.shell.vd

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.os.Looper
import android.view.Surface
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

    /** seq 回退超过该阈值即判定为新会话/计数器回绕，重置 lastSeq 基线。 */
    private const val WRAP_THRESHOLD = 100_000

    /** 最后成功编码的一帧 PNG；静态画面无新帧时复用。 */
    @Volatile
    private var lastFrame: ByteArray? = null

    @JvmStatic
    fun main(args: Array<String>) {
        val ipcDir = File(args.getOrElse(0) { "/sdcard/Android/data/org.fellow99.RPAndroid/files/mfa" })
        val w = args.getOrElse(1) { "720" }.toInt()
        val h = args.getOrElse(2) { "1560" }.toInt()
        val dpi = args.getOrElse(3) { "400" }.toInt()
        // 007-webrtc：可选 `--nonce <n>` —— 经 App 导出的 ContentProvider 拉取其 FrameHub Surface 建虚拟屏，
        // 帧直接落在 App 进程。未提供/交接失败时回退旧路径：自持 ImageReader + 文件 IPC。
        val nonce = if (args.size > 4 && args[4] == "--nonce") args.getOrNull(5) else null

        ipcDir.mkdirs()
        val cmd = File(ipcDir, "cmd.txt")
        val result = File(ipcDir, "result.txt")
        val ready = File(ipcDir, "ready.txt")
        cmd.delete(); result.delete(); ready.delete()

        Looper.prepareMainLooper()

        val sdk = android.os.Build.VERSION.SDK_INT
        // 注意：不使用 FLAG_PUBLIC——公开虚拟屏会被系统当作投屏/演示屏，从而触发
        // 系统级 mForceHideNonSystemOverlayWindow，导致应用的悬浮窗(浮窗呈现)被隐藏。
        var flags = FLAG_OWN_CONTENT_ONLY or FLAG_SUPPORTS_TOUCH or
            FLAG_ROTATES_WITH_CONTENT or FLAG_DESTROY_CONTENT_ON_REMOVAL or FLAG_TOUCH_FEEDBACK_DISABLED
        if (sdk >= 33) {
            flags = flags or FLAG_TRUSTED or FLAG_OWN_DISPLAY_GROUP or FLAG_ALWAYS_UNLOCKED
            if (sdk >= 34) flags = flags or FLAG_OWN_FOCUS or FLAG_STEAL_TOP_FOCUS_DISABLED
        }

        val appSurface: Surface? = try {
            val s = if (nonce != null) takeSurface(nonce) else null
            if (s != null) File(ipcDir, "surface_status.txt").writeText("ok")
            s
        } catch (t: Throwable) {
            println("MFA_HELPER_SURFACE_FALLBACK ${t.message}")
            runCatching { File(ipcDir, "surface_status.txt").writeText("err: ${t.javaClass.simpleName}: ${t.message}") }
            null
        }
        val reader: ImageReader? = if (appSurface == null) ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2) else null
        val dm = newDisplayManager()
        val vdSurface = appSurface ?: reader!!.surface
        val vd: VirtualDisplay = try {
            dm.createVirtualDisplay("mfa-agent", w, h, dpi, vdSurface, flags)
        } catch (e: SecurityException) {
            val f2 = FLAG_OWN_CONTENT_ONLY or FLAG_SUPPORTS_TOUCH or
                FLAG_ROTATES_WITH_CONTENT or FLAG_DESTROY_CONTENT_ON_REMOVAL or FLAG_TOUCH_FEEDBACK_DISABLED
            dm.createVirtualDisplay("mfa-agent", w, h, dpi, vdSurface, f2)
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
                // 接受单调递增指令；也接受“新会话/回绕”：seq 明显回退（差值超阈值）时重置基线。
                // 避免旧的高 seq 残留把新会话的指令永久挡掉。
                val wrapped = seq < lastSeq && (lastSeq - seq) > WRAP_THRESHOLD
                if (wrapped) lastSeq = 0
                if (seq > lastSeq) {
                    val op = parts.getOrNull(1) ?: ""
                    val a = parts.drop(2)
                    val payload = try {
                        when (op) {
                            "launch" -> { exec("am start --display $displayId -n ${a[0]}"); "ok" }
                            "closeapp" -> { exec("am force-stop ${a[0]}"); "ok" }
                            "frame" -> {
                                // 交接模式（appSurface 非空）帧由 App 进程 FrameHub 自己取；旧路径仍由助手截帧写文件。
                                if (reader != null) { captureFrame(reader, a[0]); a[0] } else a[0]
                            }
                            "tap" -> { exec("input -d $displayId tap ${a[0]} ${a[1]}"); "ok" }
                            "swipe" -> { exec("input -d $displayId swipe ${a[0]} ${a[1]} ${a[2]} ${a[3]} ${a.getOrElse(4) { "300" }}"); "ok" }
                            "key" -> { exec("input -d $displayId keyevent ${a[0]}"); "ok" }
                            // 文本注入：参数为 UTF-8 文本的 Base64（避免文件 IPC 的 `|` 分隔符与换行冲突）。
                            // 仅适用于 ASCII（系统 `input text` 限制）；中文由主进程走剪贴板+粘贴。
                            "text" -> {
                                val raw = String(
                                    android.util.Base64.decode(a[0], android.util.Base64.NO_WRAP),
                                    Charsets.UTF_8,
                                )
                                val escaped = raw.replace("'", "'\\''")
                                exec("input -d $displayId text '$escaped'")
                                "ok"
                            }
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

    /**
     * 经 App 导出的 ContentProvider 拉取待交接 Surface：用 ActivityManager.getContentProviderExternal
     * 拿到 provider 的 binder，以 AttributionSource(shell) 身份 call——跨 UID Binder 事务天然携带 Parcelable Surface。
     * 普通 Context#contentResolver.call 会因“package 与 uid 不匹配”(shell 包名/uid 归属问题)被拒。
     */
    private fun takeSurface(nonce: String): Surface {
        val authority = org.fellow99.RPAndroid.core.shell.MfaHandoff.AUTHORITY
        val am = Class.forName("android.app.ActivityManager").getMethod("getService").invoke(null)
        val token = android.os.Binder()
        val holder = am.javaClass.getMethod(
            "getContentProviderExternal", String::class.java, Int::class.javaPrimitiveType,
            android.os.IBinder::class.java, String::class.java,
        ).invoke(am, authority, 0, token, "mfa-helper")
            ?: throw IllegalStateException("provider $authority not found")
        try {
            val provider = holder.javaClass.getField("provider").get(holder)
                ?: throw IllegalStateException("provider field null")
            val extras = android.os.Bundle().apply {
                putString(org.fellow99.RPAndroid.core.shell.MfaHandoff.KEY_NONCE, nonce)
            }
            val call = provider.javaClass.methods.first { m ->
                m.name == "call" && m.parameterTypes.size == 5 &&
                    m.parameterTypes[0] == android.content.AttributionSource::class.java
            }
            val caller = android.content.AttributionSource.Builder(android.os.Process.myUid())
                .setPackageName("com.android.shell").build()
            val result = call.invoke(provider, caller, authority, org.fellow99.RPAndroid.core.shell.MfaHandoff.METHOD_TAKE_SURFACE, null, extras) as android.os.Bundle?
            val surface: Surface? = result?.getParcelable(org.fellow99.RPAndroid.core.shell.MfaHandoff.KEY_SURFACE)
            return surface ?: throw IllegalStateException(
                "no surface (result=" + result?.getString(org.fellow99.RPAndroid.core.shell.MfaHandoff.KEY_RESULT) + ")"
            )
        } finally {
            runCatching {
                am.javaClass.getMethod(
                    "removeContentProviderExternalAsUser",
                    String::class.java, android.os.IBinder::class.java, Int::class.javaPrimitiveType,
                ).invoke(am, authority, token, 0)
            }
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
        // 先尝试拿最新帧；静态画面下 SurfaceFlinger 不重绘，acquireLatestImage 可能长时间返回 null。
        var img = reader.acquireLatestImage()
        var tries = 0
        while (img == null && tries < 10) { Thread.sleep(50); img = reader.acquireLatestImage(); tries++ }
        if (img != null) {
            try {
                lastFrame = renderPng(img)
            } finally {
                img.close()
            }
        }
        // 无新帧时复用最后一帧（屏幕未变化，这是正确的当前画面）。
        val frame = lastFrame ?: throw IllegalStateException("no frame")
        FileOutputStream(path).use { it.write(frame) }
    }

    /** 把一帧 Image 编码为 PNG 字节，正确处理 rowStride/pixelStride，避免横向撕裂。 */
    private fun renderPng(img: android.media.Image): ByteArray {
        val pw = img.width; val ph = img.height
        val plane = img.planes[0]
        val buf = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val bmp = Bitmap.createBitmap(pw, ph, Bitmap.Config.ARGB_8888)
        if (pixelStride == 4 && rowStride == pw * 4) {
            bmp.copyPixelsFromBuffer(buf)
        } else {
            val rowBytes = pw * 4
            val row = ByteArray(rowStride)
            val packed = ByteArray(rowBytes * ph)
            for (y in 0 until ph) {
                buf.position(y * rowStride)
                buf.get(row, 0, minOf(rowStride, buf.remaining()))
                System.arraycopy(row, 0, packed, y * rowBytes, rowBytes)
            }
            bmp.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(packed))
        }
        val out = java.io.ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        bmp.recycle()
        return out.toByteArray()
    }

    private fun exec(command: String) {
        val p = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
        p.waitFor()
    }
}
