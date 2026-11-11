package top.fellow99.rpandroid.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import top.fellow99.rpandroid.core.controller.AppInfo
import top.fellow99.rpandroid.core.script.Cron
import top.fellow99.rpandroid.core.script.ScheduleKind
import top.fellow99.rpandroid.core.script.ScriptTask
import top.fellow99.rpandroid.core.script.TaskStore
import top.fellow99.rpandroid.present.ManagedBridge
import top.fellow99.rpandroid.script.TaskScheduler
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
    // 默认脚本必须是**可执行**的合法 JS：之前默认只有一行注释，runner 包成
    // `(async()=>{ ... })()` 后直接 SyntaxError: Unexpected end of input（真机实测）。
    var script by remember {
        mutableStateOf(
            existing?.script
                ?: "// 示例：点击虚拟屏坐标（需托管会话）\nawait host.invoke('screen.tap', { x: 360, y: 500 });",
        )
    }
    var error by remember { mutableStateOf<String?>(null) }
    var picking by remember { mutableStateOf(false) }

    // 固定日期时间：epoch 毫秒（0=未设置），用“日期时间选择控件”（日历 + 时分秒下拉）设置。
    var schAt by remember { mutableStateOf(existing?.runAt ?: 0L) }
    var pickingDateTime by remember { mutableStateOf(false) }

    /** 新建默认：明天同一时刻。 */
    fun defaultSchedule(): Long =
        Calendar.getInstance().apply { add(Calendar.DAY_OF_MONTH, 1) }.timeInMillis

    fun save() {
        when {
            name.isBlank() -> error = "请填写任务名称"
            kind == ScheduleKind.INTERVAL && (intervalSec.toLongOrNull() ?: 0) <= 0 -> error = "间隔执行需填写大于 0 的秒数"
            kind == ScheduleKind.SCHEDULED -> {
                if (schAt <= 0) error = "请选择固定日期时间"
                else if (schAt <= System.currentTimeMillis()) error = "固定日期时间需晚于当前时刻"
                else error = null
                if (error != null) return
            }
            kind == ScheduleKind.CRON && Cron.next(cron.trim(), System.currentTimeMillis()) == null ->
                error = "cron 表达式无效（示例：0 * * * * *）"
        }
        if (error != null) return
        val id = existing?.id ?: ("t" + System.currentTimeMillis())
        val runAt = if (kind == ScheduleKind.SCHEDULED) schAt else 0
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
                                // 切换到 SCHEDULED 且未设置时给默认值（明天同一时刻）
                                if (k == ScheduleKind.SCHEDULED && schAt == 0L) schAt = defaultSchedule()
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
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = if (schAt > 0) fmtDateTime(schAt) else "未设置",
                            onValueChange = {},
                            label = { Text("日期时间") },
                            enabled = false,
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedButton(onClick = { pickingDateTime = true }, modifier = Modifier.padding(start = 8.dp)) {
                            Text("选择")
                        }
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

    if (pickingDateTime) {
        DateTimePickerDialog(
            initial = schAt.takeIf { it > 0 } ?: System.currentTimeMillis(),
            onPick = { schAt = it; pickingDateTime = false },
            onDismiss = { pickingDateTime = false },
        )
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

/** 日期时间选择控件：日历选日期 + 时/分/秒下拉（含秒，M3 TimePicker 无秒故用下拉）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateTimePickerDialog(
    initial: Long,
    onPick: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val initCal = remember { Calendar.getInstance().apply { timeInMillis = initial } }
    val dateState = rememberDatePickerState(initialSelectedDateMillis = initial)
    var hour by remember { mutableStateOf(initCal.get(Calendar.HOUR_OF_DAY)) }
    var minute by remember { mutableStateOf(initCal.get(Calendar.MINUTE)) }
    var second by remember { mutableStateOf(initCal.get(Calendar.SECOND)) }

    fun commit() {
        val selUtc = dateState.selectedDateMillis ?: run { onPick(initial); return }
        // DatePicker 返回“所选日期在 UTC 的午夜”；取其年月日与当前时区时分秒组合成目标时刻。
        val day = Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply { timeInMillis = selUtc }
        val target = Calendar.getInstance().apply {
            set(Calendar.YEAR, day.get(Calendar.YEAR))
            set(Calendar.MONTH, day.get(Calendar.MONTH))
            set(Calendar.DAY_OF_MONTH, day.get(Calendar.DAY_OF_MONTH))
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, second)
            set(Calendar.MILLISECOND, 0)
        }
        onPick(target.timeInMillis)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择日期时间") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()).width(320.dp)) {
                DatePicker(state = dateState, showModeToggle = false)
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TimeSpinner(0..23, hour) { hour = it }
                    Text(" : ", style = MaterialTheme.typography.titleMedium)
                    TimeSpinner(0..59, minute) { minute = it }
                    Text(" : ", style = MaterialTheme.typography.titleMedium)
                    TimeSpinner(0..59, second) { second = it }
                }
            }
        },
        confirmButton = { TextButton(onClick = { commit() }) { Text("确定") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** 时分秒下拉选择器（无文本框输入）。 */
@Composable
private fun TimeSpinner(range: IntRange, value: Int, onChange: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }) { Text(String.format("%02d", value)) }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            range.forEach { v ->
                DropdownMenuItem(
                    text = { Text(String.format("%02d", v)) },
                    onClick = { onChange(v); expanded = false },
                )
            }
        }
    }
}

private fun fmtDateTime(epochMs: Long): String {
    val f = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
    return f.format(java.util.Date(epochMs))
}

private fun listAppsForPicker(controller: top.fellow99.rpandroid.core.controller.ScreenController?): List<AppInfo> =
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
