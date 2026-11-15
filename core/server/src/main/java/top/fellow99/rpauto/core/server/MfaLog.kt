package top.fellow99.rpauto.core.server

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * [MfaLog] —— 统一日志通道。
 *
 * - 转发到 logcat（tag 统一 `MFA`，便于 `adb logcat -s MFA`）；
 * - 同时保留最近 [CAPACITY] 条内存环形缓冲，供配置 UI / REST `/logs` 排查，无需连 adb。
 *
 * 纯 JDK + android.util.Log，可在任意模块调用（经 core:server 依赖）。
 */
object MfaLog {

    data class Entry(val time: String, val level: String, val tag: String, val msg: String)

    private const val CAPACITY = 500
    private val buffer = ConcurrentLinkedDeque<Entry>()
    private val ts = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    fun d(tag: String, msg: String) = add("D", tag, msg)
    fun i(tag: String, msg: String) = add("I", tag, msg)
    fun w(tag: String, msg: String, t: Throwable? = null) =
        add("W", tag, msg + (t?.let { " :: ${it.message}" } ?: ""))
    fun e(tag: String, msg: String, t: Throwable? = null) =
        add("E", tag, msg + (t?.let { " :: ${it}" } ?: ""))

    private fun add(level: String, tag: String, msg: String) {
        val entry = Entry(ts.format(Date()), level, tag, msg)
        buffer.addLast(entry)
        while (buffer.size > CAPACITY) buffer.pollFirst()
        when (level) {
            "E" -> Log.e("MFA/$tag", msg)
            "W" -> Log.w("MFA/$tag", msg)
            "D" -> Log.d("MFA/$tag", msg)
            else -> Log.i("MFA/$tag", msg)
        }
    }

    /** 返回最近日志（可选按级别过滤），最新在后。 */
    fun snapshot(level: String? = null): List<Entry> =
        if (level == null) buffer.toList() else buffer.filter { it.level == level }

    fun clear() = buffer.clear()
}
