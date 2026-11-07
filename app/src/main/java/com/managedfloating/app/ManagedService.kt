package com.managedfloating.app

import android.app.Service
import android.content.Intent
import android.os.IBinder

/**
 * 服务占位：委托 [ManagedRuntime]（幂等）承载 REST 服务器。
 * 后续里程碑可升为前台服务承载任务调度（立即/间隔/cron）。
 */
class ManagedService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ManagedRuntime.start(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ManagedRuntime.start(this)
        return START_STICKY
    }

    companion object {
        const val PORT = 8099
    }
}
