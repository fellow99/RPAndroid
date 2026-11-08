package com.managedfloating.app.present

import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView

/**
 * [FloatingService] —— **浮窗模式**：用 SYSTEM_ALERT_WINDOW 浮层显示被托管 App 画面。
 *
 * 支持拖动、点按回写（区分拖动/点击）、右上角关闭。需用户在系统设置授予"显示在其他应用上层"。
 */
class FloatingService : Service() {

    private var wm: WindowManager? = null
    private var root: FrameLayout? = null
    private var imageView: ImageView? = null
    private var poller: FramePoller? = null
    private var latest: Bitmap? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isActive = true
        val controller = ManagedBridge.controller()
        if (controller == null) { stopSelf(); return }
        if (!Settings.canDrawOverlays(this)) { stopSelf(); return }

        wm = getSystemService(WindowManager::class.java)
        val container = FrameLayout(this).apply { setBackgroundColor(Color.parseColor("#CC000000")) }
        val iv = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        container.addView(iv, FrameLayout.LayoutParams(-1, -1))
        val close = TextView(this).apply {
            text = "✕"
            setTextColor(Color.WHITE)
            textSize = 16f
            setPadding(16, 4, 16, 8)
            setOnClickListener { stopSelf() }
        }
        container.addView(close, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END))
        root = container
        imageView = iv

        val params = WindowManager.LayoutParams(
            (resources.displayMetrics.widthPixels * 0.6).toInt(),
            (resources.displayMetrics.heightPixels * 0.55).toInt(),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 40; y = 240
        }
        wm?.addView(container, params)

        // 拖动 + 点击
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var dragging = false
        container.setOnTouchListener { v, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = params.x; startY = params.y; dragging = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (!dragging && (Math.abs(dx) > 12 || Math.abs(dy) > 12)) dragging = true
                    if (dragging) {
                        params.x = startX + dx.toInt(); params.y = startY + dy.toInt()
                        wm?.updateViewLayout(v, params)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragging) {
                        val p = poller
                        if (p != null) {
                            TouchMapper.map(e.x, e.y, v.width, v.height, p.virtualWidth, p.virtualHeight)?.let { (x, y) ->
                                Thread { runCatching { controller.tap(x, y) } }.start()
                            }
                        }
                    }
                }
            }
            true
        }

        val p = FramePoller(controller)
        poller = p
        p.start { bmp ->
            latest?.recycle()
            latest = bmp
            iv.setImageBitmap(bmp)
        }
    }

    override fun onDestroy() {
        isActive = false
        poller?.stop()
        root?.let { runCatching { wm?.removeView(it) } }
        latest?.recycle(); latest = null
        root = null
        super.onDestroy()
    }

    companion object {
        /** 浮窗当前是否活动（主界面用于返回键转发判定）。 */
        @Volatile
        var isActive: Boolean = false
            private set

        fun start(ctx: android.content.Context) {
            ctx.startService(Intent(ctx, FloatingService::class.java))
        }
        fun stop(ctx: android.content.Context) {
            ctx.stopService(Intent(ctx, FloatingService::class.java))
        }
    }
}
