package top.fellow99.rpandroid.script

import android.content.Context
import top.fellow99.rpandroid.core.script.ScriptTask
import top.fellow99.rpandroid.core.script.TaskStore
import top.fellow99.rpandroid.core.server.MfaLog
import top.fellow99.rpandroid.core.server.TaskApi
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** [TaskApi] 的 app 实现：TaskStore + TaskScheduler + :node 执行器。 */
class TaskApiImpl(private val ctx: Context) : TaskApi {

    private val store = TaskStore(File(ctx.applicationInfo.dataDir, "files/tasks.json"))
    private val scheduler by lazy { TaskScheduler(ctx.applicationContext) }

    override fun listJson(): String {
        val arr = JSONArray()
        store.all().forEach { arr.put(JSONObject(it.toJson())) }
        return arr.toString()
    }

    override fun upsert(taskJson: String): Boolean = runCatching {
        val o = JSONObject(taskJson)
        if (!o.has("id") || o.optString("id").isEmpty()) {
            o.put("id", "t" + System.currentTimeMillis())
        }
        val t = ScriptTask.from(o)
        // 是否为新建：只有新建的「立即」任务才执行一次。
        // 历史实现每次 POST（改名字/改脚本/切启用开关）都重跑一下，是典型的“保存一下就自己动了”。
        val isNew = store.get(t.id) == null
        store.upsert(t)
        scheduler.rescheduleAll()
        if (isNew && t.kind == top.fellow99.rpandroid.core.script.ScheduleKind.IMMEDIATE && t.enabled) {
            // 立即任务：创建后即执行一次（走统一执行入口：互斥 + 统计 + watchdog 回写）。
            MfaLog.i("Script", "upsert: new IMMEDIATE task id=${t.id} name=开始自动执行")
            if (TaskRuntime.begin(t.id)) {
                MfaLog.i("Script", "upsert: auto-run begin ok id=${t.id}")
                runCatching { scheduler.runNow(t.id) }.onFailure { TaskRuntime.end(t.id) }
            } else {
                MfaLog.w("Script", "upsert: auto-run rejected (busy) id=${t.id} running=${TaskRuntime.runningTaskId}")
            }
        }
        true
    }.getOrDefault(false)

    override fun delete(id: String): Boolean = runCatching {
        // 删除前清理已排闹钟（stop 内部 cancel + 写停止信号，尽力而为）。
        runCatching { scheduler.stop(id) }
        store.delete(id)
        true
    }.getOrDefault(false)

    override fun modify(id: String, patchJson: String): Boolean = runCatching {
        val existing = store.get(id) ?: return@runCatching false
        val patch = JSONObject(patchJson)
        // 在现有任务上做部分字段覆盖，保留 id 与运行统计（runCount/lastRunAt/nextRunAt/lastStatus）。
        val merged = JSONObject(existing.toJson()).apply {
            for (key in patch.keys()) put(key, patch.get(key))
            put("id", id) // id 以路径为准，不可被 patch 改写
        }
        val updated = ScriptTask.from(merged)
        store.upsert(updated)
        scheduler.rescheduleAll()
        true
    }.getOrDefault(false)

    override fun run(id: String): Boolean {
        // 互斥：同一时刻仅一个自动化在执行（"同时只能一个"）；已有执行中则拒绝。
        if (!TaskRuntime.begin(id)) {
            MfaLog.w("Script", "run: rejected id=$id running=${TaskRuntime.runningTaskId}")
            return false
        }
        MfaLog.i("Script", "run: begin ok id=$id")
        val t = store.get(id)
        if (t == null) {
            MfaLog.e("Script", "run: task not found after begin id=$id")
            TaskRuntime.end(id)
            return false
        }
        return runCatching {
            scheduler.runNow(id)
            true
        }.getOrElse { TaskRuntime.end(id); false }
    }

    override fun stop(id: String): Boolean = runCatching {
        val wasRunning = TaskRuntime.runningTaskId == id
        scheduler.stop(id)
        // 停止是“尽力而为 + 立即反馈”：这里会立即释放运行态，watchdog 之后拿不到本次结果，
        // 所以直接写回取消状态，避免 UI/接口仍显示上一次的 ok。
        if (wasRunning) {
            store.get(id)?.let { store.upsert(it.copy(lastStatus = "cancelled")) }
        }
        TaskRuntime.end(id)
        true
    }.getOrDefault(false)

    override fun getJson(id: String): String? = runCatching { store.get(id)?.toJson() }.getOrNull()

    override fun waitRun(id: String, timeoutMs: Long): Boolean {
        if (timeoutMs <= 0) return TaskRuntime.runningTaskId != id
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (TaskRuntime.runningTaskId != id) return true
            Thread.sleep(100)
        }
        return TaskRuntime.runningTaskId != id
    }

    init {
        TaskRuntime.attach(ctx.applicationContext)
    }
}
