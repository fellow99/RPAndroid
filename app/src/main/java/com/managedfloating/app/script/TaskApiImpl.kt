package com.managedfloating.app.script

import android.content.Context
import com.managedfloating.core.script.ScriptTask
import com.managedfloating.core.script.TaskStore
import com.managedfloating.core.server.TaskApi
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
        store.upsert(t)
        scheduler.rescheduleAll()
        if (t.kind == com.managedfloating.core.script.ScheduleKind.IMMEDIATE && t.enabled) {
            // 立即任务：创建后即执行一次
            ScriptService.runTask(ctx, t.id)
        }
        true
    }.getOrDefault(false)

    override fun delete(id: String): Boolean = runCatching { store.delete(id); true }.getOrDefault(false)

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
        if (!TaskRuntime.begin(id)) return false
        val t = store.get(id)
        if (t == null) {
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
