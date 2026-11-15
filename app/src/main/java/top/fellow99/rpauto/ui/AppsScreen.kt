package top.fellow99.rpauto.ui

import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import top.fellow99.rpauto.ManagedRuntime
import top.fellow99.rpauto.present.FloatingService
import top.fellow99.rpauto.present.ManagedBridge
import top.fellow99.rpauto.present.ScreenActivity
import top.fellow99.rpauto.core.controller.AppInfo
import top.fellow99.rpauto.core.controller.ScreenController
import top.fellow99.rpauto.core.server.ServerConfig
import kotlinx.coroutines.delay

/**
 * 应用栏目四态状态机（spec FR-101-2）：
 *  S1 未配置端口 → 提示去设置；
 *  S2 未建立会话 → 居中「连接并建立会话」；
 *  S3 未承载应用 → 应用图标网格；
 *  S4 已承载应用 → 呈现区域（嵌入/全屏/浮窗 + 关闭）。
 */
@Composable
fun AppsScreen(onOpenSettings: () -> Unit) {
    val context = LocalContext.current
    val cfg = remember { ServerConfig(context.applicationContext) }
    var sessionActive by remember { mutableStateOf(ManagedBridge.controller()?.isSessionActive() == true) }
    var connecting by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    // paired / connectPort 是普通 SharedPreferences 读取，不是 Compose 状态。
    // 向导里配对会写入它们；不提为状态并在轮询中同步，"开始使用"后不会重组（看似无反应）。
    var paired by remember { mutableStateOf(cfg.paired) }
    var connectPort by remember { mutableIntStateOf(cfg.connectPort) }

    // 会话轮询：自动恢复/失效后界面回到正确分支；浮窗被手动关闭后回落嵌入模式。
    LaunchedEffect(Unit) {
        while (true) {
            sessionActive = ManagedBridge.controller()?.isSessionActive() == true
            paired = cfg.paired
            connectPort = cfg.connectPort
            if (PresentationHub.presentationMode == PresentationMode.FLOATING && !FloatingService.isActive) {
                PresentationHub.setMode(PresentationMode.EMBEDDED)
            }
            delay(1200)
        }
    }

    fun connect() {
        connecting = true
        message = "正在连接 adbd 并拉起助手…"
        // 分辨率缺省时以当前屏幕为准（防止设置页尚未初始化导致 0×0 建屏失败）
        val dm = context.resources.displayMetrics
        val w = cfg.vScreenWidth.takeIf { it > 0 } ?: dm.widthPixels
        val h = cfg.vScreenHeight.takeIf { it > 0 } ?: dm.heightPixels
        val d = cfg.vScreenDpi.takeIf { it > 0 } ?: dm.densityDpi
        Thread {
            // 端口为 0 时自动发现（AdbConnectPort 依次试缓存/mDNS/属性/扫描），无需手动填。
            val port = cfg.connectPort.takeIf { it > 0 }
            val ok = ManagedRuntime.startSession(port, w, h, d)
            Handler(Looper.getMainLooper()).post {
                connecting = false
                sessionActive = ManagedBridge.controller()?.isSessionActive() == true
                message = if (ok && sessionActive) null else connectFailureHint(context)
            }
        }.apply { name = "mfa-connect"; isDaemon = true }.start()
    }

    fun launchApp(app: AppInfo) {
        message = "正在承载 ${app.label}…"
        Thread {
            val ctl = ManagedBridge.controller()
            val r = ctl?.let { runCatching { it.launch(app.packageName, null) }.getOrNull() }
            Handler(Looper.getMainLooper()).post {
                if (r?.ok == true) {
                    PresentationHub.hostApp(app)
                    message = null
                } else {
                    message = "承载失败：" + (r?.message ?: "会话未就绪")
                }
            }
        }.apply { name = "mfa-launch"; isDaemon = true }.start()
    }

    val hosted = PresentationHub.hostedApp
    val needsWizard = !paired && connectPort == 0

    Box(Modifier.fillMaxSize()) {
        when {
            needsWizard -> PairingWizard(onDone = {
                // 向导完成：立刻同步状态（配对/端口已写入），避免"开始使用"后无重组。
                paired = cfg.paired
                connectPort = cfg.connectPort
                sessionActive = ManagedBridge.controller()?.isSessionActive() == true
            })
            // 已配对：端口未记录或未建会话 → 断开自动发现（S2），不再要求手动配端口。
            !sessionActive -> ConnectContent(
                connecting = connecting,
                message = message,
                onConnect = ::connect,
                savedPort = connectPort,
                onConnectWithPort = { p ->
                    if (p != null) {
                        cfg.connectPort = p
                        connectPort = p
                    }
                    connect()
                },
            )
            hosted == null -> AppGrid(onSelect = { launchApp(it) })
            else -> HostedContent(hosted)
        }
        if (message != null) {
            Banner(text = message!!, onDismiss = { message = null }, modifier = Modifier.align(Alignment.TopCenter))
        }
    }
}

/** S3：可启动应用图标网格（支持长按置顶，置顶排前 + 📌角标）。 */
@Composable
private fun AppGrid(onSelect: (AppInfo) -> Unit) {
    val context = LocalContext.current
    val pinStore = remember { PinnedAppsStore(context.applicationContext) }
    var apps by remember { mutableStateOf<List<AppInfo>?>(null) }
    var pinned by remember { mutableStateOf(pinStore.getAll()) }

    LaunchedEffect(Unit) {
        apps = listApps(ManagedBridge.controller())
    }

    // 置顶排前（按置顶顺序），其余保持原序。sortedWith 为稳定排序。
    val sorted = apps?.let { list ->
        list.sortedWith(compareBy { app ->
            pinned.indexOf(app.packageName).let { i -> if (i < 0) Int.MAX_VALUE else i }
        })
    }

    fun refresh() {
        apps = listApps(ManagedBridge.controller())
        pinned = pinStore.getAll()
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("选择要承载的应用", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = { refresh() }) {
                Text("刷新")
            }
        }
        val list = sorted
        if (list == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("正在加载应用列表…", style = MaterialTheme.typography.bodyMedium)
            }
        } else if (list.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("未发现可启动的应用", style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 96.dp),
                contentPadding = PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(list, key = { it.packageName }) { app ->
                    val isPinned = app.packageName in pinned
                    AppCell(
                        app = app,
                        pinned = isPinned,
                        onClick = { onSelect(app) },
                        onLongClick = {
                            if (isPinned) {
                                pinned = pinStore.remove(app.packageName)
                                Toast.makeText(context, "${app.label} 已取消置顶", Toast.LENGTH_SHORT).show()
                            } else {
                                pinned = pinStore.add(app.packageName)
                                Toast.makeText(context, "${app.label} 已置顶", Toast.LENGTH_SHORT).show()
                            }
                            refresh()
                        },
                    )
                }
            }
        }
    }
}

private fun listApps(controller: ScreenController?): List<AppInfo> =
    runCatching { controller?.listApps() ?: emptyList() }.getOrDefault(emptyList())

@Composable
private fun AppCell(app: AppInfo, pinned: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    Card(
        modifier = Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box {
                AppIconAndLabel(pkg = app.packageName, label = app.label, iconSize = 48.dp)
                if (pinned) {
                    // 置顶角标（右上角小图钉）
                    Text(
                        "📌",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.align(Alignment.TopEnd),
                    )
                }
            }
            Text(
                app.label,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            )
        }
    }
}

/** S4：已承载应用的呈现区域（右上角：嵌入/全屏/浮窗/关闭）。 */
@Composable
private fun HostedContent(app: AppInfo) {
    val context = LocalContext.current
    val controller = ManagedBridge.controller()

    // 010-display-mode §8.1：呈现区右上角「主屏」按钮，经系统授权弹窗切 Mode B（无法静默）。
    val mainModeLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val ctl = controller as? top.fellow99.rpauto.core.controller.AppScreenController
        if (ctl == null) {
            Toast.makeText(context, "会话不可用，无法进入主屏承载", Toast.LENGTH_LONG).show()
        } else {
            val ok = top.fellow99.rpauto.proj.MediaProjectionHost.onGranted(context, ctl, result.resultCode, result.data)
            if (!ok) Toast.makeText(context, "未获授权，保持虚拟屏承载", Toast.LENGTH_LONG).show()
        }
    }

    fun toggleHostMode() {
        val ctl = controller as? top.fellow99.rpauto.core.controller.AppScreenController ?: return
        if (ctl.displayMode == top.fellow99.rpauto.core.controller.DisplayMode.MAIN) {
            // B → A：经状态机释放投影并回虚拟屏承载（releaseProjection 钩子停止 MediaProjection）。
            val denied = ctl.switchDisplayMode(top.fellow99.rpauto.core.controller.DisplayMode.VIRTUAL)
            if (denied != null) Toast.makeText(context, denied, Toast.LENGTH_LONG).show()
        } else {
            // A → B：需先经系统授权弹窗（无法静默）。
            if (ctl.hasRunningTask?.invoke() == true) {
                Toast.makeText(context, "自动化任务正在运行，禁止切换", Toast.LENGTH_LONG).show()
            } else {
                val intent = top.fellow99.rpauto.proj.MediaProjectionHost.authorizationIntent(context)
                if (intent != null) mainModeLauncher.launch(intent)
                else Toast.makeText(context, "无法发起主屏授权", Toast.LENGTH_LONG).show()
            }
        }
    }

    fun setMode(mode: PresentationMode) {
        when (mode) {
            PresentationMode.EMBEDDED -> {
                FloatingService.stop(context)
                PresentationHub.setMode(mode)
            }
            PresentationMode.FULLSCREEN -> {
                PresentationHub.setMode(mode)
                context.startActivity(Intent(context, ScreenActivity::class.java))
            }
            PresentationMode.FLOATING -> {
                if (!Settings.canDrawOverlays(context)) {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            android.net.Uri.parse("package:${context.packageName}"),
                        ),
                    )
                    Toast.makeText(context, "请授予“显示在其他应用上层”，再点浮窗模式", Toast.LENGTH_LONG).show()
                } else {
                    PresentationHub.setMode(mode)
                    FloatingService.start(context)
                }
            }
        }
    }

    fun close() {
        FloatingService.stop(context)
        // 关闭虚拟屏中的应用（保留会话/虚拟屏），回到应用列表；
        // 异步清理确认（closeHostedApp 内部 force-stop + 清承载）
        Thread {
            top.fellow99.rpauto.ManagedRuntime.closeHostedApp()
        }.apply { name = "mfa-close-app"; isDaemon = true }.start()
    }

    Column(Modifier.fillMaxSize()) {
        // 顶栏：当前 App + 呈现模式切换（嵌入/全屏/浮窗） + 承载模式「主屏」按钮（独立样式，§8.1） + 关闭。
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppIconAndLabel(pkg = app.packageName, label = app.label, iconSize = 32.dp)
            Text(
                app.label,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f).padding(start = 8.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            ModeChip("嵌入", PresentationHub.presentationMode == PresentationMode.EMBEDDED) { setMode(PresentationMode.EMBEDDED) }
            ModeChip("全屏", PresentationHub.presentationMode == PresentationMode.FULLSCREEN) { setMode(PresentationMode.FULLSCREEN) }
            ModeChip("浮窗", PresentationHub.presentationMode == PresentationMode.FLOATING) { setMode(PresentationMode.FLOATING) }
            // 承载模式（A 虚拟屏 / B 主屏）：独立于呈现模式，用实心按钮与 chip 区分（避免混淆，§8.1 备注）。
            OutlinedButton(onClick = { toggleHostMode() }) {
                Text(if (PresentationHub.displayMode == top.fellow99.rpauto.core.controller.DisplayMode.MAIN) "虚拟屏" else "主屏")
            }
            OutlinedButton(onClick = { close() }) { Text("关闭") }
        }
        // 呈现区域
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (PresentationHub.presentationMode) {
                PresentationMode.EMBEDDED ->
                    if (controller != null) {
                        EmbeddedScreen(controller, Modifier.fillMaxSize())
                    } else {
                        Text(
                            "会话不可用，请回到“连接并建立会话”",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.align(Alignment.Center),
                        )
                    }
                PresentationMode.FULLSCREEN ->
                    Text(
                        "正在全屏呈现 · 可在全屏界面切换/关闭",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.align(Alignment.Center),
                    )
                PresentationMode.FLOATING ->
                    Text(
                        "正在浮窗呈现 · 可点“嵌入”回到主区域或“关闭”",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.align(Alignment.Center),
                    )
            }
        }
    }
}

@Composable
private fun ModeChip(label: String, active: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Text(
            label,
            color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
