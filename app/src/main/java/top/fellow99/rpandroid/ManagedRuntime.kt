package top.fellow99.rpandroid

import android.content.Context
import top.fellow99.rpandroid.present.ManagedBridge
import top.fellow99.rpandroid.core.controller.AppScreenController
import top.fellow99.rpandroid.core.controller.VdSessionImpl
import top.fellow99.rpandroid.core.server.ManagedServer
import top.fellow99.rpandroid.core.server.ServerConfig
import top.fellow99.rpandroid.core.shell.NoRootShell

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
            controller.uiReader = { top.fellow99.rpandroid.a11y.MfaAccessibilityService.instance?.dumpUi() }
            // 010-display-mode：承载模式初始化与持久化（C-3：controller 不依赖 server，经回调持久化）。
            val cfg = ServerConfig(app)
            controller.displayMode = top.fellow99.rpandroid.core.controller.DisplayMode.fromWire(cfg.displayMode)
            controller.persistDisplayMode = { m -> cfg.displayMode = m.wire }
            controller.hasRunningTask = { top.fellow99.rpandroid.script.TaskRuntime.runningTaskId != null }
            controller.mainOps = top.fellow99.rpandroid.ModeBOps(app, shell)
            // 跨屏迁移协调器：Mode B 本应用→虚拟屏、承载应用→主屏；Mode A 反向（§8.2 本应用搬屏）。
            val migrator = top.fellow99.rpandroid.proj.DisplayMigrator(app, shell)
            // 离开 Mode B（切回虚拟屏承载）时释放 MediaProjection，避免后台仍捕获主屏。
            controller.releaseProjection = {
                top.fellow99.rpandroid.proj.MediaProjectionHost.releaseProjection(app, controller)
            }
            // 进入/离开 Mode B 的跨屏搬屏（在投影帧源挂载后执行）。
            controller.onEnterMainMode = { migrator.enterMain(controller) }
            controller.onEnterVirtualMode = { migrator.enterVirtual(controller) }
            // 承载状态同步：REST(/client) 的 /session/launch 经 controller.launch 成功后回调，
            // 把变化发布到 App UI（应用栏自动切换呈现 / 回到网格）。与 App UI 自身发起共用同一入口。
            controller.onHostChanged = {
                top.fellow99.rpandroid.ui.PresentationHub.setHosted(controller.hosted())
            }
            // 模式变化：同步 UI；若正处 Mode B 还更新主屏承载通知文案。
            controller.onDisplayModeChanged = { m ->
                top.fellow99.rpandroid.ui.PresentationHub.updateDisplayMode(m)
                top.fellow99.rpandroid.core.server.MfaLog.i("Runtime", "display mode -> ${m.wire}")
            }
            top.fellow99.rpandroid.ui.PresentationHub.updateDisplayMode(controller.displayMode)
            ManagedBridge.bind(controller)
            val s = ManagedServer(
                controller, cfg.port, shell,
                appContext = app,
                authToken = cfg.token,
                taskApi = top.fellow99.rpandroid.script.TaskApiImpl(app),
                statusProvider = {
                    mapOf(
                        "accessibilityEnabled" to top.fellow99.rpandroid.a11y.MfaAccessibilityService.isEnabled(),
                        "cachedConnectPort" to cfg.connectPort,
                        "runningTaskId" to top.fellow99.rpandroid.script.TaskRuntime.runningTaskId,
                        // 010-display-mode：当前承载模式（A 虚拟屏 / B 主屏）
                        "mode" to controller.displayMode.wire,
                        // Mode B 投影是否存活（MediaProjection 就绪且已挂帧源）
                        "projectionActive" to (controller.displayMode == top.fellow99.rpandroid.core.controller.DisplayMode.MAIN
                            && controller.projectionFrameSource != null),
                        // 生效帧分辨率：Mode B 用主屏(投影)分辨率，Mode A 用虚拟屏分辨率
                        "frameWidth" to if (controller.displayMode == top.fellow99.rpandroid.core.controller.DisplayMode.MAIN)
                            (controller.projectionFrameSource?.width ?: cfg.vScreenWidth)
                        else cfg.vScreenWidth,
                        "frameHeight" to if (controller.displayMode == top.fellow99.rpandroid.core.controller.DisplayMode.MAIN)
                            (controller.projectionFrameSource?.height ?: cfg.vScreenHeight)
                        else cfg.vScreenHeight,
                        // 供客户端(/client)在无会话时用缓存端口自动建立会话
                        "vScreenWidth" to cfg.vScreenWidth,
                        "vScreenHeight" to cfg.vScreenHeight,
                        "vScreenDpi" to cfg.vScreenDpi,
                        // 当前承载应用（关闭后为 null，客户端据此回到应用列表）
                        "hostedApp" to controller.hosted()?.let {
                            mapOf("packageName" to it.packageName, "label" to it.label)
                        },
                    )
                },
            )
            server = s
            s.start()
        }
        // 应用进程启动后重排定时任务（覆盖安装/进程被杀后 AlarmManager 闹钟会丢失）。
        runCatching {
            top.fellow99.rpandroid.script.TaskScheduler(context.applicationContext).rescheduleAll()
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
                // 重启/换 Wi-Fi 后系统会关掉无线调试；持有 WRITE_SECURE_SETTINGS 时先把它写回开。
                runCatching { WirelessDebugging.ensureEnabled(context.applicationContext) }
                runCatching { startSession(savedPort, w, h, d) }.onSuccess { ok ->
                    top.fellow99.rpandroid.core.server.MfaLog.i(
                        "Runtime", "auto-resume session port=$savedPort ${w}x$h dpi=$d ok=$ok")
                }
            }.apply { name = "mfa-resume"; isDaemon = true }.start()
        }
        // 设备横/竖屏监听：虚拟屏跟随设备旋转（仅会话激活时）。
        startOrientationWatch(context.applicationContext)
    }

    /** 监听设备显示方向，虚拟屏配置与物理方向不一致时触发旋转（重建会话）。 */
    private fun startOrientationWatch(ctx: Context) {
        orientationWatcher?.stop()
        orientationWatcher = OrientationWatcher(ctx) { landscape ->
            if (controller()?.isSessionActive() == true) {
                Thread { rotateScreen(landscape) }.apply { name = "mfa-rotate"; isDaemon = true }.start()
            }
        }.also { it.start() }
    }

    @Volatile
    private var orientationWatcher: OrientationWatcher? = null

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
        // 连前先确保无线调试开着（重启/换 Wi-Fi 后系统会关掉；持 WRITE_SECURE_SETTINGS 时直接写开）。
        runCatching { top.fellow99.rpandroid.WirelessDebugging.ensureEnabled(ctx) }
        // 新会话开始前重置承载状态（旧承载随旧会话失效；同步 App UI）
        c.clearHosted()
        // 释放旧会话持有的帧源/线程（新会话接管前）
        (c.vdSession as? top.fellow99.rpandroid.core.controller.VdSessionImpl)?.release()
        runCatching { sh.stopHelper() }
        // 清理可能残留的旧助手（adbd 会话进程组未必随 App 结束而回收）
        runCatching {
            kotlinx.coroutines.runBlocking {
                sh.connect(connectPort)
                sh.run("pkill -f 'core.shell.vd.VdHelper' 2>/dev/null; pkill -f HelperMain 2>/dev/null; true")
            }
        }
        var session = VdSessionImpl(
            context = ctx,
            shell = sh,
            connectPort = connectPort,
            width = width,
            height = height,
            dpi = dpi,
        )
        var started = runCatching { session.start() }.getOrElse {
            top.fellow99.rpandroid.core.server.MfaLog.e("Runtime", "session start failed", it)
            false
        }
        // 端口可能已变化（重启 / 重开无线调试后系统换端口）：显式端口失败时回退自动探测。
        if (!started && connectPort != null) {
            top.fellow99.rpandroid.core.server.MfaLog.i("Runtime", "explicit port $connectPort failed; retry auto-discovery")
            runCatching { sh.stopHelper() }
            session = VdSessionImpl(
                context = ctx,
                shell = sh,
                connectPort = null,
                width = width,
                height = height,
                dpi = dpi,
            )
            started = runCatching { session.start() }.getOrElse {
                top.fellow99.rpandroid.core.server.MfaLog.e("Runtime", "session start (auto) failed", it)
                false
            }
        }
        return started.also { ok ->
            if (ok) {
                top.fellow99.rpandroid.core.server.MfaLog.i(
                    "Runtime", "session ready port=${sh.lastConnectPort() ?: connectPort} ${width}x$height")
                synchronized(stateLock) { c.vdSession = session }
                // 显式端口或自动发现到的实际端口都要持久化，保证下次恢复会话/不卡在“请先配置端口”（102-quick-start I1）。
                // 优先用实际连上的端口（自动探测/回退时会更新），否则用显式传入的端口。
                val usedPort = sh.lastConnectPort() ?: connectPort
                usedPort?.let { p -> top.fellow99.rpandroid.core.server.ServerConfig(ctx).connectPort = p }
                // 自动打开无线调试：重启/换 Wi-Fi 后系统会关掉它，配对好的 shell 权限可在会话成功时补写
                // adb_wifi_enabled（尽力而为，失败只记日志不阻塞会话，见 102-quick-start）。
                Thread {
                    runCatching { kotlinx.coroutines.runBlocking { sh.run("settings put global adb_wifi_enabled 1") } }
                        .onSuccess { top.fellow99.rpandroid.core.server.MfaLog.i("Runtime", "auto wireless-debugging put result=$it") }
                        .onFailure { top.fellow99.rpandroid.core.server.MfaLog.e("Runtime", "auto wireless-debugging failure", it) }
                }.apply { name = "mfa-wifi-on"; isDaemon = true }.start()
                // 新会话与物理横竖屏方向对齐（设备已旋转时立即校正）
                orientationWatcher?.recheck()
            }
        }
    }

    /**
     * 关闭虚拟屏上当前承载的应用（保留会话/虚拟屏），返回应用列表状态。
     * 供 App UI「关闭」与 /client 关闭使用。
     */
    fun closeHostedApp() {
        controllerRef?.closeHostedApp()
    }

    @Volatile
    private var lastRotateAt = 0L

    /**
     * 旋转虚拟屏到目标横/竖方向：交换 vScreen 宽高、重建会话，并重新承载原应用（若有）。
     * 由 [OrientationWatcher] 在后台线程调用；含去抖。
     */
    fun rotateScreen(wantLandscape: Boolean): Boolean {
        val ctx = appContext ?: return false
        val c = controllerRef ?: return false
        val now = System.currentTimeMillis()
        if (now - lastRotateAt < 1_500) return true // 去抖（传感器/显示抖动）
        lastRotateAt = now
        val cfg = top.fellow99.rpandroid.core.server.ServerConfig(ctx)
        val w = cfg.vScreenWidth
        val h = cfg.vScreenHeight
        val d = cfg.vScreenDpi
        if (w <= 0 || h <= 0) return false
        val currentLandscape = w > h
        if (currentLandscape == wantLandscape) return true
        val hosted = c.hosted()
        top.fellow99.rpandroid.core.server.MfaLog.i("Runtime", "rotate ${w}x$h -> ${h}x$w landscape=$wantLandscape")
        cfg.vScreenWidth = h
        cfg.vScreenHeight = w
        val ok = startSession(cfg.connectPort, h, w, d)
        if (ok && hosted != null) {
            val r = c.launch(hosted.packageName, null)
            top.fellow99.rpandroid.core.server.MfaLog.i("Runtime", "rotate relaunch ${hosted.packageName} ok=${r.ok}")
        }
        return ok
    }
}
