package top.fellow99.rpandroid

import android.app.Service
import android.content.Intent
import android.os.IBinder
import top.fellow99.rpandroid.core.controller.ScreenController
import top.fellow99.rpandroid.core.server.ManagedServer
import top.fellow99.rpandroid.core.shell.NoRootShell

/**
 * 主进程服务：
 * - 承载 REST 服务器（委托 ManagedRuntime，幂等）；
 * - 通过 [IScreenControl] 向 :node 进程（自动化服务执行器）暴露屏幕控制。
 */
class ManagedService : Service() {

    override fun onCreate() {
        super.onCreate()
        ManagedRuntime.start(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ManagedRuntime.start(this)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    private val binder = object : IScreenControl.Stub() {
        private fun ctl(): ScreenController? = ManagedRuntime.controller()

        override fun tap(x: Int, y: Int): Boolean = ctl()?.tap(x, y) ?: false
        override fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durMs: Int): Boolean =
            ctl()?.swipe(x1, y1, x2, y2, durMs) ?: false

        override fun launch(pkg: String, component: String?): Boolean =
            ctl()?.launch(pkg, component)?.ok ?: false

        override fun captureFrame(): ByteArray? = ctl()?.captureFrame()

        override fun listAppsJson(): String {
            val apps = ctl()?.listApps() ?: emptyList()
            val arr = org.json.JSONArray()
            apps.forEach { a ->
                arr.put(org.json.JSONObject().put("packageName", a.packageName).put("label", a.label))
            }
            return arr.toString()
        }

        override fun key(code: Int): Boolean = ctl()?.pressKey(code) ?: false

        override fun text(text: String?): Boolean = if (text == null) false else ctl()?.text(text) ?: false

        override fun info(): String {
            val c = ctl() as? top.fellow99.rpandroid.core.controller.AppScreenController
            val cfg = top.fellow99.rpandroid.core.server.ServerConfig(applicationContext)
            return org.json.JSONObject().apply {
                put("ok", c?.isSessionActive() == true)
                put("sessionActive", c?.isSessionActive() == true)
                put("w", cfg.vScreenWidth)
                put("h", cfg.vScreenHeight)
                put("dpi", cfg.vScreenDpi)
                put("hosted", c?.hosted()?.packageName ?: org.json.JSONObject.NULL)
                put("runningTaskId", top.fellow99.rpandroid.script.TaskRuntime.runningTaskId ?: org.json.JSONObject.NULL)
            }.toString()
        }

        override fun log(tag: String?, msg: String?) {
            top.fellow99.rpandroid.core.server.MfaLog.i(tag ?: "Script", msg ?: "")
        }
    }

    companion object {
        const val PORT = 8099
    }
}
