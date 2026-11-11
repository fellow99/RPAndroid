package top.fellow99.rpandroid

import android.app.Application

/**
 * M1：进程创建即启动 [ManagedRuntime]（REST 服务器），保证服务器随进程可靠起来，
 * 不依赖 Activity onCreate / 后台 startService（规避 Android 13+ 限制）。
 */
class ManagedApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ManagedRuntime.start(this)
    }
}
