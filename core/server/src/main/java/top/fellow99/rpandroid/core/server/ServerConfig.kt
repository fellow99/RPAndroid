package top.fellow99.rpandroid.core.server

import android.content.Context

/**
 * [ServerConfig] —— 服务配置（token 鉴权、端口）。
 *
 * 持久化在 SharedPreferences：`auth_token` 非空时所有业务端点要求 `X-Auth-Token` 头。
 * 默认空 token（局域网便捷），建议设置后使用。
 */
class ServerConfig(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("mfa_server", Context.MODE_PRIVATE)

    var port: Int
        get() = prefs.getInt(KEY_PORT, DEFAULT_PORT)
        set(v) = prefs.edit().putInt(KEY_PORT, v).apply()

    var token: String
        get() = prefs.getString(KEY_TOKEN, "") ?: ""
        set(v) = prefs.edit().putString(KEY_TOKEN, v).apply()

    /** 上次成功连接的 adbd 连接端口（用于 App 重启后自动恢复会话）。 */
    var connectPort: Int
        get() = prefs.getInt(KEY_CONNECT_PORT, 0)
        set(v) = prefs.edit().putInt(KEY_CONNECT_PORT, v).apply()

    /** 虚拟屏宽度（默认 0 = 首次按当前屏幕初始化）。 */
    var vScreenWidth: Int
        get() = prefs.getInt(KEY_VW, 0)
        set(v) = prefs.edit().putInt(KEY_VW, v).apply()

    /** 虚拟屏高度。 */
    var vScreenHeight: Int
        get() = prefs.getInt(KEY_VH, 0)
        set(v) = prefs.edit().putInt(KEY_VH, v).apply()

    /** 虚拟屏 dpi。 */
    var vScreenDpi: Int
        get() = prefs.getInt(KEY_VD, 0)
        set(v) = prefs.edit().putInt(KEY_VD, v).apply()

    /**
     * 当前承载模式（010-display-mode）：`"A"` 虚拟屏承载（默认）/ `"B"` 主屏承载。
     * 首次启动默认 A；持久化以便重启后恢复上次模式。
     */
    var displayMode: String
        get() = prefs.getString(KEY_MODE, "A") ?: "A"
        set(v) = prefs.edit().putString(KEY_MODE, v).apply()

    /**
     * 是否已完成首次配对（102-quick-start）。
     * 与 [connectPort] 一起决定首次使用向导是否显示：`!paired && connectPort == 0` 时展示向导。
     */
    var paired: Boolean
        get() = prefs.getBoolean(KEY_PAIRED, false)
        set(v) = prefs.edit().putBoolean(KEY_PAIRED, v).apply()

    companion object {
        const val DEFAULT_PORT = 8099
        private const val KEY_PORT = "port"
        private const val KEY_TOKEN = "auth_token"
        private const val KEY_CONNECT_PORT = "connect_port"
        private const val KEY_VW = "v_screen_w"
        private const val KEY_VH = "v_screen_h"
        private const val KEY_VD = "v_screen_dpi"
        private const val KEY_PAIRED = "paired"
        private const val KEY_MODE = "display_mode"

        /** 请求头名。 */
        const val HEADER = "X-Auth-Token"
    }
}
