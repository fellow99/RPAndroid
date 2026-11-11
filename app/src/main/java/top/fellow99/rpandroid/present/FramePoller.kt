package top.fellow99.rpandroid.present

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import top.fellow99.rpandroid.core.controller.ScreenController
import java.util.concurrent.atomic.AtomicBoolean

/**
 * [FramePoller] —— 后台定时从被托管屏拉帧（PNG → Bitmap），在主线程回调。
 * 呈现模式（常规/浮窗）共用。headless 不启动它。
 */
class FramePoller(
    private val controller: ScreenController,
    private val intervalMs: Long = 400L,
) {
    private val main = Handler(Looper.getMainLooper())
    private val running = AtomicBoolean(false)
    private var thread: Thread? = null

    var virtualWidth = 720
        private set
    var virtualHeight = 1560
        private set

    fun start(onFrame: (Bitmap) -> Unit) {
        if (!running.compareAndSet(false, true)) return
        thread = Thread {
            while (running.get()) {
                val started = System.currentTimeMillis()
                val bytes = runCatching { controller.captureFrame() }.getOrNull()
                if (bytes != null) {
                    val bmp = runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull()
                    if (bmp != null) {
                        virtualWidth = bmp.width
                        virtualHeight = bmp.height
                        main.post { if (running.get()) onFrame(bmp) }
                    }
                }
                val elapsed = System.currentTimeMillis() - started
                val sleep = (intervalMs - elapsed).coerceAtLeast(30)
                try { Thread.sleep(sleep) } catch (e: InterruptedException) { break }
            }
        }.apply { name = "mfa-frame-poller"; isDaemon = true }
        thread?.start()
    }

    fun stop() {
        running.set(false)
        thread?.interrupt()
        thread = null
    }
}

/**
 * 把显示视图上的触摸坐标换算成虚拟屏坐标。
 * 视图按 [virtualWidth]×[virtualHeight] 等比缩放居中显示（letterbox）。
 */
object TouchMapper {
    fun map(
        touchX: Float,
        touchY: Float,
        viewW: Int,
        viewH: Int,
        virtualWidth: Int,
        virtualHeight: Int,
    ): Pair<Int, Int>? {
        if (viewW <= 0 || viewH <= 0 || virtualWidth <= 0 || virtualHeight <= 0) return null
        val scale = minOf(viewW.toFloat() / virtualWidth, viewH.toFloat() / virtualHeight)
        val dispW = virtualWidth * scale
        val dispH = virtualHeight * scale
        val offX = (viewW - dispW) / 2f
        val offY = (viewH - dispH) / 2f
        val vx = (touchX - offX) / scale
        val vy = (touchY - offY) / scale
        if (vx < 0 || vy < 0 || vx > virtualWidth || vy > virtualHeight) return null
        return vx.toInt() to vy.toInt()
    }
}
