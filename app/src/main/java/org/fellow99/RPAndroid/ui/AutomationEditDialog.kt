package org.fellow99.RPAndroid.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import org.fellow99.RPAndroid.core.controller.AppInfo
import org.fellow99.RPAndroid.core.script.Cron
import org.fellow99.RPAndroid.core.script.ScheduleKind
import org.fellow99.RPAndroid.core.script.ScriptTask
import org.fellow99.RPAndroid.core.script.TaskStore
import org.fellow99.RPAndroid.present.ManagedBridge
import org.fellow99.RPAndroid.script.TaskScheduler
import java.io.File
import java.util.Calendar

/** 新建/编辑对话框可选的执行方式（CRON 暂隐藏，保留后端）。 */
private val PICKABLE_KINDS = listOf(ScheduleKind.IMMEDIATE, ScheduleKind.INTERVAL, ScheduleKind.SCHEDULED)

/**
 * 自动化任务编辑表单：名称 / 目标 App（选择器）/ 启用 / 执行方式（立即·间隔秒·固定日期时间）/ JS 脚本。
 * CRON 暂不使用（隐藏）；间隔单位为秒；固定日期时间按年月日时分秒设置。
 */
@Composable
fun AutomationEditDialog(
    existing: ScriptTask?,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
) {
    val context = LocalContext.current
    val store = remember { TaskStore(File(context.applicationInfo.dataDir, "files/tasks.json")) }
    val apps = remember { listAppsForPicker(ManagedBridge.controller()) }

    var name by remember { mutableStateOf(existing?.name ?: "") }
    var pkg by remember { mutableStateOf(existing?.packageName ?: "") }
    var enabled by remember { mutableStateOf(existing?.enabled ?: true) }
    var kind by remember { mutableStateOf(existing?.kind ?: ScheduleKind.IMMEDIATE) }
    var intervalSec by remember { mutableStateOf((existing?.intervalSec ?: 0L).toString()) }
    var cron by remember { mutableStateOf(existing?.cron ?: "") }
    var script by remember { mutableStateOf(existing?.script ?: "// await host.invoke('screen.tap', {x:360,y:500})") }
    var error by remember { mutableStateOf<String?>(null) }
    var picking by remember { mutableStateOf(false) }

    // 固定日期时间：年月日时分秒（字符串输入），初始化自现有 runAt。
    var schY by remember { mutableStateOf("") }
    var schMo by remember { mutableStateOf("") }
    var schD by remember { mutableStateOf("") }
    var schH by remember { mutableStateOf("") }
    var schMi by remember { mutableStateOf("") }
    var schS by remember { mutableStateOf("") }

    fun fillSchedule(runAt: Long) {
        if (runAt <= 0) {
            // 新建默认：明天同一时刻
            val c = Calendar.getInstance().apply { add(Calendar.DAY_OF_MONTH, 1) }
            schY = c.get(Calendar.YEAR).toString()
            schMo = (c.get(Calendar.MONTH) + 1).toString()
            schD = c.get(Calendar.DAY_OF_MONTH).toString()
            schH = c.get(Calendar.HOUR_OF_DAY).toString()
            schMi = c.get(Calendar.MINUTE).toString()
            schS = "0"
        } else {
            val c = Calendar.getInstance().apply { timeInMillis = runAt }
            schY = c.get(Calendar.YEAR).toString()
            schMo = (c.get(Calendar.MONTH) + 1).toString()
            schD = c.get(Calendar.DAY_OF_MONTH).toString()
            schH = c.get(Calendar.HOUR_OF_DAY).toString()
            schMi = c.get(Calendar.MINUTE).toString()
            schS = c.get(Calendar.SECOND).toString()
        }
    }

    fun parseSchedule(): Long? {
        val y = schY.trim().toLongOrNull() ?: return null
        val mo = schMo.trim().toLongOrNull() ?: return null
        val day = schD.trim().toLongOrNull() ?: return null
        val h = schH.trim().toLongOrNull() ?: return null
        val mi = schMi.trim().toLongOrNull() ?: return null
        val s = schS.trim().toLongOrNull() ?: return null
        if (y < 1970 || mo !in 1..12 || day !in 1..31 || h !in 0..23 || mi !in 0..59 || s !in 0..59) return null
        return try {
            Calendar.getInstance().apply {
                set(Calendar.YEAR, y.toInt()); set(Calendar.MONTH, mo.toInt() - 1); set(Calendar.DAY_OF_MONTH, day.toInt())
                set(Calendar.HOUR_OF_DAY, h.toInt()); set(Calendar.MINUTE, mi.toInt()); set(Calendar.SECOND, s.toInt())
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis
        } catch (e: Exception) {
            null
        }
    }

    fun save() {
        when {
            name.isBlank() -> error = "请填写任务名称"
            kind == ScheduleKind.INTERVAL && (intervalSec.toLongOrNull() ?: 0) <= 0 -> error = "间隔执行需填写大于 0 的秒数"
            kind == ScheduleKind.SCHEDULED -> {
                val at = parseSchedule()
                when {
                    at == null -> error = "固定日期时间不合法（年/月/日/时/分/秒须为数字且在范围内）"
                    at!! <= System.currentTimeMillis() -> error = "固定日期时间需晚于当前时刻"
                    else -> error = null
                }
                if (error != null) return
            }
            kind == ScheduleKind.CRON && Cron.next(cron.trim(), System.currentTimeMillis()) == null ->
                error = "cron 表达式无效（示例：0 * * * * *）"
        }
        if (error != null) return
        val id = existing?.id ?: ("t" + System.currentTimeMillis())
        val runAt = if (kind == ScheduleKind.SCHEDULED) (parseSchedule() ?: 0) else 0
        val task = ScriptTask(
            id = id,
            name = name.trim(),
            packageName = pkg.ifBlank { null },
            script = script,
            enabled = enabled,
            kind = kind,
            intervalSec = if (kind == ScheduleKind.INTERVAL) (intervalSec.toLongOrNull() ?: 0) else 0,
            runAt = runAt,
            cron = if (kind == ScheduleKind.CRON) cron.trim() else "",
            lastRunAt = existing?.lastRunAt ?: 0,
            nextRunAt = existing?.nextRunAt ?: 0,
            runCount = existing?.runCount ?: 0,
            lastStatus = existing?.lastStatus ?: "",
        )
        store.upsert(task)
        TaskScheduler(context.applicationContext).rescheduleAll()
        onSaved()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "新建自动化" else "编辑自动化") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                // 目标 App 选择（选择器对话框，不手写）
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = labelForPkg(context, pkg) ?: pkg.ifBlank { "未选择" },
                        onValueChange = {},
                        label = { Text("目标 App") },
                        enabled = false,
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedButton(onClick = { picking = true }, modifier = Modifier.padding(start = 8.dp)) { Text("选择") }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("启用", modifier = Modifier.weight(1f))
                    Switch(checked = enabled, onCheckedChange = { enabled = it })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PICKABLE_KINDS.forEach { k ->
                        FilterChip(
                            selected = kind == k,
                            onClick = {
                                // 切换到 SCHEDULED 且未初始化时给出默认值
                                if (k == ScheduleKind.SCHEDULED && schY.isBlank()) fillSchedule(existing?.runAt ?: 0)
                                kind = k
                            },
                            label = { Text(k.label) },
                        )
                    }
                }
                if (kind == ScheduleKind.INTERVAL) {
                    OutlinedTextField(
                        value = intervalSec,
                        onValueChange = { intervalSec = it },
                        label = { Text("间隔（秒）") },
                        placeholder = { Text("如 60") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (kind == ScheduleKind.SCHEDULED) {
                    Text("固定日期时间", style = MaterialTheme.typography.bodySmall)
                    // 年月 / 日 / 时分秒
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(value = schY, onValueChange = { schY = it }, label = { Text("年") }, singleLine = true, modifier = Modifier.width(96.dp))
                        OutlinedTextField(value = schMo, onValueChange = { schMo = it }, label = { Text("月") }, singleLine = true, modifier = Modifier.width(72.dp))
                        OutlinedTextField(value = schD, onValueChange = { schD = it }, label = { Text("日") }, singleLine = true, modifier = Modifier.width(72.dp))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(value = schH, onValueChange = { schH = it }, label = { Text("时") }, singleLine = true, modifier = Modifier.width(72.dp))
                        OutlinedTextField(value = schMi, onValueChange = { schMi = it }, label = { Text("分") }, singleLine = true, modifier = Modifier.width(72.dp))
                        OutlinedTextField(value = schS, onValueChange = { schS = it }, label = { Text("秒") }, singleLine = true, modifier = Modifier.width(72.dp))
                    }
                }
                OutlinedTextField(
                    value = script,
                    onValueChange = { script = it },
                    label = { Text("执行 JS 脚本（node-runtime）") },
                    minLines = 5,
                    maxLines = 10,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (error != null) {
                    Text(error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { save() }) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )

    if (picking) {
        AppPickerDialog(apps = apps, current = pkg, onPick = { pkg = it; picking = false }, onDismiss = { picking = false })
    }
}

@Composable
private fun AppPickerDialog(
    apps: List<AppInfo>,
    current: String?,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择目标 App") },
        text = {
            if (apps.isEmpty()) {
                Text("无可用应用", style = MaterialTheme.typography.bodyMedium)
            } else {
                LazyColumn(Modifier.fillMaxWidth()) {
                    items(apps, key = { it.packageName }) { app ->
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable { onPick(app.packageName) }
                                .padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            AppIconAndLabel(pkg = app.packageName, label = app.label, iconSize = 28.dp)
                            Text(
                                app.label,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(start = 10.dp),
                            )
                            if (app.packageName == current) {
                                Text(" ✓", color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 8.dp))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

private fun listAppsForPicker(controller: org.fellow99.RPAndroid.core.controller.ScreenController?): List<AppInfo> =
    runCatching { controller?.listApps() ?: emptyList() }.getOrDefault(emptyList())

private fun labelForPkg(context: android.content.Context, pkg: String?): String? {
    if (pkg.isNullOrEmpty()) return null
    return runCatching { context.packageManager.getApplicationInfo(pkg, 0).loadLabel(context.packageManager).toString() }.getOrNull()
}

private val ScheduleKind.label: String
    get() = when (this) {
        ScheduleKind.IMMEDIATE -> "立即"
        ScheduleKind.INTERVAL -> "间隔"
        ScheduleKind.SCHEDULED -> "定时"
        ScheduleKind.CRON -> "cron"
    }
