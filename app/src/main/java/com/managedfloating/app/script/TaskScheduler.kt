package com.managedfloating.app.script

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.managedfloating.core.script.Cron
import com.managedfloating.core.script.ScheduleKind
import com.managedfloating.core.script.ScriptTask
import com.managedfloating.core.script.TaskStore
import java.io.File

/**
 * [TaskScheduler] —— 主进程侧调度：为已启用的 INTERVAL/CRON 任务排 [AlarmManager] 精确闹钟，
 * 到点经 [AlarmReceiver] 触发 :node 进程执行。IMMEDIATE 任务由 REST/UI 手动触发。
 */
class TaskScheduler(private val ctx: Context) {

    private val store = TaskStore(File(ctx.applicationInfo.dataDir, "files/tasks.json"))
    private val am = ctx.getSystemService(AlarmManager::class.java)

    fun rescheduleAll() {
        for (t in store.all()) {
            if (!t.enabled) { cancel(t.id); continue }
            when (t.kind) {
                ScheduleKind.INTERVAL -> if (t.intervalMs > 0) scheduleAt(t, System.currentTimeMillis() + t.intervalMs)
                ScheduleKind.CRON -> Cron.next(t.cron, System.currentTimeMillis())?.let { scheduleAt(t, it) }
                ScheduleKind.IMMEDIATE -> Unit
            }
        }
    }

    fun runNow(taskId: String) {
        ScriptService.runTask(ctx, taskId)
        val t = store.get(taskId) ?: return
        val now = System.currentTimeMillis()
        val next = when (t.kind) {
            ScheduleKind.INTERVAL -> if (t.intervalMs > 0) now + t.intervalMs else 0L
            ScheduleKind.CRON -> Cron.next(t.cron, now) ?: 0L
            ScheduleKind.IMMEDIATE -> 0L
        }
        store.upsert(t.copy(lastRunAt = now, runCount = t.runCount + 1, nextRunAt = next))
        if (next > 0) scheduleAt(t.copy(nextRunAt = next), next)
    }

    private fun scheduleAt(task: ScriptTask, atMs: Long) {
        val pi = pendingIntent(task.id, PendingIntent.FLAG_UPDATE_CURRENT) ?: return
        try {
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pi)
            } else {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pi)
            }
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pi)
        }
        store.upsert(task.copy(nextRunAt = atMs))
    }

    private fun cancel(taskId: String) {
        am.cancel(pendingIntent(taskId, PendingIntent.FLAG_NO_CREATE) ?: return)
    }

    private fun pendingIntent(taskId: String, flags: Int): PendingIntent? {
        val i = Intent(ctx, AlarmReceiver::class.java).putExtra(ScriptService.EXTRA_TASK_ID, taskId)
        return PendingIntent.getBroadcast(
            ctx, taskId.hashCode(), i,
            flags or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}

/** 闹钟到点 → 触发 :node 进程执行任务。 */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val taskId = intent.getStringExtra(ScriptService.EXTRA_TASK_ID) ?: return
        ScriptService.runTask(context.applicationContext, taskId)
        // 续排下一次
        TaskScheduler(context.applicationContext).rescheduleAll()
    }
}
