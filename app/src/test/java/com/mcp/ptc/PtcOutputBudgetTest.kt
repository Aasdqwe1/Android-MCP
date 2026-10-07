package com.mcp.ptc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 日志字节预算测试桩：证明超预算时显式截断并带标记（不静默丢内容）。 */
class PtcOutputBudgetTest {

    @Test
    fun `未超预算时原样返回`() {
        val lines = listOf("a", "bb", "ccc")
        assertEquals(lines, PtcOutputBudget.capLogs(lines, 1000))
    }

    @Test
    fun `超预算时截断并追加显式标记`() {
        val lines = List(100) { "line-" + it + "-" + "x".repeat(20) }
        val capped = PtcOutputBudget.capLogs(lines, 200)
        assertTrue("应保留前缀", capped.first() == lines.first())
        assertTrue("应带截断标记: " + capped.last(), capped.last().startsWith(PtcOutputBudget.TRUNCATED_MARK))
        assertTrue("被省略行数应可读: " + capped.last(), capped.last().contains("行被省略"))
        assertTrue("裁剪后总长度应受控", capped.sumOf { it.length } < 400)
    }

    @Test
    fun `capText 保留前缀并标记`() {
        val t = "y".repeat(500)
        val capped = PtcOutputBudget.capText(t, 100)
        assertTrue(capped.startsWith("y".repeat(100)))
        assertTrue(capped.endsWith(PtcOutputBudget.TRUNCATED_MARK))
    }

    @Test
    fun `capResult 未超预算时不标记`() {
        val (text, truncated) = PtcOutputBudget.capResult("small", 100)
        assertEquals("small", text)
        assertFalse(truncated)
    }

    @Test
    fun `capResult 超预算时截断并标记`() {
        val (text, truncated) = PtcOutputBudget.capResult("z".repeat(500), 100)
        assertTrue(truncated)
        assertTrue(text.startsWith("z".repeat(100)))
        assertTrue(text.endsWith(PtcOutputBudget.TRUNCATED_MARK))
    }
}
