package com.managedfloating.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.managedfloating.app.present.ManagedBridge
import com.managedfloating.app.script.TaskScheduler
import com.managedfloating.core.controller.AppInfo
import com.managedfloating.core.script.Cron
import com.managedfloating.core.script.ScheduleKind
import com.managedfloating.core.script.ScriptTask
import com.managedfloating.core.script.TaskStore
import java.io.File

/**
 * 自动化任务编辑表单（spec FR-101-7）：名称 / 目标 App / 启用 / 执行方式（立即·间隔·cron）/ JS 脚本。
 * 新建（existing=null）或编辑（existing=任务）。
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
    var intervalMs by remember { mutableStateOf((existing?.intervalMs ?: 0L).toString()) }
    var cron by remember { mutableStateOf(existing?.cron ?: "") }
    var script by remember { mutableStateOf(existing?.script ?: "// await host.invoke('screen.tap', {x:360,y:500})") }
    var error by remember { mutableStateOf<String?>(null) }
    var picking by remember { mutableStateOf(false) }

    fun save() {
        when {
            name.isBlank() -> error = "请填写任务名称"
            kind == ScheduleKind.INTERVAL && (intervalMs.toLongOrNull() ?: 0) <= 0 -> error = "间隔执行需填写大于 0 的毫秒数"
            kind == ScheduleKind.CRON && Cron.next(cron.trim(), System.currentTimeMillis()) == null ->
                error = "cron 表达式无效（示例：0 * * * * *）"
            else -> {
                val id = existing?.id ?: ("t" + System.currentTimeMillis())
                val task = ScriptTask(
                    id = id,
                    name = name.trim(),
                    packageName = pkg.ifBlank { null },
                    script = script,
                    enabled = enabled,
                    kind = kind,
                    intervalMs = if (kind == ScheduleKind.INTERVAL) (intervalMs.toLongOrNull() ?: 0) else 0,
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
        }
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
                // 目标 App 选择
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = labelForPkg(context, pkg) ?: pkg.ifBlank { "未选择" },
                        onValueChange = {},
                        label = { Text("目标 App") },
                        enabled = false,
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedButton(onClick = { picking = true }) { Text("选择") }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("启用", modifier = Modifier.weight(1f))
                    Switch(checked = enabled, onCheckedChange = { enabled = it })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ScheduleKind.entries.forEach { k ->
                        FilterChip(
                            selected = kind == k,
                            onClick = { kind = k },
                            label = { Text(k.label) },
                        )
                    }
                }
                if (kind == ScheduleKind.INTERVAL) {
                    OutlinedTextField(
                        value = intervalMs,
                        onValueChange = { intervalMs = it },
                        label = { Text("间隔毫秒") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (kind == ScheduleKind.CRON) {
                    OutlinedTextField(
                        value = cron,
                        onValueChange = { cron = it },
                        label = { Text("cron 表达式") },
                        placeholder = { Text("如 0 * * * * *（秒 分 时 日 月 周）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
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

private fun listAppsForPicker(controller: com.managedfloating.core.controller.ScreenController?): List<AppInfo> =
    runCatching { controller?.listApps() ?: emptyList() }.getOrDefault(emptyList())

private fun labelForPkg(context: android.content.Context, pkg: String?): String? {
    if (pkg.isNullOrEmpty()) return null
    return runCatching { context.packageManager.getApplicationInfo(pkg, 0).loadLabel(context.packageManager).toString() }.getOrNull()
}

private val ScheduleKind.label: String
    get() = when (this) {
        ScheduleKind.IMMEDIATE -> "立即"
        ScheduleKind.INTERVAL -> "间隔"
        ScheduleKind.CRON -> "cron"
    }
