package com.mcp.toolbox

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** delete_range 花括号配对校验测试（对齐官方 delete_range.go）。 */
class BraceBalanceTest {

    // 完整函数块：配对 → 可删
    @Test
    fun balancedFunctionBlock() {
        val text = "fun a() {\n    return 1\n}\nfun b() {\n    return 2\n}\n"
        val start = text.indexOf("fun a()")
        val end = text.indexOf("fun b()")
        assertTrue(BraceBalance.deleteIsBalanced(text, start, end))
    }

    // 区间切开代码块（左花括号未闭合）→ 拒绝
    @Test
    fun unbalancedCutInsideBlock() {
        val text = "fun a() {\n    return 1\n}\n"
        val start = text.indexOf("fun a()")
        val end = text.indexOf("return")
        assertFalse(BraceBalance.deleteIsBalanced(text, start, end))
    }

    // 多余右花括号（区间起点在块内部）→ 拒绝
    @Test
    fun unbalancedStrayCloseBrace() {
        val text = "fun a() {\n    return 1\n}\n"
        val start = text.indexOf("return")
        val end = text.indexOf("}") + 1
        assertFalse(BraceBalance.deleteIsBalanced(text, start, end))
    }

    // 跳过字符串里的花括号（不误判）
    @Test
    fun bracesInsideStringIgnored() {
        val text = "val s = \"{\"\nval t = \"}\"\n"
        val start = 0
        val end = text.length
        assertTrue(BraceBalance.deleteIsBalanced(text, start, end), "字符串中的花括号不应计入")
    }

    // 跳过行注释与块注释里的花括号
    @Test
    fun bracesInsideCommentsIgnored() {
        val text = "// { not a brace\n/* } also not */\nfun a() {\n}\n"
        val start = 0
        val end = text.length
        assertTrue(BraceBalance.deleteIsBalanced(text, start, end), "注释中的花括号不应计入")
    }

    // 嵌套块：整体平衡
    @Test
    fun nestedBlocks() {
        val text = "fun a() {\n    if (x) {\n        return\n    }\n}\n"
        val start = 0
        val end = text.length
        assertTrue(BraceBalance.deleteIsBalanced(text, start, end))
    }

    // 边界校验：非法区间
    @Test
    fun invalidRange() {
        assertFalse(BraceBalance.deleteIsBalanced("{}", 1, 0))
        assertFalse(BraceBalance.deleteIsBalanced("{}", -1, 2))
        assertFalse(BraceBalance.deleteIsBalanced("{}", 0, 5))
    }
}
