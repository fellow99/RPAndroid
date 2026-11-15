package top.fellow99.rpauto.core.script

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 调度方式。IMMEDIATE=立即；INTERVAL=间隔秒；SCHEDULED=固定日期时间；CRON=保留（UI 暂隐藏）。 */
enum class ScheduleKind { IMMEDIATE, INTERVAL, SCHEDULED, CRON }

/**
 * [ScriptTask] —— 一个**自动化服务**任务（符号名沿用历史 ScriptTask）。
 * @param script 自动化逻辑的 JS 脚本内容（可调用宿主能力 host.invoke('screen.tap', {...}))
 * @param packageName 目标 App（可选；用于任务前自动承载）
 * @param intervalSec kind=INTERVAL 时的间隔（秒）
 * @param runAt kind=SCHEDULED 时的执行时刻（epoch 毫秒）
 */
data class ScriptTask(
    val id: String,
    val name: String,
    val packageName: String? = null,
    val script: String,
    val enabled: Boolean = true,
    val kind: ScheduleKind = ScheduleKind.IMMEDIATE,
    val intervalSec: Long = 0,
    val runAt: Long = 0,
    val cron: String = "",
    val lastRunAt: Long = 0,
    val nextRunAt: Long = 0,
    val runCount: Int = 0,
    val lastStatus: String = "",
) {
    fun toJson(): String = JSONObject().apply {
        put("id", id); put("name", name); put("packageName", packageName)
        put("script", script); put("enabled", enabled); put("kind", kind.name)
        put("intervalSec", intervalSec); put("runAt", runAt); put("cron", cron)
        put("lastRunAt", lastRunAt); put("nextRunAt", nextRunAt)
        put("runCount", runCount); put("lastStatus", lastStatus)
    }.toString()

    companion object {
        fun from(o: JSONObject) = ScriptTask(
            id = o.optString("id"),
            name = o.optString("name"),
            // 注意 org.json 的坑：optString 对 JSON null 返回字面量 "null"。客户端/脚本把
            // packageName 序列化成 null 再 upsert 回来时，会被当成真实包名（UI 显示 “null · 定时 …”）。
            packageName = o.optString("packageName").takeIf { it.isNotEmpty() && it != "null" },
            script = o.optString("script"),
            enabled = o.optBoolean("enabled", true),
            kind = runCatching { ScheduleKind.valueOf(o.optString("kind", "IMMEDIATE")) }.getOrDefault(ScheduleKind.IMMEDIATE),
            // 单位=秒；兼容旧数据 intervalMs（毫秒 → 秒），供历史任务迁移。
            intervalSec = if (o.has("intervalSec")) o.optLong("intervalSec")
            else {
                val legacy = o.optLong("intervalMs", 0)
                if (legacy > 0) (legacy / 1000).coerceAtLeast(1) else 0
            },
            runAt = o.optLong("runAt"),
            cron = o.optString("cron"),
            lastRunAt = o.optLong("lastRunAt"),
            nextRunAt = o.optLong("nextRunAt"),
            runCount = o.optInt("runCount"),
            lastStatus = o.optString("lastStatus"),
        )
    }
}

/**
 * 手动「立即执行一次」之后的下一次计划时刻（0 = 不再排）。
 *
 * 关键：`SCHEDULED`（固定日期时间）的 runAt **仍在未来时必须保留**。历史实现把手动执行后的
 * nextRunAt 一律清零，但 AlarmManager 里那支闹钟仍在——结果任务既“马上执行一次”，又在到点时
 * 再执行一次（双跑），而 UI 上又看不到待执行时刻。
 */
fun nextRunAfterManualRun(task: ScriptTask, now: Long): Long = when {
    !task.enabled -> 0L
    task.kind == ScheduleKind.INTERVAL -> if (task.intervalSec > 0) now + task.intervalSec * 1000L else 0L
    task.kind == ScheduleKind.SCHEDULED -> if (task.runAt > now) task.runAt else 0L
    task.kind == ScheduleKind.CRON -> Cron.next(task.cron, now) ?: 0L
    else -> 0L
}

/**
 * [TaskStore] —— 任务持久化（单个 JSON 文件，位于 app filesDir，可跨进程读取）。
 *
 * 锁是**进程级共享**（companion）：调度器、REST/MCP、UI、watchdog 各自 new 一个实例，
 * 用实例锁会让「读-改-写」互相覆盖（实测 runCount 恒偏小、lastStatus 偶尔被旧副本抹掉）。
 */
class TaskStore(private val file: File) {

    fun all(): List<ScriptTask> = synchronized(LOCK) { read().toList() }

    fun get(id: String): ScriptTask? = synchronized(LOCK) { read().firstOrNull { it.id == id } }

    fun upsert(task: ScriptTask) = synchronized(LOCK) {
        val list = read().toMutableList()
        val i = list.indexOfFirst { it.id == task.id }
        if (i >= 0) list[i] = task else list.add(task)
        write(list)
    }

    fun delete(id: String) = synchronized(LOCK) {
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

    private companion object {
        /** 跨实例共享锁：同进程内所有 TaskStore 的读-改-写必须串行。 */
        val LOCK = Any()
    }
}
