package com.managedfloating.core.webrtc

import com.managedfloating.core.controller.FrameSource
import org.webrtc.JavaI420Buffer
import org.webrtc.VideoFrame
import java.util.concurrent.atomic.AtomicBoolean

/**
 * [FramePusher] —— 把 App 进程共享帧源（[FrameSource]）的最新帧以目标帧率喂给 WebRTC 视频源。
 *
 * - 有新帧：按 [TARGET_FPS]（15）推送 RGBA→I420。
 * - 静态画面（表面无新帧）：以低频（约 1FPS）复用最近 RGBA 重新编码推送，保证对端画面不冻结。
 * - 无会话/无帧：空闲等待，不空转 CPU。
 */
class FramePusher(
    private val frameSource: () -> FrameSource?,
    private val sink: (VideoFrame) -> Unit = {},
) {
    private val running = AtomicBoolean(false)
    private var thread: Thread? = null

    // 静态保活：保留最近一把 RGBA 像素与尺寸
    @Volatile private var lastRgba: IntArray = IntArray(0)
    @Volatile private var lastW = 0
    @Volatile private var lastH = 0
    private var lastPushNs = 0L

    fun start() {
        if (!running.compareAndSet(false, true)) return
        thread = Thread {
            var rgba = IntArray(0)
            while (running.get()) {
                val began = System.currentTimeMillis()
                val fs = frameSource()
                var pushedNew = false
                if (fs != null && fs.hasFrame) {
                    val need = fs.width * fs.height
                    if (rgba.size != need) rgba = IntArray(need)
                    if (fs.copyRgba(rgba)) {
                        lastRgba = rgba
                        lastW = fs.width
                        lastH = fs.height
                        sink(toI420(rgba, fs.width, fs.height))
                        lastPushNs = System.nanoTime()
                        pushedNew = true
                    }
                }

                // 静态：低频复用最近 RGBA
                if (!pushedNew && lastH > 0 && System.nanoTime() - lastPushNs > 800_000_000L) {
                    sink(toI420(lastRgba, lastW, lastH))
                    lastPushNs = System.nanoTime()
                }

                val target = if (pushedNew) 1000 / TARGET_FPS else 1000
                val elapsed = System.currentTimeMillis() - began
                try { Thread.sleep((target - elapsed).coerceAtLeast(1)) } catch (e: InterruptedException) { break }
            }
        }.apply { name = "mfa-webrtc-pusher"; isDaemon = true }
        thread?.start()
    }

    fun stop() {
        running.set(false)
        thread?.interrupt()
        thread = null
    }

    /** RGBA（IntArray, 0xAARRGGBB）→ I420 VideoFrame（BT.601）。 */
    private fun toI420(rgba: IntArray, w: Int, h: Int): VideoFrame {
        val buffer = JavaI420Buffer.allocate(w, h)
        try {
            val y = buffer.getDataY()
            val u = buffer.getDataU()
            val v = buffer.getDataV()
            val yStride = buffer.getStrideY()
            val uStride = buffer.getStrideU()
            val vStride = buffer.getStrideV()

            val yArr = ByteArray(w * h)
            val uvW = w / 2
            val uvH = h / 2
            val uArr = ByteArray(uvW * uvH)
            val vArr = ByteArray(uvW * uvH)

            var ui = 0
            var yi = 0
            for (yy in 0 until h) {
                var base = yy * w
                if (yy and 1 == 0) {
                    val uRow = ui
                    for (xx in 0 until w) {
                        val argb = rgba[base + xx]
                        val r = (argb shr 16) and 0xff
                        val g = (argb shr 8) and 0xff
                        val b = argb and 0xff
                        yArr[yi++] = ((66 * r + 129 * g + 25 * b + 128) shr 8).let { (it + 16) and 0xff }.toByte()
                        if (xx and 1 == 0) {
                            uArr[uRow + xx / 2] = ((((-38 * r - 74 * g + 112 * b) shr 8) + 128) and 0xff).toByte()
                            vArr[uRow + xx / 2] = ((((112 * r - 94 * g - 18 * b) shr 8) + 128) and 0xff).toByte()
                        }
                    }
                    ui += uvW
                } else {
                    // 奇数行：只算 Y，U/V 已在偶数行取 2x2 块
                    for (xx in 0 until w) {
                        val argb = rgba[base + xx]
                        val r = (argb shr 16) and 0xff
                        val g = (argb shr 8) and 0xff
                        val b = argb and 0xff
                        yArr[yi++] = ((66 * r + 129 * g + 25 * b + 128) shr 8).let { (it + 16) and 0xff }.toByte()
                    }
                }
            }

            for (yy in 0 until h) {
                y.position(yy * yStride)
                y.put(yArr, yy * w, w)
            }
            for (yy in 0 until uvH) {
                u.position(yy * uStride)
                u.put(uArr, yy * uvW, uvW)
                v.position(yy * vStride)
                v.put(vArr, yy * uvW, uvW)
            }
            return VideoFrame(buffer, 0, System.nanoTime())
        } catch (t: Throwable) {
            buffer.release()
            throw t
        }
    }

    companion object {
        const val TARGET_FPS = 15
    }
}
