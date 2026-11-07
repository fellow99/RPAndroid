package com.managedfloating.app

import android.content.Context
import com.managedfloating.core.controller.AppScreenController
import com.managedfloating.core.server.ManagedServer
import com.managedfloating.core.shell.NoRootShell

/**
 * 幂等的服务器宿主：进程内只启动一次 REST 服务器。
 * Application.onCreate 直接调用（走独立线程，规避 Android 13+ 后台 startService 限制）。
 */
object ManagedRuntime {
    @Volatile
    private var started = false

    @Volatile
    var server: ManagedServer? = null; private set

    @Synchronized
    fun start(context: Context) {
        if (started) return
        started = true
        val controller = AppScreenController(context.applicationContext)
        val shell = NoRootShell(context.applicationContext)
        val s = ManagedServer(controller, ManagedService.PORT, shell)
        server = s
        // ManagedServer 内部已用独立线程 start(wait=true)。
        s.start()
    }
}
