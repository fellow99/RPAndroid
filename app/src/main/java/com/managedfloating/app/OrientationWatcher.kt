package com.managedfloating.app

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.view.Display

/**
 * [OrientationWatcher] —— 设备横/竖屏方向监听。
 *
 * 监听本机 Display 配置变化（含方向/尺寸变化），读取当前**物理宽高比**；
 * 当与虚拟屏配置（ServerConfig vScreenW/H）不一致时（且会话激活），回调触发
 * [ManagedRuntime.rotateScreen]，让虚拟屏跟随设备横竖屏旋转。
 *
 * 用"物理宽高比"而非 rotation 枚举，避免不同平板自然方向（竖/横）差异导致的误判。
 * 注意：必须用 `DisplayManager.getDisplay`（不需要 visual Context），
 * application context 的 `Context#display` 会抛 UnsupportedOperationException。
 */
class OrientationWatcher(
    context: Context,
    private val onChanged: (landscape: Boolean) -> Unit,
) {

    private val dm = context.applicationContext.getSystemService(DisplayManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var started = false

    private val listener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = checkDisplay(displayId)
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) = checkDisplay(displayId)
    }

    fun start() {
        if (started) return
        started = true
        dm.registerDisplayListener(listener, mainHandler)
        mainHandler.post {
            dm.getDisplay(Display.DEFAULT_DISPLAY)?.let { d -> checkDisplay(d.displayId) }
        }
    }

    fun stop() {
        if (!started) return
        started = false
        dm.unregisterDisplayListener(listener)
    }

    /** 会话建立/重建等时机主动复核一次方向，把虚拟屏与物理方向对齐。 */
    fun recheck() {
        mainHandler.post { dm.getDisplay(Display.DEFAULT_DISPLAY)?.let { d -> checkDisplay(d.displayId) } }
    }

    private fun checkDisplay(displayId: Int) {
        val display = dm.getDisplay(displayId) ?: return
        onChanged(display.width > display.height)
    }
}
