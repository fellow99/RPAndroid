package top.fellow99.rpandroid.ui

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import top.fellow99.rpandroid.ManagedRuntime
import top.fellow99.rpandroid.core.server.ServerConfig
import top.fellow99.rpandroid.core.shell.adb.AdbServiceTypes
import top.fellow99.rpandroid.core.shell.adb.NsdAdbServiceFinder
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

/** 首次使用向导的步骤（纯 UI 内状态机，对应 spec FR-102-2）。 */
enum class WizardStep {
    DEVELOPER_OPTIONS,
    USB_DEBUGGING,
    WIRELESS_DEBUGGING,
    PAIR_CODE,
    DONE,
}

/**
 * 首次使用配对向导（spec FR-102）。
 *
 * 未配对（`!paired && connectPort == 0`）时，在「应用」/「自动化」栏目替代技术文案，引导用户：
 * 开发者选项 → USB 调试 → 无线调试 → 填 6 位配对码 → 自动建立会话。
 * 文案保持"尽量简单、无技术术语"。[onDone] 在配对 + 会话成功并回写配置后调用，由上层重算状态。
 */
@Composable
fun PairingWizard(onDone: () -> Unit) {
    val context = LocalContext.current
    val cfg = remember { ServerConfig(context.applicationContext) }
    var step by remember { mutableIntStateOf(WizardStep.DEVELOPER_OPTIONS.ordinal) }
    // 配对码 + 配对端口（自动发现优先，失败可选手动）
    var code by remember { mutableStateOf("") }
    var autoPairPort by remember { mutableStateOf<Int?>(null) }
    var discoveringPort by remember { mutableStateOf(false) }
    var manualPort by remember { mutableStateOf("") }
    var pairing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    // 配对已成功后的连接状态：null=未/进行中，true=会话已建（→完成页），false=连接失败（给手动连接端口重试）
    var pairDone by remember { mutableStateOf(false) }
    var connectPortInput by remember { mutableStateOf("") }
    // 手机上系统开关的实际状态（读不到按关着/不显示）
    var devOptions by remember { mutableStateOf(false) }
    var usbDebug by remember { mutableStateOf(false) }
    var wirelessDebug by remember { mutableStateOf(false) }

    // 轮询三个系统开关，用于在 Step1–3 显示"检测到：开/关"（读不到不拦，可点下一步继续）。
    LaunchedEffect(Unit) {
        while (true) {
            devOptions = WizardChecks.developerOptions(context)
            usbDebug = WizardChecks.usbDebugging(context)
            wirelessDebug = WizardChecks.wirelessDebugging(context)
            delay(1200)
        }
    }

    // 进入"输入配对码"一步时，后台自动发现配对端口（mDNS _adb-tls-pairing）。
    LaunchedEffect(step) {
        if (step == WizardStep.PAIR_CODE.ordinal && autoPairPort == null && !discoveringPort) {
            discoveringPort = true
            Thread {
                val found = runCatching {
                    runBlocking {
                        NsdAdbServiceFinder(context.applicationContext)
                            .findPorts(AdbServiceTypes.PAIRING, 6_000)
                    }
                }.getOrDefault(emptyList()).firstOrNull()
                Handler(Looper.getMainLooper()).post {
                    discoveringPort = false
                    autoPairPort = found
                }
            }.apply { name = "mfa-find-pair-port"; isDaemon = true }.start()
        }
    }

    fun goToNext() {
        step++
        error = null
    }

    /** 建立会话：优先用用户填的连接端口（[connectPortInput]），否则自动发现。 */
    fun connectSession(app: android.content.Context) {
        pairing = true
        error = null
        Thread {
            val dm = app.resources.displayMetrics
            val w = cfg.vScreenWidth.takeIf { it > 0 } ?: dm.widthPixels
            val h = cfg.vScreenHeight.takeIf { it > 0 } ?: dm.heightPixels
            val d = cfg.vScreenDpi.takeIf { it > 0 } ?: dm.densityDpi
            val manualPort = connectPortInput.trim().toIntOrNull()
            val okSession = ManagedRuntime.startSession(manualPort, w, h, d)
            Handler(Looper.getMainLooper()).post {
                pairing = false
                if (okSession && ManagedRuntime.controller()?.isSessionActive() == true) {
                    step = WizardStep.DONE.ordinal
                } else {
                    // 连接失败：配对已完成，只缺连接。引导填无线调试页顶部端口。
                    error = "配对已完成 ✓，但连接没成功。\n无线调试页顶部的端口号和这里不同时可填到下面重试。"
                }
            }
        }.apply { name = "mfa-connect"; isDaemon = true }.start()
    }

    /** 配对 + 建立会话；成功后回写配置并通知上层。 */
    fun doPair() {
        val c = code.trim()
        if (c.length != 6 || c.any { !it.isDigit() }) {
            error = "请输入屏幕上显示的 6 位数字配对码"
            return
        }
        val port = autoPairPort ?: manualPort.trim().toIntOrNull()
        if (port == null) {
            error = "没有自动找到配对入口，请把屏幕上显示的端口号填到下面"
            return
        }
        if (port !in 1..65535) {
            error = "配对端口须在 1~65535 之间"
            return
        }
        pairing = true
        error = null
        val app = context.applicationContext
        Thread {
            val sh = ManagedRuntime.shell()
            // 配对只连本机 adbd（127.0.0.1），拿 RSA 钥匙。配对本身成功即算配好
            // （设备“已配对设备”里会出现本 App 的钥匙）；会话连接失败不再回退向导。
            val pairedOk = sh != null && runCatching { runBlocking { sh.pair("127.0.0.1", port, c) } }.getOrDefault(false)
            if (!pairedOk) {
                Handler(Looper.getMainLooper()).post {
                    pairing = false
                    error = "配对没成功：请确认屏幕上的 6 位码没写错、配对窗口还开着，再试一次"
                }
                return@Thread
            }
            // 配对成功：立即落盘，设备已认这把钥匙。
            cfg.paired = true
            Handler(Looper.getMainLooper()).post { pairDone = true }
            connectSession(app)
        }.apply { name = "mfa-pair"; isDaemon = true }.start()
    }

    fun startOver() {
        step = WizardStep.DEVELOPER_OPTIONS.ordinal
        error = null
        // 重新配对时清掉上一轮的输入与状态，避免残留误导。
        code = ""
        manualPort = ""
        connectPortInput = ""
        pairDone = false
        autoPairPort = null
        discoveringPort = false
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        val s = WizardStep.entries[step]
        StepIndicator(step = step, count = WizardStep.entries.size - 1)
        Spacer(Modifier.height(20.dp))

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.fillMaxWidth().padding(20.dp)) {
                Text(s.heading(), style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(12.dp))
                Text(s.body(), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))

                when (s) {
                    WizardStep.DEVELOPER_OPTIONS -> {
                        SwitchHint("开发者选项", devOptions)
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = { openSystemSettings(context, 0) }) { Text("去关于手机") }
                    }
                    WizardStep.USB_DEBUGGING -> {
                        SwitchHint("USB 调试", usbDebug)
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = { openSystemSettings(context, 1) }) { Text("去开发者选项") }
                    }
                    WizardStep.WIRELESS_DEBUGGING -> {
                        SwitchHint("无线调试", wirelessDebug)
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = { openSystemSettings(context, 1) }) { Text("去开发者选项") }
                    }

                    WizardStep.PAIR_CODE -> {
                        OutlinedTextField(
                            value = code,
                            onValueChange = { code = it.filter { ch -> ch.isDigit() }.take(6) },
                            label = { Text("6 位配对码") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(8.dp))
                        PairPortStatus(
                            discovering = discoveringPort,
                            autoPort = autoPairPort,
                            manualPort = manualPort,
                            onManualPort = { manualPort = it },
                        )
                        // 配对成功后连接失败：给连接端口（无线调试页顶部）输入 + 重试。
                        if (pairDone) {
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(
                                value = connectPortInput,
                                onValueChange = { connectPortInput = it.filter { c -> c.isDigit() }.take(5) },
                                label = { Text("无线调试连接端口") },
                                placeholder = { Text("从无线调试页顶部那一行看到" ) },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }

                    WizardStep.DONE -> {
                        Text(
                            "配对完成，可以开始使用了！",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }

                if (error != null) {
                    Spacer(Modifier.height(12.dp))
                    Text(error!!, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        }

        Spacer(Modifier.height(20.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (step > WizardStep.DEVELOPER_OPTIONS.ordinal && step < WizardStep.DONE.ordinal) {
                OutlinedButton(onClick = { step-- }) { Text("上一步") }
            }
            when {
                pairing -> CircularProgressIndicator(modifier = Modifier.size(32.dp))
                step < WizardStep.PAIR_CODE.ordinal -> {
                    // 系统设置入口（跳「关于手机」/「开发者选项」）放在正文、此处置"我已做好"。
                    Button(onClick = ::goToNext) { Text("我已经做好了") }
                }
                step == WizardStep.PAIR_CODE.ordinal -> {
                    if (pairDone) {
                        // 配对已完成，只差连接：填好连接端口后「重新连接」。
                        Button(onClick = { connectSession(context.applicationContext) }) { Text("重新连接") }
                    } else {
                        Button(onClick = ::doPair) { Text("完成配对") }
                    }
                }
                else -> Button(onClick = onDone) { Text("开始使用") }
            }
        }
        if (step == WizardStep.DONE.ordinal) {
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = ::startOver) { Text("重新配对") }
        }
    }
}

/** 顶部步骤指示（1~N 点）。 */
@Composable
private fun StepIndicator(step: Int, count: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        repeat(count) { i ->
            val active = i == step
            Text(
                if (active) "●" else "○",
                color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 系统开关检测提示：读得到显示开/关，读不到只给个"以屏幕为准"的兜底。 */
@Composable
private fun SwitchHint(switchName: String, detected: Boolean) {
    Text(
        if (detected) "手机检测到「$switchName」已打开 ✓（如果没有，按屏幕提示找一下再点下一步）"
        else "打开后，下面会显示检测结果；有些手机读不到，直接点「我已经做好了」继续就行",
        style = MaterialTheme.typography.bodySmall,
        color = if (detected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** 配对端口状态：自动发现中 / 已找到 / 找不到时可选手动输入。 */
@Composable
private fun PairPortStatus(
    discovering: Boolean,
    autoPort: Int?,
    manualPort: String,
    onManualPort: (String) -> Unit,
) {
    when {
        discovering -> Text("正在自动寻找配对入口…", style = MaterialTheme.typography.bodySmall)
        autoPort != null -> Text(
            "已自动找到配对入口，填好上面的 6 位码点「完成配对」即可。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
        )
        else -> {
            Text(
                "没有自动找到配对入口：如果屏幕上显示了端口号（通常和配对码在一个窗口），填到下面。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = manualPort,
                onValueChange = { onManualPort(it.filter { ch -> ch.isDigit() }.take(5)) },
                label = { Text("配对端口（可选）") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** 读取系统开发者选项 / USB 调试 / 无线调试开关（只读；无线调试按未公开 key `adb_wifi_enabled` 读）。 */
private object WizardChecks {
    const val ADB_WIFI_ENABLED = "adb_wifi_enabled"

    fun developerOptions(context: Context): Boolean =
        runCatching { Settings.Global.getInt(context.contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1 }
            .getOrDefault(false)

    fun usbDebugging(context: Context): Boolean =
        runCatching { Settings.Global.getInt(context.contentResolver, Settings.Global.ADB_ENABLED, 0) == 1 }
            .getOrDefault(false)

    fun wirelessDebugging(context: Context): Boolean =
        runCatching { Settings.Global.getInt(context.contentResolver, ADB_WIFI_ENABLED, 0) == 1 }
            .getOrDefault(false)
}

/** 步骤文案（无术语）。 */
private fun WizardStep.heading(): String = when (this) {
    WizardStep.DEVELOPER_OPTIONS -> "第一步：打开开发者选项"
    WizardStep.USB_DEBUGGING -> "第二步：打开 USB 调试"
    WizardStep.WIRELESS_DEBUGGING -> "第三步：打开无线调试"
    WizardStep.PAIR_CODE -> "第四步：填写 6 位配对码"
    WizardStep.DONE -> "完成"
}

private fun WizardStep.body(): String = when (this) {
    WizardStep.DEVELOPER_OPTIONS -> "进入 设置 › 关于手机，向下找到“版本号”，用手指连续点它 7 次（只做这一次，屏幕会提示已进入开发者模式）。"
    WizardStep.USB_DEBUGGING -> "回到 设置 › 开发者选项，往下找到“USB 调试”，把它打开（弹出授权对话框时点允许）。"
    WizardStep.WIRELESS_DEBUGGING -> "在开发者选项里继续打开“无线调试”；打开后点“使用配对码配对设备”，屏幕上会出现一个 6 位数字配对码，把它记下来。"
    WizardStep.PAIR_CODE -> "把屏幕上显示的 6 位数字配对码填到下面的框里，然后点「完成配对」。配对成功后 App 会自动准备好。\n\n提示：有些手机会在你切走配对窗口时立刻取消配对。建议先把本应用设为「浮窗」，让配对码窗口和本应用同屏，再填码和端口。"
    WizardStep.DONE -> ""
}

/** 系统设置入口 Intent。 */
private fun settingsIntent(kind: Int): Intent = when (kind) {
    0 -> Intent(Settings.ACTION_DEVICE_INFO_SETTINGS)
    else -> Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
}

/** 跳到系统设置指定页（不同 ROM 处理，尽力而为）。 */
private fun openSystemSettings(context: Context, kind: Int) {
    runCatching {
        context.startActivity(
            settingsIntent(kind).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
