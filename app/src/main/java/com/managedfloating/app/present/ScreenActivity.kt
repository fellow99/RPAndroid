package com.managedfloating.app.present

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Bundle
import android.view.MotionEvent
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.app.Activity

/**
 * [ScreenActivity] —— **常规模式**：全屏显示被托管 App 画面，触摸回写到虚拟屏。
 *
 * 画面按虚拟屏等比缩放居中；触摸经 [TouchMapper] 换算后经 [ScreenController] 注入。
 * 需要 [ManagedRuntime] 已启动会话（controller 有 VdSession）。
 */
@SuppressLint("ViewConstructor")
class ScreenActivity : Activity() {

    private var imageView: ImageView? = null
    private var poller: FramePoller? = null
    private var latest: Bitmap? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val controller = ManagedBridge.controller()
        if (controller == null) {
            setContentView(TextView(this).apply { text = "会话未就绪：请先在主界面启动会话" })
            return
        }
        val root = FrameLayout(this)
        val iv = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        root.addView(iv, FrameLayout.LayoutParams(-1, -1))
        imageView = iv
        setContentView(root)

        val p = FramePoller(controller)
        poller = p
        p.start { bmp ->
            latest?.recycle()
            latest = bmp
            iv.setImageBitmap(bmp)
        }

        root.setOnTouchListener { v, e -> handleTouch(v.width, v.height, e) }
    }

    private fun handleTouch(w: Int, h: Int, e: MotionEvent): Boolean {
        val controller = ManagedBridge.controller() ?: return false
        val p = poller ?: return false
        if (e.action == MotionEvent.ACTION_DOWN) {
            TouchMapper.map(e.x, e.y, w, h, p.virtualWidth, p.virtualHeight)?.let { (x, y) ->
                Thread { runCatching { controller.tap(x, y) } }.start()
            }
        }
        return true
    }

    override fun onDestroy() {
        poller?.stop()
        latest?.recycle()
        latest = null
        super.onDestroy()
    }
}
