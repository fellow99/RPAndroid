package top.fellow99.rpandroid.script

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import top.fellow99.rpandroid.core.script.Cron
import top.fellow99.rpandroid.core.script.ScheduleKind
import top.fellow99.rpandroid.core.script.ScriptTask
import top.fellow99.rpandroid.core.script.TaskStore
import top.fellow99.rpandroid.core.script.nextRunAfterManualRun
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
                ScheduleKind.INTERVAL -> if (t.intervalSec > 0) scheduleAt(t, System.currentTimeMillis() + t.intervalSec * 1000L)
                ScheduleKind.SCHEDULED -> {
                    // 固定日期时间：未来时刻排一次；已过则不排。
                    if (t.runAt > System.currentTimeMillis()) scheduleAt(t, t.runAt) else cancel(t.id)
                }
                ScheduleKind.CRON -> Cron.next(t.cron, System.currentTimeMillis())?.let { scheduleAt(t, it) }
                ScheduleKind.IMMEDIATE -> Unit
            }
        }
    }

    fun runNow(taskId: String) {
        // 先落统计再启动：脚本可能瞬间结束，watchdog 回写 lastStatus 后不能被这里的旧副本覆盖。
        val t = store.get(taskId)
        if (t == null) {
            top.fellow99.rpandroid.core.server.MfaLog.e("Script", "runNow: task not found id=$taskId (可能执行态泄漏，本 id 早已卡住)")
            TaskRuntime.end(taskId)
            return
        }
        val now = System.currentTimeMillis()
        val next = nextRunAfterManualRun(t, now)
        store.upsert(t.copy(lastRunAt = now, runCount = t.runCount + 1, nextRunAt = next))
        ScriptService.clearStopFlag(ctx, taskId)
        val started = runCatching { ScriptService.runTask(ctx, taskId) }.onFailure {
            top.fellow99.rpandroid.core.server.MfaLog.e("Script", "runNow: startScriptService FAILED task=$taskId err=${it.message}")
            // 启动失败必须回收执行态，否则 UI 会永久显示「正在执行」而脚本从未运行。
            TaskRuntime.end(taskId)
        }
        top.fellow99.rpandroid.core.server.MfaLog.i("Script", "runNow: task=$taskId scriptLen=${t.script.length} kind=${t.kind} startServiceOk=${started.isSuccess} next=$next")
        // next>0：保留/续排计划（SCHEDULED 的固定时间仍在未来时必须保留，否则会双跑）。
        if (next > 0) scheduleAt(store.get(taskId) ?: t, next)
    }

    /**
     * 启动/重新启用调度（用户视角的「启动」）：启用该任务并按执行方式排好闹钟，**不立即执行**。
     * @return 实际的下次执行时刻（0 = 未排上，例如固定时间已过或间隔非法）
     */
    fun arm(taskId: String): Long {
        val t = store.get(taskId) ?: return 0
        if (!t.enabled || t.nextRunAt <= 0) store.upsert(t.copy(enabled = true, nextRunAt = 0))
        rescheduleAll()
        return store.get(taskId)?.nextRunAt ?: 0
    }

    /**
     * 闹钟到点：记录运行统计 → 占用全局执行权（互斥）→ 交给 :node 执行 → 续排下一次。
     *
     * 修复：历史实现（AlarmReceiver 直接 runTask）不经 TaskRuntime/runNow，导致定时触发时
     * `runCount/lastRunAt` 不增长、`runningTaskId` 不可观、`lastStatus` 不回写。
     */
    fun onAlarm(taskId: String) {
        val t = store.get(taskId)
        top.fellow99.rpandroid.core.server.MfaLog.i("Script", "onAlarm fired task=$taskId exists=${t != null}")
        if (t == null) {
            cancel(taskId)
            return
        }
        if (!t.enabled) {
            cancel(taskId)
            top.fellow99.rpandroid.core.server.MfaLog.w("Script", "onAlarm: task disabled, cancelled alarm task=$taskId")
            return
        }
        val now = System.currentTimeMillis()
        if (TaskRuntime.begin(taskId)) {
            top.fellow99.rpandroid.core.server.MfaLog.i("Script", "onAlarm begin ok task=$taskId")
            store.upsert(t.copy(lastRunAt = now, runCount = t.runCount + 1))
            ScriptService.clearStopFlag(ctx, taskId)
            // 后台启前台服务在部分 ROM 可能抛 ForegroundServiceStartNotAllowedException；
            // BroadcastReceiver 里不能让异常逸出，否则会直接崩进程。
            runCatching { ScriptService.runTask(ctx, taskId) }.onFailure {
                top.fellow99.rpandroid.core.server.MfaLog.e("Script", "scheduled run start failed: ${it.message}")
                TaskRuntime.end(taskId)
            }
        } else {
            top.fellow99.rpandroid.core.server.MfaLog.w("Script", "scheduled run skipped (busy) task=$taskId")
        }
        rescheduleAll()
    }

    /**
     * 停止某任务：取消已排闹钟 + 写停止信号中止 :node 当前执行（尽力而为）+ 停用该任务。
     *
     * 必须同时置 `enabled=false`：否则下一次 [rescheduleAll]（App 启动、保存任一任务、开机恢复）
     * 会把这个仍处于启用态的任务重新排上，出现“停止后到点又执行”。
     */
    fun stop(taskId: String) {
        cancel(taskId)
        ScriptService.requestStop(ctx, taskId)
        store.get(taskId)?.let { if (it.enabled) store.upsert(it.copy(enabled = false, nextRunAt = 0)) }
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
