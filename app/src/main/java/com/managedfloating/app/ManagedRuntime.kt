package com.managedfloating.app

import android.content.Context
import com.managedfloating.app.present.ManagedBridge
import com.managedfloating.core.controller.AppScreenController
import com.managedfloating.core.controller.VdSessionImpl
import com.managedfloating.core.server.ManagedServer
import com.managedfloating.core.server.ServerConfig
import com.managedfloating.core.shell.NoRootShell

/**
 * 幂等的服务器 + 会话宿主。
 * - Application.onCreate 启动 REST 服务器（独立线程，规避 Android 13+ 后台 startService 限制）。
 * - 提供 [startSession] 拉起免 root 会话（连接 adbd + 助手），并把 controller 暴露给呈现界面。
 */
object ManagedRuntime {
    @Volatile private var started = false

    @Volatile private var appContext: Context? = null
    @Volatile private var controllerRef: AppScreenController? = null
    @Volatile private var shellRef: NoRootShell? = null
    @Volatile var server: ManagedServer? = null; private set

    @Synchronized
    fun start(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        appContext = app
        val controller = AppScreenController(app)
        val shell = NoRootShell(app)
        controllerRef = controller
        shellRef = shell
        ManagedBridge.bind(controller)
        val cfg = ServerConfig(app)
        val s = ManagedServer(controller, cfg.port, shell, appContext = app, authToken = cfg.token)
        server = s
        s.start()
        // 自动恢复上次会话（App 被系统回收重启后仍可用）
        val savedPort = cfg.connectPort
        if (savedPort in 1..65535) {
            Thread {
                runCatching { startSession(savedPort) }.onSuccess { ok ->
                    android.util.Log.i("ManagedRuntime", "auto-resume session port=$savedPort ok=$ok")
                }
            }.apply { name = "mfa-resume"; isDaemon = true }.start()
        }
    }

    fun controller(): AppScreenController? = controllerRef

    fun shell(): NoRootShell? = shellRef

    /**
     * 启动被托管会话：连接 adbd（[connectPort]，null 走自动发现）+ 拉起 shell 助手 + 建虚拟屏。
     * 成功后 controller 的 launch/frame/action 生效，呈现界面可显示。
     */
    @Synchronized
    fun startSession(connectPort: Int?, width: Int = 720, height: Int = 1560, dpi: Int = 400): Boolean {
        val c = controllerRef ?: return false
        val sh = shellRef ?: return false
        val ctx = appContext ?: return false
        runCatching { sh.stopHelper() }
        // 清理可能残留的旧助手（adbd 会话进程组未必随 App 结束而回收）
        runCatching {
            kotlinx.coroutines.runBlocking {
                sh.connect(connectPort)
                sh.run("pkill -f 'core.shell.vd.VdHelper' 2>/dev/null; pkill -f HelperMain 2>/dev/null; true")
            }
        }
        val session = VdSessionImpl(
            context = ctx,
            shell = sh,
            connectPort = connectPort,
            width = width,
            height = height,
            dpi = dpi,
        )
        return runCatching { session.start() }.getOrElse { false }.also { ok ->
            if (ok) {
                c.vdSession = session
                connectPort?.let { p -> com.managedfloating.core.server.ServerConfig(ctx).connectPort = p }
            }
        }
    }
}
