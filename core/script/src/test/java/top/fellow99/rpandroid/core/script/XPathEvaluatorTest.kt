package top.fellow99.rpandroid.core.script

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 013-xpath-evaluator 的 JVM 离线测试（TC-013-001~011）。
 * 纯 Kotlin，无需设备/Android 环境：`gradlew :core:script:testDebugUnitTest`。
 */
class XPathEvaluatorTest {

    private fun btnOk(t: String = "确定") =
        UiNode(text = t, className = "android.widget.TextView",
            resourceId = "com.demo:id/btn_ok", clickable = true)
    private fun txt(t: String) = UiNode(text = t, className = "android.widget.TextView")

    private val sampleRoots: List<UiNode> by lazy {
        listOf(
            UiNode(className = "android.view.View", children = listOf(
                btnOk(),
                UiNode(className = "android.widget.LinearLayout", children = listOf(
                    UiNode(text = "取消", className = "android.widget.Button", clickable = true),
                )),
                UiNode(text = "", className = "android.widget.EditText", editable = true),
            )),
            UiNode(className = "android.view.View", children = listOf(
                txt("标题"),
            )),
        )
    }

    @Test
    fun tc001_descendantWildcard() {
        // //* → 全部 7 个节点
        assertEquals(7, XPathEvaluator.query(sampleRoots, "//*").size)
    }

    @Test
    fun tc002_shortClassName() {
        assertEquals(listOf("确定", "标题"),
            XPathEvaluator.query(sampleRoots, "//TextView").map { it.text })
    }

    @Test
    fun tc003_fullClassName() {
        assertEquals(listOf("确定", "标题"),
            XPathEvaluator.query(sampleRoots, "//android.widget.TextView").map { it.text })
    }

    @Test
    fun tc004_textPredicate() {
        val r = XPathEvaluator.query(sampleRoots, "//*[@text='确定']")
        assertEquals(1, r.size)
        assertEquals("确定", r[0].text)
    }

    @Test
    fun tc005_resourceId() {
        val r = XPathEvaluator.query(sampleRoots, "//*[@resourceId='com.demo:id/btn_ok']")
        assertEquals(1, r.size)
        assertEquals("确定", r[0].text)
    }

    @Test
    fun tc006_boolPredicate() {
        assertEquals(2, XPathEvaluator.query(sampleRoots, "//*[@clickable=true]").size)
        val edits = XPathEvaluator.query(sampleRoots, "//*[@editable=true]")
        assertEquals(1, edits.size)
        assertTrue(edits[0].editable)
    }

    @Test
    fun tc007_childAxis() {
        // /* → 虚拟根的直接子级 = 两个 Root
        assertEquals(2, XPathEvaluator.query(sampleRoots, "/*").size)
        // //LinearLayout/* → LL 的直接子级 = 取消
        val buttons = XPathEvaluator.query(sampleRoots, "//LinearLayout/*")
        assertEquals(listOf("取消"), buttons.map { it.text })
    }

    @Test
    fun tc008_position() {
        assertEquals(listOf("确定"), XPathEvaluator.query(sampleRoots, "//TextView[1]").map { it.text })
        assertEquals(listOf("标题"), XPathEvaluator.query(sampleRoots, "//TextView[2]").map { it.text })
    }

    @Test
    fun tc009_noMatch() {
        assertTrue(XPathEvaluator.query(sampleRoots, "//NoSuchTag").isEmpty())
        assertTrue(XPathEvaluator.query(sampleRoots, "//*[@text='不存在']").isEmpty())
    }

    @Test
    fun tc010_invalidSyntax() {
        assertThrows(IllegalArgumentException::class.java) { XPathEvaluator.query(sampleRoots, "") }
        assertThrows(IllegalArgumentException::class.java) { XPathEvaluator.query(sampleRoots, "//*[") }
        assertThrows(IllegalArgumentException::class.java) { XPathEvaluator.query(sampleRoots, "[[") }
        assertThrows(IllegalArgumentException::class.java) { XPathEvaluator.query(sampleRoots, "no-leading-slash") }
    }

    @Test
    fun tc011_notEqual() {
        // className != android.widget.TextView → 排除两个 TextView 与根（根 className=android.view.View 会被命中？）
        // 根 className = android.view.View（非 TextView）→ 命中；故共 5 个
        val r = XPathEvaluator.query(sampleRoots, "//*[@className!='android.widget.TextView']")
        assertEquals(5, r.size)
    }
}
