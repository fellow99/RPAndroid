package org.fellow99.RPAndroid.core.script

/**
 * [Cron] —— 最小 cron 解析，支持两种字段数：
 *  - 5 字段：`分 时 日 月 周`（最小粒度 1 分钟）
 *  - 6 字段：`秒 分 时 日 月 周`（最小粒度 1 秒，用于“每次 00 秒”这类需求）
 * 每个字段支持 `*`、数字、`a-b`、`a,b`、步长（如 `0-59/5`）。返回 fromMs 之后的下一次触发时间（毫秒）；
 * 解析失败返回 null。
 */
object Cron {

    fun next(cron: String, fromMs: Long): Long? {
        val f = cron.trim().split(Regex("\\s+"))
        // 只接受 5 字段（分 时 日 月 周）或 6 字段（秒 分 时 日 月 周）。
        if (f.size != 5 && f.size != 6) return null
        val hasSeconds = f.size == 6
        val off = if (hasSeconds) 1 else 0
        if (f.size < 5 + off) return null
        val seconds = if (hasSeconds) parse(f[0], 0, 59) ?: return null else null
        val minutes = parse(f[off], 0, 59) ?: return null
        val hours = parse(f[off + 1], 0, 23) ?: return null
        val doms = parse(f[off + 2], 1, 31) ?: return null
        val months = parse(f[off + 3], 1, 12) ?: return null
        val dows = parseDow(f[off + 4]) ?: return null

        // 5 字段：从下一分钟边界开始匹配；6 字段：从当前分钟开始（同一分钟内可能还有匹配的秒）。
        val cal = java.util.Calendar.getInstance().apply {
            timeInMillis = fromMs
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
            if (!hasSeconds) add(java.util.Calendar.MINUTE, 1)
        }
        // 最多向后找 366 天（按分钟推进，秒在命中分钟内再选）。
        repeat(366 * 24 * 60) {
            val min = cal.get(java.util.Calendar.MINUTE)
            val hour = cal.get(java.util.Calendar.HOUR_OF_DAY)
            val dom = cal.get(java.util.Calendar.DAY_OF_MONTH)
            val mon = cal.get(java.util.Calendar.MONTH) + 1
            val dow = cal.get(java.util.Calendar.DAY_OF_WEEK) // 1=Sunday
            if (minutes.contains(min) && hours.contains(hour) && months.contains(mon) &&
                doms.contains(dom) && dows.contains(dow)
            ) {
                if (!hasSeconds) return cal.timeInMillis
                val base = cal.timeInMillis
                for (s in 0..59) {
                    if (seconds!!.contains(s)) {
                        val t = base + s * 1000L
                        if (t > fromMs) return t
                    }
                }
            }
            cal.add(java.util.Calendar.MINUTE, 1)
        }
        return null
    }

    private fun parse(field: String, lo: Int, hi: Int): Set<Int>? {
        val out = HashSet<Int>()
        for (part in field.split(",")) {
            val stepParts = part.split("/")
            val step = if (stepParts.size == 2) stepParts[1].toIntOrNull() ?: return null else 1
            val range = stepParts[0]
            val (a, b) = when {
                range == "*" || range == "?" -> lo to hi
                range.contains("-") -> {
                    val ab = range.split("-")
                    (ab.getOrNull(0)?.toIntOrNull() ?: return null) to (ab.getOrNull(1)?.toIntOrNull() ?: return null)
                }
                else -> {
                    val v = range.toIntOrNull() ?: return null
                    v to if (step > 1) hi else v
                }
            }
            if (a < lo || b > hi || a > b) return null
            var v = a
            while (v <= b) { out += v; v += step }
        }
        return out.takeIf { it.isNotEmpty() }
    }

    /** 周日=0 也接受 7=周日。 */
    private fun parseDow(field: String): Set<Int>? {
        val raw = parse(field.replace("7", "0"), 0, 6) ?: return null
        // 转成 Calendar 的 1..7（周日=1）
        return raw.map { if (it == 0) 1 else it + 1 }.toSet()
    }
}
