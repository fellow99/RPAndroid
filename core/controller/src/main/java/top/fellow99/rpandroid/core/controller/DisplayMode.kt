package top.fellow99.rpandroid.core.controller

/**
 * [DisplayMode] —— 承载模式语义（010-display-mode）。
 *
 * - [VIRTUAL]（对外序列化 `"A"`）：虚拟屏承载（默认）。被托管 App 在私有虚拟屏（用户不可见），
 *   工作链路为「截帧 + 坐标注入（`input -d <vd>`）」，`ui_read` 在部分 ROM 不可读。
 * - [MAIN]（对外序列化 `"B"`）：主屏承载。被托管 App 前置用户主屏，MediaProjection 捕获主屏帧，
 *   无障碍读树/手势可用（结构化读屏）。
 *
 * 任意时刻仅存在一个全局承载模式；首启默认 A。
 */
enum class DisplayMode(val wire: String) {
    VIRTUAL("A"),
    MAIN("B");

    companion object {
        fun fromWire(v: String?): DisplayMode = when (v) {
            "B", "b" -> MAIN
            else -> VIRTUAL
        }
    }
}

/**
 * [ProjectionAuth] —— MediaProjection 授权态（Mode B 前置）。
 *
 * - [NONE]：未经授权（可发起授权）。
 * - [GRANTED]：已授权（投影帧源就绪，Mode B 生效中）。
 * - [FAILED]：授权被拒或投影 token 失效（应回滚 Mode A）。
 */
enum class ProjectionAuth { NONE, GRANTED, FAILED }
