package com.managedfloating.core.server

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

    companion object {
        const val DEFAULT_PORT = 8099
        private const val KEY_PORT = "port"
        private const val KEY_TOKEN = "auth_token"
        private const val KEY_CONNECT_PORT = "connect_port"

        /** 请求头名。 */
        const val HEADER = "X-Auth-Token"
    }
}
