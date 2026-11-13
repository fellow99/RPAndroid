# 013-xpath-evaluator Test Cases — XPathEvaluator（JVM 可运行）

> 状态：Proposed（2026-11-11）｜ 依据：`spec.md` FR-013-001~007
> 执行方式：**离线 JVM local unit test**（`core:script` 模块，真实编译求值器）→ `gradlew :core:script:testDebugUnitTest`
> 另附真机用例（经 MCP `ui_query` 对真实 App 验证）。`Status`: PASS/FAIL。

## 固定样例树（单元测试内构建）

```
Root0
 ├─ TextView  text="确定"   resourceId="com.demo:id/btn_ok"   clickable=true
 ├─ LinearLayout
 │   └─ Button  text="取消"  className=android.widget.Button  clickable=true
 └─ EditText  text="" editable=true
Root1
 └─ TextView  text="标题"  className=android.widget.TextView
```

## TC-013-001：后代轴 `//` + 通配（FR-013-002/006）
- **Steps**: `query(roots, "//*")`
- **Expected**: 命中所有节点（6 个，含两根）
- **Status**: PASS

## TC-013-002：className 简写匹配（FR-013-002）
- **Steps**: `query(roots, "//TextView")`
- **Expected**: 命中 `确定` 与 `标题`（2 个）
- **Status**: PASS

## TC-013-003：className 全名匹配（FR-013-002）
- **Steps**: `query(roots, "//android.widget.TextView")`
- **Expected**: 与 TC-013-002 同（2 个）
- **Status**: PASS

## TC-013-004：字符串谓词 `[@text=..]`（FR-013-003）
- **Steps**: `query(roots, "//*[@text='确定']")`
- **Expected**: 命中 1 个（text=确定 的 TextView）
- **Status**: PASS

## TC-013-005：resourceId 谓词（FR-013-003）
- **Steps**: `query(roots, "//*[@resourceId='com.demo:id/btn_ok']")`
- **Expected**: 命中 1 个
- **Status**: PASS

## TC-013-006：布尔谓词 `[@clickable=true]`（FR-013-003）
- **Steps**: `query(roots, "//*[@clickable=true]")`
- **Expected**: 命中 确定 / 取消（2 个）；`@editable=true` 命中 EditText
- **Status**: PASS

## TC-013-007：子轴 `/`（FR-013-002）
- **Steps**: `query(roots, "/*")`
- **Expected**: 从虚拟根的子级 = Root0、Root1（2 个）；`query(roots,"//LinearLayout/*")` 命中 取消
- **Status**: PASS

## TC-013-008：位置 `[N]`（FR-013-002/007）
- **Steps**: `query(roots, "//TextView[2]")`
- **Expected**: 命中匹配集中第 2 个（标题）；`[1]` 命中 确定
- **Status**: PASS

## TC-013-009：无匹配（FR-013-005）
- **Steps**: `query(roots, "//NoSuchTag")` 、`query(roots,"//*[@text='不存在']")`
- **Expected**: 空列表（非错误）
- **Status**: PASS

## TC-013-010：非法语法（FR-013-004）
- **Steps**: `query(roots, "")`、`query(roots, "//*[")`、`query(roots, "[[")`
- **Expected**: 抛出可识别异常（IllegalArgumentException），上层转明确错误；不崩溃
- **Status**: PASS

## TC-013-011：`!=` 与组合（FR-013-003，可选）
- **Steps**: `query(roots, "//*[@className!='android.widget.TextView']")`
- **Expected**: 命中非 TextView 节点（Button/EditText/LinearLayout）
- **Status**: PASS

---

## 真机用例（经 MCP `ui_query`，Mode B）

## TC-013-101：对真实 App 定位（012 TC-012-003）
- **Steps**: 承载计算器/设置等 → `ui_query` `//android.widget.TextView[@text="<实际文本>"]`
- **Expected**: 命中含 bounds 节点，中心点可 `screen_tap` 触发
- **Status**: 

## TC-013-102：非法 XPath 经 ui_query 报错（012 TC-012-004）
- **Steps**: `ui_query` 传非法语法
- **Expected**: 返回明确错误（不崩溃）
- **Status**: 
