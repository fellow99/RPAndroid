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
 *
 * **不阻塞主线程（007 修复）**：start()/startSession() 不做类级 @Synchronized——长阻塞的会话
 * 启动（adbd 连接 + Surface 交接 + 等 ready 最长 20s）改在后台线程执行；start() 用快速路径
 * 在不存在已启动状态的情况下直接返回，避免 MainActivity.onCreate 在锁上被卡 6s+（MIUI 判定
 * APP_SCOUT_HANG 并回收进程）。
 */
object ManagedRuntime {
    @Volatile private var started = false

    @Volatile private var appContext: Context? = null
    @Volatile private var controllerRef: AppScreenController? = null
    @Volatile private var shellRef: NoRootShell? = null
    @Volatile var server: ManagedServer? = null; private set

    private val stateLock = Any()

    fun start(context: Context) {
        // 快速路径：已在运行则立即返回，不做任何加锁等待
        if (started) return
        synchronized(stateLock) {
            if (started) return
            // Application.onCreate 在每个进程都会跑；:node 进程只跑自动化服务，不启动 REST 服务器。
            if (currentProcessName(context) != context.packageName) return
            started = true
            val app = context.applicationContext
            appContext = app
            val controller = AppScreenController(app)
            val shell = NoRootShell(app)
            controllerRef = controller
            shellRef = shell
            controller.uiReader = { com.managedfloating.app.a11y.MfaAccessibilityService.instance?.dumpUi() }
            // 承载状态同步：REST(/client) 的 /session/launch 经 controller.launch 成功后回调，
            // 把变化发布到 App UI（应用栏自动切换呈现 / 回到网格）。与 App UI 自身发起共用同一入口。
            controller.onHostChanged = {
                com.managedfloating.app.ui.PresentationHub.setHosted(controller.hosted())
            }
            ManagedBridge.bind(controller)
            val cfg = ServerConfig(app)
            val s = ManagedServer(
                controller, cfg.port, shell,
                appContext = app,
                authToken = cfg.token,
                taskApi = com.managedfloating.app.script.TaskApiImpl(app),
                statusProvider = {
                    mapOf(
                        "accessibilityEnabled" to com.managedfloating.app.a11y.MfaAccessibilityService.isEnabled(),
                        "cachedConnectPort" to cfg.connectPort,
                        "runningTaskId" to com.managedfloating.app.script.TaskRuntime.runningTaskId,
                        // 供客户端(/client)在无会话时用缓存端口自动建立会话
                        "vScreenWidth" to cfg.vScreenWidth,
                        "vScreenHeight" to cfg.vScreenHeight,
                        "vScreenDpi" to cfg.vScreenDpi,
                    )
                },
            )
            server = s
            s.start()
        }
        // 自动恢复上次会话（后台线程，不阻塞 onCreate）——使用设置中的虚拟屏分辨率
        val cfg = ServerConfig(context.applicationContext)
        val savedPort = cfg.connectPort
        if (savedPort in 1..65535) {
            val dm = context.applicationContext.resources.displayMetrics
            val w = cfg.vScreenWidth.takeIf { it > 0 } ?: dm.widthPixels
            val h = cfg.vScreenHeight.takeIf { it > 0 } ?: dm.heightPixels
            val d = cfg.vScreenDpi.takeIf { it > 0 } ?: dm.densityDpi
            Thread {
                runCatching { startSession(savedPort, w, h, d) }.onSuccess { ok ->
                    com.managedfloating.core.server.MfaLog.i(
                        "Runtime", "auto-resume session port=$savedPort ${w}x$h dpi=$d ok=$ok")
                }
            }.apply { name = "mfa-resume"; isDaemon = true }.start()
        }
    }

    private fun currentProcessName(ctx: Context): String? = runCatching {
        if (android.os.Build.VERSION.SDK_INT >= 28) android.app.Application.getProcessName()
        else Class.forName("android.app.ActivityThread").getMethod("currentProcessName").invoke(null) as String?
    }.getOrNull() ?: ctx.packageName

    fun controller(): AppScreenController? = controllerRef

    fun shell(): NoRootShell? = shellRef

    /**
     * 启动被托管会话：连接 adbd（[connectPort]，null 走自动发现）+ 拉起 shell 助手 + 建虚拟屏。
     * 成功后 controller 的 launch/frame/action 生效，呈现界面可显示。
     * 不在类级 @Synchronized 下执行长阻塞逻辑（避免把 start() 卡住）。
     */
    fun startSession(connectPort: Int?, width: Int = 720, height: Int = 1560, dpi: Int = 400): Boolean {
        val c = controllerRef ?: return false
        val sh = shellRef ?: return false
        val ctx = appContext ?: return false
        // 新会话开始前重置承载状态（旧承载随旧会话失效；同步 App UI）
        c.clearHosted()
        // 释放旧会话持有的帧源/线程（新会话接管前）
        (c.vdSession as? com.managedfloating.core.controller.VdSessionImpl)?.release()
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
        return runCatching { session.start() }.getOrElse {
            com.managedfloating.core.server.MfaLog.e("Runtime", "session start failed", it)
            false
        }.also { ok ->
            if (ok) {
                com.managedfloating.core.server.MfaLog.i(
                    "Runtime", "session ready port=$connectPort ${width}x$height")
                synchronized(stateLock) { c.vdSession = session }
                connectPort?.let { p -> com.managedfloating.core.server.ServerConfig(ctx).connectPort = p }
            }
        }
    }
}
