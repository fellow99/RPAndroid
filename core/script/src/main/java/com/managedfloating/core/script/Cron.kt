package com.managedfloating.core.script

/**
 * [Cron] —— 最小 cron 解析（5 字段：分 时 日 月 周），支持 `*`、数字、`a-b`、`a,b`、`*&#47;n`。
 * 返回 now 之后的下一次触发时间（毫秒）。解析失败返回 null。
 */
object Cron {

    fun next(cron: String, fromMs: Long): Long? {
        val f = cron.trim().split(Regex("\\s+"))
        if (f.size < 5) return null
        val minutes = parse(f[0], 0, 59) ?: return null
        val hours = parse(f[1], 0, 23) ?: return null
        val doms = parse(f[2], 1, 31) ?: return null
        val months = parse(f[3], 1, 12) ?: return null
        val dows = parseDow(f[4]) ?: return null

        val cal = java.util.Calendar.getInstance().apply {
            timeInMillis = fromMs
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
            add(java.util.Calendar.MINUTE, 1)
        }
        // 最多向后找 366 天
        repeat(366 * 24 * 60) {
            val min = cal.get(java.util.Calendar.MINUTE)
            val hour = cal.get(java.util.Calendar.HOUR_OF_DAY)
            val dom = cal.get(java.util.Calendar.DAY_OF_MONTH)
            val mon = cal.get(java.util.Calendar.MONTH) + 1
            val dow = cal.get(java.util.Calendar.DAY_OF_WEEK) // 1=Sunday
            if (minutes.contains(min) && hours.contains(hour) && months.contains(mon) &&
                doms.contains(dom) && dows.contains(dow)
            ) {
                return cal.timeInMillis
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
