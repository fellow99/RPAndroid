package com.managedfloating.app

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.managedfloating.core.controller.AppScreenController
import com.managedfloating.core.server.ManagedServer
import com.managedfloating.core.shell.NoRootShell

/**
 * 后台服务：承载 [ManagedServer]（REST）并与 [AppScreenController] 接线。
 * M1：普通 startService 即可（应用在前台时运行）；后续里程碑可升为前台服务承载任务调度。
 */
class ManagedService : Service() {

    private lateinit var controller: AppScreenController
    private var server: ManagedServer? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        controller = AppScreenController(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (server == null) {
            server = ManagedServer(controller, PORT, shell = NoRootShell(applicationContext))
        }
        server?.start()
        return START_STICKY
    }

    override fun onDestroy() {
        server?.stop()
        server = null
        super.onDestroy()
    }

    companion object {
        const val PORT = 8099
    }
}
