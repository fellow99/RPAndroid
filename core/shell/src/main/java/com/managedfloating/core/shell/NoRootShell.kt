package com.managedfloating.core.shell

import android.content.Context
import com.managedfloating.core.shell.adb.AdbConnection
import com.managedfloating.core.shell.adb.AdbConnectPort
import com.managedfloating.core.shell.adb.AdbPairing
import com.managedfloating.core.shell.adb.AndroidAdb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [NoRootShell] —— 免 root 自举（M1-2）：经无线调试回环连接本机 adbd，拉起 shell-UID 助手。
 *
 * 流程：
 *  1. 探测无线调试连接端口（[AdbConnectPort]：系统属性 service.adb.tls.port / 用户提供）。
 *  2. 若本应用钥匙未配对，先配对（[AdbPairing]：用户提供配对端口 + 6 位码）。
 *  3. [AdbConnection.open] 连接（用已配对钥匙做 TLS 认证）。
 *  4. [AdbConnection.exec] 运行 `app_process` 助手命令（shell UID 2000）。
 *
 * 成功后，后续所有建虚拟屏 / 承载 / 截帧 / 注入都由助手完成（core:shell 其余部分 / tools/vdisplay）。
 */
class NoRootShell(private val context: Context) {

    private val identities by lazy { AndroidAdb.identities(context) }

    @Volatile
    var connected: AdbConnection? = null; private set

    /** 配对：把本应用钥匙经 pairing 端口 + 6 位码授权给设备 adbd。 */
    suspend fun pair(pairHost: String, pairPort: Int, code: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val tls = AndroidAdb.tls(identities.forPairing())
            AdbPairing(tls).pair(pairHost, pairPort, code, 15_000)
            true
        } catch (e: Exception) {
            lastError = e
            false
        }
    }

    /** 连接本机 adbd（无线调试）。[connectPort] 为 null 时自动探测。 */
    suspend fun connect(connectPort: Int? = null): Boolean = withContext(Dispatchers.IO) {
        try {
            connected?.close()
            val tls = AndroidAdb.tls(identities.forConnect())
            if (connectPort != null) {
                connected = AdbConnection.open(LOCAL, connectPort, tls, 10_000)
            } else {
                val acp = AdbConnectPort(
                    property = { ServiceProps.connectPort() },
                    finder = AndroidAdbFinder,
                    cache = AndroidAdbPortCache,
                )
                acp.connect { host, port -> AdbConnection.open(host, port, tls, 10_000) }.also { connected = it }
            }
            true
        } catch (e: Exception) {
            lastError = e
            connected = null
            false
        }
    }

    /** 在助手连接上运行一条命令并返回输出（如拉起 app_process 助手）。 */
    suspend fun run(command: String): String? = withContext(Dispatchers.IO) {
        try {
            connected?.exec(command)?.readText()
        } catch (e: Exception) {
            lastError = e
            null
        }
    }

    @Volatile
    var lastError: Throwable? = null; private set

    companion object {
        const val LOCAL = "127.0.0.1"

        /** 读系统属性 service.adb.tls.port（无线调试连接端口）。 */
        object ServiceProps {
            fun connectPort(): Int? = runCatching {
                System.getProperty("service.adb.tls.port")?.toIntOrNull()
            }.getOrNull()
        }
    }
}

/** 简易端口缓存（内存）。免 root 场景下连接端口可在本进程内复用。 */
private object AndroidAdbPortCache : com.managedfloating.core.shell.adb.PortCache {
    @Volatile private var p: Int? = null
    override fun load(): Int? = p
    override fun save(port: Int) { p = port }
}

/** 简易服务发现：M1 先从系统属性 service.adb.tls.port 读连接端口；mDNS 可后续接入 NsdAdbServiceFinder。 */
private object AndroidAdbFinder : com.managedfloating.core.shell.adb.AdbServiceFinder {
    override suspend fun findPorts(serviceType: String, timeoutMs: Long): List<Int> {
        return listOfNotNull(runCatching { System.getProperty("service.adb.tls.port")?.toIntOrNull() }.getOrNull())
    }
}
