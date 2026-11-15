package top.fellow99.rpauto.ui

import android.content.Context
import org.json.JSONArray
import java.util.LinkedHashSet

/**
 * [PinnedAppsStore] —— `应用`页「置顶」的有序持久化存储。
 *
 * 用独立 SharedPreferences 存一个 **有序** 包名列表（JSON 数组，保插入顺序），
 * 以 packageName 标识（跨应用重命名稳定）。置顶 0..n 个。
 */
class PinnedAppsStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("mfa_app_pins", Context.MODE_PRIVATE)
    private val key = "pinned_apps"

    /** 读取置顶包名（保持置顶顺序）。 */
    fun getAll(): LinkedHashSet<String> {
        val raw = prefs.getString(key, null) ?: return LinkedHashSet()
        return runCatching {
            val arr = JSONArray(raw)
            val set = LinkedHashSet<String>(arr.length())
            for (i in 0 until arr.length()) set.add(arr.getString(i))
            set
        }.getOrDefault(LinkedHashSet())
    }

    /** 置顶（已存在则移到末尾），返回最新置顶集合。 */
    fun add(pkg: String): LinkedHashSet<String> {
        val set = getAll()
        set.remove(pkg)
        set.add(pkg)
        save(set)
        return set
    }

    /** 取消置顶，返回最新置顶集合。 */
    fun remove(pkg: String): LinkedHashSet<String> {
        val set = getAll()
        set.remove(pkg)
        save(set)
        return set
    }

    private fun save(set: LinkedHashSet<String>) {
        val arr = JSONArray()
        set.forEach { arr.put(it) }
        prefs.edit().putString(key, arr.toString()).apply()
    }
}
