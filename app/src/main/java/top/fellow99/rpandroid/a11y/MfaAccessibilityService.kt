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

/**
 * [MfaAccessibilityService] —— 读被托管 App 界面的控件树（可覆盖虚拟屏窗口）。
 *
 * 用户需在系统设置里启用本服务（设置 → 无障碍）。启用后：
 *  - 遍历所有窗口（含虚拟屏），提取 text/bounds/class/clickable 等；
 *  - 供 REST `/ui` 与 MCP `ui_read` 使用。
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

    /** 收集所有窗口（含虚拟屏）的元素树，输出 JSON 字符串。 */
    fun dumpUi(): String {
        val out = JSONArray()
        val windows = runCatching { windows }.getOrNull() ?: emptyList()
        for (w in windows) {
            val root = runCatching { w.root }.getOrNull() ?: continue
            val displayId = if (Build.VERSION.SDK_INT >= 30) runCatching { w.displayId }.getOrDefault(-1) else -1
            collect(root, out, displayId)
        }
        if (out.length() == 0) {
            rootInActiveWindow?.let { collect(it, out, -1) }
        }
        return out.toString()
    }

    private fun collect(node: AccessibilityNodeInfo?, out: JSONArray, displayId: Int) {
        if (node == null) return
        val bounds = Rect().also { node.getBoundsInScreen(it) }
        val text = node.text?.toString() ?: node.contentDescription?.toString() ?: ""
        if (text.isNotEmpty() || node.isClickable || node.isEditable) {
            out.put(JSONObject().apply {
                put("text", text)
                put("className", node.className?.toString() ?: "")
                put("packageName", node.packageName?.toString() ?: "")
                put("clickable", node.isClickable)
                put("editable", node.isEditable)
                put("scrollable", node.isScrollable)
                put("displayId", displayId)
                put("bounds", JSONObject().put("l", bounds.left).put("t", bounds.top)
                    .put("r", bounds.right).put("b", bounds.bottom))
            })
        }
        for (i in 0 until node.childCount) {
            collect(node.getChild(i), out, displayId)
        }
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
        val start = if (x1 == x2 && y1 == y2) 0L else 0L
        val duration = if (x1 == x2 && y1 == y2) 60L else durMs.toLong().coerceAtLeast(60)
        val stroke = GestureDescription.StrokeDescription(path, start, duration)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        val latch = java.util.concurrent.CountDownLatch(1)
        var ok = false
        mainHandler.post {
            ok = dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
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
