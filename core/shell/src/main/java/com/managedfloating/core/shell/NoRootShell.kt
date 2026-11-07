package com.managedfloating.core.shell

import android.content.Context
import com.managedfloating.core.shell.adb.AndroidAdb
import com.managedfloating.core.shell.adb.AdbConnection
import com.managedfloating.core.shell.adb.AdbConnectPort
import com.managedfloating.core.shell.adb.AdbPairing
import com.managedfloating.core.shell.adb.NsdAdbServiceFinder
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

    /** 连接本机 adbd（无线调试）。[connectPort] 为 null 时经 mDNS + 系统属性自动探测。 */
    suspend fun connect(connectPort: Int? = null): Boolean = withContext(Dispatchers.IO) {
        try {
            connected?.close()
            val tls = AndroidAdb.tls(identities.forConnect())
            if (connectPort != null) {
                connected = AdbConnection.open(LOCAL, connectPort, tls, 10_000)
            } else {
                val acp = AdbConnectPort(
                    property = { ServiceProps.connectPort() },
                    finder = NsdAdbServiceFinder(context),
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

    /** 保持着的 pty 助手流（不关闭则 adbd 会话存活、助手进程存活）。 */
    @Volatile
    private var helperStream: com.managedfloating.core.shell.adb.AdbConnection.AdbStream? = null

    /**
     * 启动常驻 shell 助手：开一条 pty 流执行 [command]，**保持流不关**。
     * 本机（MIUI/Android 13）上 adbd 会话结束会杀掉该会话进程组，setsid 无法脱离；
     * 因此靠“App 进程持有该 pty 流”来维持助手会话存活。App 进程死了助手才退，属可接受。
     */
    suspend fun startHelper(command: String): String? = withContext(Dispatchers.IO) {
        try {
            helperStream?.close()
            val stream = connected?.open("shell:$command") ?: return@withContext null
            val out = StringBuilder()
            val deadline = System.currentTimeMillis() + 4_000
            while (System.currentTimeMillis() < deadline) {
                val chunk = stream.readOrTimeout(500) ?: break
                if (AdbConnection.isTimeout(chunk)) {
                    if (out.isNotEmpty()) break
                    continue
                }
                out.append(String(chunk, Charsets.UTF_8))
                if (out.contains("started")) break
            }
            helperStream = stream   // 保持不关
            out.toString()
        } catch (e: Exception) {
            lastError = e
            null
        }
    }

    fun stopHelper() {
        runCatching { helperStream?.close() }
        helperStream = null
    }

    /**
     * 经 adb 的 **pty shell** 服务运行命令（`shell:`）。
     * 与 [run]（`exec:`，会话结束即杀进程组）不同，pty 会话里用 setsid 脱离的子进程能存活，
     * 是拉起常驻 shell 助手（持有虚拟屏）的正确通道。
     */
    suspend fun runPty(command: String): String? = withContext(Dispatchers.IO) {
        try {
            val stream = connected?.open("shell:$command") ?: return@withContext null
            try {
                val out = StringBuilder()
                val deadline = System.currentTimeMillis() + 5_000
                while (System.currentTimeMillis() < deadline) {
                    val chunk = stream.readOrTimeout(500)
                    if (chunk == null) break
                    if (AdbConnection.isTimeout(chunk)) continue
                    out.append(String(chunk, Charsets.UTF_8))
                    if (out.contains("launched")) break
                }
                stream.close()
                out.toString()
            } catch (e: Exception) {
                lastError = e
                null
            }
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
