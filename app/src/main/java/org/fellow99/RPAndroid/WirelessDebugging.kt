package org.fellow99.RPAndroid

import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log

/**
 * 无线调试自动开启（102-quick-start FR-102-4）。
 *
 * 背景：Android 在**重启**或**切换 Wi-Fi**后会关闭无线调试（`adb_wifi_enabled` 变 0），
 * 导致下次无法连接本机 adbd，而"经已配对 shell 会话补写"会陷入"没会话就连不上、连不上就写不了"的死循环。
 *
 * 解法：用户用电脑**一次性**授权 `WRITE_SECURE_SETTINGS`（不弹框、不需 root）：
 * ```
 * adb shell pm grant org.fellow99.RPAndroid android.permission.WRITE_SECURE_SETTINGS
 * ```
 * 授权后 App 可在启动/连接前直接把该开关写回 1，系统随即重启 adbd 无线调试服务。
 * 未授权或写失败时静默降级（返回 false），由界面引导用户手动打开。
 */
object WirelessDebugging {
    /** 未公开的系统开关名（AOSP `Settings.Global`）。 */
    const val ADB_WIFI_ENABLED = "adb_wifi_enabled"

    private const val TAG = "MFA/WifiDebug"

    /** 自动开启的结果（给界面说一句怎么说）。 */
    enum class Result {
        /** 已开或写成功。 */
        ENABLED,
        /** 没连 Wi-Fi：系统会马上拨回 0，写了也白写。 */
        NO_WIFI,
        /** 没拿到 WRITE_SECURE_SETTINGS（需用电脑一次性授权）。 */
        NO_PERMISSION,
        /** 写了但被系统拨回（如当前网络未被允许无线调试）。 */
        REFUSED,
    }

    /** 当前是否连着 Wi-Fi。无线调试只在 Wi-Fi 下可用。 */
    fun hasWifi(context: Context): Boolean = runCatching {
        val cm = context.getSystemService(android.net.ConnectivityManager::class.java)
        val n = cm?.activeNetwork ?: return@runCatching false
        val caps = cm.getNetworkCapabilities(n) ?: return@runCatching false
        caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)
    }.getOrDefault(false)

    /** 当前是否持有写入系统设置的权限（一次性授权后为 true）。 */
    fun canWrite(context: Context): Boolean = runCatching {
        context.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") ==
            PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    /** 读当前无线调试开关；读不到按关着算。 */
    fun isEnabled(context: Context): Boolean = runCatching {
        Settings.Global.getInt(context.contentResolver, ADB_WIFI_ENABLED, 0) == 1
    }.getOrDefault(false)

    /**
     * 尽力把无线调试打开，并给出结果。
     * 没 Wi-Fi 不写（系统会立刻拨回 0）；无权限不写（由界面引导一次性授权）。
     */
    fun ensureEnabled(context: Context): Result {
        if (isEnabled(context)) return Result.ENABLED
        if (!hasWifi(context)) {
            Log.i(TAG, "no Wi-Fi; skip auto-enable wireless debugging")
            return Result.NO_WIFI
        }
        if (!canWrite(context)) {
            Log.i(TAG, "no WRITE_SECURE_SETTINGS; cannot auto-enable wireless debugging")
            return Result.NO_PERMISSION
        }
        return runCatching {
            Settings.Global.putInt(context.contentResolver, ADB_WIFI_ENABLED, 1)
            // 给系统一点时间处理，再读回确认（部分 ROM 会立即拨回 0）。
            Thread.sleep(600)
            val ok = isEnabled(context)
            Log.i(TAG, "auto-enable wireless debugging ok=$ok")
            if (ok) Result.ENABLED else Result.REFUSED
        }.getOrElse {
            Log.w(TAG, "auto-enable wireless debugging failed", it)
            Result.REFUSED
        }
    }
}
