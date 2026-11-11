package top.fellow99.rpandroid.ui

import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
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
import top.fellow99.rpandroid.ManagedRuntime
import top.fellow99.rpandroid.present.FloatingService
import top.fellow99.rpandroid.present.ManagedBridge
import top.fellow99.rpandroid.present.ScreenActivity
import top.fellow99.rpandroid.core.controller.AppInfo
import top.fellow99.rpandroid.core.controller.ScreenController
import top.fellow99.rpandroid.core.server.ServerConfig
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

/** S3：可启动应用图标网格。 */
@Composable
private fun AppGrid(onSelect: (AppInfo) -> Unit) {
    var apps by remember { mutableStateOf<List<AppInfo>?>(null) }

    LaunchedEffect(Unit) {
        apps = listApps(ManagedBridge.controller())
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("选择要承载的应用", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = { apps = listApps(ManagedBridge.controller()) }) {
                Text("刷新")
            }
        }
        val list = apps
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
                    AppCell(app, onClick = { onSelect(app) })
                }
            }
        }
    }
}

private fun listApps(controller: ScreenController?): List<AppInfo> =
    runCatching { controller?.listApps() ?: emptyList() }.getOrDefault(emptyList())

@Composable
private fun AppCell(app: AppInfo, onClick: () -> Unit) {
    Card(onClick = onClick, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(
            Modifier.fillMaxWidth().padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AppIconAndLabel(pkg = app.packageName, label = app.label, iconSize = 48.dp)
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
            top.fellow99.rpandroid.ManagedRuntime.closeHostedApp()
        }.apply { name = "mfa-close-app"; isDaemon = true }.start()
    }

    Column(Modifier.fillMaxSize()) {
        // 顶栏：当前 App + 呈现模式切换（嵌入/全屏/浮窗） + 关闭。
        // （承载模式切换「主屏/虚拟屏」已移至 设置 页，见 SettingsScreen §承载模式。）
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
