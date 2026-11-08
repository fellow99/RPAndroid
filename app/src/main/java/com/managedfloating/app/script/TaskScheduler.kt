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
        ScriptService.clearStopFlag(ctx, taskId)
        ScriptService.runTask(ctx, taskId)
        val t = store.get(taskId) ?: return
        val now = System.currentTimeMillis()
        val next = when {
            !t.enabled -> 0L // 停用任务手动执行一次，不排后续闹钟
            t.kind == ScheduleKind.INTERVAL -> if (t.intervalMs > 0) now + t.intervalMs else 0L
            t.kind == ScheduleKind.CRON -> Cron.next(t.cron, now) ?: 0L
            else -> 0L
        }
        store.upsert(t.copy(lastRunAt = now, runCount = t.runCount + 1, nextRunAt = next))
        if (next > 0) scheduleAt(store.get(taskId) ?: t, next)
    }

    /**
     * 闹钟到点：记录运行统计 → 占用全局执行权（互斥）→ 交给 :node 执行 → 续排下一次。
     *
     * 修复：历史实现（AlarmReceiver 直接 runTask）不经 TaskRuntime/runNow，导致定时触发时
     * `runCount/lastRunAt` 不增长、`runningTaskId` 不可观、`lastStatus` 不回写。
     */
    fun onAlarm(taskId: String) {
        val t = store.get(taskId) ?: return
        if (!t.enabled) {
            cancel(taskId)
            return
        }
        val now = System.currentTimeMillis()
        if (TaskRuntime.begin(taskId)) {
            store.upsert(t.copy(lastRunAt = now, runCount = t.runCount + 1))
            ScriptService.clearStopFlag(ctx, taskId)
            ScriptService.runTask(ctx, taskId)
        } else {
            com.managedfloating.core.server.MfaLog.w("Script", "scheduled run skipped (busy) task=$taskId")
        }
        rescheduleAll()
    }

    /** 停止某任务：取消已排闹钟 + 写停止信号中止 :node 当前执行（尽力而为）。 */
    fun stop(taskId: String) {
        cancel(taskId)
        ScriptService.requestStop(ctx, taskId)
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
        // 只回写 nextRunAt：不能用调用方手里的旧副本整条覆盖，否则会抹掉刚写入的
        // lastRunAt / runCount / lastStatus（实测 runCount 恒为 0 的根因）。
        store.get(task.id)?.let { store.upsert(it.copy(nextRunAt = atMs)) }
    }

    private fun cancel(taskId: String) {
        pendingIntent(taskId, PendingIntent.FLAG_NO_CREATE)?.let { am.cancel(it) }
        // 取消后清掉下次计划时间（即使闹钟已不存在），避免 UI/接口展示一个不会触发的时刻。
        store.get(taskId)?.let { if (it.nextRunAt != 0L) store.upsert(it.copy(nextRunAt = 0)) }
    }

    private fun pendingIntent(taskId: String, flags: Int): PendingIntent? {
        val i = Intent(ctx, AlarmReceiver::class.java).putExtra(ScriptService.EXTRA_TASK_ID, taskId)
        return PendingIntent.getBroadcast(
            ctx, taskId.hashCode(), i,
            flags or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}

/** 闹钟到点 → 经 [TaskScheduler.onAlarm] 记录统计、占用执行权并触发 :node 执行。 */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val taskId = intent.getStringExtra(ScriptService.EXTRA_TASK_ID) ?: return
        TaskScheduler(context.applicationContext).onAlarm(taskId)
    }
}
