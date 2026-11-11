package top.fellow99.rpandroid.core.controller

import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * [FrameHub] —— App 进程侧的共享帧源（FrameSource 实现）。
 *
 * 虚拟屏输出用的 [Surface] 由本 hub 的 ImageReader 提供，经跨进程（Surface 交接）交给 shell 助手
 * 建虚拟屏后，帧**直接落在 App 进程**（不再逐帧文件 IPC），供以下消费方共用：
 *   - MJPEG `GET /stream` 与 `GET /frame`（PNG，[latestPng]）
 *   - WebRTC H.264 编码（RGBA 像素，[copyRgba]）
 *   - 本地预览（Bitmap，[latestBitmap]）
 *
 * 静态画面（SurfaceFlinger 不重绘 → [android.media.ImageReader.acquireLatestImage] 返回 null）时
 * 复用最近一帧；PNG 在画面未变化时缓存复用，避免重复编码。
 */
class FrameHub(
    private val captureWidth: Int,
    private val captureHeight: Int,
) : FrameSource {

    private val reader = ImageReader.newInstance(captureWidth, captureHeight, PixelFormat.RGBA_8888, 2)
    private val thread = HandlerThread("mfa-frame").apply { start() }
    private val handler = Handler(thread.looper)

    private val lock = Any()

    /** 最近一帧 RGBA Bitmap（含 rowStride 处理后的收包结果）；null 表示尚未收到帧。 */
    private var latest: Bitmap? = null

    /** 最近一帧的 PNG 字节缓存；画面未变化时复用。 */
    private var pngCache: ByteArray? = null
    private var pngDirty = true

    @Volatile private var released = false

    init {
        reader.setOnImageAvailableListener({ _ -> handler.post(::processImage) }, handler)
    }

    /** 交给助手的帧接收 Surface（跨进程交接用）。 */
    val surface: Surface
        get() = reader.surface

    override val width: Int = captureWidth
    override val height: Int = captureHeight

    override val hasFrame: Boolean
        get() = synchronized(lock) { latest != null }

    /** 取最新帧 decode 为 PNG；静态画面复用缓存。无帧或已释放返回 null。 */
    override fun latestPng(): ByteArray? = synchronized(lock) {
        val bmp = latest ?: return synchronized(lock) { null }
        if (!pngDirty && pngCache != null) return synchronized(lock) { pngCache }
        val out = ByteArrayOutputStream()
        if (!bmp.compress(Bitmap.CompressFormat.PNG, 100, out)) return synchronized(lock) { null }
        pngCache = out.toByteArray()
        pngDirty = false
        pngCache
    }

    /** 复制最近帧 RGBA 像素到 [dst]（须为 width*height）。返回是否复制成功。 */
    override fun copyRgba(dst: IntArray): Boolean = synchronized(lock) {
        val bmp = latest ?: return synchronized(lock) { false }
        if (dst.size < bmp.width * bmp.height) return synchronized(lock) { false }
        bmp.copyPixelsToBuffer(java.nio.IntBuffer.wrap(dst))
        true
    }

    /** 最近帧 Bitmap 的只读引用调用方（供本地预览/编码）；无帧返回 null。调用方不应复用它。 */
    fun latestBitmap(block: (Bitmap) -> Unit): Boolean = synchronized(lock) {
        val bmp = latest ?: return synchronized(lock) { false }
        block(bmp)
        true
    }

    /** 释放：先取走帧再销毁读取器/线程。 */
    fun release() {
        if (released) return
        released = true
        handler.removeCallbacksAndMessages(null)
        handler.post {
            runCatching { reader.close() }
            synchronized(lock) {
                latest?.recycle()
                latest = null
                pngCache = null
            }
        }
        thread.quitSafely()
    }

    private fun processImage() {
        if (released) return
        val image = try {
            reader.acquireLatestImage()
        } catch (t: Throwable) {
            null
        } ?: return
        try {
            val w = image.width
            val h = image.height
            val plane = image.planes[0]
            val buf = plane.buffer
            val rowStride = plane.rowStride
            val pixelStride = plane.pixelStride
            synchronized(lock) {
                var bmp = latest
                if (bmp == null || bmp.width != w || bmp.height != h) {
                    bmp?.recycle()
                    bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    latest = bmp
                }
                if (pixelStride == 4 && rowStride == w * 4) {
                    bmp.copyPixelsFromBuffer(buf.rewind())
                } else {
                    // 收包：逐行去掉行填充，避免横向撕裂（与 VdHelper.renderPng 同理）。
                    val rowBytes = w * 4
                    val row = ByteArray(rowStride)
                    val packed = ByteBuffer.allocate(rowBytes * h)
                    for (y in 0 until h) {
                        buf.position(y * rowStride)
                        buf.get(row, 0, minOf(rowStride, buf.remaining()))
                        packed.put(row, 0, rowBytes)
                    }
                    packed.rewind()
                    bmp.copyPixelsFromBuffer(packed)
                }
                pngDirty = true
            }
        } finally {
            image.close()
        }
    }
}

/** 供 server/webrtc 取帧的统一接口（读路径，不违反 C-1 写单入口）。 */
interface FrameSource {
    val width: Int
    val height: Int
    val hasFrame: Boolean
    /** 最新一帧 PNG；静态画面复用缓存。 */
    fun latestPng(): ByteArray?
    /** 复制最新帧 RGBA 像素到 [dst]（width*height）。 */
    fun copyRgba(dst: IntArray): Boolean
}
