package top.fellow99.rpandroid.a11y

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject
import top.fellow99.rpandroid.core.script.UiNode
import top.fellow99.rpandroid.core.script.XPathEvaluator

/**
 * [MfaAccessibilityService] —— 读被托管 App 界面的控件树（可覆盖虚拟屏窗口）。
 *
 * 用户需在系统设置里启用本服务（设置 → 无障碍）。启用后：
 *  - 把各窗口（含虚拟屏/主屏）的 a11y 树构建成纯 Kotlin [UiNode] 树；
 *  - 提供四产出（供 REST `/ui`、MCP `ui_*`、脚本 `ui.*` 使用）：
 *    - [dumpUi]：层级树 + resourceId
 *    - [readUi]：扁平数组（兼容旧语义：仅保留文本/可点/可编辑节点）
 *    - [queryUi]：XPath 子集查询（见 `013-xpath-evaluator`）
 *    - [getUi]：按 resourceId 定位
 */
class MfaAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    // ---- 四产出（依赖 a11y 服务实例；未启用返回 null，调用方转为 503/"ui unavailable"） ----

    /** 层级树 JSON（root → children 递归，含 resourceId）。 */
    fun dumpUi(): String? {
        val roots = buildUiRoots() ?: return null
        val arr = JSONArray()
        roots.forEach { arr.put(nodeJson(it, withChildren = true)) }
        return arr.toString()
    }

    /** 扁平数组 JSON（无 children；保留旧语义：text/clickable/editable 节点）。 */
    fun readUi(): String? {
        val roots = buildUiRoots() ?: return null
        val arr = JSONArray()
        roots.flatMap { it.selfAndDescendants().toList() }
            .filter { it.text.isNotEmpty() || it.clickable || it.editable }
            .forEach { arr.put(nodeJson(it, withChildren = false)) }
        return arr.toString()
    }

    /**
     * XPath 子集查询 JSON（命中节点扁平数组）。
     * 非法 XPath 抛 [IllegalArgumentException]，由调用方转明确错误。
     */
    @Throws(IllegalArgumentException::class)
    fun queryUi(xpath: String): String? {
        val roots = buildUiRoots() ?: return null
        val arr = JSONArray()
        XPathEvaluator.query(roots, xpath).forEach { arr.put(nodeJson(it, withChildren = false)) }
        return arr.toString()
    }

    /** 按 resourceId 定位（扁平数组；无匹配返回空数组）。 */
    fun getUi(resourceId: String): String? {
        val roots = buildUiRoots() ?: return null
        val arr = JSONArray()
        roots.flatMap { it.selfAndDescendants().toList() }
            .filter { it.resourceId == resourceId }
            .forEach { arr.put(nodeJson(it, withChildren = false)) }
        return arr.toString()
    }

    // ---- UiNode 树构建 ----

    /** 收集所有窗口（含虚拟屏/主屏）的 UiNode 根；无窗口时回退 rootInActiveWindow。 */
    private fun buildUiRoots(): List<UiNode>? {
        val roots = mutableListOf<UiNode>()
        val windows = runCatching { windows }.getOrNull() ?: emptyList()
        for (w in windows) {
            val root = runCatching { w.root }.getOrNull() ?: continue
            val displayId = if (Build.VERSION.SDK_INT >= 30) runCatching { w.displayId }.getOrDefault(-1) else -1
            roots.add(toUiNode(root, displayId, 0))
        }
        if (roots.isEmpty()) {
            rootInActiveWindow?.let { toUiNode(it, -1, 0) }?.let { roots.add(it) }
        }
        return roots
    }

    private fun toUiNode(n: AccessibilityNodeInfo, displayId: Int, index: Int): UiNode {
        val bounds = Rect().also { n.getBoundsInScreen(it) }
        val childCount = n.childCount
        val children = ArrayList<UiNode>(childCount.coerceAtLeast(0))
        for (i in 0 until childCount) {
            n.getChild(i)?.let { children.add(toUiNode(it, displayId, i)) }
        }
        return UiNode(
            text = n.text?.toString() ?: n.contentDescription?.toString() ?: "",
            className = n.className?.toString() ?: "",
            packageName = n.packageName?.toString() ?: "",
            clickable = n.isClickable,
            editable = n.isEditable,
            scrollable = n.isScrollable,
            resourceId = n.viewIdResourceName ?: "",
            bounds = intArrayOf(bounds.left, bounds.top, bounds.right, bounds.bottom),
            indexInParent = index,
            displayId = displayId,
            children = children,
        )
    }

    /** 节点统一序列化（字段见 012 spec §3.2）。 */
    private fun nodeJson(n: UiNode, withChildren: Boolean): JSONObject {
        val b = n.bounds
        val o = JSONObject().apply {
            put("text", n.text)
            put("className", n.className)
            put("packageName", n.packageName)
            put("clickable", n.clickable)
            put("editable", n.editable)
            put("scrollable", n.scrollable)
            put("displayId", n.displayId)
            put("resourceId", n.resourceId)
            if (b == null || b.size < 4) {
                put("bounds", JSONObject.NULL)
            } else {
                put("bounds", JSONObject().put("l", b[0]).put("t", b[1]).put("r", b[2]).put("b", b[3]))
            }
        }
        if (withChildren) {
            val ca = JSONArray()
            n.children.forEach { ca.put(nodeJson(it, withChildren = true)) }
            o.put("children", ca)
        }
        return o
    }

    companion object {
        @Volatile
        var instance: MfaAccessibilityService? = null
            private set

        fun isEnabled(): Boolean = instance != null

        private val mainHandler: Handler by lazy { Handler(Looper.getMainLooper()) }

        /**
         * Mode B 主屏点击：经 a11y [dispatchGesture] 注入点到前台主屏（010-display-mode）。
         * a11y 未启用或手势被拒时返回 false（调用方回退 `input -d 0`）。
         */
        @JvmStatic
        fun dispatchTap(x: Int, y: Int): Boolean {
            val svc = instance ?: return false
            return svc.performGesture(x, y, x, y)
        }

        /**
         * Mode B 主屏滑动：经 a11y [dispatchGesture] 注入滑动路径到前台主屏（010-display-mode）。
         */
        @JvmStatic
        fun dispatchSwipe(x1: Int, y1: Int, x2: Int, y2: Int, durMs: Int): Boolean {
            val svc = instance ?: return false
            return svc.performGesture(x1, y1, x2, y2, durMs)
        }
    }

    /** 单点手势实现：start==end 时退化为点击。 */
    private fun performGesture(x1: Int, y1: Int, x2: Int, y2: Int, durMs: Int = 60): Boolean {
        val path = Path().apply {
            moveTo(x1.toFloat(), y1.toFloat())
            lineTo(x2.toFloat(), y2.toFloat())
        }
        val start = 0L
        val duration = if (x1 == x2 && y1 == y2) 60L else durMs.toLong().coerceAtLeast(60)
        val stroke = GestureDescription.StrokeDescription(path, start, duration)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        val latch = java.util.concurrent.CountDownLatch(1)
        var ok = false
        mainHandler.post {
            ok = dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) { latch.countDown() }
                override fun onCancelled(gestureDescription: GestureDescription?) { latch.countDown() }
            }, null)
        }
        if (ok) {
            try { latch.await(2, java.util.concurrent.TimeUnit.SECONDS) } catch (_: InterruptedException) {}
        }
        return ok
    }
}
