package org.fellow99.RPAndroid.core.shell.adb

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/**
 * [AdbServiceFinder] 组合器：按顺序问多个来源，把结果合并去重。
 *
 * 无线调试连接端口在各版本/各 ROM 上来源不一：mDNS 在部分系统被拦、`/proc/net/tcp` 在 Android 10+
 * 对 App 不可读、`service.adb.tls.port` 常为空。单个来源都不可靠，因此按「便宜的在前」依次尝试，
 * 任一来源给出结果即可；总耗时受 [timeoutMs] 约束，避免某来源卡住拖慢连接。
 */
class CompositeServiceFinder(
    private val finders: List<AdbServiceFinder>,
    private val timeoutMs: Long = 4_000,
) : AdbServiceFinder {

    override suspend fun findPorts(serviceType: String, timeoutMs: Long): List<Int> {
        val out = LinkedHashSet<Int>()
        val deadline = System.currentTimeMillis() + minOf(timeoutMs, this.timeoutMs)
        for (f in finders) {
            val left = deadline - System.currentTimeMillis()
            if (left <= 0) break
            val got = try {
                withTimeoutOrNull(left) { f.findPorts(serviceType, left) } ?: emptyList()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyList()
            }
            out += got
            if (out.isNotEmpty()) break
        }
        return out.toList()
    }
}
