package com.mcp.compaction

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets

/**
 * ToolResultSpill 纯函数预览的单元测试（不依赖 Android Context）。
 * 覆盖：小文本原样、超大 ASCII 头尾裁剪、CJK 增补平面代理对不被切断、省略字节数正确。
 */
class ToolResultSpillTest {

    private fun bytes(s: String) = s.toByteArray(StandardCharsets.UTF_8).size

    @Test
    fun `小文本原样返回`() {
        val text = "hello world"
        assertEquals(text, ToolResultSpill.previewContent(text, 100))
    }

    @Test
    fun `空文本原样返回`() {
        assertEquals("", ToolResultSpill.previewContent("", 100))
    }

    @Test
    fun `超大 ASCII 头尾裁剪且概不超预算`() {
        val text = "A".repeat(20_000) // 20KB ASCII
        val budget = 1_000
        val out = ToolResultSpill.previewContent(text, budget)
        // 头尾原文拼接不超过预算（外加省略标记开销，给一定松弛）
        val head = out.substringBefore("\n…[已省略")
        val tail = out.substringAfterLast("字节]…\n")
        assertTrue("head 不应超预算", bytes(head) <= budget)
        assertTrue("tail 不应超预算", bytes(tail) <= budget)
        assertTrue("应出现省略标记", out.contains("已省略约"))
        assertTrue("应保留首字符", out.startsWith("A".repeat(1)))
        assertTrue("应保留末字符", out.trimEnd().endsWith("A"))
    }

    @Test
    fun `CJK 增补平面字符不被切断代理对`() {
        // 用 emoji（增补平面，UTF-16 为代理对）构造超长文本，验证预览不出现孤立代理。
        val text = "😀".repeat(5_000) // 每个 emoji 4 字节 UTF-8
        val out = ToolResultSpill.previewContent(text, 800)
        // 整个输出必须是合法的 UTF-16（无孤立代理对）：重新编码回 UTF-8 不应抛异常，
        // 且没有出现半个代理对（head 末尾 / tail 开头应是完整的 emoji）。
        val reencoded = out.toByteArray(StandardCharsets.UTF_8).toString(StandardCharsets.UTF_8)
        assertEquals(out, reencoded)
        // 头尾都应是完整 emoji 重复
        assertTrue("头部应为完整 emoji", out.startsWith("😀"))
        assertTrue("尾部应为完整 emoji", out.trimEnd().endsWith("😀"))
    }

    @Test
    fun `省略字节数为正且合理`() {
        val text = "X".repeat(10_000)
        val out = ToolResultSpill.previewContent(text, 1_000)
        val m = Regex("已省略约 (\\d+) 字节").find(out)
        assertTrue("应解析出省略字节数", m != null)
        val omitted = m!!.groupValues[1].toInt()
        assertTrue("省略字节数应为正", omitted > 0)
        assertTrue("省略数应接近 9000", omitted in 8_500..9_500)
    }
}
