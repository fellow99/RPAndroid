package com.managedfloating.app.present

import com.managedfloating.core.controller.ScreenController

/**
 * [ManagedBridge] —— 主进程内共享当前会话的 [ScreenController]（供呈现界面取用）。
 * 由 [ManagedRuntime] 在启动会话后填入。
 */
object ManagedBridge {
    @Volatile
    private var controllerRef: ScreenController? = null

    fun controller(): ScreenController? = controllerRef

    fun bind(c: ScreenController?) {
        controllerRef = c
    }
}
