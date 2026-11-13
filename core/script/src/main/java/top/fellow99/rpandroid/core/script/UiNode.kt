package top.fellow99.rpandroid.core.script

/**
 * [UiNode] —— 与 Android 解耦的轻量界面树模型（纯 Kotlin，可在 JVM 离线单测）。
 *
 * 由 `app` 层 a11y 服务把 `AccessibilityNodeInfo` 构建一次；`012-ui-read` 的
 * `dumpUi/readUi/queryUi/getUi` 与 `013` 的 `XPathEvaluator` 均在此树上操作。
 * 字段语义与 `AccessibilityNodeInfo` 对应，但**不 import 任何 Android 类**。
 */
data class UiNode(
    /** 展示文本：优先 `text`，其次 `contentDescription`（与 a11y 采集一致）。 */
    val text: String = "",
    /** 完整类名，如 `android.widget.TextView`。 */
    val className: String = "",
    /** 所属包名。 */
    val packageName: String = "",
    val clickable: Boolean = false,
    val editable: Boolean = false,
    val scrollable: Boolean = false,
    /** `viewIdResourceName`，如 `com.android.settings:id/title`；无则空串。 */
    val resourceId: String = "",
    /** 屏幕坐标 [l, t, r, b]；未知/无界时为 null。 */
    val bounds: IntArray? = null,
    /** 在父节点所有子节点中的序号（a11y 语义），无父/未知时为 -1。 */
    val indexInParent: Int = -1,
    /** 所属 displayId（-1 未知）。 */
    val displayId: Int = -1,
    /** 子节点（层级树用）。 */
    val children: List<UiNode> = emptyList(),
) {
    /** className 简写（最后一段），如 `TextView`。 */
    val shortClass: String
        get() = className.substringAfterLast('.')

    /** bounds 中心点 X；无 bounds 返回 null。 */
    val cx: Int? get() = bounds?.let { (it[0] + it[2]) / 2 }
    /** bounds 中心点 Y；无 bounds 返回 null。 */
    val cy: Int? get() = bounds?.let { (it[1] + it[3]) / 2 }

    /**
     * 深度优先遍历（含自身），用于扁平化与 resourceId 查找。
     */
    fun selfAndDescendants(): Sequence<UiNode> = sequence {
        yield(this@UiNode)
        children.forEach { c -> yieldAll(c.selfAndDescendants()) }
    }
}
