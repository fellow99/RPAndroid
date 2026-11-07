package com.managedfloating.core.script

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 调度方式。 */
enum class ScheduleKind { IMMEDIATE, INTERVAL, CRON }

/**
 * [ScriptTask] —— 一个脚本任务。
 * @param script JS 脚本内容（可调用宿主能力 host.invoke('screen.tap', {...})）
 * @param packageName 目标 App（可选；用于任务前自动承载）
 */
data class ScriptTask(
    val id: String,
    val name: String,
    val packageName: String? = null,
    val script: String,
    val enabled: Boolean = true,
    val kind: ScheduleKind = ScheduleKind.IMMEDIATE,
    val intervalMs: Long = 0,
    val cron: String = "",
    val lastRunAt: Long = 0,
    val nextRunAt: Long = 0,
    val runCount: Int = 0,
    val lastStatus: String = "",
) {
    fun toJson(): String = JSONObject().apply {
        put("id", id); put("name", name); put("packageName", packageName)
        put("script", script); put("enabled", enabled); put("kind", kind.name)
        put("intervalMs", intervalMs); put("cron", cron)
        put("lastRunAt", lastRunAt); put("nextRunAt", nextRunAt)
        put("runCount", runCount); put("lastStatus", lastStatus)
    }.toString()

    companion object {
        fun from(o: JSONObject) = ScriptTask(
            id = o.optString("id"),
            name = o.optString("name"),
            packageName = o.optString("packageName").takeIf { it.isNotEmpty() },
            script = o.optString("script"),
            enabled = o.optBoolean("enabled", true),
            kind = runCatching { ScheduleKind.valueOf(o.optString("kind", "IMMEDIATE")) }.getOrDefault(ScheduleKind.IMMEDIATE),
            intervalMs = o.optLong("intervalMs"),
            cron = o.optString("cron"),
            lastRunAt = o.optLong("lastRunAt"),
            nextRunAt = o.optLong("nextRunAt"),
            runCount = o.optInt("runCount"),
            lastStatus = o.optString("lastStatus"),
        )
    }
}

/**
 * [TaskStore] —— 任务持久化（单个 JSON 文件，位于 app filesDir，可跨进程读取）。
 */
class TaskStore(private val file: File) {

    @Synchronized
    fun all(): List<ScriptTask> = read().toList()

    @Synchronized
    fun get(id: String): ScriptTask? = read().firstOrNull { it.id == id }

    @Synchronized
    fun upsert(task: ScriptTask) {
        val list = read().toMutableList()
        val i = list.indexOfFirst { it.id == task.id }
        if (i >= 0) list[i] = task else list.add(task)
        write(list)
    }

    @Synchronized
    fun delete(id: String) {
        write(read().filterNot { it.id == id })
    }

    private fun read(): MutableList<ScriptTask> {
        if (!file.exists()) return mutableListOf()
        return runCatching {
            val arr = JSONArray(file.readText())
            MutableList(arr.length()) { ScriptTask.from(arr.getJSONObject(it)) }
        }.getOrDefault(mutableListOf())
    }

    private fun write(list: List<ScriptTask>) {
        file.parentFile?.mkdirs()
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject(it.toJson())) }
        file.writeText(arr.toString())
    }
}
