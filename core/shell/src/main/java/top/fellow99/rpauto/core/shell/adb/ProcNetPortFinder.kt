package top.fellow99.rpauto.core.shell.adb

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 从本机 `/proc/net/tcp`、`/proc/net/tcp6` 里找 **shell(uid 2000) 监听**的端口。
 *
 * 无线调试的 adbd（TLS 连接服务）以 shell 身份监听一个系统随机端口；本机 `service.adb.tls.port`
 * 常常为空，mDNS 在部分系统上也不可靠。直接读进程表最稳：筛 LISTEN + uid==2000 的端口，
 * 逐个交给调用方做 TLS 握手识别真正的 adbd（[AdbConnection] 会拒绝非 adbd 目标）。
 */
class ProcNetPortFinder(
    private val shellUid: Int = 2000,
) : AdbServiceFinder {

    override suspend fun findPorts(serviceType: String, timeoutMs: Long): List<Int> = withContext(Dispatchers.IO) {
        val ports = LinkedHashSet<Int>()
        for (path in listOf("/proc/net/tcp", "/proc/net/tcp6")) {
            runCatching {
                File(path).readLines().drop(1).forEach { line ->
                    val cols = line.trim().split(Regex("\\s+"))
                    if (cols.size < 8) return@forEach
                    // cols: sl local_address rem_address st ... uid ...
                    val st = cols[3]
                    val uid = cols[7].toIntOrNull() ?: return@forEach
                    if (st != "0A") return@forEach          // 0A = TCP_LISTEN
                    if (uid != shellUid) return@forEach
                    val local = cols[1]
                    val portHex = local.substringAfterLast(':')
                    portHex.toIntOrNull(16)?.let { if (it in 1..65535) ports += it }
                }
            }
        }
        ports.toList()
    }
}
