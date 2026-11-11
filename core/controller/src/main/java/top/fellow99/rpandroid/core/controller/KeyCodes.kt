package top.fellow99.rpandroid.core.controller

/**
 * [KeyCodes] —— 按键名 → KeyEvent keycode 映射（供 MCP `screen_key` 与脚本 `screen.key` 共用）。
 *
 * 只收录自动化常用键；调用方也可直接传数值 code（优先级更高）。
 */
object KeyCodes {

    private val MAP: Map<String, Int> = mapOf(
        "back" to 4,
        "home" to 3,
        "enter" to 66,
        "del" to 67,
        "delete" to 67,
        "forward_del" to 112,
        "paste" to 279,
        "copy" to 278,
        "cut" to 277,
        "tab" to 61,
        "esc" to 111,
        "escape" to 111,
        "space" to 62,
        "up" to 19,
        "down" to 20,
        "left" to 21,
        "right" to 22,
        "volume_up" to 24,
        "volume_down" to 25,
        "power" to 26,
        "menu" to 82,
        "search" to 84,
        "app_switch" to 187,
        "move_home" to 122,
        "wakeup" to 224,
        "sleep" to 223,
        "clear" to 28,
    )

    /** 按名称取 keycode；未知名称返回 0。 */
    fun byName(name: String): Int = MAP[name.trim().lowercase()] ?: 0

    /** 可用名称清单（供工具描述/文档生成）。 */
    fun names(): List<String> = MAP.keys.sorted()
}
