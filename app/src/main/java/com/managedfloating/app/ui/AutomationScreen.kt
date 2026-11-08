package com.managedfloating.app.ui

import android.content.pm.PackageManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.managedfloating.app.present.ManagedBridge
import com.managedfloating.app.script.TaskRuntime
import com.managedfloating.app.script.TaskScheduler
import com.managedfloating.core.script.ScheduleKind
import com.managedfloating.core.script.ScriptTask
import com.managedfloating.core.script.TaskStore
import com.managedfloating.core.server.ServerConfig
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/**
 * 自动化栏目（spec FR-101-5/6/7）：复用 S1/S2 会话门控，之后展示任务列表；
 * 启用项可按「执行」互斥地运行（运行中其它项按钮隐藏），可「停止」恢复；可新建/编辑任务。
 */
@Composable
fun AutomationScreen(onOpenSettings: () -> Unit) {
    val context = LocalContext.current
    val cfg = remember { ServerConfig(context.applicationContext) }
    val store = remember {
        TaskStore(File(context.applicationInfo.dataDir, "files/tasks.json"))
    }
    var sessionActive by remember { mutableStateOf(ManagedBridge.controller()?.isSessionActive() == true) }
    var connecting by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var tasks by remember { mutableStateOf<List<ScriptTask>?>(null) }
    var editing by remember { mutableStateOf<ScriptTask?>(null) }
    var showNew by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun reload() {
        tasks = store.all()
    }

    fun runNow(task: ScriptTask) {
        // 互斥：同一时刻仅一个自动化在执行（与 /client/REST 共用 TaskRuntime 权威）。
        if (!TaskRuntime.begin(task.id)) {
            message = "已有自动化在执行"
            return
        }
        message = "已调度执行：${task.name.ifBlank { task.id }}"
        scope.launch {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                TaskScheduler(context.applicationContext).runNow(task.id)
            }
            reload()
        }
    }

    fun stop(task: ScriptTask) {
        scope.launch {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                TaskRuntime.end(task.id)
                TaskScheduler(context.applicationContext).stop(task.id)
            }
            message = "已停止：${task.name.ifBlank { task.id }}"
        }
    }

    fun toggleEnabled(task: ScriptTask, enabled: Boolean) {
        store.upsert(task.copy(enabled = enabled))
        TaskScheduler(context.applicationContext).rescheduleAll()
        reload()
    }

    // 轮询：同步会话状态 + 外部(/client/REST)对任务、执行状态的变更；
    // 结果回写由 TaskRuntime 的 watchdog 统一消费（此处不再自行 takeRunOutcome）。
    LaunchedEffect(Unit) {
        reload()
        var prevRunning: String? = null
        while (true) {
            sessionActive = ManagedBridge.controller()?.isSessionActive() == true
            reload()
            val running = TaskRuntime.runningTaskId
            if (prevRunning != null && running == null) {
                message = "自动化运行结束"
            }
            prevRunning = running
            delay(1200)
        }
    }

    Box(Modifier.fillMaxSize()) {
        when {
            cfg.connectPort == 0 -> PortNeededContent(onOpenSettings)
            !sessionActive -> ConnectContent(connecting, message, onConnect = {
                connecting = true
                message = "正在连接 adbd 并拉起助手…"
                // 分辨率缺省时以当前屏幕为准（防止 0×0 建屏失败）
                val dm = context.resources.displayMetrics
                val w = cfg.vScreenWidth.takeIf { it > 0 } ?: dm.widthPixels
                val h = cfg.vScreenHeight.takeIf { it > 0 } ?: dm.heightPixels
                val d = cfg.vScreenDpi.takeIf { it > 0 } ?: dm.densityDpi
                Thread {
                    val ok = com.managedfloating.app.ManagedRuntime.startSession(cfg.connectPort, w, h, d)
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        connecting = false
                        sessionActive = ManagedBridge.controller()?.isSessionActive() == true
                        message = if (ok && sessionActive) null else "连接失败：请检查端口/配对后重试"
                    }
                }.apply { name = "mfa-connect"; isDaemon = true }.start()
            })
            else -> AutomationList(
                tasks = tasks,
                onRun = { runNow(it) },
                onStop = { stop(it) },
                onToggle = { t, e -> toggleEnabled(t, e) },
                onEdit = { editing = it },
                onNew = { showNew = true },
                onRefresh = { reload() },
            )
        }
        if (message != null) {
            Banner(text = message!!, onDismiss = { message = null }, modifier = Modifier.align(Alignment.TopCenter))
        }
    }

    if (showNew) {
        AutomationEditDialog(existing = null, onDismiss = { showNew = false }, onSaved = {
            showNew = false
            reload()
        })
    }
    editing?.let { t ->
        AutomationEditDialog(existing = t, onDismiss = { editing = null }, onSaved = {
            editing = null
            reload()
        })
    }
}

@Composable
private fun AutomationList(
    tasks: List<ScriptTask>?,
    onRun: (ScriptTask) -> Unit,
    onStop: (ScriptTask) -> Unit,
    onToggle: (ScriptTask, Boolean) -> Unit,
    onEdit: (ScriptTask) -> Unit,
    onNew: () -> Unit,
    onRefresh: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("自动化任务", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = onRefresh) { Text("刷新") }
            OutlinedButton(onClick = onNew) { Text("＋ 新建") }
        }
        val list = tasks
        if (list == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("正在加载任务…", style = MaterialTheme.typography.bodyMedium)
            }
        } else if (list.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "还没有自动化任务\n点右上角“＋ 新建”创建",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        } else {
            val runningId = TaskRuntime.runningTaskId
            LazyColumn(
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(list, key = { it.id }) { task ->
                    AutomationRow(
                        task = task,
                        running = runningId == task.id,
                        othersRunning = runningId != null && runningId != task.id,
                        onRun = { onRun(task) },
                        onStop = { onStop(task) },
                        onToggle = { onToggle(task, it) },
                        onEdit = { onEdit(task) },
                    )
                }
            }
        }
    }
}

@Composable
private fun AutomationRow(
    task: ScriptTask,
    running: Boolean,
    othersRunning: Boolean,
    onRun: () -> Unit,
    onStop: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
) {
    val context = LocalContext.current
    val label = remember(task.packageName) { appLabelFor(context, task.packageName) }

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppIconAndLabel(pkg = task.packageName, label = label, iconSize = 36.dp)
            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                Text(
                    task.name.ifBlank { task.id },
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    buildString {
                        append(label ?: task.packageName ?: "未指定 App")
                        append(" · ")
                        append(scheduleLabel(task))
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (task.lastStatus.isNotBlank()) {
                    Text(
                        "上次：${task.lastStatus}",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (task.lastStatus.startsWith("error")) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Switch(checked = task.enabled, onCheckedChange = onToggle)
            // 右侧：执行状态及按钮（启用项）+ 编辑
            if (task.enabled) {
                when {
                    running -> OutlinedButton(onClick = onStop) { Text("⏹ 停止") }
                    !othersRunning -> OutlinedButton(onClick = onRun) { Text("▶ 执行") }
                    else -> androidx.compose.material3.Text("", style = MaterialTheme.typography.bodySmall)
                }
            }
            TextButton(onClick = onEdit) { Text("编辑") }
        }
    }
}

private fun appLabelFor(context: android.content.Context, pkg: String?): String? {
    if (pkg.isNullOrEmpty()) return null
    return runCatching {
        val pm: PackageManager = context.packageManager
        pm.getApplicationInfo(pkg, 0).loadLabel(pm).toString()
    }.getOrNull()
}

private fun scheduleLabel(t: ScriptTask): String = when (t.kind) {
    ScheduleKind.IMMEDIATE -> "立即执行"
    ScheduleKind.INTERVAL -> "间隔 " + if (t.intervalMs >= 1000) "${t.intervalMs / 1000} 秒" else "${t.intervalMs} ms"
    ScheduleKind.CRON -> "cron: ${t.cron}"
}
