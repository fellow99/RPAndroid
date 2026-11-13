# 013-xpath-evaluator Specification — XPathEvaluator（XPath 子集求值器）

> Module: 013-xpath-evaluator
> Status: Proposed（2026-11-11）
> 上游：`012-ui-read`（`queryUi` 依赖本求值器）
> 约束沿用总纲：免 root、arm64-v8a、minSdk 24、仅局域网/USB、**敏感信息不入档**。
> 技术决策（用户已确认）：**方案 B——自研 XPath 子集求值器**，不用 JAXP/DOM（真机 a11y 树大，DOM 开销高、属性映射别扭）。

## 1. Module Overview

### 1.1 Purpose

为 `012-ui-read.queryUi` 提供**纯 Kotlin、可直接在 JVM 单测**的 XPath 子集求值器。
求值对象是与 a11y 无关的轻量树模型 `UiNode`（由 a11y 服务从 `AccessibilityNodeInfo` 构建一次，可复用给 `dumpUi/readUi`），
因此本模块**不 import 任何 Android 类**，可在离线 JVM local unit test 中加载真实求值器验证（脚本可测）。

### 1.2 Problems Solved

- 完整 W3C XPath（JAXP + DOM）在真机 a11y 大树上开销高、属性映射别扭。
- 用受限子集覆盖 95% 定位诉求（文字 / resourceId / className / clickable 等），行为可控、可单测。

## 2. Grammar（支持子集）

用 EBNF 描述（求值器解析的稳定子集）：

```
LocationPath  := PathStep ( '/' PathStep )*
PathStep      := Axis? NodeTest Predicate*
Axis          := '/' | '//'          -- '/'=子轴；'//'=任意深度后代(含自身)
NodeTest      := '*' | 'TextView' | 'android.widget.TextView'
Predicate     := '[' Pred ']'
Pred          := '@attr' RelOp Value | Number
attr          := text | resourceId | className | packageName | clickable | editable | scrollable | index
RelOp         := '=' | '!='           -- 字符串/布尔/数字比较
Value         := "'" Str "'" | '"' Str '"' | 'true' | 'false' | Number
Number        := [0-9]+
```

匹配规则：
- 根：以各 `window` 根节点构成的**森林**为虚拟根 S。
- 路径首步：
  - 以 `/` 开头 → 从 S 的**直接子级**开始。
  - 以 `//` 开头 → 从 S 的**任意深度后代（含根自身）**开始。
- 后续步：`/` 从当前集和的**直接子级**扩展；`//` 从当前集和的**任意深度后代**扩展。
- `*` 匹配任意 className；`TextView` 匹配 className 简写（`android.widget.TextView`）；全名精确匹配。
- 谓词 `[@attr=v]` 按属性过滤；`[N]` 取该步匹配集中第 N 个（1 基）；`[@index=N]` 按节点在父中的兄弟序号过滤。
- 返回：所有命中节点（扁平），复用 `UiNode` → 可由上层序列化成 `012` 节点记录（含 bounds）。

## 3. Functional Requirements

- **FR-013-001**：`XPathEvaluator` MUST 为纯 Kotlin 类，不依赖 Android 类（可 JVM 单测）。
- **FR-013-002**：MUST 解析并求值 `/[//]tag[@attr='v'][N]` 子集；`/`、`//`、`*`、className 简写/全名、谓词、位置。
- **FR-013-003**：属性匹配：字符串按 `=`/`!=`；`clickable/editable/scrollable` 按 `true/false`；`index` 按整数。
- **FR-013-004**：非法语法 / 空路径 MUST 抛可识别异常或返回明确的「无效」结果（上层 `queryUi` 转成错误，不崩溃）。
- **FR-013-005**：无匹配 MUST 返回空列表（非错误）。
- **FR-013-006**：求值 MUST 在内存 `UiNode` 树上进行，禁止在匹配循环内截帧/触发操作。
- **FR-013-007**：MUST 提供一组可在 JVM 运行的测试用例（`core:script` local unit test），覆盖：子轴/后代轴、通配、className 简写/全名、字符串/布尔/数字谓词、位置、无匹配、非法语法。

## 4. Key Entities

| 实体 | 说明 | 关键属性 |
|---|---|---|
| `UiNode` | 轻量树模型（纯 Kotlin） | `text/className/packageName/clickable/editable/scrollable/resourceId/bounds/indexInParent/children` |
| `XPathEvaluator` | 子集求值器 | `query(roots, xpath): List<UiNode>` |

## 5. Acceptance Scenarios

- 构建 `{root: [{TextView text="确定" id="btn_ok"}, {ViewGroup:[{Button text="取消"}]}]}` 的 UiNode 树：
  - `//TextView` → 命中 `确定` 的 TextView
  - `//android.widget.TextView[@text="确定"]` → 命中同名
  - `//*[@resourceId="btn_ok"]` → 命中
  - `//Button[@clickable=true]` → 命中 取消
  - `无路径` / 非法语法 → 报错不崩溃
  - `//NoSuchTag` → 空列表

## 6. Non-Functional Requirements

- **性能**：单次解析、单遍匹配；O(节点数×步数) 内完成。
- **健壮**：任意输入（含畸形语法）不崩溃。
- **可测**：离线 JVM 单测为 Done 门禁之一。

## 7. Assumptions & Constraints

- 只支持上述子集；不支持 `../`、轴全称（`descendant::`）、合并路径 `|`、`@*`、函数、`[@id]` 存在性简写（首版）。
- 位置 `[N]` 语义为该步匹配集第 N 项（文档化），非父内全局兄弟序号（需 `@index` 时显式使用）。

## 8. Dependencies

- 上游：无（自包含）。
- 下游：`012-ui-read`（`queryUi`）、a11y 服务（构建 UiNode 树）、MCP `ui_query`、脚本 `ui.query`。

## 9. 已知限制（首版）

- 不支持完整 XPath；位置语义见 §7。
- 不做跨父祖先返回 / 上下文轴。
