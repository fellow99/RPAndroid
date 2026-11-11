package top.fellow99.rpandroid.core.shell

import android.view.Surface

/**
 * [MfaHandoff] —— App ↔ 免 root 助手（shell UID）之间的 Surface 交接。
 *
 * 背景（007-webrtc）：为让虚拟屏帧直接落在 App 进程（FrameHub），助手需要用 **App 进程的 Surface** 建虚拟屏。
 * Surface 含 Binder，无法经 Parcel.marshall 塞进 socket/文件（RuntimeException），只能走真正的 Binder 事务。
 * 本方案采用 ContentProvider 交接通道：助手（shell）经 App 导出的 ContentProvider `call()` 拉取 Surface——
 * 这是一次跨 UID 的 Binder 事务，`Bundle` 允许携带 Parcelable Surface；App 在进程内持有"待取 Surface"，
 * 助手取到即用它建虚拟屏（帧直达 App），取不到则回退旧路径（助手自持 ImageReader + 文件 IPC）。
 *
 * 说明：ContentProvider 在 App 进程实例化，holder 状态（[prepare] 后的待取 Surface）天然属于 App 进程；
 * 助手（独立 shell 进程）只使用常量与协议。
 */
object MfaHandoff {

    const val AUTHORITY = "top.fellow99.rpandroid.helper"
    val URI: android.net.Uri = android.net.Uri.parse("content://$AUTHORITY")

    /** ContentProvider.call 方法名：助手取 Surface。 */
    const val METHOD_TAKE_SURFACE = "takeSurface"
    const val KEY_SURFACE = "surface"
    const val KEY_NONCE = "nonce"
    const val KEY_RESULT = "result"
    const val RESULT_OK = "ok"
    const val RESULT_EMPTY = "no-pending-surface"
    const val RESULT_BAD_UID = "uid-denied"

    /** 只接受 shell 身份的助手（request 由 adb/shell 拉起）。 */
    const val EXPECTED_CALLER_UID = 2000 // Process.SHELL_UID

    @Volatile
    private var pendingSurface: Surface? = null
    @Volatile
    private var pendingNonce: String? = null

    /** App 侧：准备待交接的 Surface（一次有效），返回随机 nonce 供助手携带。 */
    fun prepare(surface: Surface): String {
        val nonce = java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 16)
        pendingSurface = surface
        pendingNonce = nonce
        return nonce
    }

    /** Provider 侧：校验后取出 pending Surface（消费一次）。 */
    fun take(nonce: String?): Surface? {
        if (nonce == null || nonce != pendingNonce) return null
        val s = pendingSurface
        pendingSurface = null
        pendingNonce = null
        return s
    }
}
