package top.fellow99.rpauto.core.script

/**
 * [XPathEvaluator] —— 在 [UiNode] 树上求值的 **XPath 子集**求值器（纯 Kotlin，可 JVM 离线单测）。
 *
 * 支持（见 `specs/013-xpath-evaluator`）：
 * - 起始轴：`/`（直接子级，即各根节点本身）、`//`（任意深度后代，含根自身）。
 * - 节点测试：`*`、className 简写（`TextView`）或全名（`android.widget.TextView`）。
 * - 谓词：`[@attr=v]`（`text/resourceId/className/packageName/clickable/editable/scrollable/index`，支持 `=`/`!=`）、
 *   位置 `[N]`（该步匹配集第 N 个，1 基）。
 *
 * 非目标：完整 W3C XPath、`../`、轴全称、合并路径 `|`、`@*`、函数、存在性简写 `[@id]`（首版不支持）。
 */
object XPathEvaluator {

    private enum class Axis { CHILD, DESC }

    private enum class NodeTest { ANY, CLASS }

    private data class Step(
        val axis: Axis,
        val test: NodeTest,
        val testClass: String,
        /** 属性过滤（按节点属性求值）。 */
        val filters: List<(UiNode) -> Boolean>,
        /** 位置谓词 [N]（null 表示无）。 */
        val pos: Int? = null,
    )

    /**
     * 从根节点森林（如多个 window 根）求值 `xpath`。
     * 首步 CHILD 语义＝虚拟根的直接子级（即 [roots] 本身）；DESC＝任意深度后代（含 roots）。
     * 非法语法抛 [IllegalArgumentException]，由上层转明确错误。
     */
    fun query(roots: List<UiNode>, xpath: String): List<UiNode> {
        val steps = parse(xpath)
        if (steps.isEmpty()) throw IllegalArgumentException("xpath has no steps: $xpath")

        var current: List<UiNode> = when (steps[0].axis) {
            Axis.CHILD -> roots
            Axis.DESC -> roots.flatMap { it.selfAndDescendants().toList() }
        }
        current = applyStep(current, steps[0])

        for (i in 1 until steps.size) {
            val st = steps[i]
            current = when (st.axis) {
                Axis.CHILD -> current.flatMap { it.children }
                Axis.DESC -> current.flatMap { it.selfAndDescendants().toList() }
            }
            current = applyStep(current, st)
        }
        return current
    }

    private fun applyStep(list: List<UiNode>, step: Step): List<UiNode> {
        var matched = list.filter { n ->
            when (step.test) {
                NodeTest.ANY -> true
                NodeTest.CLASS -> n.className == step.testClass || n.shortClass == step.testClass
            }
        }
        step.filters.forEach { f -> matched = matched.filter(f) }
        if (step.pos != null) {
            matched = matched.getOrNull(step.pos - 1)?.let { listOf(it) } ?: emptyList()
        }
        return matched
    }

    // ---- 解析 ----

    private fun parse(xpath: String): List<Step> {
        if (xpath.isBlank()) throw IllegalArgumentException("xpath is empty")
        if (!xpath.startsWith("/")) {
            throw IllegalArgumentException("xpath must start with '/' or '//': $xpath")
        }
        val s = xpath
        var i = 0
        var axis = if (s.startsWith("//")) { i = 2; Axis.DESC } else { i = 1; Axis.CHILD }
        val steps = mutableListOf<Step>()

        while (true) {
            val testEnd = indexOfAny(s, i, '/', '[')
            val token = s.substring(i, testEnd)
            i = testEnd
            val (test, testClass) = when {
                token == "*" -> NodeTest.ANY to ""
                token.isEmpty() -> throw IllegalArgumentException("missing node test at '$xpath' near offset $i")
                else -> NodeTest.CLASS to token
            }

            val filters = mutableListOf<(UiNode) -> Boolean>()
            var pos: Int? = null
            while (i < s.length && s[i] == '[') {
                i++
                if (i < s.length && s[i] == '@') {
                    i++
                    val (after, pred) = parseAttrPred(s, i)
                    filters += pred
                    i = after
                } else {
                    if (i >= s.length) throw IllegalArgumentException("unterminated position predicate: $xpath")
                    val end = s.indexOf(']', i)
                    if (end < 0) throw IllegalArgumentException("unterminated position predicate: $xpath")
                    val num = s.substring(i, end).trim().toIntOrNull()
                        ?: throw IllegalArgumentException("bad position predicate '$xpath'")
                    if (num <= 0) throw IllegalArgumentException("position must be >=1: $xpath")
                    pos = num
                    i = end + 1
                }
            }
            steps += Step(axis, test, testClass, filters, pos)

            if (i >= s.length) break
            if (s[i] != '/') throw IllegalArgumentException("unexpected token at offset $i: $xpath")
            i++
            axis = Axis.CHILD
            if (i < s.length && s[i] == '/') { axis = Axis.DESC; i++ }
            if (i >= s.length) throw IllegalArgumentException("trailing '/': $xpath")
        }
        return steps
    }

    private fun indexOfAny(s: String, start: Int, vararg stops: Char): Int {
        var i = start
        while (i < s.length) {
            if (s[i] in stops) return i
            i++
        }
        return s.length
    }

    /** 解析 `attr op value`，返回 [处理后的下标, 过滤器]。当前 i 指向 attr 首字符。 */
    private fun parseAttrPred(s: String, start: Int): Pair<Int, (UiNode) -> Boolean> {
        var i = start
        val attrStart = i
        while (i < s.length && s[i] != '=' && s[i] != ']' && s[i] != '!') i++
        if (attrStart == i) throw IllegalArgumentException("empty attribute name")
        val attr = s.substring(attrStart, i)

        var op = "="
        if (i < s.length && s[i] == '!') {
            op = "!="
            i++
        }
        if (i >= s.length || s[i] != '=') throw IllegalArgumentException("expected '=' after attribute '$attr'")
        i++

        val value: String
        if (i < s.length && (s[i] == '\'' || s[i] == '"')) {
            val q = s[i]
            i++
            val end = s.indexOf(q, i)
            if (end < 0) throw IllegalArgumentException("unterminated string value")
            value = s.substring(i, end)
            i = end + 1
        } else {
            val end = indexOfAny(s, i, ']')
            value = s.substring(i, end).trim()
            i = end
        }
        if (i >= s.length || s[i] != ']') throw IllegalArgumentException("unterminated predicate")
        i++

        val filter: (UiNode) -> Boolean = { n -> compare(n, attr, op, value) }
        return i to filter
    }

    /** 属性比较：attr/op/value → 布尔。 */
    private fun compare(n: UiNode, attr: String, op: String, value: String): Boolean {
        val lhs: String = when (attr) {
            "text" -> n.text
            "resourceId" -> n.resourceId
            "className" -> n.className
            "packageName" -> n.packageName
            "clickable" -> n.clickable.toString()
            "editable" -> n.editable.toString()
            "scrollable" -> n.scrollable.toString()
            "index" -> n.indexInParent.toString()
            else -> throw IllegalArgumentException("unsupported attribute '$attr'")
        }
        val lInt = lhs.toIntOrNull()
        val rInt = value.toIntOrNull()
        return if (op == "!=") {
            if (lInt != null && rInt != null) lInt != rInt else lhs != value
        } else {
            if (lInt != null && rInt != null) lInt == rInt else lhs == value
        }
    }
}
