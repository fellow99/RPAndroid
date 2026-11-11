package top.fellow99.rpandroid.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import top.fellow99.rpandroid.R
import top.fellow99.rpandroid.a11y.MfaAccessibilityService
import top.fellow99.rpandroid.core.server.ServerConfig
import kotlinx.coroutines.delay
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * 设置栏目（spec FR-101-8）：adbd 连接端口、安全 token、虚拟屏分辨率、无障碍服务。
 */
@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val cfg = remember { ServerConfig(context.applicationContext) }

    var port by remember { mutableStateOf(cfg.connectPort.toString()) }
    var token by remember { mutableStateOf(cfg.token) }
    var vw by remember { mutableStateOf(cfg.vScreenWidth.toString()) }
    var vh by remember { mutableStateOf(cfg.vScreenHeight.toString()) }
    var vdpi by remember { mutableStateOf(cfg.vScreenDpi.toString()) }
    var a11yEnabled by remember { mutableStateOf(MfaAccessibilityService.isEnabled()) }
    var info by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        // 分辨率默认：与当前应用（窗口）分辨率一致，使全屏呈现区域无黑边（仅首次未配置时初始化）
        if (cfg.vScreenWidth == 0 || cfg.vScreenHeight == 0) {
            val dm = context.resources.displayMetrics
            val bounds = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                context.getSystemService(WindowManager::class.java)?.currentWindowMetrics?.bounds
            } else null
            cfg.vScreenWidth = bounds?.width() ?: dm.widthPixels
            cfg.vScreenHeight = bounds?.height() ?: dm.heightPixels
            cfg.vScreenDpi = dm.densityDpi
            vw = cfg.vScreenWidth.toString()
            vh = cfg.vScreenHeight.toString()
            vdpi = cfg.vScreenDpi.toString()
        }
        // 仅轮询无障碍启用状态（不影响其它已保存状态展示）
        while (true) {
            a11yEnabled = MfaAccessibilityService.isEnabled()
            delay(2000)
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
    ) {
        // 应用名 + slogan 头部
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary)) {
            Column(Modifier.fillMaxWidth().padding(20.dp)) {
                Text(
                    stringResource(R.string.app_name),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.slogan),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        SectionTitle("连接")
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Field("adbd 连接端口", port, { port = it }, hint = "无线调试端口，如 37577")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = {
                        cfg.connectPort = port.trim().toIntOrNull() ?: 0
                        saved = "端口已保存"
                        Toast.makeText(context, "端口已保存", Toast.LENGTH_SHORT).show()
                    }, modifier = Modifier.padding(top = 8.dp)) { Text("保存") }
                }
                Spacer(Modifier.height(16.dp))
                Field("安全 token", token, { token = it }, hint = "留空表示不校验")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = {
                        cfg.token = token.trim()
                        saved = "token 已保存"
                        Toast.makeText(context, "token 已保存", Toast.LENGTH_SHORT).show()
                    }, modifier = Modifier.padding(top = 8.dp)) { Text("保存") }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        SectionTitle("虚拟屏")
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(value = vw, onValueChange = { vw = it }, label = { Text("宽") }, modifier = Modifier.weight(1f))
                    OutlinedTextField(value = vh, onValueChange = { vh = it }, label = { Text("高") }, modifier = Modifier.weight(1f))
                    OutlinedTextField(value = vdpi, onValueChange = { vdpi = it }, label = { Text("dpi") }, modifier = Modifier.weight(1f))
                }
                Text(
                    "默认与当前应用的分辨率一致，使全屏呈现无黑边；修改后需重新“连接并建立会话”生效。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Button(onClick = {
                    val w = vw.trim().toIntOrNull()
                    val h = vh.trim().toIntOrNull()
                    val d = vdpi.trim().toIntOrNull()
                    if (w == null || h == null || d == null || w <= 0 || h <= 0) {
                        Toast.makeText(context, "分辨率须为大于 0 的整数", Toast.LENGTH_SHORT).show()
                    } else {
                        cfg.vScreenWidth = w
                        cfg.vScreenHeight = h
                        cfg.vScreenDpi = d
                        Toast.makeText(context, "分辨率已保存，下次连接会话生效", Toast.LENGTH_LONG).show()
                    }
                }, modifier = Modifier.padding(top = 8.dp)) { Text("保存") }
            }
        }

        Spacer(Modifier.height(16.dp))
        SectionTitle("无障碍")
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Row(
                Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("启用无障碍服务", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "用于读取被托管界面（ui_read）；当前：" + if (a11yEnabled) "已启用" else "未启用",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedButton(onClick = {
                    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }) { Text(if (a11yEnabled) "管理" else "去启用") }
            }
        }

        Spacer(Modifier.height(16.dp))
        SectionTitle("接入信息")
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Row(
                Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("REST / MCP / MJPEG / WebRTC / 客户端", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "查看当前设备的访问地址与鉴权方式",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedButton(onClick = { info = true }) { Text("查看") }
            }
        }

        if (saved != null) {
            Spacer(Modifier.height(8.dp))
            Text(saved!!, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
        }
    }

    if (info) {
        ConnectionInfoDialog(cfg = cfg, onDismiss = { info = false })
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(bottom = 8.dp, top = 4.dp),
    )
}

@Composable
private fun Field(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    hint: String,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        placeholder = { Text(hint) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

/** 接入信息弹窗（播控客户端 / WebRTC / API / MCP / MJPEG），不含真实 IP/token，展示占位推导。 */
@Composable
private fun ConnectionInfoDialog(cfg: ServerConfig, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val port = cfg.port
    val ip = lanIp()
    val tokenInfo = if (cfg.token.isBlank()) "未设置，无需鉴权" else "已设置（在设置栏保存）"
    val base = "http://$ip:$port"

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("接入信息") },
        text = {
            Text(
                buildString {
                    appendLine("▶ 播控客户端（/client）")
                    appendLine("  浏览器打开：$base/client")
                    appendLine("  已设 token 时：$base/client?token=<token>")
                    appendLine("  单页播控台：视频回显(WebRTC/MJPEG)、应用切换、自动化调度、日志")
                    appendLine("  详见 docs/Client.md")
                    appendLine()
                    appendLine("▶ WebRTC 控制台")
                    appendLine("  浏览器打开：$base/webrtc")
                    appendLine("  已设 token 时：$base/webrtc?token=<token>")
                    appendLine("  信令：ws://<host>:$port/webrtc/signaling（DataChannel 输入）")
                    appendLine("  并发浏览器上限 2；非安全上下文自动降级 MJPEG")
                    appendLine()
                    appendLine("▶ REST API")
                    appendLine("  局域网 Base：$base")
                    appendLine("  USB(adb forward)：http://localhost:$port")
                    appendLine("  鉴权头：X-Auth-Token: <token>")
                    appendLine("  示例：GET $base/status / apps / frame")
                    appendLine("  token：$tokenInfo")
                    appendLine()
                    appendLine("▶ MCP")
                    appendLine("  POST $base/mcp（Streamable HTTP）")
                    appendLine("  GET  $base/mcp/sse（SSE）")
                    appendLine("  工具：screen_* / app_* / task_* / ui_read")
                    appendLine()
                    appendLine("▶ MJPEG 视频流")
                    appendLine("  GET $base/stream（query token 见 docs/MJPEG.md）")
                    appendLine("  并发上限 8 路")
                },
                style = MaterialTheme.typography.bodySmall,
            )
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

/** 取第一个非回环 IPv4 地址（局域网 IP）。 */
internal fun lanIp(): String = runCatching {
    NetworkInterface.getNetworkInterfaces().toList()
        .flatMap { it.inetAddresses.toList() }
        .firstOrNull { !it.isLoopbackAddress && it is Inet4Address }
        ?.hostAddress ?: "127.0.0.1"
}.getOrDefault("127.0.0.1")
